package dev.lvtd.healthledger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

private val flows = mapOf(
    "/auth/google" to "/auth/google/callback", "/auth/github" to "/auth/github/callback",
    "/settings/google" to "/auth/google/callback", "/settings/github" to "/auth/github/callback",
    "/health/cloud/withings/connect" to "/health/cloud/withings/callback",
    "/health/cloud/hevy/connect" to "/health/cloud/hevy/callback",
    "/health/cloud/google-health/connect" to "/health/cloud/google-health/callback"
)

internal fun rememberProvider(context: Context, origin: String, home: String, title: String, action: String, url: String) {
    val callback = flows[action] ?: error("Unexpected provider")
    val uri = Uri.parse(url)
    require(uri.scheme=="https" && !uri.host.isNullOrEmpty())
    val state = uri.getQueryParameter("state") ?: error("Missing provider state")
    require(state.matches(Regex("android-[0-9a-f]{64}"))) { "Update your server for native provider authorization." }
    context.getSharedPreferences("provider-return",Context.MODE_PRIVATE).edit().clear()
        .putString("state",state).putString("callback",callback).putString("origin",origin)
        .putString("binding",LedgerSession.current()?.binding).putString("home",home).putString("title",title)
        .putLong("expires",System.currentTimeMillis()+600000).apply()
}

/** Receive only the outstanding provider flow for the currently selected account. */
class ProviderReturnActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try {
            val uri = intent.data ?: return
            val pending = getSharedPreferences("provider-return", MODE_PRIVATE)
            val expected = pending.getString("state",null) ?: return
            val callback = pending.getString("callback",null) ?: return
            if (uri.scheme!="healthledger" || uri.host!="provider-return" || uri.path!=callback ||
                uri.getQueryParameter("state")!=expected || pending.getLong("expires",0)<System.currentTimeMillis() ||
                LedgerApp.origin!=pending.getString("origin",null) || LedgerSession.current()?.binding!=pending.getString("binding",null)) return
            val result = Uri.Builder().path(callback).appendQueryParameter("state",expected).apply {
                for (key in listOf("code","error")) uri.getQueryParameter(key)?.let { appendQueryParameter(key,it) }
            }.build().toString()
            val next = Intent(this,LedgerScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("path",pending.getString("home",null)).putExtra("title",pending.getString("title",null)).putExtra("providerResult",result)
            pending.edit().clear().apply()
            startActivity(next)
        } finally { finish() }
    }
}
