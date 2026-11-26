package dev.lvtd.healthledger

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import java.util.concurrent.Executors

internal class TrendsScreen(
    private val activity: Activity, private val ui: MobileUi,
    private val signIn: () -> Unit, private val sources: () -> Unit
) {
    val view = ui.column()
    private val body = ui.column()
    private val repository = TrendsRepository()
    private val io = Executors.newSingleThreadExecutor()
    private var owner: String? = null
    private var request = 0
    private var loading = false
    private var data: TrendSeries? = null
    var metricId: String? = null
        private set
    var range = TrendRange.Month
        private set
    var mode = ChartMode.Average
        private set

    init {
        view.addView(ui.header("Trends"))
        body.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(32))
        view.addView(ScrollView(activity).apply { addView(body) })
    }

    fun restore(id: String?, rangeName: String?, modeName: String?) {
        metricId = id
        range = TrendRange.entries.find { it.name == rangeName } ?: TrendRange.Month
        mode = ChartMode.entries.find { it.name == modeName } ?: ChartMode.Average
    }

    fun close() { request++; io.shutdownNow() }

    fun show(refresh: Boolean = false) {
        val session = LedgerSession.current()
        if (owner != null && owner != session?.binding) {
            request++
            data = null
            loading = false
            restore(null, null, null)
        }
        owner = session?.binding
        if (session == null) {
            body.removeAllViews()
            ui.row(body, "Sign in", "See trends from your journal", signIn)
            return
        }
        if (!refresh && (loading || data != null)) return
        val serial = ++request
        val selected = metricId
        val window = range
        loading = true
        body.removeAllViews()
        body.addView(ProgressBar(activity).apply { contentDescription = "Loading trends" }, -1, ui.dp(48))
        io.execute {
            val result = runCatching { repository.load(session, selected, window, refresh) }
            activity.runOnUiThread {
                if (activity.isDestroyed || request != serial) return@runOnUiThread
                loading = false
                if (LedgerSession.current()?.binding != owner) { show(); return@runOnUiThread }
                body.removeAllViews()
                result.onSuccess {
                    metricId = it.selected
                    data = it
                    draw(it)
                }.onFailure {
                    data = null
                    if (it is SignInRequired) ui.row(body, "Sign in to see trends", action = signIn)
                    else ui.row(body, "Couldn’t load trends", "Tap to retry") { show(true) }
                }
            }
        }
    }

    private fun select(id: String?, window: TrendRange) {
        metricId = id
        range = window
        data = null
        show()
    }

    private fun selector(labels: List<String>, active: Int, select: (Int) -> Unit): View =
        RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, ui.dp(12), 0, ui.dp(8))
            labels.forEachIndexed { index, label ->
                addView(RadioButton(activity).apply {
                    id = View.generateViewId()
                    buttonDrawable = null
                    text = label
                    gravity = Gravity.CENTER
                    minimumHeight = ui.dp(48)
                    setTextColor(if (index == active) ui.accent else ui.muted)
                    background = ui.interactive(if (index == active) ui.tint else ui.surface, 10)
                    isChecked = index == active
                    setOnClickListener { if (index != active) select(index) }
                }, RadioGroup.LayoutParams(0, -2, 1f))
            }
        }

    internal fun draw(series: TrendSeries) {
        body.removeAllViews()
        val active = series.metrics.find { it.id == series.selected }
        if (active == null) {
            body.addView(ui.text("No measurements yet", 21, true))
            ui.row(body, "Connect a source", "Import readings to see your trends", sources)
            return
        }
        ui.row(body, active.label, "Choose measurement") {
            AlertDialog.Builder(activity).setTitle("Measurement")
                .setSingleChoiceItems(series.metrics.map { it.label }.toTypedArray(), series.metrics.indexOf(active)) { d, i ->
                    d.dismiss()
                    if (i != series.metrics.indexOf(active)) select(series.metrics[i].id, range)
                }.setNegativeButton("Cancel", null).show()
        }
        body.addView(selector(TrendRange.entries.map { it.label }, range.ordinal) { select(metricId, TrendRange.entries[it]) })
        if (series.buckets.isEmpty()) {
            body.addView(ui.text("No readings in this period", 18, true).apply { setPadding(0, ui.dp(28), 0, ui.dp(8)) })
            if (range != TrendRange.All) ui.row(body, "Show all time") { select(metricId, TrendRange.All) }
            return
        }
        val monthly = range == TrendRange.All
        val bucketUnit = if (monthly) "month" else "day"
        val boundsLabel = { low: Double, high: Double -> "${trendNumber(low)}–${trendNumber(high)}" }
        val unit = series.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        val countLabel = if (series.count == 1L) "1 reading" else "${series.count} readings"
        val (caption, headline, chartTitle) = when (mode) {
            ChartMode.Average -> Triple("Average of $countLabel", trendNumber(checkNotNull(series.mean)) + unit, "Average by $bucketUnit")
            ChartMode.Spread -> Triple("Range across $countLabel", boundsLabel(series.buckets.minOf { it.low }, series.buckets.maxOf { it.high }) + unit,
                "Low, average and high by $bucketUnit")
            ChartMode.Count -> Triple("Total readings", series.count.toString(), "Readings by $bucketUnit")
        }
        if (series.unit == "steps") ui.paragraph(body, "Each reading is a reported step count and may cover an interval or a full day.")
        body.addView(ui.text(caption, 15, color = ui.muted).apply { setPadding(0, ui.dp(22), 0, 0) })
        body.addView(ui.text(headline, 36, true))
        body.addView(ui.text(chartTitle, 15, true).apply { setPadding(0, ui.dp(24), 0, ui.dp(12)) })
        body.addView(TrendChart(activity, ui, series, range, mode) {
            val values = series.buckets.asReversed().map {
                val value = when (mode) {
                    ChartMode.Average -> trendNumber(it.mean) + unit
                    ChartMode.Spread -> boundsLabel(it.low, it.high) + unit + " (avg ${trendNumber(it.mean)})"
                    ChartMode.Count -> it.count.toString()
                }
                val date = if (monthly) trendDate(it.day, true) else it.day.toString()
                "$date    $value"
            }
            AlertDialog.Builder(activity).setTitle(chartTitle)
                .setItems(values.toTypedArray(), null).setPositiveButton("Done", null).show()
        }, -1, ui.dp(184))
        body.addView(selector(ChartMode.entries.map { it.label }, mode.ordinal) { mode = ChartMode.entries[it]; draw(series) })
        ui.line(body)
        val bounds = LinearLayout(activity)
        for ((label, value) in listOf("Lowest reading" to series.buckets.minOf { it.low }, "Highest reading" to series.buckets.maxOf { it.high })) {
            val cell = ui.column().apply {
                setPadding(0, ui.dp(20), ui.dp(6), 0)
                addView(ui.text(label, 14, color = ui.muted))
                addView(ui.text("${trendNumber(value)} ${series.unit}".trim(), 19, true))
            }
            bounds.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(bounds)
        if (series.limited) body.addView(ui.text("Based on the latest 90 readings available from this server.", 14, color = ui.muted)
            .apply { setPadding(0, ui.dp(16), 0, 0) })
    }
}
