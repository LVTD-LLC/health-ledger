package dev.lvtd.healthledger

import android.app.Activity
import android.app.AlertDialog
import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.widget.LinearLayout
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Future

private const val JOB = 3143
private val Context.samsung get() = getSharedPreferences("samsung-daily", Context.MODE_PRIVATE)
private val samsungLock = Any()
private val samsungExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
internal fun stopSamsung(context: Context) = synchronized(samsungLock) {
    context.samsung.edit().clear().apply()
    context.getSystemService(JobScheduler::class.java).cancel(JOB)
}

private fun runSamsung(context: Context, expected: String, connection: String): Boolean {
    val session = LedgerSession.current() ?: return false
    fun active() = session.binding == expected && context.samsung.getString("owner", null) == expected &&
        context.samsung.getString("connection", null) == connection && LedgerSession.current()?.binding == expected
    if (!active() || !SamsungReader.available(context)) return false
    return try {
        // Retry a partially completed scan at the last acknowledged month. Recent corrections are replayed daily.
        val today = LocalDate.now()
        val last = context.samsung.getString("next", null)?.let(LocalDate::parse) ?: LocalDate.of(1970, 1, 1)
        var start = minOf(last, today.minusDays(7))
        while (start <= today) {
            if (!active() || Thread.currentThread().isInterrupted) return false
            val end = minOf(start.withDayOfMonth(1).plusMonths(1), today.plusDays(1))
            val days = SamsungReader.days(context, start, end)
            if (!active() || Thread.currentThread().isInterrupted) return false
            Ledger().sendSamsungDays(session, days)
            synchronized(samsungLock) {
                if (!active()) return false
                context.samsung.edit().putString("next", end.toString()).apply()
            }
            start = end
        }
        synchronized(samsungLock) {
            if (active()) context.samsung.edit().putString("status", "Synced through $today").apply()
        }
        false
    } catch (e: Exception) {
        synchronized(samsungLock) {
            if (active()) context.samsung.edit().putString("status", when (e) {
                is SecurityException -> "Step permission is missing. Reconnect Samsung Health."
                is SignInRequired -> "Sign in again and reconnect Samsung Health."
                else -> "Sync interrupted. Your next sync will resume."
            }).apply()
        }
        e !is SecurityException && e !is SignInRequired
    }
}

internal fun samsungSource(activity: Activity, ui: MobileUi, panel: LinearLayout, refresh: () -> Unit) {
    if (!SamsungReader.available(activity) || runCatching { activity.packageManager.getPackageInfo(SAMSUNG, 0) }.isFailure) return
    val session = LedgerSession.current() ?: return
    val prefs = activity.samsung
    val connected = prefs.getString("owner", null) == session.binding
    fun sync() {
        val connection = prefs.getString("connection", null) ?: return
        samsungExecutor.execute {
            runSamsung(activity.applicationContext, session.binding, connection)
            activity.runOnUiThread { if (!activity.isDestroyed && !activity.isFinishing) refresh() }
        }
    }
    ui.row(panel, "Samsung Health", if (connected) prefs.getString("status", null) ?: "Daily step sync enabled"
        else "Import historical daily steps") {
        val dialog = AlertDialog.Builder(activity).setTitle("Samsung Health")
        if (connected) dialog.setMessage("Daily steps sync to ${session.origin}.")
            .setPositiveButton("Sync now") { _, _ -> sync() }
            .setNegativeButton("Disconnect") { _, _ -> stopSamsung(activity); refresh() }
        else dialog.setMessage("Send available daily step totals to ${session.origin} and check for new days daily. Days already covered by step readings are skipped.")
            .setPositiveButton("Connect") { _, _ ->
                SamsungReader.authorize(activity) { allowed ->
                    if (LedgerSession.current()?.binding != session.binding) return@authorize
                    if (allowed) {
                        stopSamsung(activity)
                        prefs.edit().putString("owner", session.binding).putString("connection", java.util.UUID.randomUUID().toString()).apply()
                        val job = JobInfo.Builder(JOB, ComponentName(activity, SamsungJob::class.java))
                            .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPeriodic(Duration.ofDays(1).toMillis()).build()
                        if (activity.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS) sync()
                        else { stopSamsung(activity); android.widget.Toast.makeText(activity, "Android could not schedule sync. Try connecting again.", android.widget.Toast.LENGTH_LONG).show() }
                    } else android.widget.Toast.makeText(activity, "Samsung Health step access was unavailable.", android.widget.Toast.LENGTH_LONG).show()
                    refresh()
                }
            }.setNegativeButton("Cancel", null)
        dialog.show()
    }
}

class SamsungJob : JobService() {
    private var task: Future<*>? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val owner = samsung.getString("owner", null) ?: return false
        if (LedgerSession.current()?.binding != owner) { stopSamsung(this); return false }
        val connection = samsung.getString("connection", null) ?: return false
        task = samsungExecutor.submit {
            val retry = runSamsung(applicationContext, owner, connection)
            if (!Thread.currentThread().isInterrupted) jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(true); return samsung.contains("owner") }
}
