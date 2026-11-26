package dev.lvtd.healthledger

import android.app.Application
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

// Only used to recover a signed-in installation of the original single-server app.
internal const val LEGACY_SERVER = "https://health-ledger.cr.lvtd.dev"

internal fun serverOrigin(raw: String): String {
    val text = raw.trim()
    require(text.isNotEmpty())
    val url = URI(if ("://" in text) text else "https://$text")
    require(url.scheme.equals("https", true) && url.host != null && url.rawUserInfo == null)
    require(url.rawPath in listOf("", "/") && url.rawQuery == null && url.rawFragment == null)
    require(!url.rawAuthority.endsWith(":"))
    require(url.port == -1 || url.port in 1..65535)
    return URI("https", null, url.host.lowercase(Locale.ROOT), if (url.port == 443) -1 else url.port, null, null, null).toString()
}

internal fun isLedgerUrl(raw: String, origin: String?): Boolean = runCatching {
    val uri = URI(raw)
    origin != null && uri.scheme.equals("https", true) && uri.rawUserInfo == null &&
        uri.host != null && serverOrigin("https://${uri.rawAuthority}") == origin
}.getOrDefault(false)

internal fun verifyServer(origin: String) {
    val request = URL("$origin/.well-known/health-ledger").openConnection() as HttpURLConnection
    try {
        request.instanceFollowRedirects = false
        request.connectTimeout = 10000
        request.readTimeout = 10000
        if (request.responseCode != 200) throw IOException("This address needs a Health Ledger server with mobile setup enabled.")
        val bytes = request.inputStream.use { it.readNBytes(4097) }
        require(bytes.size <= 4096)
        verifyDescriptor(origin, bytes.toString(Charsets.UTF_8))
    } finally { request.disconnect() }
}

internal fun verifyDescriptor(origin: String, json: String) {
    val data = JSONObject(json)
    if (data.optString("application") != "health-ledger" || data.optInt("mobile_api") != 1)
        throw IOException("This server is incompatible with this version of the app.")
    val advertised = data.getString("site_url")
    if (advertised != origin && serverOrigin(advertised) != origin)
        throw IOException("This address differs from the server’s SITE_URL. Use the address shown on its Sources page.")
}

class LedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        servers = getSharedPreferences("ledger-server", MODE_PRIVATE)
        if (!servers.contains("initialized")) {
            val legacySession = LedgerSession.forOrigin(LEGACY_SERVER)
            servers.edit().apply {
                if (legacySession != null) putString("origin", LEGACY_SERVER)
                putBoolean("initialized", true)
            }.apply()
        }
    }
    companion object {
        internal lateinit var servers: SharedPreferences
            private set
        internal val origin: String? get() = servers.getString("origin", null)
    }
}
