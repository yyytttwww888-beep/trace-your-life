package dev.tracelife.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.actions.ActionDecision
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExecution
import dev.tracelife.actions.ActionStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Synthetic action journeys through real forms and SQLite; no model or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionsActivityTest {
    private lateinit var controller: ActivityController<ActionsActivity>
    private lateinit var app: TraceLifeApplication
    private lateinit var original: ActionDraft
    private val actionId = "synthetic-form-action"
    private val activity get() = controller.get()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        original = ActionDraft(
            title = "合成资料：午后散步",
            trigger = "合成资料：午饭结束后",
            behavior = "合成资料：到楼下走十分钟",
            expectedSignal = "合成资料：回来看下午的精神状态",
            reviewOn = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(7),
            zoneId = "Asia/Shanghai",
        )
        app.io.submit { app.actions.create(original, actionId) }.get(20, TimeUnit.SECONDS)
        controller = Robolectric.buildActivity(ActionsActivity::class.java).setup()
        drain()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `editing and saving an existing action preserves every prefilled plan value`() {
        click("action:$actionId")
        click("editAction")

        assertEquals(original.title, field("想尝试什么").text.toString())
        assertEquals(original.trigger, field("什么时候做").text.toString())
        assertEquals(original.behavior, field("准备怎么做").text.toString())
        assertEquals(original.expectedSignal, field("怎样看出效果").text.toString())
        assertEquals(original.reviewOn.toString(), field("回看日期").text.toString())
        click("actionTimeZone")
        assertEquals(original.zoneId, field("时区").text.toString())
        click("actionTimeZone")
        click("saveEdit")

        val unchanged = requireNotNull(app.actions.get(actionId)).value
        assertEquals(1, unchanged.revision)
        assertEquals(original, unchanged.draft)
        assertEquals(1, app.actions.history(actionId).size)

        click("editAction")
        val changedTitle = "合成资料：午后短暂散步"
        field("想尝试什么").setText(changedTitle)
        click("saveEdit")

        val saved = requireNotNull(app.actions.get(actionId)).value
        assertEquals(2, saved.revision)
        assertEquals(original.copy(title = changedTitle), saved.draft)
        assertEquals(2, app.actions.history(actionId).size)
        assertEquals(original, app.actions.history(actionId).last().value.draft)
    }

    @Test fun `switching from an adjustment back to continue ignores hidden plan edits`() {
        click("action:$actionId")
        click("reviewAction")
        assertFalse(field("标题").isShown)

        select("decision", "调整做法")
        assertTrue(field("标题").isShown)
        assertEquals(original.title, field("标题").text.toString())
        assertEquals(original.trigger, field("触发情境").text.toString())
        assertEquals(original.behavior, field("具体做法").text.toString())
        assertEquals(original.expectedSignal, field("期望信号").text.toString())
        field("标题").setText("合成资料：没有采纳的新标题")
        field("具体做法").setText("合成资料：没有采纳的新做法")

        select("decision", "继续原做法")
        assertFalse(field("标题").isShown)
        val nextDate = original.reviewOn.plusDays(7)
        field("下一次回看日期").setText(nextDate.toString())
        val observed = "合成资料：这周试过两次，暂时继续原做法观察"
        field("这次观察到什么").setText(observed)
        select("execution", "已执行")
        click("saveReview")

        val saved = requireNotNull(app.actions.get(actionId)).value
        assertEquals(2, saved.revision)
        assertEquals(ActionStatus.OPEN, saved.status)
        assertEquals(original.copy(reviewOn = nextDate), saved.draft)
        assertEquals(original, saved.reviewedDraft)
        val review = requireNotNull(saved.review)
        assertEquals(ActionDecision.CONTINUE, review.decision)
        assertEquals(ActionExecution.EXECUTED, review.execution)
        assertEquals(observed, review.observedText)
    }

    /** Locate the input by its visible form label, without depending on its position in the page. */
    private fun field(label: String): EditText = descendants(activity.window.decorView)
        .filterIsInstance<TextView>()
        .filter { it !is EditText && it.text.toString() == label }
        .mapNotNull { caption ->
            val parent = caption.parent as? ViewGroup ?: return@mapNotNull null
            parent.getChildAt(parent.indexOfChild(caption) + 1) as? EditText
        }.single()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun select(tag: String, label: String) {
        val spinner = view<Spinner>(tag)
        val position = (0 until spinner.count).single { spinner.getItemAtPosition(it).toString() == label }
        spinner.setSelection(position)
        drain()
    }

    private fun click(tag: String) {
        assertTrue("Missing/enabled action $tag", view<View>(tag).performClick())
        drain()
    }

    private fun <T : View> view(tag: String): T = requireNotNull(activity.window.decorView.findViewWithTag<T>(tag)) {
        "Missing view $tag"
    }

    private fun drain() {
        repeat(4) {
            app.io.submit {}.get(20, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
