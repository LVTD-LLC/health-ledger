package dev.lvtd.healthledger

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.*
import android.health.connect.changelog.*
import android.health.connect.datatypes.DataOrigin
import android.health.connect.datatypes.Record
import android.os.OutcomeReceiver
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.*

internal fun Context.granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
internal val Context.syncPrefs get() = getSharedPreferences("samsung-sync", Context.MODE_PRIVATE)
internal fun Context.readings() = Reading.entries.filter { granted(it.permission) }
internal val syncExecutor = Executors.newSingleThreadExecutor()

private fun <T> healthCall(call: (Executor, OutcomeReceiver<T, HealthConnectException>) -> Unit): T {
    val future = CompletableFuture<T>()
    call(Executor { it.run() }, object : OutcomeReceiver<T, HealthConnectException> {
        override fun onResult(result: T) { future.complete(result) }
        override fun onError(error: HealthConnectException) { future.completeExceptionally(error) }
    })
    try { return future.get(45, TimeUnit.SECONDS) }
    catch (e: ExecutionException) { throw e.cause ?: e }
}

internal fun checkpointKey(binding: String, reading: Reading, allApps: Boolean): String =
    if (allApps) "$binding/all-apps/${reading.name}" else "$binding/${reading.name}"

internal class PhoneSync(private val context: Context) {
    private val health = context.getSystemService(HealthConnectManager::class.java)
    private val prefs = context.syncPrefs
    // Pre-upgrade connections consented to Samsung only. Their original tokens stay valid.
    private val allApps = prefs.getBoolean("allApps", false)
    private val origin = if (allApps) null else DataOrigin.Builder().setPackageName(SAMSUNG).build()
    private val ledger = Ledger()

    fun run(): Int {
        val session = LedgerSession.current() ?: throw SignInRequired()
        val binding = session.binding
        fun checkActive() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (prefs.getString("binding", null) != binding || LedgerSession.current()?.binding != binding) throw SignInRequired()
        }
        checkActive()
        val types = context.readings()
        if (types.isEmpty()) throw SecurityException()
        ledger.verify(session)
        var total = 0
        fun send(records: List<Record>) {
            val rows = records.asSequence().filter { origin == null || it.metadata.dataOrigin.packageName == origin.packageName }
                .flatMap { it.ledgerRows() }
            for (batch in rows.chunked(500)) {
                checkActive()
                ledger.upload(session, batch)
                total += batch.size
            }
        }
        for (reading in types) {
            checkActive()
            val key = checkpointKey(binding, reading, allApps)
            var token = prefs.getString("$key/token", null)
            if (token == null) {
                val request = ChangeLogTokenRequest.Builder().addRecordType(reading.type).apply { origin?.let(::addDataOriginFilter) }.build()
                token = healthCall<ChangeLogTokenResponse> { ex, cb -> health.getChangeLogToken(request, ex, cb) }.token
                prefs.edit().putString("$key/token", token).putLong("$key/end", System.currentTimeMillis()).commit()
            }
            val wantsAll = prefs.getBoolean("fullHistory", false) && context.granted(HISTORY)
            if (wantsAll && !prefs.getBoolean("$key/all", false)) {
                // A newly granted history permission extends even a previously completed first import.
                prefs.edit().putBoolean("$key/all", true).putLong("$key/end", System.currentTimeMillis())
                    .remove("$key/page").commit()
            }
            val end = prefs.getLong("$key/end", 0)
            if (end != 0L) {
                val until = Instant.ofEpochMilli(end)
                val from = if (wantsAll) Instant.EPOCH else until.minus(Duration.ofDays(30))
                history(reading.type, from, until, key, ::checkActive, ::send)
            }
            do {
                checkActive()
                val request = ChangeLogsRequest.Builder(token!!).setPageSize(200).build()
                val changes = try { healthCall<ChangeLogsResponse> { ex, cb -> health.getChangeLogs(request, ex, cb) } }
                catch (error: HealthConnectException) {
                    if (error.errorCode == HealthConnectException.ERROR_INVALID_ARGUMENT) {
                        prefs.edit().remove("$key/token").remove("$key/page").remove("$key/end").remove("$key/all").commit()
                    }
                    throw error
                }
                send(changes.upsertedRecords)
                token = changes.nextChangesToken
                prefs.edit().putString("$key/token", token).commit()
            } while (changes.hasMorePages())
        }
        return total
    }

    private fun <T : Record> history(type: Class<T>, from: Instant, end: Instant, key: String, check: () -> Unit, send: (List<Record>) -> Unit) {
        val filter = TimeInstantRangeFilter.Builder().setStartTime(from).setEndTime(end).build()
        var page = prefs.getLong("$key/page", -1)
        do {
            check()
            val builder = ReadRecordsRequestUsingFilters.Builder(type).apply { origin?.let(::addDataOrigins) }.setTimeRangeFilter(filter).setPageSize(200)
            if (page != -1L) builder.setPageToken(page)
            val result = healthCall<ReadRecordsResponse<T>> { ex, cb -> health.readRecords(builder.build(), ex, cb) }
            send(result.records)
            page = result.nextPageToken
            val update = prefs.edit()
            if (page == -1L) update.remove("$key/end").remove("$key/page") else update.putLong("$key/page", page)
            update.commit()
        } while (page != -1L)
    }
}

internal fun syncOnce(context: Context): Boolean {
    val prefs = context.syncPrefs
    val binding = prefs.getString("binding", null) ?: return false
    fun report(message: String) {
        if (prefs.getString("binding", null) == binding) prefs.edit().putString("message", message).apply()
    }
    try {
        val count = PhoneSync(context).run()
        if (prefs.getString("binding", null) == binding) {
            report(if (count == 0) "No new readings." else "Sent $count readings for import.")
            prefs.edit().putLong("last", System.currentTimeMillis()).apply()
        }
        return false
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        return true
    } catch (e: SignInRequired) {
        report("Sign in to your journal and reconnect sync.")
        if (prefs.getString("binding", null) == binding) schedule(context, false)
        return false
    } catch (e: SecurityException) {
        report("Check your Health Connect read permissions.")
        return false
    } catch (e: Exception) {
        report(when (e) {
            is HealthConnectException -> "Health Connect is unavailable (code ${e.errorCode}). Try again."
            is IOException -> "Couldn’t reach your journal. Check your connection and retry."
            else -> "Sync was interrupted. Try again."
        })
        return true
    }
}

internal fun schedule(context: Context, enabled: Boolean, hours: Int = 24): Boolean {
    val scheduler = context.getSystemService(JobScheduler::class.java)
    val id = 3142
    if (!enabled) {
        scheduler.cancel(id)
        context.syncPrefs.edit().putBoolean("automatic", false).apply()
        return true
    }
    val job = JobInfo.Builder(id, ComponentName(context, SyncJob::class.java))
        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        .setPersisted(true).setPeriodic(Duration.ofHours(hours.toLong()).toMillis()).build()
    val accepted = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    context.syncPrefs.edit().putBoolean("automatic", accepted).putInt("hours", hours).apply()
    return accepted
}

class SyncJob : JobService() {
    private var task: Future<*>? = null
    override fun onStartJob(params: JobParameters): Boolean {
        if (!syncPrefs.getBoolean("automatic", false)) return false
        if (!granted(BACKGROUND)) {
            syncPrefs.edit().putString("message", "Grant background access to resume scheduled sync.").apply()
            return false
        }
        task = syncExecutor.submit {
            val retry = syncOnce(applicationContext)
            if (!Thread.currentThread().isInterrupted) jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel(true)
        return syncPrefs.getBoolean("automatic", false)
    }
}
