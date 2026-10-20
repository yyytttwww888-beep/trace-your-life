package dev.tracelife.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.tracelife.TraceLifeApplication
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.transfer.StaleSnapshotException

/**
 * Shared base for functional screens in the app visual language (see [Ui]). All authoritative
 * reads and writes run on the app IO worker through [runIo]; UI callbacks return on the main thread.
 */
abstract class ScreenActivity : Activity() {
    protected val app get() = application as TraceLifeApplication
    protected lateinit var root: LinearLayout
    protected var busy = false
    private var generation = 0
    protected var screen = ""

    /** Render the entry screen of this activity. Called once from onCreate. */
    protected abstract fun showHome()

    /** Restore local navigation before the first render; this must never restart an online call. */
    protected open fun restoreScreenState(savedInstanceState: Bundle?) = Unit

    /** Back navigation within this activity; default leaves the activity. */
    protected open fun navigateBack() {
        if (busy) return
        finish()
    }

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                navigateBack()
            }
        }
        restoreScreenState(savedInstanceState)
        showHome()
    }

    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("For Android versions before predictive back")
    final override fun onBackPressed() = navigateBack()

    /** Rebuilds the whole screen; stale async results are dropped via the generation counter. */
    protected fun beginScreen(name: String, title: String, showBack: Boolean = true) {
        generation++
        screen = name
        root = Ui.mount(this, title, onBack = {
            if (showBack) navigateBack() else if (!busy) finish()
        })
    }

    protected fun <T> runIo(work: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true
        setEnabled(root, false)
        val requestGeneration = generation
        app.io.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isDestroyed || isFinishing || requestGeneration != generation) return@runOnUiThread
                busy = false
                setEnabled(root, true)
                result.fold(success) { showError(it) }
            }
        }
    }

    protected open fun showError(failure: Throwable) {
        val message = when (failure) {
            is RevisionConflictException -> "资料已经发生修改。请返回查看最新版本后再操作。"
            is StaleSnapshotException -> failure.message ?: "当前内容已失效，请重新打开。"
            is java.io.IOException -> "文件读写失败。请检查剩余存储空间与文件是否可读。"
            is IllegalArgumentException, is IllegalStateException -> failure.message ?: "操作未完成。"
            else -> "操作未完成，请稍后重试。"
        }
        AlertDialog.Builder(this).setTitle("操作未完成").setMessage(message).setPositiveButton("知道了", null).show()
    }

    protected fun confirm(title: String, message: String, positive: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton(positive) { _, _ -> action() }.show()
    }

    protected fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    /** New card container appended to [root]; add content into the returned layout. */
    protected fun card(): LinearLayout = Ui.card(root)
    protected fun section(title: String) = Ui.sectionTitle(root, title)
    protected fun note(value: String) = Ui.note(root, value)
    protected fun divider() = Ui.divider(root)
    protected fun chip(parent: LinearLayout, label: String, tone: Ui.Tone) = Ui.chip(parent, label, tone)

    protected fun text(value: String, size: Float = 15f) = addText(root, value, size)
    protected fun addText(parent: LinearLayout, value: String, size: Float = 15f) {
        when {
            size >= 18f -> Ui.sectionTitle(parent, value)
            size >= 15f -> Ui.body(parent, value)
            else -> Ui.note(parent, value)
        }
    }
    protected fun button(label: String, tag: String, action: () -> Unit) = addButton(root, label, tag, action)
    protected fun addButton(parent: LinearLayout, label: String, viewTag: String, action: () -> Unit) {
        Ui.button(parent, label, viewTag) { if (!busy) action() }
    }
    protected fun primaryButton(label: String, tag: String, action: () -> Unit) {
        Ui.primary(root, label, tag) { if (!busy) action() }
    }
    protected fun dangerButton(label: String, tag: String, action: () -> Unit) {
        Ui.dangerButton(root, label, tag) { if (!busy) action() }
    }
    /** Card-colored start-aligned row for paged result entries. */
    protected fun listRow(parent: LinearLayout, label: String, tag: String, action: () -> Unit) {
        Ui.listRow(parent, label, tag) { if (!busy) action() }
    }
    protected fun field(hint: String, value: String = "", multiline: Boolean = false, maxLength: Int = 2_000): EditText =
        fieldIn(root, hint, value, multiline, maxLength)

    /** Field inside an arbitrary container (usually a card). */
    protected fun fieldIn(parent: LinearLayout, hint: String, value: String = "", multiline: Boolean = false, maxLength: Int = 2_000): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0
            minLines = if (multiline) 3 else 1
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(value)
            setFreezesText(false)
            isSaveEnabled = false
        }
        Ui.styleField(this, input)
        parent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(2), 0, dp(10))
        })
        input.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        return input
    }

    /** Codex-style form block: small gray label above the field; placeholder keeps format hints only. */
    protected fun labeledField(label: String, hint: String = "", value: String = "", multiline: Boolean = false, maxLength: Int = 2_000): EditText =
        labeledFieldIn(root, label, hint, value, multiline, maxLength)

    protected fun labeledFieldIn(parent: LinearLayout, label: String, hint: String = "", value: String = "", multiline: Boolean = false, maxLength: Int = 2_000): EditText {
        Ui.fieldLabel(parent, label)
        return fieldIn(parent, hint, value, multiline, maxLength)
    }
    protected fun check(label: String, checked: Boolean) = CheckBox(this).apply { text = label; isChecked = checked; Ui.styleCheck(this@ScreenActivity, this); root.addView(this) }
    protected fun checkIn(parent: LinearLayout, label: String, checked: Boolean) = CheckBox(this).apply { text = label; isChecked = checked; Ui.styleCheck(this@ScreenActivity, this); parent.addView(this) }
    protected fun spinner(items: List<String>, selected: Int, viewTag: String) = spinnerIn(root, items, selected, viewTag)
    protected fun spinnerIn(parent: LinearLayout, items: List<String>, selected: Int, viewTag: String) = Spinner(this).apply {
        tag = viewTag
        adapter = ArrayAdapter(this@ScreenActivity, android.R.layout.simple_spinner_item, items).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(selected)
        Ui.styleSpinner(this@ScreenActivity, this)
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
    }
    /** Quiet centered empty-state line. */
    protected fun emptyState(parent: LinearLayout, value: String) = Ui.emptyState(parent, value)
    protected fun primaryIn(parent: LinearLayout, label: String, tag: String, action: () -> Unit) {
        Ui.primary(parent, label, tag) { if (!busy) action() }
    }
    protected fun dangerIn(parent: LinearLayout, label: String, tag: String, action: () -> Unit) {
        Ui.dangerButton(parent, label, tag) { if (!busy) action() }
    }
    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setEnabled(view.getChildAt(i), enabled)
    }
    protected fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
