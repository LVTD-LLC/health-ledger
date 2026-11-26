package dev.lvtd.healthledger

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.*
import kotlinx.coroutines.*
import java.time.LocalDate

internal object SamsungReader {
    // The SDK property is for partner-approved distributions. Debug developer-mode data stays on device.
    fun available(context: Context): Boolean {
        val signer = BuildConfig.SAMSUNG_SIGNER
        if (BuildConfig.DEBUG || signer.isEmpty()) return false
        val digest = signer.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return context.packageManager.hasSigningCertificate(context.packageName, digest,
            android.content.pm.PackageManager.CERT_INPUT_SHA256)
    }
    private val permission = setOf(Permission.of(DataTypes.STEPS, AccessType.READ))
    fun authorize(activity: Activity, result: (Boolean) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val granted = runCatching {
                val store = HealthDataService.getStore(activity.applicationContext)
                if (!store.getGrantedPermissions(permission).containsAll(permission)) {
                    store.requestPermissions(permission, activity)
                }
                store.getGrantedPermissions(permission).containsAll(permission)
            }.getOrDefault(false)
            if (!activity.isDestroyed && !activity.isFinishing) result(granted)
        }
    }

    fun days(context: Context, start: LocalDate, end: LocalDate): Map<LocalDate, Long> = runBlocking {
        check(available(context))
        val store = HealthDataService.getStore(context)
        if (!store.getGrantedPermissions(permission).containsAll(permission)) throw SecurityException()
        val query = DataType.StepsType.TOTAL.requestBuilder.setLocalTimeFilterWithGroup(
            LocalTimeFilter.of(start.atStartOfDay(), end.atStartOfDay()), LocalTimeGroup.of(LocalTimeGroupUnit.DAILY, 1)
        ).setOrdering(Ordering.ASC).build()
        buildMap {
            for (row in store.aggregateData(query).dataList) {
                val count = row.value ?: continue
                if (count == 0L) continue
                val date = row.getStartLocalDateTime().toLocalDate()
                require(date >= start && date < end && count >= 0 && put(date, count) == null)
            }
        }
    }
}
