package dev.lvtd.healthledger

import android.app.Activity
import android.app.DatePickerDialog
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.*
import org.jsoup.nodes.Element
import java.time.LocalDate

/** Map the controls of a single server action to labelled Android inputs. */
internal class NativeForm(private val activity: Activity, private val ui: MobileUi,
    private val form: Element, private val parent: LinearLayout,
    private val pick: (String,String,(SelectedFile)->Unit)->Unit) {
    private val fields = mutableListOf<()->Pair<String,String>?>()
    private val validate = mutableListOf<()->Boolean>()
    private var file: SelectedFile? = null

    init {
        for (control in form.select("input[name],select[name],textarea[name]")) {
            if (control.hasAttr("disabled")) continue
            val name = control.attr("name")
            val value = control.attr("value")
            val type = control.attr("type").lowercase()
            if (type == "hidden") { fields.add { name to value }; continue }
            val id = View.generateViewId()
            val label = control.closest("label") ?: form.select("label[for]").firstOrNull { it.attr("for") == control.id() }
            val title = label?.ownText()?.takeIf { it.isNotBlank() } ?: control.attr("aria-label").ifBlank { name.replace('_',' ').replaceFirstChar(Char::uppercase) }
            parent.addView(ui.text(title,15,true).apply { labelFor=id; setPadding(0,ui.dp(12),0,ui.dp(2)) })
            when {
                type == "file" -> {
                    val choose = Button(activity).apply { this.id=id; text="Choose file"; isAllCaps=false }
                    choose.setOnClickListener { pick(name,if (control.attr("accept").startsWith("image/")) "image/*" else "*/*") { file=it; choose.text=it.name } }
                    if (control.hasAttr("required")) validate.add { (file != null).also { if (!it) choose.error="Choose a file" } }
                    parent.addView(choose)
                }
                type == "date" -> {
                    var date = runCatching { LocalDate.parse(value) }.getOrNull()
                    val choose = Button(activity).apply { this.id=id; isAllCaps=false; text=date?.toString() ?: "Choose date" }
                    choose.setOnClickListener {
                        val start=date ?: LocalDate.now()
                        val dialog=DatePickerDialog(activity,{ _,y,m,d -> date=LocalDate.of(y,m+1,d); choose.text=date.toString(); choose.error=null },start.year,start.monthValue-1,start.dayOfMonth)
                        if (!control.hasAttr("required")) dialog.setButton(DatePickerDialog.BUTTON_NEUTRAL,"Clear") { _,_ -> date=null; choose.text="Choose date" }
                        dialog.show()
                    }
                    fields.add { name to (date?.toString() ?: "") }
                    if (control.hasAttr("required")) validate.add { (date != null).also { if (!it) choose.error="Choose a date" } }
                    parent.addView(choose)
                }
                control.normalName()=="select" -> {
                    val opts = control.select("option")
                    val picker = Spinner(activity).apply {
                        this.id=id; minimumHeight=ui.dp(48); contentDescription=title
                        adapter=ArrayAdapter(activity,android.R.layout.simple_spinner_dropdown_item,opts.map { it.text() })
                        setSelection(opts.indexOfFirst { it.hasAttr("selected") }.coerceAtLeast(0))
                    }
                    fields.add { opts.getOrNull(picker.selectedItemPosition)?.let { name to (if(it.hasAttr("value")) it.attr("value") else it.text()) } }
                    parent.addView(picker)
                }
                type=="checkbox" -> {
                    val box=CheckBox(activity).apply { this.id=id; isChecked=control.hasAttr("checked"); contentDescription=title }
                    fields.add { if(box.isChecked) name to value.ifEmpty { "on" } else null }; parent.addView(box)
                }
                else -> {
                    val edit = EditText(activity).apply {
                        this.id=id; isSaveEnabled=false
                        inputType=when {
                            type=="password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            type=="email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                            control.attr("inputmode")=="numeric" || type=="number" -> InputType.TYPE_CLASS_NUMBER
                            else -> InputType.TYPE_CLASS_TEXT
                        }
                        setSingleLine(control.normalName()!="textarea"); hint=control.attr("placeholder")
                        control.attr("maxlength").toIntOrNull()?.let { filters=arrayOf(InputFilter.LengthFilter(it)) }
                        if(type!="password") setText(if(control.normalName()=="textarea") control.text() else value)
                    }
                    fields.add { name to edit.text.toString() }
                    if(control.hasAttr("required")) validate.add { edit.text.isNotEmpty().also { if (!it) { edit.error="Required"; edit.requestFocus() } } }
                    parent.addView(edit)
                }
            }
        }
        for (hint in form.select(".hint:not([data-resend-status])")) if (hint.text().isNotBlank()) parent.addView(ui.text(hint.text(),14,color=ui.muted))
    }

    fun buttons(submit: (String,List<Pair<String,String>>,SelectedFile?)->Unit) {
        for (node in form.select("button:not([type=button]), input[type=submit]")) {
            val title=node.text().ifBlank { node.attr("value") }
            parent.addView(Button(activity).apply {
                text=title; isAllCaps=false; isEnabled=!node.hasAttr("disabled")
                node.attr("data-resend-seconds").toLongOrNull()?.takeIf { it > 0 }?.let { seconds ->
                    text = "$title (available in ${seconds}s)"
                    postDelayed({ isEnabled = true; text = title }, seconds * 1000)
                }
                setOnClickListener {
                    if (!validate.all { it() }) return@setOnClickListener
                    val data=fields.mapNotNull { it() }.toMutableList()
                    if(node.hasAttr("name")) data.add(node.attr("name") to node.attr("value"))
                    submit(title,data,file)
                }
            })
        }
    }
}
