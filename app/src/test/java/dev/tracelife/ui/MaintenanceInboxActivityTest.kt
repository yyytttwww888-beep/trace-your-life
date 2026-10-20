package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.actions.SyntheticActionReflectionProvider
import dev.tracelife.background.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.ProfileDimension
import dev.tracelife.storage.AnalysisQuery
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MaintenanceInboxActivityTest {
    private lateinit var app: TraceLifeApplication
    private var inbox: ActivityController<MaintenanceInboxActivity>? = null
    private var settings: ActivityController<SettingsActivity>? = null
    private var review: ActivityController<ReviewActivity>? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {}.get(20, TimeUnit.SECONDS)
    }
    @After fun tearDown() {
        inbox?.pause()?.stop()?.destroy(); settings?.pause()?.stop()?.destroy(); review?.pause()?.stop()?.destroy()
        app.io.submit { app.incrementalOrganizer.close(); app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `opening and recreating empty inbox never enables or sends analysis`() {
        inbox = Robolectric.buildActivity(MaintenanceInboxActivity::class.java).setup()
        drain()
        assertTrue(texts(inbox!!.get().window.decorView).contains("尚未开启自动整理"))
        inbox!!.recreate(); drain()
        assertFalse(app.incrementalOrganizer.policy().enabled)
        assertEquals(0, app.incrementalOrganizer.settings().reservedRequestsToday)
        assertTrue(app.repository.analysisRunIndex().isEmpty())
    }

    @Test fun `completed periodic draft opens the exact task without another provider call`() {
        val provider = SyntheticActionReflectionProvider()
        val runId = app.io.submit<String> {
            val day = LocalDate.now()
            app.repository.createRecord(RecordDraft("合成验收：今天完成一次小练习，是否持续有帮助仍未知。", Occurrence(day)), "synthetic-inbox")
            val run = app.reflectionWorkflow.start(AnalysisQuery(day, day, ZoneId.systemDefault().id))
            app.reflectionWorkflow.process(run.id, provider, 1)
            app.incrementalOrganizer.settings()
            BackgroundStore(File(app.noBackupFilesDir, "incremental-analysis")).use { store ->
                store.transaction { save(BackgroundJob("synthetic-weekly", 1, "", 0, 0, BackgroundJobStatus.PROPOSALS_READY,
                    run.id, 1, 1, false, Instant.now(), Instant.now(), BackgroundJobKind.WEEKLY_REFLECTION, day, day, ZoneId.systemDefault().id)) }
            }
            run.id
        }.get(20, TimeUnit.SECONDS)
        val calls = provider.calls
        inbox = Robolectric.buildActivity(MaintenanceInboxActivity::class.java).setup(); drain()
        val activity = inbox!!.get()
        assertTrue(texts(activity.window.decorView).contains("一周回看"))
        activity.window.decorView.findViewWithTag<View>("openJob:synthetic-weekly").performClick(); drain()
        val target = shadowOf(activity).nextStartedActivity
        assertEquals(ReflectionActivity::class.java.name, target.component?.className)
        assertEquals(runId, target.getStringExtra(ReflectionActivity.EXTRA_RUN_ID))
        assertEquals(calls, provider.calls)
        assertTrue(app.repository.knowledgeHeads().isEmpty())
    }

    @Test fun `periodic options require explicit confirmation and disclose three month profile range`() {
        settings = Robolectric.buildActivity(SettingsActivity::class.java).setup(); drain()
        val activity = settings!!.get()
        val checks = descendants(activity.window.decorView).filterIsInstance<CheckBox>().toList()
        checks.single { it.text.toString() == "自动整理新记录" }.isChecked = true
        activity.window.decorView.findViewWithTag<CheckBox>("weeklyReflection").isChecked = true
        activity.window.decorView.findViewWithTag<CheckBox>("monthlyProfile").isChecked = true
        activity.window.decorView.findViewWithTag<View>("applyPolicy").performClick(); drain()
        assertFalse(app.incrementalOrganizer.policy().enabled)
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(texts(dialog.window!!.decorView).contains("最近三个完整月"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain()
        val policy = app.incrementalOrganizer.policy()
        assertTrue(policy.enabled)
        assertTrue(policy.weeklyReflection)
        assertFalse(policy.monthlyReflection)
        assertEquals(ProfileDimension.ABILITIES_AND_STRATEGIES, policy.monthlyProfileDimension)
        assertEquals(0, app.incrementalOrganizer.settings().reservedRequestsToday)
        assertTrue(app.repository.analysisRunIndex().isEmpty())
    }

    @Test fun `single record intent opens existing task and consumes its navigation extra`() {
        val task = app.io.submit<String> {
            val day = LocalDate.now()
            app.analysis.start(dev.tracelife.analysis.AnalysisRequest("合成检查", AnalysisQuery(day, day, ZoneId.systemDefault().id))).stored.id
        }.get(20, TimeUnit.SECONDS)
        review = Robolectric.buildActivity(ReviewActivity::class.java,
            Intent(app, ReviewActivity::class.java).putExtra("runId", task)).setup(); drain()
        assertFalse(review!!.get().intent.hasExtra("runId"))
        assertEquals(0, app.analysis.task(task).state.calls.size)
    }

    private fun drain() {
        repeat(3) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
}
