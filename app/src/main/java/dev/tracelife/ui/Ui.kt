package dev.tracelife.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import dev.tracelife.R
import kotlin.math.roundToInt

/**
 * Shared visual language for all screens: one accent (deep teal), cool neutral surfaces,
 * card 16dp / control 12dp / pill radius scale. Colors resolve per light/dark mode from resources.
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
        Tone.ACCENT -> c.getColor(R.color.ui_accent_soft) to accent(c)
        Tone.OK -> c.getColor(R.color.ui_ok_soft) to c.getColor(R.color.ui_ok)
        Tone.WARN -> c.getColor(R.color.ui_warn_soft) to c.getColor(R.color.ui_warn)
        Tone.ERROR -> c.getColor(R.color.ui_danger_soft) to danger(c)
    }

    fun dp(c: Context, value: Int) = (c.resources.displayMetrics.density * value).roundToInt()

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
            background = rounded(c, surface(c), 16, border(c))
            setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
            layoutParams = margins(parent, bottom = 12)
            parent.addView(this)
        }
    }

    fun screenTitle(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(text(parent.context))
        setPadding(0, dp(context, 4), 0, dp(context, 10))
        parent.addView(this)
    }

    fun sectionTitle(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(text(parent.context))
        setPadding(0, dp(context, 8), 0, dp(context, 4))
        parent.addView(this)
    }

    fun body(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 15f
        setTextColor(text(parent.context))
        setTextIsSelectable(true)
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(context, 3), 0, dp(context, 3))
        parent.addView(this)
    }

    fun note(parent: ViewGroup, value: String): TextView = TextView(parent.context).apply {
        text = value
        textSize = 13f
        setTextColor(textSecondary(parent.context))
        setTextIsSelectable(true)
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(context, 2), 0, dp(context, 4))
        parent.addView(this)
    }

    fun divider(parent: ViewGroup): View = View(parent.context).apply {
        setBackgroundColor(border(parent.context))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(parent.context, 1)).apply {
            setMargins(0, dp(parent.context, 8), 0, dp(parent.context, 8))
        }
        parent.addView(this)
    }

    private fun styleButton(c: Context, b: Button, bgColor: Int, fg: Int, rippleColor: Int) {
        b.isAllCaps = false
        b.textSize = 15f
        b.setTextColor(fg)
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10))
        b.stateListAnimator = null
        b.background = touchable(c, rounded(c, bgColor, 12), rippleColor)
    }

    fun button(parent: ViewGroup, label: String, tag: String, onClick: () -> Unit): Button {
        val c = parent.context
        return Button(c).apply {
            text = label
            this.tag = tag
            styleButton(c, this, c.getColor(R.color.ui_neutral_soft), text(c), border(c))
            layoutParams = margins(parent, bottom = 8)
            setOnClickListener { onClick() }
            parent.addView(this)
        }
    }

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
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
            stateListAnimator = null
            background = touchable(c, rounded(c, surface(c), 12, border(c)), border(c))
            layoutParams = margins(parent, bottom = 8)
            setOnClickListener { onClick() }
            parent.addView(this)
        }
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

    fun styleField(c: Context, input: EditText) {
        input.background = rounded(c, c.getColor(R.color.ui_field_bg), 12, border(c))
        input.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        input.setTextColor(text(c))
        input.setHintTextColor(textTertiary(c))
        input.textSize = 15f
    }

    fun styleCheck(c: Context, box: CheckBox) {
        box.setTextColor(text(c))
        box.textSize = 15f
    }

    fun styleSpinner(c: Context, spinner: Spinner) {
        spinner.background = rounded(c, surface(c), 12, border(c))
        spinner.setPadding(dp(c, 8), 0, dp(c, 8), 0)
    }

    fun backRow(parent: ViewGroup, onBack: () -> Unit) {
        val c = parent.context
        TextView(c).apply {
            text = "‹ 返回"
            this.tag = "back"
            textSize = 15f
            setTextColor(accent(c))
            background = touchable(c, rounded(c, c.getColor(R.color.ui_accent_soft), 100), border(c))
            setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(c, 6))
            }
            setOnClickListener { onBack() }
            parent.addView(this)
        }
    }
}
