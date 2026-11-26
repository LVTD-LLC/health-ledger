package dev.lvtd.healthledger

import android.net.Uri
import org.json.JSONArray
import org.jsoup.Jsoup
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal data class JournalEntry(
    val title: String, val metric: String, val kind: String, val source: String, val note: String, val at: Instant
) {
    val localTime get() = at.atZone(ZoneId.systemDefault())
    val clock get() = localTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}

internal fun decodeJournal(json: String): List<JournalEntry> {
    val rows = JSONArray(json)
    return List(rows.length()) { i ->
        val r = rows.getJSONObject(i)
        JournalEntry(r.getString("title"), listOf(r.getString("value"), r.getString("unit")).filter(String::isNotBlank).joinToString(" "),
            r.getString("kind"), r.getString("source"), r.getString("note"), Instant.parse(r.getString("time")))
    }
}

// Compatibility with servers released before the private JSON timeline.
internal fun decodeWebJournal(html: String): List<JournalEntry> {
    val timeline = Jsoup.parse(html).getElementById("timeline") ?: throw IOException("Unavailable journal")
    return timeline.select("article.ledger-row").map { r ->
        fun field(selector: String) = r.selectFirst(selector)?.text().orEmpty()
        JournalEntry(field("h3"), field(".record-metric"), field(".kind-badge"), field(".source-pill"),
            field(".record-note"), Instant.parse(r.selectFirst("time[datetime]")!!.attr("datetime")))
    }
}

internal class JournalRepository(private val ledger: Ledger = Ledger()) {
    private var legacy = false
    fun page(session: LedgerSession, search: String, offset: Int): List<JournalEntry> {
        val query = "?q=${Uri.encode(search)}&offset=$offset"
        fun get(path: String, json: Boolean): List<JournalEntry>? {
            val req = ledger.connect(path + query, session)
            try {
                return when (req.responseCode) {
                    401, 403, in 300..399 -> throw SignInRequired()
                    404 -> null
                    200 -> req.inputStream.bufferedReader().use {
                        if (json) decodeJournal(it.readText()) else decodeWebJournal(it.readText())
                    }
                    else -> throw IOException("Journal unavailable")
                }
            } finally { req.disconnect() }
        }
        if (!legacy) {
            get("/health/timeline.json", true)?.let { return it }
            legacy = true
        }
        return get("/dashboard", false) ?: throw IOException("Journal unavailable")
    }
}
