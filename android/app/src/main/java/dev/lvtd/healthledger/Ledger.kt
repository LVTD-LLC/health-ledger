package dev.lvtd.healthledger

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

internal class SignInRequired : IOException("Sign in to your journal, then reconnect Health Connect.")

internal class LedgerSession(val cookie: String, val origin: String) {
    // Bind permissions and checkpoints to the sign-in that explicitly enabled sync.
    val binding = MessageDigest.getInstance("SHA-256").digest((if (origin == LEGACY_SERVER) cookie else "$origin\n$cookie").toByteArray()).joinToString("") { "%02x".format(it) }
    companion object {
        fun current(): LedgerSession? = LedgerApp.origin?.let(::forOrigin)
        fun forOrigin(origin: String): LedgerSession? {
            val cookie = CookieManager.getInstance().getCookie(origin)?.split(';')?.map { it.trim() }
                ?.firstOrNull { it.matches(Regex("session=[a-fA-F0-9]{64}")) } ?: return null
            return LedgerSession(cookie, origin)
        }
    }
}

internal class Ledger {
    internal fun connect(path: String, session: LedgerSession) =
        (URL(session.origin + path).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Cookie", session.cookie)
        }

    fun addEntry(session: LedgerSession, fields: Map<String, String>) {
        val body = fields.entries.joinToString("&") { (name, value) ->
            "$name=" + java.net.URLEncoder.encode(value, "UTF-8")
        }.toByteArray()
        val req = connect("/health/records", session)
        try {
            req.requestMethod = "POST"
            req.doOutput = true
            req.setRequestProperty("Origin", session.origin)
            req.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            req.setFixedLengthStreamingMode(body.size)
            req.outputStream.use { it.write(body) }
            if (req.getHeaderField("Location") == "/sign-in") throw SignInRequired()
            if (req.responseCode != 303 || req.getHeaderField("Location") != "/dashboard") throw IOException("Entry was not confirmed")
        } finally { req.disconnect() }
    }

    fun verify(session: LedgerSession) {
        val req = connect("/dashboard", session)
        try {
            req.requestMethod = "HEAD"
            if (req.responseCode in 300..399 || req.responseCode == 401) throw SignInRequired()
            if (req.responseCode != 200) throw IOException("Journal unavailable (HTTP ${req.responseCode}).")
        } finally { req.disconnect() }
    }

    fun sendSamsungDays(session: LedgerSession, days: Map<java.time.LocalDate, Long>): Int {
        if (days.isEmpty()) return 0
        val rows = JSONArray()
        days.forEach { (date, count) -> rows.put(JSONObject().put("date", date.toString()).put("steps", count)) }
        val req = connect("/health/samsung/daily-steps", session)
        try {
            req.requestMethod = "POST"
            req.setRequestProperty("Origin", session.origin)
            req.setRequestProperty("Content-Type", "application/json")
            req.doOutput = true
            req.outputStream.use { it.write(rows.toString().toByteArray()) }
            if (req.responseCode == 401 || req.getHeaderField("Location") == "/sign-in") throw SignInRequired()
            if (req.responseCode != 200) throw IOException("Server did not accept daily steps")
            val receipt = req.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            require(receipt.getInt("received") == days.size)
            return receipt.getInt("changed")
        } finally { req.disconnect() }
    }

    fun upload(session: LedgerSession, rows: List<JSONObject>) {
        if (rows.isEmpty()) return
        val json = JSONObject().put("records", JSONArray(rows)).toString().toByteArray()
        uploadFile(session, "health-connect.json", json.inputStream(), json.size.toLong(), "Health Connect")
    }

    fun uploadFile(session: LedgerSession, name: String, input: InputStream, size: Long, source: String) {
        val limit = 32L * 1024 * 1024
        require(size in 1..limit) { "Choose a non-empty file up to 32 MiB." }
        val boundary = "ledger-" + UUID.randomUUID()
        val filename = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(160)
        val prefix = buildString {
            append("--$boundary\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nimport\r\n")
            append("--$boundary\r\nContent-Disposition: form-data; name=\"source\"\r\n\r\n$source\r\n")
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val req = connect("/health/files", session)
        try {
            req.requestMethod = "POST"
            req.doOutput = true
            req.setRequestProperty("Origin", session.origin)
            req.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            req.setFixedLengthStreamingMode(prefix.size + size + suffix.size)
            req.outputStream.use { output ->
                output.write(prefix)
                input.copyTo(output)
                output.write(suffix)
            }
            val location = req.getHeaderField("Location").orEmpty()
            if (location == "/sign-in" || req.responseCode == 401) throw SignInRequired()
            if (req.responseCode != 303 || location.substringBefore('#') != "/sources") {
                throw IOException("Import was not accepted (HTTP ${req.responseCode}).")
            }
        } finally { req.disconnect() }
    }
}
