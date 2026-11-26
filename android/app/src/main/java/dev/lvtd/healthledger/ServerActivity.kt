package dev.lvtd.healthledger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.*
import java.io.IOException
import java.util.concurrent.Executors

class ServerActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val ui = MobileUi(this)
        val form = ui.column().apply { setPadding(ui.dp(24), ui.dp(36), ui.dp(24), ui.dp(24)) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ui.surface)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            addView(form)
        })
        form.addView(ui.text("Connect to your server", 28, true))
        form.addView(ui.text("Use the address where you open Health Ledger in your browser.", 16, color = ui.muted)
            .apply { setPadding(0, ui.dp(12), 0, ui.dp(24)) })
        val addressId = android.view.View.generateViewId()
        form.addView(ui.text("Server address", 16, true).apply { labelFor = addressId })
        val address = EditText(this).apply {
            id = addressId
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = "https://health.example.org"
            val link = intent.data?.takeIf { it.scheme == "healthledger" && it.host == "connect" && it.isHierarchical }
            setText(state?.getString("draft") ?: link?.getQueryParameter("server") ?: LedgerApp.origin.orEmpty())
        }
        field = address
        form.addView(address)
        val error = ui.text("", 15).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val next = Button(this).apply { text = "Continue to sign in" }
        form.addView(next)
        form.addView(error)
        fun submit() {
            if (!next.isEnabled) return
            val origin = runCatching { serverOrigin(address.text.toString()) }.getOrNull()
            if (origin == null) { address.error = "Enter an HTTPS server address, including its port if needed."; return }
            next.isEnabled = false
            address.isEnabled = false
            error.text = "Checking server…"
            io.execute {
                val result = runCatching { verifyServer(origin) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    next.isEnabled = true
                    address.isEnabled = true
                    error.text = ""
                    result.onSuccess {
                        val previous = LedgerApp.origin
                        if (previous != null && previous != origin)
                            AlertDialog.Builder(this).setTitle("Switch servers?")
                                .setMessage("Sign out on this phone and stop its sync to $previous. Then sign in at $origin.")
                                .setNegativeButton("Cancel", null).setPositiveButton("Switch") { _, _ -> connect(origin) }.show()
                        else connect(origin)
                    }.onFailure {
                        error.text = if (it is IOException && it.javaClass == IOException::class.java) it.message
                            else "Couldn’t verify this server. Check its address, HTTPS certificate and your network or VPN."
                    }
                }
            }
        }
        next.setOnClickListener { submit() }
        address.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { submit(); true } else false }
    }
    private lateinit var field: EditText
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("draft", field.text.toString()) }

    private fun connect(origin: String) {
        fun open() {
            LedgerApp.servers.edit().putString("origin", origin).apply()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("signIn", LedgerSession.current() == null))
            finish()
        }
        if (LedgerApp.origin == origin) { open(); return }
        // Disable uploads before replacing the WebView cookie jar (cookies are shared across ports).
        schedule(this, false)
        stopSamsung(this)
        syncPrefs.edit().clear().apply()
        LedgerApp.servers.edit().remove("origin").apply()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            open()
        }
    }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
