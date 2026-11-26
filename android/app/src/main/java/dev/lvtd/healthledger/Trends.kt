package dev.lvtd.healthledger

import android.net.Uri
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class TrendRange(val label: String, val key: String, val days: Long?) {
    Week("7D", "7d", 7), Month("30D", "30d", 30), Quarter("90D", "90d", 90), All("All", "all", null);

    fun firstDay(today: LocalDate): LocalDate? = days?.let { today.minusDays(it - 1) }
    fun bucket(day: LocalDate): LocalDate = if (this == All) day.withDayOfMonth(1) else day
}

internal data class TrendMetric(val id: String, val label: String)
internal data class TrendBucket(val day: LocalDate, val count: Long, val mean: Double, val low: Double, val high: Double)
internal data class TrendSeries(
    val metrics: List<TrendMetric>, val selected: String?, val unit: String, val buckets: List<TrendBucket>,
    val today: LocalDate, val limited: Boolean = false
) {
    val count = buckets.sumOf { it.count }
    val mean = if (count == 0L) null else buckets.sumOf { it.mean * (it.count.toDouble() / count) }
}

internal fun decodeTrends(json: String): TrendSeries {
    val data = JSONObject(json)
    val options = data.getJSONArray("metrics")
    val selected = data.optString("selected").takeUnless { data.isNull("selected") }
    var selectedUnit = ""
    val metrics = List(options.length()) { i ->
        val m = options.getJSONObject(i)
        val unit = m.getString("unit")
        val id = m.getString("id")
        if (id == selected) selectedUnit = unit
        val title = m.getString("title")
        TrendMetric(id, if (unit.isBlank() || title.equals(unit, ignoreCase = true)) title else "$title ($unit)")
    }
    val rows = data.getJSONArray("buckets")
    val buckets = List(rows.length()) { i ->
        val r = rows.getJSONObject(i)
        TrendBucket(LocalDate.parse(r.getString("date")), r.getLong("count"), r.getDouble("mean"),
            r.getDouble("low"), r.getDouble("high"))
    }
    return TrendSeries(metrics, selected, selectedUnit, buckets, LocalDate.parse(data.getString("today")))
}

private val webDate = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)

internal data class WebTrend(
    val metrics: List<TrendMetric>, val selected: String?, val unit: String, val points: List<Pair<LocalDate, Double>>
) {
    fun series(range: TrendRange, today: LocalDate): TrendSeries {
        val start = range.firstDay(today)
        val buckets = points.filter { (day, _) -> (start == null || day >= start) && day <= today }
            .groupBy { range.bucket(it.first) }.toSortedMap().map { (day, samples) ->
                val values = samples.map { it.second }
                TrendBucket(day, values.size.toLong(), values.average(), values.min(), values.max())
            }
        // Earlier web servers send the latest 90 readings. A window strictly after the oldest
        // available day is complete even if the server reached its limit.
        val limited = points.size >= 90 && (start == null || start <= points.minOf { it.first })
        return TrendSeries(metrics, selected, unit, buckets, today, limited)
    }
}

internal fun decodeWebTrend(html: String): WebTrend {
    val doc = Jsoup.parse(html)
    val overview = doc.getElementById("overview") ?: throw IOException("Overview unavailable")
    val options = overview.select("#metric-select option")
    val metrics = options.map { TrendMetric(it.attr("value"), it.text()) }
    val selected = options.firstOrNull { it.hasAttr("selected") }?.attr("value")
    val points = overview.select("[data-chart-point]").map {
        LocalDate.parse(it.attr("data-date"), webDate) to it.attr("data-val").replace(",", "").toDouble()
    }
    val unit = overview.selectFirst("#trend-heading .unit-pill")?.text().orEmpty()
    return WebTrend(metrics, selected, unit, points)
}

internal class TrendsRepository(private val ledger: Ledger = Ledger()) {
    private var legacy = false
    private var cached: WebTrend? = null
    private var binding: String? = null

    fun load(session: LedgerSession, id: String?, range: TrendRange, refresh: Boolean): TrendSeries {
        if (binding != session.binding || refresh) cached = null
        binding = session.binding
        val metricQuery = id?.let { "&metric=" + Uri.encode(it) }.orEmpty()
        fun fetch(path: String): String? {
            val request = ledger.connect(path, session)
            try {
                return when (request.responseCode) {
                    200 -> request.inputStream.bufferedReader().use { it.readText() }
                    404 -> null
                    401, 403, in 300..399 -> throw SignInRequired()
                    else -> throw IOException("Trends unavailable")
                }
            } finally { request.disconnect() }
        }
        if (!legacy) {
            val json = fetch("/health/trends.json?range=${range.key}$metricQuery")
            if (json != null) return decodeTrends(json)
            legacy = true
        }
        val series = cached?.takeIf { id == it.selected } ?: decodeWebTrend(
            fetch("/dashboard?range=all$metricQuery") ?: throw IOException("Trends unavailable")
        ).also { cached = it }
        return series.series(range, LocalDate.now(ZoneOffset.UTC))
    }
}
