package dev.tracelife.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import dev.tracelife.MainActivity
import dev.tracelife.TraceLifeApplication
import dev.tracelife.model.RecordRevision
import dev.tracelife.overview.PersonalDirection
import dev.tracelife.overview.PersonalOverview
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
import org.robolectric.shadows.ShadowAlertDialog
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OverviewActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<OverviewActivity>? = null
    private val activity get() = requireNotNull(controller).get()

    @Before fun setup() { app = RuntimeEnvironment.getApplication() as TraceLifeApplication; app.io.submit {}.get(20, TimeUnit.SECONDS) }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS); app.io.shutdownNow()
    }

    @Test fun `direction editor survives recreation then saves original prose once and navigates to source`() {
        open(); click("editDirection")
        field("goals").setText("合成目标：把一个练习运行起来。")
        field("stage").setText("合成条件：每天半小时。")
        field("signals").setText("合成观察：能运行并解释。")
        field("reviewOn").setText(LocalDate.now().plusDays(7).toString())
        requireNotNull(controller).recreate(); drain()
        assertEquals("合成目标：把一个练习运行起来。", field("goals").text.toString())
        click("saveDirection")
        assertTrue(text().contains("合成目标：把一个练习运行起来。"))
        assertTrue(text().contains("你在"))
        val record = app.io.submit<RecordRevision> { PersonalOverview(app.repository).open().direction!!.record }.get(20, TimeUnit.SECONDS)
        assertEquals(1, record.revision)
        requireNotNull(controller).recreate(); drain()
        assertEquals(1, app.repository.history(record.id).size)
        click("directionSource")
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component!!.className)
        assertEquals(record.id, next.getStringExtra(MainActivity.EXTRA_RECORD_ID))
    }

    @Test fun `concurrent source edit refuses old form without losing its unsaved words`() {
        val record = app.io.submit<RecordRevision> { PersonalOverview(app.repository).save(PersonalDirection(goals = "合成初始目标", statedOn = LocalDate.now()), creationId = "direction") }.get(20, TimeUnit.SECONDS)
        open(); click("editDirection")
        field("goals").setText("合成表单里尚未保存的文字")
        app.io.submit { app.repository.updateRecord(record.id, record.revision, record.draft.copy(body = "合成另一个页面修改了原文")) }.get(20, TimeUnit.SECONDS)
        click("saveDirection")
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().findViewById<TextView>(android.R.id.message).text.toString().contains("修改"))
        assertEquals("合成表单里尚未保存的文字", field("goals").text.toString())
        assertEquals("合成另一个页面修改了原文", app.repository.record(record.id)!!.draft.body)
    }

    @Test fun `empty direction remains a choice and opening overview makes no analysis task`() {
        open()
        assertTrue(text().contains("还没有当前有效、已采用的画像"))
        click("editDirection"); click("saveDirection")
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().findViewById<TextView>(android.R.id.message).text.toString().contains("至少"))
        assertEquals(0, app.repository.listRecords().total)
        assertTrue(app.repository.analysisRuns(0, 10).isEmpty())
    }

    @Test fun `returning while original overview result awaits delivery refreshes current direction after busy read`() {
        controller = Robolectric.buildActivity(OverviewActivity::class.java).setup()
        // The original empty snapshot is ready, but its main-thread callback is still queued.
        app.io.submit {}.get(20, TimeUnit.SECONDS)
        requireNotNull(controller).pause()
        app.io.submit { PersonalOverview(app.repository).save(PersonalDirection(goals = "合成外部页面新方向",
            statedOn = LocalDate.now()), creationId = "external-direction") }.get(20, TimeUnit.SECONDS)
        requireNotNull(controller).resume(); drain()
        assertTrue(text().contains("合成外部页面新方向"))
        assertTrue(app.repository.analysisRuns(0, 10).isEmpty())
    }

    private fun open() { controller = Robolectric.buildActivity(OverviewActivity::class.java).setup(); drain() }
    private fun field(key: String): EditText = activity.window.decorView.findViewWithTag("direction:$key")
    private fun click(tag: String) { assertTrue(activity.window.decorView.findViewWithTag<View>(tag).performClick()); drain() }
    private fun text() = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.joinToString("\n") { it.text.toString() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun drain() { repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() } }
}
