package dev.tracelife.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.templates.ActivityLogTemplate
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActivityTrendActivityTest {
    private lateinit var app: TraceLifeApplication
    private lateinit var controller: ActivityController<DashboardActivity>
    private val activity get() = controller.get()
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {
            val ref = ActivityLogTemplate.obtain(app.repository)
            app.repository.createRecord(RecordDraft("合成普通记录：学习两小时", Occurrence(LocalDate.now()), setOf("学习")), "prose")
            app.repository.createRecord(RecordDraft("合成活动", Occurrence(LocalDate.now()), setOf("学习"), ref,
                mapOf(ActivityLogTemplate.DOMAIN to "学习", ActivityLogTemplate.MINUTES to "30", ActivityLogTemplate.PRACTICES to "2",
                    ActivityLogTemplate.OUTCOME to "合成：完成了一个可运行模块")), "activity")
        }.get(20, TimeUnit.SECONDS)
        controller = Robolectric.buildActivity(DashboardActivity::class.java).setup(); drain()
    }
    @After fun cleanup() {
        controller.pause().stop().destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `activity chart comes from explicit fields and drills into the exact original revision`() {
        val chart = view<ActivityTrendView>("activityTrendChart")
        assertEquals(30, chart.points.last().minutes)
        assertNull(chart.points.first().minutes)
        assertTrue(text().contains("30 分钟 · 2 次实践"))
        chart.performClick(); drain()
        assertNotNull(activity.window.decorView.findViewWithTag<View>("activitySource:activity"))
        assertNull(activity.window.decorView.findViewWithTag<View>("activitySource:prose"))
        click("activitySource:activity")
        click("activityField:answer:${ActivityLogTemplate.MINUTES}")
        assertTrue(text().contains("30"))
    }

    @Test fun `outcome timeline shows only explicitly entered outcome and numeric settings survive recreation`() {
        click("activityOutcomes")
        assertTrue(text().contains("合成：完成了一个可运行模块"))
        assertFalse(text().contains("合成普通记录"))
        click("back")
        click("activityDomain:WORK")
        click("activityMeasure:PRACTICES")
        controller.recreate(); drain()
        assertTrue(text().contains("时长未记录 · 实践次数未记录"))
        assertEquals(dev.tracelife.metrics.ActivityMeasure.PRACTICES, view<ActivityTrendView>("activityTrendChart").measure)
        assertTrue(view<ActivityTrendView>("activityTrendChart").points.all { it.practices == null })
    }

    @Test fun `missing day remains unknown when selected by keyboard and input is reachable`() {
        val chart = view<ActivityTrendView>("activityTrendChart")
        chart.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(chart.contentDescription.contains("未记录"))
        chart.performClick(); drain()
        assertTrue(text().contains("没有符合条件的明确记录"))
        click("back")
        click("newActivity")
        assertEquals(ActivityEntryActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun `both domain and measure choices fit within a real narrow screen`() {
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 360, 800)
        listOf("activityDomain:WORK", "activityDomain:LEARNING", "activityMeasure:MINUTES", "activityMeasure:PRACTICES").forEach { tag ->
            val button = view<View>(tag)
            val row = button.parent as View
            assertTrue("$tag has usable width", button.width > 0)
            assertTrue("$tag must remain inside its row", button.left >= 0 && button.right <= row.width)
        }
        click("activityMeasure:PRACTICES")
        assertEquals(dev.tracelife.metrics.ActivityMeasure.PRACTICES, view<ActivityTrendView>("activityTrendChart").measure)
    }
    private fun click(tag: String) { view<View>(tag).performClick(); drain() }
    private fun <T : View> view(tag: String): T = requireNotNull(activity.window.decorView.findViewWithTag(tag))
    private fun text() = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.joinToString("\n") { it.text.toString() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun drain() { repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() } }
}
