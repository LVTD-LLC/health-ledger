package dev.lvtd.healthledger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class ChartMode(val label: String) { Average("Average"), Spread("Low–high"), Count("Readings") }

internal fun trendNumber(value: Double): String = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = if (abs(value) in 0.0..1.0) 3 else 2
}.format(value)

internal fun trendDate(date: LocalDate, monthly: Boolean): String =
    date.format(DateTimeFormatter.ofPattern(if (monthly) "MMM yyyy" else "MMM d", Locale.getDefault()))

// Constructed with a loaded series by TrendsScreen, never inflated from XML.
@android.annotation.SuppressLint("ViewConstructor")
internal class TrendChart(
    context: Context, private val ui: MobileUi, private val series: TrendSeries,
    private val range: TrendRange, private val mode: ChartMode, showValues: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelSize = ui.text("", 12).textSize
    private val grid = context.getColor(R.color.line)
    private val rows = series.buckets
    private val monthly = range == TrendRange.All
    private val start = range.firstDay(series.today) ?: rows.first().day
    private val end = if (monthly) rows.last().day else series.today
    private val countMode = mode == ChartMode.Count
    private fun slot(day: LocalDate): Long = if (monthly) day.year.toLong() * 12 + day.monthValue else day.toEpochDay()
    private fun value(row: TrendBucket): Double = if (countMode) row.count.toDouble() else row.mean
    init {
        isFocusable = true
        contentDescription = "${mode.label} chart. Show chart values"
        setOnClickListener { showValues() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spread = mode == ChartMode.Spread
        val minValue = if (countMode) 0.0 else rows.minOf { if (spread) it.low else it.mean }
        val maxValue = rows.maxOf { if (spread) it.high else value(it) }
        val margin = if (maxValue == minValue) max(abs(minValue) * 0.02, 0.5) else (maxValue - minValue) * 0.08
        val low = if (countMode) 0.0 else minValue - margin
        val high = if (countMode) maxValue else maxValue + margin
        val ticks = if (countMode && maxValue < 2) listOf(low, high) else listOf(low, if (countMode) kotlin.math.ceil(high / 2) else low + (high - low) / 2, high)
        paint.textSize = labelSize
        val axisWidth = ticks.maxOf { paint.measureText(trendNumber(it)) }
        val left = axisWidth + ui.dp(10)
        val right = width - ui.dp(4).toFloat()
        val top = paint.textSize
        val bottom = height - paint.textSize * 2.2f
        if (right <= left || bottom <= top) return
        val slots = slot(end) - slot(start) + 1
        val step = (right - left) / slots
        fun x(day: LocalDate) = left + (slot(day) - slot(start) + 0.5f) * step
        fun y(v: Double) = bottom - ((v - low) / (high - low)).toFloat() * (bottom - top)
        paint.textAlign = Paint.Align.RIGHT
        for (tick in ticks) {
            paint.color = grid
            paint.strokeWidth = ui.dp(1).toFloat()
            canvas.drawLine(left, y(tick), right, y(tick), paint)
            paint.color = ui.muted
            canvas.drawText(trendNumber(tick), left - ui.dp(7), y(tick) + paint.textSize / 3, paint)
        }
        val bar = min(ui.dp(24).toFloat(), step * .68f).coerceAtLeast(1f)
        paint.color = ui.accent
        paint.strokeWidth = ui.dp(2).toFloat()
        var previous: TrendBucket? = null
        for (row in rows) {
            val cx = x(row.day)
            val cy = y(value(row))
            if (countMode) canvas.drawRect(cx - bar / 2, cy, cx + bar / 2, bottom, paint)
            else if (spread) {
                val cap = min(ui.dp(5).toFloat(), step * .32f)
                canvas.drawLine(cx, y(row.low), cx, y(row.high), paint)
                for (bound in listOf(row.low, row.high)) canvas.drawLine(cx - cap, y(bound), cx + cap, y(bound), paint)
                canvas.drawCircle(cx, cy, min(ui.dp(3).toFloat(), max(2f, cap)), paint)
            } else {
                previous?.takeIf { slot(row.day) - slot(it.day) == 1L }?.let {
                    canvas.drawLine(x(it.day), y(it.mean), cx, cy, paint)
                }
                canvas.drawCircle(cx, cy, min(ui.dp(3).toFloat(), max(2f, step * .36f)), paint)
            }
            previous = row
        }
        paint.color = ui.muted
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(trendDate(start, monthly), left, height - ui.dp(2).toFloat(), paint)
        if (start != end) {
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(trendDate(end, monthly), right, height - ui.dp(2).toFloat(), paint)
        }
    }
}
