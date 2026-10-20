package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import dev.tracelife.TraceLifeApplication
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
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

/** All provider results and personal writing are explicitly synthetic; no network or key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileFlowActivityTest {
    private lateinit var app: TraceLifeApplication
    private val controllers = mutableListOf<ActivityController<*>>()
    private var requests = 0
    private val day get() = LocalDate.now()
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        io { app.repository.createRecord(RecordDraft("合成原文：边界明确时，先做一个小练习有助于检查遗漏。", Occurrence(day)), "source") }
    }
    @After fun cleanup() {
        controllers.forEach { it.pause().stop().destroy() }
        io { app.repository.close() }
        app.io.shutdownNow()
    }
    @Test fun `long term form and offline task history survive recreation without requests`() {
        val controller = Robolectric.buildActivity(LongitudinalProfileActivity::class.java).setup().also(controllers::add)
        drain()
        var activity = controller.get()
        activity.window.decorView.findViewWithTag<EditText>("longitudinalQuestion").setText("合成问题：不同月份如何调整做法？")
        activity.window.decorView.findViewWithTag<Spinner>("longitudinalMonths").setSelection(4)
        controller.recreate(); drain(); activity = controller.get()
        assertEquals("合成问题：不同月份如何调整做法？", activity.window.decorView.findViewWithTag<EditText>("longitudinalQuestion").text.toString())
        assertEquals(4, activity.window.decorView.findViewWithTag<Spinner>("longitudinalMonths").selectedItemPosition)
        val task = io {
            app.repository.createRecord(RecordDraft("合成资料：只有上个月有一次练习记录。", Occurrence(day.withDayOfMonth(1).minusDays(1))), "one-month")
            app.longitudinalProfiles.startMonthly(ProfileDimension.ABILITIES_AND_STRATEGIES, "合成跨月任务", "Asia/Shanghai", 3, id = "synthetic-longitudinal-ui")
        }
        click(activity, "profileTaskHistory")
        click(activity, "profileTask:${task.stored.id}")
        assertTrue(text(activity).contains("至少两个比较月份"))
        assertNull(activity.window.decorView.findViewWithTag<View>("continueProfileTask"))
        controller.recreate(); drain()
        assertTrue(text(controller.get()).contains("合成跨月任务"))
        assertEquals(0, requests)
        assertTrue(app.analysis.task(task.stored.id).state.calls.isEmpty())
    }
    @Test fun `maintenance confirmation and full review gate saving while adoption remains separate`() {
        val claim = candidate("synthetic-maintenance-ui")
        val controller = Robolectric.buildActivity(ProfileMaintenanceActivity::class.java,
            ProfileMaintenanceActivity.intent(app, "synthetic-maintenance-ui", claim)).setup().also(controllers::add)
        drain(); var activity = controller.get()
        assertNotNull(activity.window.decorView.findViewWithTag<View>("beginMaintenance"))
        assertNull(activity.window.decorView.findViewWithTag<View>("applyMaintenance:NEW:new"))
        activity.window.decorView.findViewWithTag<EditText>("maintenanceFeedbackText").setText("合成补充：只在任务边界明确时尝试。")
        controller.recreate(); drain(); activity = controller.get()
        assertEquals("合成补充：只在任务边界明确时尝试。", activity.window.decorView.findViewWithTag<EditText>("maintenanceFeedbackText").text.toString())
        val before = requests
        click(activity, "beginMaintenance")
        assertEquals(before, requests)
        io { app.profileMaintenance.process("synthetic-maintenance-ui", claim, relation, scan, reviewer, maxModelRequests = 10) }
        click(activity, "refreshMaintenance")
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, app.profileMaintenance.inspect("synthetic-maintenance-ui", claim).status)
        assertNull(activity.window.decorView.findViewWithTag<View>("applyMaintenance:NEW:new"))
        click(activity, "confirmMaintenanceClaim")
        assertEquals(ProfileMaintenanceStatus.FINAL_READY, app.profileMaintenance.inspect("synthetic-maintenance-ui", claim).status)
        assertNull(activity.window.decorView.findViewWithTag<View>("applyMaintenance:NEW:new"))
        io { app.profileMaintenance.process("synthetic-maintenance-ui", claim, relation, scan, reviewer, maxModelRequests = 10) }
        click(activity, "refreshMaintenance")
        assertNotNull(activity.window.decorView.findViewWithTag<View>("applyMaintenance:NEW:new"))
        click(activity, "applyMaintenance:NEW:new")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick(); drain()
        val saved = app.repository.profileHeads().single()
        assertEquals(ProfileAdoption.CANDIDATE, app.repository.profileRevision(saved.id)!!.adoption)
        assertEquals(0, app.personalProfile.open().total)
        assertTrue(text(activity).contains("已保留这次选择"))
        controller.recreate(); drain()
        assertTrue(text(controller.get()).contains("已保留这次选择"))
        click(controller.get(), "openAppliedProfile")
        val intent = shadowOf(controller.get()).nextStartedActivity
        assertEquals(saved.id, intent.getStringExtra("entryId"))
        val profile = Robolectric.buildActivity(ProfileActivity::class.java, intent).setup().also(controllers::add)
        drain()
        assertNotNull(profile.get().window.decorView.findViewWithTag<View>("adopt"))
        assertTrue(text(profile.get()).contains("合成判断"))
    }
    @Test fun `counterevidence blocks application and reopening does not repeat provider calls`() {
        val claim = candidate("synthetic-blocked-ui")
        io {
            app.analysisFeedback.respond("synthetic-blocked-ui", claim, app.analysis.task("synthetic-blocked-ui").stored.version, ClaimFeedbackDecision.CONFIRMED)
            app.profileMaintenance.begin("synthetic-blocked-ui", claim)
            app.analysis.scanProfile("synthetic-blocked-ui", "counter", claim, listOf(EvidenceSelection("source", "body")), counterScan)
        }
        val controller = Robolectric.buildActivity(ProfileMaintenanceActivity::class.java,
            ProfileMaintenanceActivity.intent(app, "synthetic-blocked-ui", claim)).setup().also(controllers::add)
        drain()
        assertTrue(text(controller.get()).contains("反例 1 段"))
        assertNull(controller.get().window.decorView.findViewWithTag<View>("applyMaintenance:NEW:new"))
        val before = requests
        controller.recreate(); drain()
        assertEquals(before, requests)
        assertTrue(app.repository.profileHeads().isEmpty())
    }
    private fun candidate(id: String): ClaimRef = io {
        app.profileCandidates.start(AnalysisRequest("合成问题：检查情境策略", AnalysisQuery(day, day, "Asia/Shanghai"),
            profileTarget = ProfileAnalysisTarget(ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.CONTEXTUAL_PATTERN)), id)
        app.analysis.synthesize(id, "generation", listOf(EvidenceSelection("source", "body")), object : AnalysisProvider {
            override val id = "synthetic-profile-ui-generator"
            override val contextWindowTokens = 20_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest): BatchProposal {
                requests++
                val evidence = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成判断：这次边界明确的小练习可能帮助检查遗漏，不能外推所有任务。", listOf(evidence), ClaimKind.POSSIBLE_INTERPRETATION)))
            }
        })
        app.analysis.claimRef(id, "generation", 0)
    }
    private val relation = object : SemanticProfileRelationProvider {
        override val id = "synthetic-profile-ui-relation"
        override val contextWindowTokens = 20_000
        override fun prepareRelation(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun relatePrepared(request: PreparedModelRequest): ProfileRelationCompletion {
            requests++
            return ProfileRelationCompletion(ProfileRelationResult(ProfileRelation.UNRELATED, ProfileMaintenanceRecommendation.KEEP, "合成比较：不同范围。"))
        }
    }
    private fun scanner(relevance: ReductionRelevance) = object : SemanticReductionScanProvider {
        override val id = "synthetic-profile-ui-scan-${relevance.name}"
        override val contextWindowTokens = 20_000
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            requests++
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            return ReductionScanCompletion(ReductionScanResult((0 until evidence.length()).map {
                ReductionFinding(evidence.getJSONObject(it).getString("id"), relevance, "合成分类。")
            }))
        }
    }
    private val scan get() = scanner(ReductionRelevance.SUPPORT)
    private val counterScan get() = scanner(ReductionRelevance.COUNTEREVIDENCE)
    private val reviewer = object : SemanticReviewProvider {
        override val id = "synthetic-profile-ui-review"
        override val contextWindowTokens = 20_000
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests++
            val id = JSONObject(request.original.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(id)))
        }
    }
    private fun click(activity: android.app.Activity, tag: String) { requireNotNull(activity.window.decorView.findViewWithTag<View>(tag)).performClick(); drain() }
    private fun text(activity: android.app.Activity) = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.joinToString("\n") { it.text.toString() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun <T> io(block: () -> T): T = app.io.submit<T> { block() }.get(20, TimeUnit.SECONDS)
    private fun drain() { repeat(5) { io {}; shadowOf(Looper.getMainLooper()).idle() } }
}
