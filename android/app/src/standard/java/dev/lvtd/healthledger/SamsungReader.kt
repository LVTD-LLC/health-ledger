package dev.lvtd.healthledger

import android.app.Activity
import android.content.Context
import java.time.LocalDate

internal object SamsungReader {
    fun available(context: Context) = false
    fun authorize(activity: Activity, result: (Boolean) -> Unit) = result(false)
    fun days(context: Context, start: LocalDate, end: LocalDate): Map<LocalDate, Long> =
        error("This build uses Health Connect.")
}
