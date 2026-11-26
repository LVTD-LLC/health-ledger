package dev.lvtd.healthledger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom

/** RFC 8252 loopback redirect for passkeys and federated sign-in on self-hosted origins. */
internal class BrowserLogin(private val origin: String) {
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var closed = false
    private fun encoded(bytes: ByteArray) = Base64.encodeToString(bytes,Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    private fun random() = encoded(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun start(activity: Activity, client: ScreenClient, done: (Boolean)->Unit) {
        Thread {
            val success = runCatching {
                ServerSocket(0,4,InetAddress.getByName("127.0.0.1")).use { server ->
                    listener = server
                    check(!closed)
                    val verifier = random()
                    val state = random()
                    val redirect = "http://127.0.0.1:${server.localPort}/android-sign-in"
                    val authorize = Uri.parse("$origin/oauth/authorize").buildUpon()
                        .appendQueryParameter("response_type","code")
                        .appendQueryParameter("client_id","health-ledger-android")
                        .appendQueryParameter("redirect_uri",redirect)
                        .appendQueryParameter("state",state)
                        .appendQueryParameter("code_challenge",encoded(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
                        .appendQueryParameter("code_challenge_method","S256").build()
                    activity.runOnUiThread {
                        if (!closed && !activity.isFinishing) runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW,authorize)) }.onFailure { close() }
                    }
                    val deadline = android.os.SystemClock.elapsedRealtime() + 300000
                    while (!closed) {
                        val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                        if (remaining <= 0) break
                        server.soTimeout = remaining.toInt()
                        val result = server.accept().use { socket ->
                            socket.soTimeout = 4000
                            val input = socket.getInputStream()
                            val line = StringBuilder()
                            while (line.length < 8192) {
                                val ch = input.read()
                                if (ch < 0 || ch == 10) break
                                line.append(ch.toChar())
                            }
                            val uri = Uri.parse(line.toString().substringAfter("GET ","").substringBefore(" HTTP/"))
                            val valid = uri.path == "/android-sign-in" && uri.getQueryParameter("state") == state && uri.getQueryParameter("iss") == origin
                            val code = if (valid) uri.getQueryParameter("code") else null
                            val ok = !code.isNullOrBlank() && !closed && LedgerApp.origin == origin &&
                                client.request("/mobile/sign-in/complete",listOf("code" to code, "code_verifier" to verifier,"redirect_uri" to redirect)).redirect == "/dashboard"
                            val message = if (ok) "Signed in. Return to Health Ledger." else "Sign-in did not finish."
                            socket.getOutputStream().write(("HTTP/1.1 " + (if(ok) "200 OK" else "400 Bad Request") +
                                "\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${message.length}\r\n\r\n$message").toByteArray())
                            if(valid) ok else null
                        }
                        if (result != null) return@use result
                    }
                    false
                }
            }.getOrDefault(false)
            activity.runOnUiThread { if (!closed) done(success) }
        }.start()
    }

    fun close() { closed=true; runCatching { listener?.close() } }
}
