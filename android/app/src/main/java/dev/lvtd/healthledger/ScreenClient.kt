package dev.lvtd.healthledger

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.CookieManager
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

internal data class SelectedFile(val field: String, val uri: Uri, val name: String, val size: Long)
internal data class ScreenReply(val path: String, val document: Document?, val redirect: String?, val status: Int)

internal fun isScreenUrl(raw: String, origin: String): Boolean {
    if (isLedgerUrl(raw, origin)) return true
    // Only the isolated probe APK can use the USB loopback fixture.
    if (BuildConfig.BUILD_TYPE != "probe" || origin != "http://127.0.0.1:38689") return false
    return runCatching { URI(raw).let { it.scheme == "http" && it.rawAuthority == "127.0.0.1:38689" } }.getOrDefault(false)
}

internal fun localPath(origin: String, from: String, target: String): String {
    val url = URI(origin + from).resolve(target)
    require(isScreenUrl(url.toString(), origin)) { "This link points outside your server." }
    return url.rawPath.ifEmpty { "/" } + (url.rawQuery?.let { "?$it" } ?: "") + (url.rawFragment?.let { "#$it" } ?: "")
}

internal fun selectedFile(resolver: ContentResolver, field: String, uri: Uri): SelectedFile {
    require(uri.scheme == "content")
    return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        require(it.moveToFirst() && !it.isNull(1))
        SelectedFile(field, uri, it.getString(0).orEmpty(), it.getLong(1)).also { f ->
            require(f.size in 1..32L * 1024 * 1024) { "Choose a file up to 32 MiB." }
        }
    } ?: throw IOException("Could not open this file.")
}

internal class ScreenClient(private val origin: String, private val resolver: ContentResolver) {
    private fun open(path: String): HttpURLConnection {
        check(LedgerApp.origin == origin)
        val url = origin + localPath(origin, "/", path).substringBefore('#')
        return (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("X-Ledger-Client","android")
            connectTimeout = 15000
            readTimeout = 30000
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
        }
    }

    fun request(path: String, fields: List<Pair<String,String>>? = null, file: SelectedFile? = null): ScreenReply {
        val conn = open(path)
        try {
            if (fields != null) {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Origin", origin)
                conn.doOutput = true
                if (file == null) {
                    val body = fields.joinToString("&") { (k,v) -> URLEncoder.encode(k,"UTF-8") + "=" + URLEncoder.encode(v,"UTF-8") }.toByteArray()
                    conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                } else upload(conn, fields, file)
            }
            val code = conn.responseCode
            check(LedgerApp.origin == origin)
            val updated = conn.headerFields.entries.firstOrNull { it.key.equals("Set-Cookie",true) }?.value.orEmpty()
            if (updated.isNotEmpty()) {
                val cookies = CookieManager.getInstance()
                updated.forEach { cookies.setCookie(conn.url.toString(), it) }
                cookies.flush()
            }
            if (code in 300..399) return ScreenReply(path,null,conn.getHeaderField("Location") ?: throw IOException("Missing redirect."),code)
            if (code !in listOf(200, 422, 429)) throw IOException(when(code) {
                401 -> "Sign in again to continue."
                404 -> "Update your Health Ledger server to use this feature."
                503 -> "Your server has not configured this connection."
                else -> "The server could not complete the request (HTTP $code)."
            })
            val bytes = (if (code >= 400) conn.errorStream else conn.inputStream).use { it.readNBytes(4*1024*1024+1) }
            require(bytes.size <= 4*1024*1024)
            return ScreenReply(path,Jsoup.parse(bytes.toString(Charsets.UTF_8), conn.url.toString()),null,code)
        } finally { conn.disconnect() }
    }

    private fun upload(conn: HttpURLConnection, fields: List<Pair<String,String>>, file: SelectedFile) {
        val boundary = "ledger-" + UUID.randomUUID()
        val safeName = file.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(160).ifEmpty { "attachment" }
        val prefix = buildString {
            for ((name,value) in fields) {
                require(name.matches(Regex("[a-zA-Z0-9_]+")))
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            require(file.field.matches(Regex("[a-zA-Z0-9_]+")))
            append("--$boundary\r\nContent-Disposition: form-data; name=\"${file.field}\"; filename=\"$safeName\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val end = "\r\n--$boundary--\r\n".toByteArray()
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setFixedLengthStreamingMode(prefix.size + file.size + end.size)
        conn.outputStream.use { out ->
            out.write(prefix)
            resolver.openInputStream(file.uri)?.use { it.copyTo(out) } ?: throw IOException("File unavailable.")
            out.write(end)
        }
    }

    fun download(path: String, destination: Uri) {
        val conn = open(path)
        try {
            require(conn.responseCode == 200 && conn.getHeaderField("Content-Disposition").orEmpty().startsWith("attachment"))
            resolver.openOutputStream(destination,"wt")?.use { out -> conn.inputStream.use { it.copyTo(out) } } ?: throw IOException("Could not save file.")
        } finally { conn.disconnect() }
    }
}
