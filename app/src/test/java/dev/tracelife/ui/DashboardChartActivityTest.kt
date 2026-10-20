package dev.tracelife.ui

import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
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

/** Synthetic record counts; never substitutes text mentions for health measurements. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DashboardChartActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<DashboardActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val day get() = LocalDate.now()
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {
            app.repository.createRecord(RecordDraft("合成资料：今天记录一次练习。", Occurrence(day)), "today")
            app.repository.createRecord(RecordDraft("合成资料：日期未定。", Occurrence()), "unknown")
            app.repository.createRecord(RecordDraft("合成资料：跨日总结。", Occurrence(day.minusDays(2), day)), "interval")
        }.get(20, TimeUnit.SECONDS)
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }
    @Test fun `chart uses exact date counts and activation opens only contributing records`() {
        open()
        val chart = view<RecordTrendView>("recordTrendChart")
        assertEquals(7, chart.points.size)
        assertEquals(1, chart.points.sumOf { it.exactDateRecords })
        assertEquals(1, chart.points.last().exactDateRecords)
        assertTrue(chart.points.last().inProgress)
        assertTrue(text().contains("日期未知 1 条、跨多天 1 条另列"))
        chart.performClick(); drain()
        assertNotNull(activity.window.decorView.findViewWithTag<View>("source:today"))
        assertNull(activity.window.decorView.findViewWithTag<View>("source:unknown"))
        assertNull(activity.window.decorView.findViewWithTag<View>("source:interval"))
    }
    @Test fun `window survives recreation and ninety day points retain actual week boundaries`() {
        open(); click("trendWindow:DAYS_90")
        val before = view<RecordTrendView>("recordTrendChart").points
        assertEquals(90, before.sumOf { it.days })
        assertTrue(before.size in 13..14)
        assertEquals(day.minusDays(89), before.first().from)
        assertEquals(day, before.last().through)
        controller!!.recreate(); drain()
        assertEquals(before, view<RecordTrendView>("recordTrendChart").points)
        click("trendWindow:DAYS_30")
        assertEquals(30, view<RecordTrendView>("recordTrendChart").points.size)
    }
    @Test fun `keyboard selection can open a genuine zero count day without unknown records`() {
        open()
        val chart = view<RecordTrendView>("recordTrendChart")
        chart.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
        chart.performClick(); drain()
        assertTrue(text().contains("共 0 条记录"))
        assertTrue(text().contains("这段时间还没有记录"))
    }
    private fun open() { controller = Robolectric.buildActivity(DashboardActivity::class.java).setup(); drain() }
    private fun click(tag: String) { view<View>(tag).performClick(); drain() }
    private fun <T : View> view(tag: String): T = requireNotNull(activity.window.decorView.findViewWithTag(tag))
    private fun text() = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.joinToString("\n") { it.text.toString() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun drain() { repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() } }
}
