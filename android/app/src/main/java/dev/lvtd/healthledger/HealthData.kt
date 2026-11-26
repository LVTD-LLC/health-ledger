package dev.lvtd.healthledger

import android.health.connect.datatypes.*
import android.health.connect.datatypes.Record
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

internal const val SAMSUNG = "com.sec.android.app.shealth"
internal const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
internal const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

internal enum class Reading(val title: String, permission: String, val type: Class<out Record>) {
    Steps("Steps", "STEPS", StepsRecord::class.java),
    Heart("Heart rate", "HEART_RATE", HeartRateRecord::class.java),
    Resting("Resting heart rate", "RESTING_HEART_RATE", RestingHeartRateRecord::class.java),
    Sleep("Sleep", "SLEEP", SleepSessionRecord::class.java),
    Exercise("Exercise", "EXERCISE", ExerciseSessionRecord::class.java),
    Weight("Weight", "WEIGHT", WeightRecord::class.java),
    Distance("Distance", "DISTANCE", DistanceRecord::class.java),
    Pressure("Blood pressure", "BLOOD_PRESSURE", BloodPressureRecord::class.java),
    Oxygen("Oxygen saturation", "OXYGEN_SATURATION", OxygenSaturationRecord::class.java);

    val permission = "android.permission.health.READ_" + permission
}

internal fun sourceName(origin: String) = when (origin) {
    SAMSUNG -> "Samsung Health"
    "com.google.android.apps.fitness" -> "Google Fit"
    "com.fitbit.FitbitMobile" -> "Fitbit"
    else -> origin.ifBlank { "Health Connect" }
}

// A stable ID per output metric makes retries and overlapping history imports safe.
internal fun Record.ledgerRows(): Sequence<JSONObject> = sequence {
    val record = this@ledgerRows
    fun row(time: Instant, title: String, value: Number, unit: String,
            part: String = "value", kind: String = "Measurement"): JSONObject =
        JSONObject().put("id", "hc/${record.javaClass.simpleName}/${metadata.id}/$part")
            .put("timestamp", time.toString()).put("metric", title).put("value", value)
            .put("unit", unit).put("kind", kind).put("source", sourceName(metadata.dataOrigin.packageName))
    when (record) {
        is StepsRecord -> yield(row(record.startTime, "Steps", record.count, "steps"))
        is DistanceRecord -> yield(row(record.startTime, "Distance", record.distance.inMeters, "m"))
        is WeightRecord -> yield(row(record.time, "Weight", record.weight.inGrams / 1000.0, "kg"))
        is OxygenSaturationRecord -> yield(row(record.time, "Oxygen saturation", record.percentage.value, "%"))
        is RestingHeartRateRecord -> yield(row(record.time, "Resting heart rate", record.beatsPerMinute, "bpm"))
        is HeartRateRecord -> for (sample in record.samples) {
            yield(row(sample.time, "Heart rate", sample.beatsPerMinute, "bpm", sample.time.toString()))
        }
        is BloodPressureRecord -> {
            yield(row(record.time, "Systolic blood pressure", record.systolic.inMillimetersOfMercury, "mmHg", "systolic"))
            yield(row(record.time, "Diastolic blood pressure", record.diastolic.inMillimetersOfMercury, "mmHg", "diastolic"))
        }
        is SleepSessionRecord -> yield(row(record.startTime, "Sleep session",
            Duration.between(record.startTime, record.endTime).toMinutes(), "min", kind = "Sleep"))
        is ExerciseSessionRecord -> yield(row(record.startTime, record.title?.toString()?.take(160) ?: "Exercise session",
            Duration.between(record.startTime, record.endTime).toMinutes(), "min", kind = "Workout"))
    }
}
