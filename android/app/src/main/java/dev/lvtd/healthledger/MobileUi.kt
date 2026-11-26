package dev.lvtd.healthledger

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

internal enum class Destination(val title: String, val icon: Int) {
    Trends("Trends", R.drawable.ic_trends), Journal("Journal", R.drawable.ic_journal), Sources("Sources", R.drawable.ic_sources), More("More", R.drawable.ic_more)
}

internal class MobileUi(private val ctx: Context) {
    val surface = ctx.getColor(R.color.surface)
    val ink = ctx.getColor(R.color.ink)
    val muted = ctx.getColor(R.color.muted)
    val accent = ctx.getColor(R.color.accent)
    val tint = ctx.getColor(R.color.tint)
    fun dp(n: Int) = (n * ctx.resources.displayMetrics.density + .5f).toInt()
    fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun line(parent: LinearLayout) = parent.addView(View(ctx).apply { setBackgroundColor(ctx.getColor(R.color.line)) }, -1, dp(1))
    fun text(value: String, size: Int, bold: Boolean = false, color: Int = ink) = TextView(ctx).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    fun interactive(color: Int = surface, radius: Int = 0): RippleDrawable {
        val shape = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(ctx.getColor(R.color.line)), shape, null)
    }
    fun iconButton(title: String, res: Int, action: () -> Unit) = ImageButton(ctx).apply {
        contentDescription = title
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(accent)
        background = interactive()
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }
    fun header(title: String, vararg actions: View) = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(12), dp(8), dp(8))
        addView(text(title, 30, true), LinearLayout.LayoutParams(0, -2, 1f))
        actions.forEach { addView(it) }
    }
    fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title, 15, true, accent).apply { setPadding(dp(20), dp(22), dp(20), dp(8)) })
    }
    fun paragraph(parent: LinearLayout, value: String) {
        parent.addView(text(value, 15, color = muted).apply { setPadding(dp(20), dp(8), dp(20), dp(12)) })
    }
    fun row(parent: LinearLayout, title: String, detail: String = "", action: () -> Unit) {
        val view = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(20), dp(12), dp(16), dp(12))
            background = interactive()
            isFocusable = true
            setOnClickListener { action() }
        }
        val labels = column()
        labels.addView(text(title, 17, true))
        if (detail.isNotBlank()) labels.addView(text(detail, 14, color = muted).apply { setPadding(0, dp(2), 0, 0) })
        view.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_next); imageTintList = ColorStateList.valueOf(muted) }, dp(16), dp(16))
        parent.addView(view)
        line(parent)
    }
    fun navigation(selected: Destination, open: (Destination) -> Unit) = RadioGroup(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        Destination.entries.forEach { dest ->
            val active = dest == selected
            val item = RadioButton(ctx).apply {
                id = View.generateViewId()
                buttonDrawable = null
                text = dest.title
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(5))
                setTextColor(if (active) accent else muted)
                setCompoundDrawablesWithIntrinsicBounds(0, dest.icon, 0, 0)
                compoundDrawableTintList = ColorStateList.valueOf(if (active) accent else muted)
                compoundDrawablePadding = dp(3)
                background = interactive(if (active) tint else surface, 12)
                isChecked = active
                setOnClickListener { open(dest) }
            }
            addView(item, RadioGroup.LayoutParams(0,-2,1f).apply { setMargins(dp(4),0,dp(4),0) })
        }
    }
}
