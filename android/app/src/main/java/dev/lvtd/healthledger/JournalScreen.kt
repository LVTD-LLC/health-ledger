package dev.lvtd.healthledger

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.Executors

internal class JournalScreen(
    private val activity: Activity, private val ui: MobileUi,
    private val signIn: () -> Unit, private val add: () -> Unit
) {
    val view = ui.column()
    private val repo = JournalRepository()
    private val io = Executors.newSingleThreadExecutor()
    private val list = ListView(activity)
    private val body = ui.column()
    private var serial = 0
    private var owner: String? = null
    private var loading = false
    private var loaded = false
    var query = ""
        private set
    var offset = 0
        private set

    init {
        view.addView(ui.header("Journal", ui.iconButton("Search journal", R.drawable.ic_search, ::search),
            ui.iconButton("Add an entry", R.drawable.ic_add, add)))
        view.addView(body, LinearLayout.LayoutParams(-1,0,1f))
        list.divider = null
    }
    fun restore(term: String, start: Int) { query = term; offset = start }
    fun close() { serial++; io.shutdownNow() }
    fun clear() {
        serial++
        owner = null
        loading = false
        loaded = false
        query = ""
        offset = 0
        list.adapter = null
        body.removeAllViews()
    }
    fun show(refresh: Boolean = false) {
        val session = LedgerSession.current()
        if (owner != null && owner != session?.binding) clear()
        if (session == null) {
            body.removeAllViews()
            ui.row(body, "Sign in", "Open your Health Ledger journal", signIn)
            return
        }
        if ((loaded || loading) && !refresh) return
        loading = true
        owner = session.binding
        val request = ++serial
        val term = query
        val start = offset
        val keepPosition = list.firstVisiblePosition
        body.removeAllViews()
        body.addView(ProgressBar(activity).apply { contentDescription = "Loading journal" }, -1, ui.dp(48))
        io.execute {
            val result = runCatching { repo.page(session, term, start) }
            activity.runOnUiThread {
                if (activity.isDestroyed || serial != request) return@runOnUiThread
                loading = false
                if (LedgerSession.current()?.binding != session.binding) { clear(); show(); return@runOnUiThread }
                body.removeAllViews()
                result.onSuccess { entries ->
                    loaded = true
                    if (query.isNotBlank()) ui.row(body, "Results for “$query”", "Clear search") { go("", 0) }
                    if (entries.isEmpty()) ui.paragraph(body, if (term.isEmpty() && start == 0) "Your journal is empty." else "No entries on this page.")
                    list.adapter = Rows(entries)
                    body.addView(list, LinearLayout.LayoutParams(-1,0,1f))
                    list.setSelection(if (refresh) keepPosition else 0)
                    list.setOnItemClickListener { _, _, i, _ ->
                        val r = entries[i]
                        AlertDialog.Builder(activity).setTitle(r.title)
                            .setMessage(listOf(r.metric, r.kind, r.source, r.localTime.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)), r.note)
                                .filter(String::isNotBlank).joinToString("\n"))
                            .setPositiveButton("Done", null).show()
                    }
                    if (offset > 0) ui.row(body, "Newer entries") { go(query, (offset-50).coerceAtLeast(0)) }
                    if (entries.size == 50) ui.row(body, "Older entries") { go(query, offset+50) }
                }.onFailure {
                    loaded = false
                    if (it is SignInRequired) ui.row(body, "Sign in to your journal", action = signIn)
                    else ui.row(body, "Couldn’t load journal", "Tap to retry") { show(true) }
                }
            }
        }
    }
    private fun go(term: String, start: Int) { query = term; offset = start; loading = false; loaded = false; show() }
    private fun search() {
        val input = EditText(activity).apply {
            hint = "Measurement, source or note"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(query)
            selectAll()
        }
        val holder = ui.column().apply { setPadding(ui.dp(24),ui.dp(6),ui.dp(24),0); addView(input) }
        val dialog = AlertDialog.Builder(activity).setTitle("Search journal").setView(holder)
            .setPositiveButton("Search") { _, _ -> go(input.text.toString().trim(), 0) }.setNegativeButton("Cancel",null).create()
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { dialog.dismiss(); go(input.text.toString().trim(),0); true } else false
        }
        dialog.show()
    }
    private inner class Rows(private val entries: List<JournalEntry>) : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(index: Int) = entries[index]
        override fun getItemId(index: Int) = index.toLong()
        override fun getView(index: Int, convertView: View?, parent: ViewGroup?): View {
            val r = entries[index]
            val row = convertView as? LinearLayout ?: ui.column()
            row.removeAllViews()
            val date = r.localTime.toLocalDate()
            if (index == 0 || entries[index-1].localTime.toLocalDate() != date) {
                ui.section(row, when (date) {
                    LocalDate.now() -> "Today"
                    LocalDate.now().minusDays(1) -> "Yesterday"
                    else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
                })
            }
            val contents = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(12))
            }
            val title = ui.column()
            title.addView(ui.text(r.title, 17, true))
            title.addView(ui.text(listOf(r.clock, r.source).filter(String::isNotBlank).joinToString(" · "), 13, color=ui.muted))
            contents.addView(title, LinearLayout.LayoutParams(0,-2,1f))
            if (r.metric.isNotBlank()) contents.addView(ui.text(r.metric, 17, true).apply {
                maxWidth = activity.resources.displayMetrics.widthPixels / 3
                gravity = Gravity.END
                setPadding(ui.dp(8),0,0,0)
            })
            row.addView(contents)
            ui.line(row)
            return row
        }
    }
}
