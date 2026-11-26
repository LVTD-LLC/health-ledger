package dev.lvtd.healthledger

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.WindowInsets
import android.widget.*
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class EntryActivity : Activity() {
    private lateinit var at: LocalDateTime
    private var saving = false
    private lateinit var form: LinearLayout
    private val fields = linkedMapOf<String, EditText>()
    private lateinit var category: Spinner
    private var binding: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = state?.getString("binding") ?: LedgerSession.current()?.binding
        at = state?.getString("at")?.let(LocalDateTime::parse) ?: LocalDateTime.now().withNano(0)
        val ui = MobileUi(this)
        val root = ui.column().apply {
            setBackgroundColor(ui.surface)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
                insets
            }
        }
        root.addView(ui.header("Add an entry",ui.iconButton("Close",R.drawable.ic_back) { finish() }))
        form = ui.column().apply { setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(12)) }
        root.addView(ScrollView(this).apply { addView(form) },LinearLayout.LayoutParams(-1,0,1f))
        form.addView(ui.text("Category",14,true))
        category = Spinner(this).apply {
            val categories = listOf("Measurement","Medicine","Supplement","Workout","Check-in","Symptom","Lab","Nutrition","Sleep","Note")
            adapter = ArrayAdapter(this@EntryActivity, android.R.layout.simple_spinner_dropdown_item, categories)
            setSelection(state?.getInt("kind") ?: 0)
            contentDescription = "Category"
            minimumHeight = ui.dp(48)
        }
        form.addView(category)
        fun field(key: String, title: String, max: Int) {
            val id = View.generateViewId()
            form.addView(ui.text(title,14,true).apply { labelFor = id; setPadding(0,ui.dp(10),0,0) })
            val input = EditText(this).apply {
                this.id = id
                if (key != "note") setSingleLine()
                filters = arrayOf(InputFilter.LengthFilter(max))
                setText(state?.getString(key).orEmpty())
            }
            fields[key] = input
            form.addView(input)
        }
        field("title","Name",160)
        field("value","Value or dose",160)
        field("unit","Unit",64)
        field("note","Note",4000)
        val whenButton = Button(this).apply { isAllCaps = false }
        fun updateTime() { whenButton.text = at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT)) }
        updateTime()
        whenButton.setOnClickListener {
            DatePickerDialog(this,{ _,y,m,d ->
                TimePickerDialog(this,{ _,h,min ->
                    at = LocalDateTime.of(y,m+1,d,h,min)
                    updateTime()
                },at.hour,at.minute,android.text.format.DateFormat.is24HourFormat(this)).show()
            },at.year,at.monthValue-1,at.dayOfMonth).show()
        }
        form.addView(ui.text("When",14,true))
        form.addView(whenButton)
        val status = ui.text("",14,color=ui.muted)
        form.addView(status)
        val save = Button(this).apply { text = "Save entry"; isAllCaps = false }
        root.addView(save,LinearLayout.LayoutParams(-1,-2).apply { setMargins(ui.dp(16),ui.dp(4),ui.dp(16),ui.dp(6)) })
        setContentView(root)
        save.setOnClickListener {
            val title = fields.getValue("title")
            if (title.text.isBlank()) { title.error = "Enter a name"; title.requestFocus(); return@setOnClickListener }
            val session = LedgerSession.current()
            if (session == null || session.binding != binding) { status.text = "Sign in again to save."; return@setOnClickListener }
            val params = fields.mapValues { it.value.text.toString() }.toMutableMap()
            params["kind"] = category.selectedItem.toString()
            params["at"] = at.atZone(ZoneId.systemDefault()).toInstant().toString()
            saving = true
            save.isEnabled = false
            status.text = "Saving…"
            Thread {
                val result = runCatching { Ledger().addEntry(session,params) }
                runOnUiThread {
                    if (!isDestroyed) {
                        saving = false
                        save.isEnabled = true
                        if (result.isSuccess) { setResult(RESULT_OK); finish() }
                        else status.text = "Couldn’t confirm the save. Check your journal before retrying."
                    }
                }
            }.start()
        }
        if (state?.getBoolean("saving") == true) status.text = "Check your journal before retrying the save."
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        fields.forEach { (key, input) -> out.putString(key,input.text.toString()) }
        out.putInt("kind",category.selectedItemPosition)
        out.putString("at",at.toString())
        out.putString("binding",binding)
        out.putBoolean("saving",saving)
    }
}
