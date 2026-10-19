package dev.tracelife.ui

import android.content.Context
import android.app.Activity
import android.os.Build
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import dev.tracelife.R
import kotlin.math.roundToInt

/**
 * Shared visual language: monochrome surfaces, hairline borders, pill controls, one inverted
 * primary action per screen. Colors resolve per light/dark mode from resources.
 */
object Ui {
    enum class Tone { NEUTRAL, ACCENT, OK, WARN, ERROR }

    fun bg(c: Context) = c.getColor(R.color.ui_bg)
    fun surface(c: Context) = c.getColor(R.color.ui_surface)
    fun border(c: Context) = c.getColor(R.color.ui_border)
    fun text(c: Context) = c.getColor(R.color.ui_text)
    fun textSecondary(c: Context) = c.getColor(R.color.ui_text_secondary)
    fun textTertiary(c: Context) = c.getColor(R.color.ui_text_tertiary)
    fun accent(c: Context) = c.getColor(R.color.ui_accent)
    fun danger(c: Context) = c.getColor(R.color.ui_danger)

    private fun toneColors(c: Context, tone: Tone): Pair<Int, Int> = when (tone) {
        Tone.NEUTRAL -> c.getColor(R.color.ui_neutral_soft) to textSecondary(c)
        Tone.ACCENT -> c.getColor(R.color.ui_accent_soft) to text(c)
        Tone.OK -> c.getColor(R.color.ui_ok_soft) to c.getColor(R.color.ui_ok)
        Tone.WARN -> c.getColor(R.color.ui_warn_soft) to c.getColor(R.color.ui_warn)
        Tone.ERROR -> c.getColor(R.color.ui_danger_soft) to danger(c)
    }

    fun dp(c: Context, value: Int) = (c.resources.displayMetrics.density * value).roundToInt()

    /** Fixed navigation, independently scrolling content, and a single owner of system/IME insets. */
    fun mount(activity: Activity, title: String, onBack: (() -> Unit)? = null, onMenu: (() -> Unit)? = null): LinearLayout {
        activity.title = title
        val frame = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg(activity))
        }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 12), dp(activity, 4))
        }
        fun navButton(symbol: String, label: String, tag: String, action: () -> Unit) {
            TextView(activity).apply {
                text = symbol
                textSize = if (tag == "back") 32f else 23f
                setTextColor(text(activity))
                gravity = Gravity.CENTER
                contentDescription = label
                this.tag = tag
                isFocusable = true
                background = touchable(activity, rounded(activity, Color.TRANSPARENT, 14), border(activity))
                setOnClickListener { action() }
                bar.addView(this, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)))
            }
        }
        if (onBack != null) navButton("‹", "返回", "back", onBack)
        else if (onMenu != null) navButton("☰", "打开导航", "navigation", onMenu)
        TextView(activity).apply {
            text = title
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(text(activity))
            setPadding(dp(activity, 8), 0, 0, 0)
            bar.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        frame.addView(bar)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 32))
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(content)
        }
        frame.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        activity.setContentView(frame)
        frame.requestApplyInsets()
        return content
    }

    /** Contents stay mounted so collapsing a section never loses its unsubmitted fields. */
    fun disclosure(parent: ViewGroup, label: String, tag: String, expanded: Boolean = false, content: (LinearLayout) -> Unit): LinearLayout {
        val c = parent.context
        val block = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(block, margins(parent, bottom = 8))
        val toggle = TextView(c).apply {
            this.tag = tag
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textSecondary(c))
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(c, 48)
            setPadding(dp(c, 2), dp(c, 8), dp(c, 2), dp(c, 8))
            isFocusable = true
            background = touchable(c, rounded(c, Color.TRANSPARENT, 12), border(c))
        }
        block.addView(toggle)
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            this.tag = "$tag:content"
        }
        block.addView(body)
        content(body)
        fun show(open: Boolean) {
            body.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = "$label  ${if (open) "⌃" else "⌄"}"
            toggle.contentDescription = "$label，${if (open) "收起" else "展开"}"
            if (Build.VERSION.SDK_INT >= 30) toggle.stateDescription = if (open) "已展开" else "已收起"
        }
        show(expanded)
        toggle.setOnClickListener { show(body.visibility != View.VISIBLE) }
        return body
    }

    private fun rounded(c: Context, fill: Int, radiusDp: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(c, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(c, 1), stroke)
        }

    private fun touchable(c: Context, content: GradientDrawable, ripple: Int) =
        RippleDrawable(ColorStateList.valueOf(ripple), content, null)

    private fun margins(parent: ViewGroup, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(parent.context, top), 0, dp(parent.context, bottom))
        }

    fun card(parent: ViewGroup): LinearLayout {
        val c = parent.context
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c, surface(c), 18)
            setPadding(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14))
            layoutParams = margins(parent, bottom = 12)
            parent.addView(this)
        }
    }

    fun screenTitle(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(text(parent.context))
        setPadding(0, dp(context, 6), 0, dp(context, 12))
        parent.addView(this)
    }

    /** Small quiet group label, Codex-style section header. */
    fun sectionTitle(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textSecondary(parent.context))
        setPadding(0, dp(context, 6), 0, dp(context, 6))
        parent.addView(this)
    }

    fun body(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 15f
        setTextColor(text(parent.context))
        setTextIsSelectable(true)
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(context, 3), 0, dp(context, 3))
        parent.addView(this)
    }

    fun note(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 13f
        setTextColor(textSecondary(parent.context))
        setTextIsSelectable(true)
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(context, 2), 0, dp(context, 4))
        parent.addView(this)
    }

    fun divider(parent: ViewGroup): View = View(parent.context).apply {
        setBackgroundColor(border(parent.context))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(parent.context, 1)).apply {
            setMargins(0, dp(parent.context, 10), 0, dp(parent.context, 10))
        }
        parent.addView(this)
    }

    private fun styleButton(c: Context, b: Button, bgColor: Int, fg: Int, rippleColor: Int, stroke: Int? = null) {
        b.isAllCaps = false
        b.textSize = 15f
        b.setTextColor(fg)
        b.minHeight = dp(c, 48)
        b.minimumHeight = dp(c, 48)
        b.setPadding(dp(c, 18), dp(c, 11), dp(c, 18), dp(c, 11))
        b.stateListAnimator = null
        b.background = touchable(c, rounded(c, bgColor, 100, stroke), rippleColor)
    }

    /** Quiet secondary action: surface fill with hairline. */
    fun button(parent: ViewGroup, label: String, tag: String, onClick: () -> Unit): Button {
        val c = parent.context
        return Button(c).apply {
            text = label
            this.tag = tag
            styleButton(c, this, surface(c), text(c), border(c), stroke = border(c))
            layoutParams = margins(parent, bottom = 8)
            setOnClickListener { onClick() }
            parent.addView(this)
        }
    }

    fun textButton(parent: ViewGroup, label: String, tag: String, onClick: () -> Unit): Button {
        val c = parent.context
        return Button(c).apply {
            text = label
            this.tag = tag
            styleButton(c, this, Color.TRANSPARENT, text(c), border(c))
            layoutParams = margins(parent, bottom = 4)
            setOnClickListener { onClick() }
            parent.addView(this)
        }
    }

    /** The single loud action on a screen: inverted solid pill. */
    fun primary(parent: ViewGroup, label: String, tag: String, onClick: () -> Unit): Button {
        val c = parent.context
        return Button(c).apply {
            text = label
            this.tag = tag
            typeface = Typeface.DEFAULT_BOLD
            styleButton(c, this, accent(c), c.getColor(R.color.ui_on_accent), c.getColor(R.color.ui_accent_soft))
            layoutParams = margins(parent, bottom = 8)
            setOnClickListener { onClick() }
            parent.addView(this)
        }
    }

    fun dangerButton(parent: ViewGroup, label: String, tag: String, onClick: () -> Unit): Button {
        val c = parent.context
        return Button(c).apply {
            text = label
            this.tag = tag
            styleButton(c, this, c.getColor(R.color.ui_danger_soft), danger(c), c.getColor(R.color.ui_danger_soft))
            layoutParams = margins(parent, bottom = 8)
            setOnClickListener { onClick() }
            parent.addView(this)
        }
    }

    /** List-row button: card-colored, start-aligned, for paged result entries. */
    fun listRow(parent: ViewGroup, label: String, tag: String, onClick: () -> Unit): Button {
        val c = parent.context
        return Button(c).apply {
            text = label
            this.tag = tag
            isAllCaps = false
            textSize = 15f
            setTextColor(text(c))
            setLineSpacing(0f, 1.2f)
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            minHeight = dp(c, 48)
            minimumHeight = dp(c, 48)
            setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13))
            stateListAnimator = null
            background = touchable(c, rounded(c, surface(c), 14), border(c))
            layoutParams = margins(parent, bottom = 8)
            setOnClickListener { onClick() }
            parent.addView(this)
        }
    }

    fun recordRow(parent: ViewGroup, date: String, preview: String, topics: String, tag: String, onClick: () -> Unit) {
        val c = parent.context
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            this.tag = tag
            isFocusable = true
            contentDescription = listOf(date, preview, topics).filter { it.isNotBlank() }.joinToString("，")
            background = touchable(c, rounded(c, surface(c), 18), border(c))
            setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16))
            layoutParams = margins(parent, bottom = 10)
            setOnClickListener { onClick() }
            parent.addView(this)
        }
        fun line(value: String, size: Float, color: Int, bottom: Int) {
            if (value.isBlank()) return
            TextView(c).apply {
                text = value
                textSize = size
                setTextColor(color)
                setLineSpacing(0f, 1.25f)
                setPadding(0, 0, 0, dp(c, bottom))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                row.addView(this)
            }
        }
        line(date, 12f, textSecondary(c), 8)
        line(preview, 16f, text(c), if (topics.isBlank()) 0 else 10)
        line(topics, 12f, textSecondary(c), 0)
    }

    fun chip(parent: ViewGroup, label: String, tone: Tone): TextView {
        val c = parent.context
        val (fill, fg) = toneColors(c, tone)
        return TextView(c).apply {
            text = label
            textSize = 12f
            setTextColor(fg)
            background = rounded(c, fill, 100)
            setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(c, 2), dp(c, 6), dp(c, 4))
            }
            parent.addView(this)
        }
    }

    /** Small gray label rendered directly above a field; placeholders never serve as labels. */
    fun fieldLabel(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 13f
        setTextColor(textSecondary(parent.context))
        setPadding(0, dp(context, 6), 0, dp(context, 2))
        parent.addView(this)
    }

    /** 16sp semibold card header for cards whose title carries content (e.g. a metric name). */
    fun cardTitle(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(text(parent.context))
        setPadding(0, dp(context, 2), 0, dp(context, 4))
        parent.addView(this)
    }

    /** Quiet centered empty-state line with generous breathing room. */
    fun emptyState(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 14f
        gravity = android.view.Gravity.CENTER
        setTextColor(textTertiary(parent.context))
        setPadding(dp(context, 24), dp(context, 22), dp(context, 24), dp(context, 22))
        parent.addView(this)
    }

    fun styleField(c: Context, input: EditText) {
        input.background = rounded(c, c.getColor(R.color.ui_field_bg), 14, border(c))
        input.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11))
        input.setTextColor(text(c))
        input.setHintTextColor(textTertiary(c))
        input.textSize = 15f
    }

    fun styleCheck(c: Context, box: CheckBox) {
        box.setTextColor(text(c))
        box.textSize = 15f
        box.minHeight = dp(c, 48)
    }

    fun styleSpinner(c: Context, spinner: Spinner) {
        spinner.minimumHeight = dp(c, 48)
        spinner.background = rounded(c, surface(c), 14, border(c))
        spinner.setPadding(dp(c, 10), 0, dp(c, 10), 0)
    }

    fun backRow(parent: ViewGroup, onBack: () -> Unit) {
        val c = parent.context
        TextView(c).apply {
            text = "‹ 返回"
            this.tag = "back"
            textSize = 15f
            setTextColor(text(c))
            background = touchable(c, rounded(c, surface(c), 100, border(c)), border(c))
            setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(c, 6))
            }
            setOnClickListener { onBack() }
            parent.addView(this)
        }
    }
}
