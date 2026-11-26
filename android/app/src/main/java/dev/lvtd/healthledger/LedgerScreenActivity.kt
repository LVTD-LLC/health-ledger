package dev.lvtd.healthledger

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.util.concurrent.Executors

/** Native editors for the account and journal sections exposed by the selected instance.
 * The server owns validation, available actions, and the account's current source state. */
class LedgerScreenActivity : Activity() {
    private lateinit var ui: MobileUi
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var client: ScreenClient
    private lateinit var origin: String
    private lateinit var home: String
    private lateinit var path: String
    private var binding: String? = null
    private val io = Executors.newSingleThreadExecutor()
    private var busy = false
    private var fileCallback: ((Uri) -> Unit)? = null
    private var downloadPath: String? = null
    private var browserOpened = false
    private var browserLogin: BrowserLogin? = null
    private val isAuth get() = path.substringBefore('?') in listOf("/sign-in","/sign-up","/verify-email","/sign-in/two-factor")
    private val history = ArrayDeque<String>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        origin = LedgerApp.origin ?: return finish()
        if (state != null && state.getString("origin") != origin) return finish()
        home = intent.getStringExtra("path") ?: return finish()
        path = state?.getString("path") ?: home
        binding = state?.getString("binding") ?: LedgerSession.current()?.binding
        state?.getStringArrayList("history")?.let { history.addAll(it) }
        client = ScreenClient(origin, contentResolver)
        ui = MobileUi(this)
        val root = ui.column().apply {
            setBackgroundColor(ui.surface)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
            }
        }
        root.addView(ui.header(intent.getStringExtra("title") ?: "Health Ledger", ui.iconButton("Back",R.drawable.ic_back) { back() }))
        if (home == "/sign-in") ui.row(root,"Server",origin) { startActivity(Intent(this,ServerActivity::class.java)) }
        status = ui.text("",15,color=ui.muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(ui.dp(20),0,ui.dp(20),0)
        }
        root.addView(status)
        body = ui.column().apply { setPadding(ui.dp(20),0,ui.dp(20),ui.dp(24)) }
        scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll)
        setContentView(root)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        load(intent.getStringExtra("providerResult") ?: path)
        intent.removeExtra("providerResult")
    }

    private fun back() { if (history.isEmpty() || busy) finish() else load(history.removeLast()) }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("origin",origin)
        out.putString("binding",binding)
        out.putString("path", path.substringBefore('?'))
        out.putStringArrayList("history", ArrayList(history.map { it.substringBefore('?') }))
    }
    override fun onResume() {
        super.onResume()
        if (browserOpened && !busy && !isFinishing) { browserOpened = false; load(path) }
    }
    override fun onDestroy() { browserLogin?.close(); io.shutdownNow(); super.onDestroy() }

    private fun active() = !isFinishing && !isDestroyed && LedgerApp.origin == origin
    private fun load(target: String, fields: List<Pair<String,String>>? = null, file: SelectedFile? = null) {
        if (busy || !active()) return
        if (!isAuth && LedgerSession.current()?.binding != binding) { finish(); return }
        busy = true
        status.text = if (fields == null) "Loading…" else "Saving…"
        body.removeAllViews()
        fileCallback = null
        io.execute {
            val result = runCatching {
                var reply = client.request(target,fields,file)
                var redirects = 0
                while (reply.redirect != null) {
                    if (!isScreenUrl(resolveUrl(reply.path,reply.redirect!!).orEmpty(),origin)) break
                    val location = localPath(origin,reply.path,reply.redirect!!)
                    if (++redirects > 5) throw IOException("Too many redirects.")
                    val base = location.substringBefore('#').substringBefore('?')
                    if ((base == "/dashboard" && (home == "/sign-in" || isAuth)) || base == "/") break
                    val next = when {
                        base == home.substringBefore('#') && home.contains('#') -> location.substringBefore('#') + "#" + home.substringAfter('#')
                        base == "/settings" && path.startsWith("/settings#") -> location.substringBefore('#') + "#" + path.substringAfter('#')
                        else -> location
                    }
                    reply = client.request(next)
                }
                reply
            }
            runOnUiThread {
                if (!active()) return@runOnUiThread
                busy = false
                result.onSuccess { reply ->
                    if (reply.redirect != null) {
                        if (reply.redirect.startsWith("https://") && !isScreenUrl(reply.redirect, origin)) {
                            runCatching { rememberProvider(this,origin,home,intent.getStringExtra("title").orEmpty(),target,reply.redirect) }
                                .onSuccess { launchBrowser(reply.redirect) }
                                .onFailure { status.text="Update your server to authorize this provider."; button("Refresh") { load(path) } }
                        } else finish()
                        return@onSuccess
                    }
                    if (reply.path.substringBefore('?') == "/sign-in") binding = LedgerSession.current()?.binding
                    if (!isAuth && LedgerSession.current()?.binding != binding) { finish(); return@onSuccess }
                    // Validation responses belong to the editor that submitted the form.
                    if (fields == null || reply.path != target) path = reply.path
                    display(reply.document!!, reply.status)
                }.onFailure { failure ->
                    status.text = if (failure is IOException) failure.message ?: "Couldn’t reach your server." else "This screen is unavailable. Check your server and try again."
                    button("Refresh") { load(path) }
                }
            }
        }
    }

    private fun display(doc: Document, httpStatus: Int) {
        body.removeAllViews()
        scroll.scrollTo(0,0)
        val notices = doc.select("main [role=alert], main [role=status]:not(.api-key-reveal)").map { it.text() }.filter(String::isNotBlank).distinct()
        status.text = notices.joinToString("\n").ifEmpty { if (httpStatus == 422) "Check the form and try again." else "" }
        val fragment = path.substringAfter('#',"")
        val base = path.substringBefore('#').substringBefore('?')
        val main = doc.selectFirst("main") ?: doc.body()
        when {
            base == "/settings" && fragment.isEmpty() -> {
                // Notices and newly issued keys can appear outside an individual setting.
                doc.select(".api-key-reveal").forEach { renderNode(it,body) }
                for (panel in main.select("section.panel[id]")) {
                    val title = panel.selectFirst("h3,h2")?.text() ?: continue
                    ui.row(body,title) { navigate("/settings#${panel.id()}") }
                }
                if (main.select("section.panel[id]").isEmpty()) renderNode(main,body)
                button("Sign out") { confirm("Sign out of Health Ledger?") { load("/sign-out", emptyList()) } }
            }
            fragment == "manual-import" -> {
                val table = main.selectFirst("#manual-import table")
                if (table == null) paragraph(body,"No recent imports.") else renderNode(table,body)
                button("Refresh") { load(path) }
            }
            fragment == "appearance" -> appearance()
            fragment.isNotEmpty() -> {
                val section = main.getElementById(fragment)
                if (section == null) {
                    paragraph(body,"Update your Health Ledger server to use this feature.")
                } else renderNode(section,body)
                if (base == "/sources") button("Refresh status") { load(path) }
            }
            else -> renderNode(main,body)
        }
    }

    private fun appearance() {
        val mgr = getSystemService(UiModeManager::class.java)
        for ((label, mode) in listOf("System" to UiModeManager.MODE_NIGHT_AUTO, "Light" to UiModeManager.MODE_NIGHT_NO,"Dark" to UiModeManager.MODE_NIGHT_YES)) {
            ui.row(body,label) { mgr.setApplicationNightMode(mode) }
        }
    }
    private fun paragraph(parent: LinearLayout, text: String) {
        if (text.isNotBlank()) parent.addView(ui.text(text,16).apply { setPadding(0,ui.dp(6),0,ui.dp(6)); setTextIsSelectable(true) })
    }
    private fun button(label: String, parent: LinearLayout = body, action: () -> Unit) {
        parent.addView(Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } })
    }
    private fun confirm(label: String, action: () -> Unit) {
        AlertDialog.Builder(this).setMessage(label).setNegativeButton("Cancel",null).setPositiveButton("Continue") { _,_ -> action() }.show()
    }
    private fun navigate(target: String) { history.addLast(path); load(target) }
    private fun launchBrowser(url: String) {
        browserOpened = true
        runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }.onFailure { browserOpened = false; status.text = "No browser available." }
    }
    private fun startBrowserLogin() {
        if (browserLogin != null) return
        val login = BrowserLogin(origin)
        browserLogin = login
        status.text = "Complete sign-in in your browser, then return here."
        login.start(this,client) { success ->
            browserLogin = null
            if (active()) {
                if (success) finish() else status.text = "Browser sign-in did not finish. Try again or use your email and password."
            }
        }
    }

    private fun link(node: Element, parent: LinearLayout) {
        val href = node.attr("href")
        val label = node.text().ifBlank { node.attr("aria-label") }
        if (label.isBlank()) return
        val url = resolveUrl(path,href) ?: return
        when {
            url.startsWith("otpauth://totp/") -> button(label,parent) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            !isScreenUrl(url,origin) -> if (url.startsWith("https://")) button(label,parent) { launchBrowser(url) }
            else -> {
                val target = localPath(origin,path,href)
                when {
                    target.substringBefore('?').endsWith("/download") -> button(label,parent) { saveDocument(target,node.closest(".file-row")?.selectFirst("h3")?.text() ?: intent.getStringExtra("title") ?: "document") }
                    target.startsWith("/docs") || target.startsWith("/privacy") || target.startsWith("/terms") -> button(label,parent) { launchBrowser(url) }
                    target == "/dashboard" || target == "/sources" || target == "/dashboard#files" && home == target -> button(label,parent) { finish() }
                    else -> button(label,parent) { navigate(target) }
                }
            }
        }
    }
    private fun resolveUrl(from: String, target: String) = runCatching { java.net.URI(origin + from).resolve(target).toString() }.getOrNull()

    private fun renderNode(node: Element, parent: LinearLayout) {
        if (node.hasClass("qr")) {
            val svg=node.selectFirst("svg") ?: return
            val size=svg.attr("viewBox").substringAfterLast(' ').toIntOrNull()?.takeIf { it in 21..256 } ?: return
            val bitmap=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888)
            val canvas=android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint=android.graphics.Paint().apply { color=android.graphics.Color.BLACK }
            val runs=Regex("""M(\d+) (\d+)h(\d+)v1h-\d+z""").findAll(svg.select("path").attr("d"))
            for (run in runs) {
                val (x,y,len)=run.destructured
                canvas.drawRect(x.toFloat(),y.toFloat(),x.toFloat()+len.toFloat(),y.toFloat()+1,paint)
            }
            parent.addView(ImageView(this).apply {
                setImageBitmap(bitmap)
                (drawable as android.graphics.drawable.BitmapDrawable).isFilterBitmap=false
                contentDescription="Authenticator setup QR code"
            },-1,ui.dp(220))
            return
        }
        val tag = node.normalName()
        if (node.hasAttr("data-passkey-signin")) { button("Sign in with a passkey",parent,::startBrowserLogin); return }
        if (node.hasAttr("hidden") || tag in setOf("script","style","svg","img","nav","footer","noscript") || (node.attr("role") in setOf("alert","status") && !node.hasClass("api-key-reveal")) || node.hasClass("source-icon")) return
        when (tag) {
            "form" -> renderForm(node,parent)
            "h1","h2","h3","h4" -> if (node.text() != intent.getStringExtra("title")) parent.addView(ui.text(node.text(),if(tag=="h1") 23 else 18,true).apply { setPadding(0,ui.dp(14),0,ui.dp(6)) })
            "a" -> link(node,parent)
            "details" -> {
                val summary = node.children().firstOrNull { it.normalName()=="summary" }?.text() ?: "Details"
                val contents = ui.column()
                button(summary,parent) { contents.visibility = if (contents.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
                parent.addView(contents)
                node.children().filter { it.normalName() != "summary" }.forEach { renderNode(it,contents) }
                contents.visibility = if (node.hasAttr("open")) View.VISIBLE else View.GONE
            }
            "tr" -> {
                if (node.parent()?.normalName() == "thead") return
                val headings = node.closest("table")?.select("thead th").orEmpty()
                for ((i,cell) in node.children().withIndex()) {
                    if (cell.select("a,form,button").isEmpty()) paragraph(parent,listOfNotNull(headings.getOrNull(i)?.text(),cell.text()).joinToString(" · "))
                    else renderNode(cell,parent)
                }
                ui.line(parent)
            }
            "p","li","pre","code","strong","span","small","time","th","td","label" -> {
                if (node.select("form, a, button, ul, ol").isEmpty()) paragraph(parent,if(tag=="pre") node.wholeText() else node.text())
                else node.childNodes().forEach { child ->
                    if (child is Element) renderNode(child,parent)
                    else if (child is org.jsoup.nodes.TextNode) paragraph(parent,child.text().trim())
                }
            }
            else -> node.children().forEach { renderNode(it,parent) }
        }
    }

    private fun renderForm(form: Element, parent: LinearLayout) {
        val action = localPath(origin,path, form.attr("action").ifEmpty { path })
        val label = form.selectFirst("button")?.text().orEmpty()
        // Provider consent and browser-bound verification run in the user's system browser.
        if (form.hasAttr("data-passkey-register")) {
            button(label,parent) { launchBrowser(origin + path) }; return
        }
        val editor = NativeForm(this,ui,form,parent, { field,mime, callback ->
            fileCallback = { uri ->
                runCatching { selectedFile(contentResolver,field,uri) }.onSuccess(callback).onFailure { status.text = "Choose a readable file up to 32 MiB." }
            }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime),1)
        })
        editor.buttons { button, fields, file ->
            val post = { load(action,fields,file) }
            if (button.contains("delete",true) || button.contains("disconnect",true) || button.contains("revoke",true)) confirm("$button?",post) else post()
        }
    }
    private fun saveDocument(target: String, name: String) {
        downloadPath = target
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,name),2)
    }
    @Deprecated("Activity result")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request,result,data)
        if (result != RESULT_OK || !active() || binding != LedgerSession.current()?.binding) return
        val uri = data?.data ?: return
        if (request == 1) { fileCallback?.invoke(uri); fileCallback=null }
        if (request == 2) downloadPath?.let { target ->
            io.execute {
                val ok = runCatching { client.download(target,uri) }.isSuccess
                runOnUiThread { if (active()) status.text = if (ok) "Document saved." else "Could not save document." }
            }
        }
    }
}
