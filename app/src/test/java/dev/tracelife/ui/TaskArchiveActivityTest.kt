package dev.tracelife.ui

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.StoredKnowledgeRevision
import org.json.JSONObject
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

/** Ordinary extraction tasks use the same archive UI; opening it never starts provider work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TaskArchiveActivityTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<TaskArchiveActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private val day get() = LocalDate.now()
    private val scope get() = AnalysisQuery(day, day, "Asia/Shanghai")
    private var requests = 0
    private val provider = object : AnalysisProvider {
        override val id = "synthetic-general-archive-provider"
        override val contextWindowTokens = 128_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            requests++
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(listOf(ProposedClaim("合成观察：用户记录了一次学习练习。", listOf(id), ClaimKind.REPORTED_EXPERIENCE)))
        }
    }
    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit { app.repository.createRecord(RecordDraft("合成原文：完成一次学习练习。", Occurrence(day), setOf("学习")), "source") }.get(20, TimeUnit.SECONDS)
    }
    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `menu opens all task types and archive restore keep ordinary published knowledge without provider calls`() {
        val saved = publishOrdinary()
        val before = app.repository.analysisRun("synthetic-background-task")!!
        val calls = requests
        val reflection = Robolectric.buildActivity(ReflectionActivity::class.java).setup()
        drain()
        reflection.get().window.decorView.findViewWithTag<View>("reflectionTaskManagement").performClick(); drain()
        reflection.get().window.decorView.findViewWithTag<View>("manageAllTasks").performClick(); drain()
        val intent = shadowOf(reflection.get()).nextStartedActivity
        assertEquals(TaskArchiveActivity::class.java.name, intent.component!!.className)
        assertEquals(calls, requests)
        reflection.pause().stop().destroy()
        controller = Robolectric.buildActivity(TaskArchiveActivity::class.java, intent).setup(); drain()
        assertTrue(text().contains("合成普通整理任务"))
        click("archiveAnyTask:${before.id}")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); drain()
        assertNull(app.repository.analysisRun(before.id)!!.archivedAt)
        click("archiveAnyTask:${before.id}")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain()
        assertTrue(text().contains("这里暂时没有任务"))
        val archived = app.repository.analysisRun(before.id)!!
        assertNotNull(archived.archivedAt)
        assertEquals(before.payload, archived.payload)
        assertEquals(KnowledgeEvidenceState.CURRENT, app.knowledge.get(saved.id)!!.evidenceState)
        click("allTasksToggle")
        assertTrue(text().contains("合成普通整理任务"))
        assertNotNull(activity.window.decorView.findViewWithTag<View>("restoreAnyTask:${before.id}"))
        requireNotNull(controller).recreate(); drain()
        assertTrue(text().contains("已归档"))
        assertNotNull(activity.window.decorView.findViewWithTag<View>("restoreAnyTask:${before.id}"))
        click("restoreAnyTask:${before.id}")
        assertNull(app.repository.analysisRun(before.id)!!.archivedAt)
        assertEquals(before.payload, app.repository.analysisRun(before.id)!!.payload)
        assertEquals(KnowledgeEvidenceState.CURRENT, app.knowledge.get(saved.id)!!.evidenceState)
        click("allTasksToggle")
        assertNotNull(activity.window.decorView.findViewWithTag<View>("archiveAnyTask:${before.id}"))
        assertEquals(calls, requests)
        assertEquals(before.payload, app.repository.analysisRun(before.id)!!.payload)
    }

    @Test fun `archived pagination and rotation retain earlier non reflection tasks`() {
        val ids = app.io.submit<List<String>> {
            List(21) { index ->
                val id = "synthetic-ordinary-${index.toString().padStart(2, '0')}"
                app.analysis.start(AnalysisRequest("合成其他任务 $index", scope), id)
                app.repository.archiveAnalysisRun(id, app.repository.analysisRun(id)!!.version)
                id
            }
        }.get(20, TimeUnit.SECONDS)
        open(); click("allTasksToggle")
        val first = taskTags("restoreAnyTask:")
        assertEquals(20, first.size)
        click("allTasksNext")
        val second = taskTags("restoreAnyTask:")
        assertEquals(1, second.size)
        assertEquals(ids.map { "restoreAnyTask:$it" }.toSet(), first + second)
        requireNotNull(controller).recreate(); drain()
        assertEquals(second, taskTags("restoreAnyTask:"))
        assertTrue(text().contains("查看进行中的任务"))
        click("allTasksPrevious")
        assertEquals(first, taskTags("restoreAnyTask:"))
        assertEquals(0, requests)
    }

    @Test fun `opening task manager does not dispatch or offer archiving an interrupted in flight call`() {
        app.io.submit {
            app.analysis.start(AnalysisRequest("合成等待恢复的整理任务", scope), "synthetic-interrupted")
            val crashed = object : AnalysisProvider {
                override val id = "synthetic-interrupted-archive-provider"
                override val contextWindowTokens = 128_000
                override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
                override fun complete(request: ModelRequest): BatchProposal { requests++; throw AssertionError("synthetic-process-interruption") }
            }
            assertThrows(AssertionError::class.java) {
                app.analysis.runBatch("synthetic-interrupted", "interrupted", listOf(EvidenceSelection("source", "body")), crashed)
            }
        }.get(20, TimeUnit.SECONDS)
        val before = requests
        open()
        assertTrue(text().contains("这项任务正在请求或等待恢复，暂不能归档"))
        assertNull(activity.window.decorView.findViewWithTag<View>("archiveAnyTask:synthetic-interrupted"))
        assertEquals(CallStatus.IN_FLIGHT, app.analysis.task("synthetic-interrupted").state.calls.single().status)
        assertEquals(before, requests)
    }

    private fun publishOrdinary(): StoredKnowledgeRevision = app.io.submit<StoredKnowledgeRevision> {
        val id = "synthetic-background-task"
        app.analysis.start(AnalysisRequest("合成普通整理任务", scope), id)
        app.analysis.runBatch(id, "extraction", listOf(EvidenceSelection("source", "body")), provider)
        val claim = app.analysis.claimRef(id, "extraction", 0)
        app.analysis.reviewClaim(id, "review", claim, provider = object : SemanticReviewProvider {
            override val id = "synthetic-general-archive-review"
            override val contextWindowTokens = 128_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                requests++
                val evidence = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidence)))
            }
        })
        app.knowledge.publish(id, claim, "review", KnowledgeCategory.OBSERVATION, app.repository.analysisRun(id)!!.version)
    }.get(20, TimeUnit.SECONDS)

    private fun open() { controller = Robolectric.buildActivity(TaskArchiveActivity::class.java).setup(); drain() }
    private fun click(tag: String) { assertTrue(activity.window.decorView.findViewWithTag<View>(tag).performClick()); drain() }
    private fun taskTags(prefix: String) = descendants(activity.window.decorView).mapNotNull { it.tag as? String }.filter { it.startsWith(prefix) }.toSet()
    private fun text() = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.joinToString("\n") { it.text.toString() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun drain() { repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() } }
}
