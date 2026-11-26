package dev.lvtd.healthledger

import android.graphics.Rect
import android.view.View
import android.widget.RadioButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrendsTest {
    private val today = LocalDate.parse("2026-05-10")

    @Test fun chartAndModeButtonsHaveSeparateTouchAreasOnCompactScreens() {
        ActivityScenario.launch(ServerActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val ui = MobileUi(activity)
                val screen = TrendsScreen(activity, ui, {}, {})
                try {
                    screen.draw(TrendSeries(listOf(TrendMetric("a", "Weight")), "a", "kg",
                        listOf(TrendBucket(today, 1, 70.0, 70.0, 70.0)), today))
                    val root = screen.view
                    root.measure(View.MeasureSpec.makeMeasureSpec(ui.dp(360), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(ui.dp(640), View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val controls = ArrayList<View>()
                    root.findViewsWithText(controls, "Average", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                    val chart = controls.filterIsInstance<TrendChart>().single()
                    controls.clear()
                    root.findViewsWithText(controls, "Average", View.FIND_VIEWS_WITH_TEXT)
                    val button = controls.filterIsInstance<RadioButton>().single()
                    fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                        root.offsetDescendantRectToMyCoords(view, it)
                    }
                    assertTrue(bounds(button).top - bounds(chart).bottom >= ui.dp(8))
                    assertTrue(button.height >= ui.dp(48))
                } finally { screen.close() }
            }
        }
    }

    @Test fun weightsThePeriodAverageByReadingsAndKeepsEmptyDaysMissing() {
        val points = listOf("3 May 2026" to "88", "4 May 2026" to "0", "4 May 2026" to "2", "10 May 2026" to "1,001",
            "11 May 2026" to "99").joinToString("") { (day, value) ->
            """<circle data-chart-point data-val="$value" data-date="$day"></circle>"""
        }
        val html = """<section id="overview"><select id="metric-select"><option value="a" selected>Mass (g)</option></select>
            <h2 id="trend-heading">Mass <span class="unit-pill">g</span></h2><svg>$points</svg></section>"""
        val web = decodeWebTrend(html)
        val week = web.series(TrendRange.Week, today)
        assertEquals("g", week.unit)
        assertEquals("a", week.selected)
        assertEquals(3L, week.count)
        assertEquals(2, week.buckets.size)
        assertEquals(1003.0 / 3, week.mean!!, 0.000001)
        assertEquals(0.0, week.buckets.first().low, 0.0)
        val all = web.series(TrendRange.All, today).buckets.single()
        assertEquals(today.withDayOfMonth(1), all.day)
        assertEquals(4L, all.count)
    }

    @Test fun marksPartialHistoryAndHandlesAnEmptyWindow() {
        val web = WebTrend(emptyList(), null, "", List(90) { today.minusDays(it.toLong()) to 0.0 })
        assertTrue(web.series(TrendRange.Quarter, today).limited)
        assertTrue(web.series(TrendRange.All, today).limited)
        assertFalse(web.series(TrendRange.Week, today).limited)
        val empty = web.series(TrendRange.Month, today.plusYears(1))
        assertTrue(empty.buckets.isEmpty())
        assertNull(empty.mean)
    }

    @Test fun decodesFullServerAggregatesWithoutTheLegacyReadingLimit() {
        val json = """{"metrics":[{"id":"a","title":"Weight","unit":"kg"}],"selected":"a","today":"2026-05-10",
            "buckets":[{"date":"2026-05-01","count":200,"mean":"71.56","low":"70","high":"73.2"}]}"""
        val series = decodeTrends(json)
        assertEquals("Weight (kg)", series.metrics.single().label)
        assertEquals(200L, series.count)
        assertEquals(71.56, series.mean!!, 0.000001)
        assertFalse(series.limited)
    }
}
