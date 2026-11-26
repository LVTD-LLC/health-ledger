package dev.lvtd.healthledger

import android.app.Activity
import android.app.AlertDialog
import android.provider.OpenableColumns
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.os.Build
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.WindowInsets
import android.widget.*
import java.text.DateFormat
import java.util.Date

internal const val PRIVACY = "Health Ledger sends the health readings you authorize to the server you select at sign-in. You can pause sync or revoke access in Android Settings. Removing a reading from your phone keeps its journal copy."

internal enum class ArchiveFormat(val title: String, val extensions: Set<String>) {
    Samsung("Samsung Health", setOf("zip","csv")), Fitbit("Fitbit", setOf("zip","json")),
    Apple("Apple Health", setOf("zip","xml")), Strava("Strava", setOf("zip","csv")),
    Csv("CSV import", setOf("csv","tsv")), Labs("FHIR labs", setOf("json"))
}

class PrivacyActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AlertDialog.Builder(this).setTitle("Health data").setMessage(PRIVACY)
            .setPositiveButton("Done") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
}

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var ui: MobileUi
    private lateinit var trends: TrendsScreen
    private lateinit var journal: JournalScreen
    private lateinit var pages: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var nativePane: ScrollView
    private var destination = Destination.Trends
    private var syncDetail = false
    private var running = false
    private var pendingHours = 24
    private var archiveBinding: String? = null
    private var archiveFormat = ArchiveFormat.Samsung
    private var backRegistered = false
    private val back = OnBackInvokedCallback {
        when {
            syncDetail -> { syncDetail = false; render() }
            else -> select(Destination.Trends)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = MobileUi(this)
        val root = ui.column().apply {
            setBackgroundColor(ui.surface)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        pages = FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        journal = JournalScreen(this, ui, { openScreen("/sign-in","Sign in") }, {
            if (LedgerSession.current() == null) openScreen("/sign-in","Sign in")
            else startActivityForResult(Intent(this,EntryActivity::class.java),8)
        })
        trends = TrendsScreen(this, ui, { openScreen("/sign-in","Sign in") }, { select(Destination.Sources) })
        pages.addView(trends.view)
        pages.addView(journal.view)
        nativePane = ScrollView(this)
        pages.addView(nativePane)
        ui.line(root)
        tabs = ui.column()
        root.addView(tabs)
        setContentView(root)
        destination = Destination.valueOf(state?.getString("destination") ?: "Trends")
        syncDetail = state?.getBoolean("syncDetail") == true
        pendingHours = state?.getInt("pendingHours", 24) ?: 24
        archiveBinding = state?.getString("archiveBinding")
        archiveFormat = ArchiveFormat.valueOf(state?.getString("format") ?: "Samsung")
        trends.restore(state?.getString("trendMetric"), state?.getString("trendRange"), state?.getString("trendMode"))
        journal.restore(state?.getString("search").orEmpty(), state?.getInt("offset") ?: 0)
        if (LedgerApp.origin == null) {
            startActivity(Intent(this, ServerActivity::class.java))
            finish()
        } else if (state == null && (LedgerSession.current() == null || intent.getBooleanExtra("signIn", false))) {
            openScreen("/sign-in","Sign in")
        } else render()
    }

    override fun onResume() {
        super.onResume()
        syncPrefs.registerOnSharedPreferenceChangeListener(this)
        val bound = syncPrefs.getString("binding", null)
        if (bound != null && bound != LedgerSession.current()?.binding) disconnect()
        if (!isFinishing) render()
    }
    override fun onPause() {
        syncPrefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("origin", LedgerApp.origin)
        out.putString("destination",destination.name)
        out.putBoolean("syncDetail",syncDetail)
        out.putString("trendMetric", trends.metricId)
        out.putString("trendRange", trends.range.name)
        out.putString("trendMode", trends.mode.name)
        out.putString("search",journal.query)
        out.putInt("offset",journal.offset)
        out.putInt("pendingHours",pendingHours)
        out.putString("archiveBinding",archiveBinding)
        out.putString("format",archiveFormat.name)
        super.onSaveInstanceState(out)
    }
    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (destination == Destination.Sources && key in listOf("message","last","binding","automatic","archiveStatus","fullHistory","hours","allApps")) render()
    }
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 8 && result == RESULT_OK) journal.show(true)
        if (request == 7 && result == RESULT_OK) data?.data?.let { importArchive(it) }
    }
    private fun updateBackNavigation() {
        val needed = destination != Destination.Trends
        if (needed == backRegistered) return
        if (needed) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
        backRegistered = needed
    }
    override fun onDestroy() {
        if (backRegistered) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
        trends.close()
        journal.close()
        super.onDestroy()
    }
    private fun openScreen(path: String, title: String) {
        startActivity(Intent(this, LedgerScreenActivity::class.java).putExtra("path", path).putExtra("title", title))
    }
    private fun select(target: Destination) {
        val refreshTrends = target == Destination.Trends
        val refresh = destination == target && target == Destination.Journal
        syncDetail = false
        destination = target
        nativePane.scrollTo(0,0)
        render(refreshTrends)
        if (refresh) journal.show(true)
    }
    private fun render(refreshTrends: Boolean = false) {
        updateBackNavigation()
        journal.view.visibility = if (destination == Destination.Journal) View.VISIBLE else View.GONE
        trends.view.visibility = if (destination == Destination.Trends) View.VISIBLE else View.GONE
        nativePane.visibility = if ((destination == Destination.Sources || destination == Destination.More)) View.VISIBLE else View.GONE
        tabs.removeAllViews()
        tabs.addView(ui.navigation(destination,::select))
        if (destination == Destination.Trends) { trends.show(refreshTrends); return }
        if (destination == Destination.Journal) { journal.show(); return }
        val scroll = nativePane.scrollY
        nativePane.removeAllViews()
        val panel = ui.column()
        nativePane.addView(panel)
        when {
            destination == Destination.More -> more(panel)
            syncDetail -> syncSettings(panel)
            else -> sources(panel)
        }
        nativePane.post { nativePane.scrollTo(0,scroll) }
    }
    private fun sources(panel: LinearLayout) {
        panel.addView(ui.header("Sources"))
        val session = LedgerSession.current()
        val bound = session != null && syncPrefs.getString("binding",null) == session.binding
        ui.section(panel,"On this phone")
        ui.row(panel,"Health Connect", if (bound) "${scopeLabel()} · ${scheduleLabel()}" else "Sync readings shared by your Android apps") {
            syncDetail = true; nativePane.scrollTo(0,0); render()
        }
        samsungSource(this, ui, panel) { if (destination == Destination.Sources) render() }
        ui.section(panel,"File imports")
        ui.row(panel,"Import an export","Samsung Health, Fitbit, Apple Health, Strava, CSV or labs") {
            if (session == null) openScreen("/sign-in","Sign in")
            else AlertDialog.Builder(this).setTitle("Import from").setItems(ArchiveFormat.entries.map { it.title }.toTypedArray()) { _, i ->
                archiveFormat = ArchiveFormat.entries[i]
                archiveBinding = session.binding
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),7)
            }.show()
        }
        syncPrefs.getString("archiveStatus",null)?.let { ui.paragraph(panel,it) }
        ui.row(panel,"Import history","Files and processing status") { openScreen("/sources#manual-import","Import history") }
        ui.section(panel,"Cloud services")
        for ((name, anchor, description) in listOf(
            Triple("PICOOC", "source-picooc", "Scale weight and body composition"),
            Triple("RENPHO", "source-renpho", "Scale and smart tape"),
            Triple("Intervals.icu", "source-intervals", "Wellness and training"),
            Triple("Withings", "source-withings", "Body measurements and blood pressure"),
            Triple("Hevy", "source-hevy", "Workouts and exercise sets"),
            Triple("Google Health / Fitbit", "source-google-health", "Activity, sleep and heart rate")
        )) {
            ui.row(panel,name,description) { openScreen("/sources#$anchor", name) }
        }
    }
    private fun chooseServer() { startActivity(Intent(this, ServerActivity::class.java)) }
    private fun more(panel: LinearLayout) {
        panel.addView(ui.header("More"))
        ui.section(panel,"Your journal")
        for ((title, path) in listOf("Medicines" to "/dashboard#medicines","Documents" to "/dashboard#files","Reminders" to "/dashboard#reminders"))
            ui.row(panel,title) { openScreen(path,title) }
        ui.section(panel,"Account")
        ui.row(panel,"Server address", LedgerApp.origin.orEmpty(), ::chooseServer)
        ui.row(panel,"Account settings") { openScreen("/settings","Account settings") }
        ui.row(panel,"Data and permissions",action=::privacy)
    }
    private fun scopeLabel() = if (syncPrefs.getBoolean("allApps",false)) "All sharing apps" else "Samsung Health"
    private fun scheduleLabel() = when {
        !syncPrefs.getBoolean("automatic",false) || !granted(BACKGROUND) -> "Manual"
        syncPrefs.getInt("hours",1) == 24 -> "Daily"
        else -> "Hourly"
    }
    private fun syncSettings(panel: LinearLayout) {
        panel.addView(ui.header("Health Connect",ui.iconButton("Back to sources",R.drawable.ic_back) { syncDetail=false; render() }))
        val session = LedgerSession.current()
        if (session == null) {
            ui.row(panel,"Sign in","Choose the journal to sync with") { openScreen("/sign-in","Sign in") }
            return
        }
        if (syncPrefs.getString("binding",null) != session.binding) {
            ui.paragraph(panel,"Choose which Health Connect readings to send to ${session.origin}.")
            packageManager.getLaunchIntentForPackage(SAMSUNG)?.let { launch ->
                ui.row(panel, "Open Samsung Health", "In Settings → Health Connect, allow writing the readings you want to share.") {
                    runCatching { startActivity(launch) }.onFailure { toast("Open Samsung Health on this phone.") }
                }
            }
            ui.row(panel,"Connect Health Connect") {
                syncPrefs.edit().putString("binding",session.binding).putBoolean("allApps",true).remove("message").remove("last").apply()
                if (readings().isEmpty()) requestReadings() else foregroundSync()
            }
            return
        }
        ui.section(panel,"Connection")
        ui.row(panel,"Source apps",scopeLabel()) {
            if (syncPrefs.getBoolean("allApps",false)) privacy()
            else AlertDialog.Builder(this).setTitle("Include other apps?")
                .setMessage("Import the permitted reading types from every app that shares them with Health Connect. Each reading keeps its source.")
                .setPositiveButton("Include all apps") { _, _ -> syncPrefs.edit().putBoolean("allApps",true).apply(); foregroundSync() }
                .setNegativeButton("Cancel",null).show()
        }
        ui.row(panel,"Permissions",readings().joinToString(", ") { it.title }.ifBlank { "Choose reading types" }) {
            if (readings().isEmpty()) requestReadings() else permissions()
        }
        val last = syncPrefs.getLong("last",0)
        val status = if (last == 0L) "Ready for first sync" else "Last checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(last))
        ui.section(panel,"Sync")
        ui.row(panel,if (running) "Syncing…" else "Sync now",status) { if (!running) { if (readings().isEmpty()) requestReadings() else foregroundSync() } }
        syncPrefs.getString("message",null)?.let { ui.paragraph(panel,it) }
        ui.row(panel,"Schedule",scheduleLabel()) {
            AlertDialog.Builder(this).setTitle("Sync schedule").setItems(arrayOf("Daily","Hourly","Manual")) { _, i ->
                when (i) { 0 -> enableSchedule(24); 1 -> enableSchedule(1); else -> { schedule(this,false); render() } }
            }.show()
        }
        val full = syncPrefs.getBoolean("fullHistory",false) && granted(HISTORY)
        ui.row(panel,"History",if (full) "All available history" else "Recent 30 days") {
            if (full) toast("All available history is enabled.")
            else if (granted(HISTORY)) enableHistory()
            else if (supportsExtendedAccess()) requestPermissions(arrayOf(HISTORY),5)
            else toast("Update Google Play system components or import a source archive.")
        }
        ui.row(panel,"Disconnect") {
            AlertDialog.Builder(this).setTitle("Disconnect Health Connect?").setMessage("Scheduled imports will stop. Your journal entries will remain available.")
                .setPositiveButton("Disconnect") { _,_ -> disconnect();render() }.setNegativeButton("Cancel",null).show()
        }
    }
    private fun permissions() {
        runCatching { startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")) }
            .onFailure { toast("Open Health Connect in Android Settings.") }
    }
    private fun privacy() {
        AlertDialog.Builder(this).setTitle("Data and permissions").setMessage(PRIVACY)
            .setPositiveButton("Done",null).setNeutralButton("Health Connect") { _,_ -> permissions() }.show()
    }
    private fun supportsExtendedAccess() =
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) >= 13
    private fun enableSchedule(hours: Int) {
        pendingHours = hours
        when {
            readings().isEmpty() -> requestReadings()
            granted(BACKGROUND) -> {
                if (!schedule(this, true, hours)) toast("Android could not schedule the job. Try again.")
            }
            supportsExtendedAccess() -> requestPermissions(arrayOf(BACKGROUND), 3)
            else -> toast("Update Google Play system components to enable background access.")
        }
        render()
    }
    private fun enableHistory() {
        syncPrefs.edit().putBoolean("fullHistory", true).apply()
        foregroundSync()
    }
    private fun importArchive(uri: Uri) {
        val session = LedgerSession.current() ?: return
        val owner = archiveBinding
        archiveBinding = null
        if (session.binding != owner) return
        val format = archiveFormat
        syncPrefs.edit().putString("archiveStatus", "Uploading archive…").apply()
        syncExecutor.execute {
            val message = try {
                require(uri.scheme == "content")
                val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
                val info = contentResolver.query(uri, columns, null, null, null)?.use {
                    check(it.moveToFirst())
                    it.getString(0) to it.getLong(1)
                } ?: error("File unavailable")
                require(info.first.substringAfterLast('.').lowercase() in format.extensions)
                if (LedgerSession.current()?.binding != owner) return@execute
                contentResolver.openInputStream(uri)?.use { Ledger().uploadFile(session, info.first, it, info.second, format.title) }
                    ?: error("File unavailable")
                "Archive accepted. Check Data sources → Recent imports in your journal."
            } catch (e: SignInRequired) { "Sign in again before uploading." }
            catch (e: IllegalArgumentException) { "Choose a supported file up to 32 MiB. Extract larger ZIPs first." }
            catch (e: Exception) { "Upload failed. Check your connection and try the file again." }
            if (LedgerSession.current()?.binding == owner) syncPrefs.edit().putString("archiveStatus", message).apply()
        }
    }
    private fun disconnect() {
        schedule(this, false)
        syncPrefs.edit().clear().apply()
    }
    private fun requestReadings() {
        requestPermissions(Reading.entries.map { it.permission }.toTypedArray(), 2)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 2 && readings().isNotEmpty()) foregroundSync()
        if (code == 3) {
            if (granted(BACKGROUND)) schedule(this, true, pendingHours) else toast("Background access is needed for scheduled sync.")
        }
        if (code == 5) {
            if (granted(HISTORY)) enableHistory() else toast("Recent readings can still sync. You can also import a Samsung archive.")
        }
        if (code in listOf(2, 3, 5)) render()
    }
    private fun foregroundSync() {
        if (running) return
        running = true
        render()
        val app = applicationContext
        syncExecutor.execute {
            syncOnce(app)
            runOnUiThread {
                running = false
                if (!isDestroyed && destination == Destination.Sources) render()
            }
        }
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
