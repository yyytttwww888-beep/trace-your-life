package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.actions.*
import dev.tracelife.model.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic records and user reports; actual executor metadata, no network or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionInputCoverageTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private val database = "synthetic-action-coverage-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-20")
    private val provider = Provider()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `twelve text fragments in four records and two action events are completely provided in generation and review`() {
        val selections = fourRecords()
        action(withReview = true)
        start()
        val actions = actions()
        assertEquals(2, actions.size)
        analysis.synthesizeActions("run", "synthesis", selections, actions, provider)
        assertEquals(4, request().getJSONObject("range").getInt("records"))
        assertEquals(12, coverage().getInt("providedFragments"))
        assertComplete(true, true, true, 4)
        assertEquals(0, coverage().getInt("scopeAttachments"))
        assertActionCount(2, 2, true)
        assertKind(ActionEvidenceKind.PLAN_CHANGE, 1, 1, true)
        assertKind(ActionEvidenceKind.USER_REVIEW, 1, 1, true)
        assertKind(ActionEvidenceKind.OPENING_PLAN, 0, 0, true)
        review()
        assertEquals(12, coverage().getInt("reviewedFragments"))
        assertComplete(true, true, true, 4)
        assertEquals(0, coverage().getInt("scopeAttachments"))
        assertActionCount(2, 2, true)
    }

    @Test fun `a partial text fragment and only the review cannot imply complete record or plan event coverage`() {
        fourRecords(); action(withReview = true)
        start(fragmentSize = 4)
        analysis.synthesizeActions("run", "synthesis", listOf(EvidenceSelection("record-0", "body")),
            actions().filter { it.kind == ActionEvidenceKind.USER_REVIEW }, provider)
        assertComplete(false, false, false, 1)
        assertEquals(1, coverage().getInt("providedFragments"))
        assertActionCount(1, 2, false)
        assertKind(ActionEvidenceKind.PLAN_CHANGE, 0, 1, false)
        assertKind(ActionEvidenceKind.USER_REVIEW, 1, 1, true)
        review()
        assertComplete(false, false, false, 1)
        assertActionCount(1, 2, false)
    }

    @Test fun `complete selected bodies still expose missing answer fields and an omitted user review`() {
        fourRecords(); action(withReview = true); start()
        analysis.synthesizeActions("run", "synthesis", (0..3).map { EvidenceSelection("record-$it", "body") },
            actions().filter { it.kind == ActionEvidenceKind.PLAN_CHANGE }, provider)
        assertComplete(true, false, false, 4)
        assertKind(ActionEvidenceKind.USER_REVIEW, 0, 1, false)
        review()
        assertComplete(true, false, false, 4)
        assertKind(ActionEvidenceKind.USER_REVIEW, 0, 1, false)
    }

    @Test fun `complete action text does not imply an attached image was read`() {
        val image = repository.importAttachment("synthetic-unread-image".byteInputStream(), "synthetic.jpg", "image/jpeg")
        repository.createRecord(RecordDraft("合成完整文字记录", Occurrence(day), attachmentIds = setOf(image.id)), "record")
        action(withReview = false); start()
        analysis.synthesizeActions("run", "synthesis", listOf(EvidenceSelection("record", "body")), actions(), provider)
        assertComplete(true, true, true, 1)
        assertEquals(1, coverage().getInt("scopeAttachments"))
        assertKind(ActionEvidenceKind.USER_REVIEW, 0, 0, true)
        assertFalse(request().toString().contains("synthetic-unread-image"))
        review()
        assertEquals(1, coverage().getInt("scopeAttachments"))
        assertEquals(0, coverage().getInt("attachmentsRead"))
    }

    @Test fun `an action only scope has zero text and zero reviews rather than unknown unread records`() {
        action(withReview = false); start()
        analysis.synthesizeActions("run", "synthesis", emptyList(), actions(), provider)
        assertEquals(0, request().getJSONObject("range").getInt("records"))
        assertComplete(true, true, true, 0)
        assertEquals(0, coverage().getInt("providedFragments"))
        assertEquals(0, coverage().getInt("scopeAttachments"))
        assertActionCount(1, 1, true)
        assertKind(ActionEvidenceKind.USER_REVIEW, 0, 0, true)
        review()
        assertComplete(true, true, true, 0)
    }

    private fun fourRecords(): List<EvidenceSelection> {
        val template = repository.saveTemplate(TemplateDraft("合成复盘", Cadence.DAILY,
            questions = listOf(TemplateQuestion("first", "合成做了什么"), TemplateQuestion("second", "合成待观察什么"))))
        return (0..3).flatMap { index ->
            val id = "record-$index"
            repository.createRecord(RecordDraft("合成正文 $index：练习后仍需观察适用情境。", Occurrence(day),
                template = TemplateRef(template.id, template.version), answers = mapOf(
                    "first" to "合成自述：完成一次练习。", "second" to "合成观察：下一次难度不同是否仍能启动。")), id)
            listOf("body", "answer:first", "answer:second").map { EvidenceSelection(id, it) }
        }
    }
    private fun action(withReview: Boolean) {
        val service = ActionExperiments(repository, clock)
        service.create(ActionDraft("合成练习计划", "合成晚间有空时", "合成先练习五分钟", "合成记录是否启动", day.plusDays(7), "UTC"), "action")
        if (withReview) service.review("action", 1, "review", ActionReview(ActionExecution.EXECUTED,
            "合成自述：只完成一次练习，效果未定。", ActionDecision.CONTINUE, day), day.plusDays(8))
    }
    private fun start(fragmentSize: Int = 1024) = analysis.start(AnalysisRequest("合成行动覆盖核验", AnalysisQuery(day, day, "UTC"),
        AnalysisLimits(pageSize = 1, fragmentCodePoints = fragmentSize, inputTokenLimit = 32_000), actionPeriod = true), "run")
    private fun actions() = requireNotNull(analysis.task("run").state.actionPlan).entries
    private fun review() = analysis.reviewClaim("run", "review", analysis.claimRef("run", "synthesis", 0), provider = provider)
    private fun request() = provider.requests.last()
    private fun coverage() = request().getJSONObject("coverage")
    private fun assertComplete(fields: Boolean, records: Boolean, scope: Boolean, count: Int) {
        assertEquals(fields, coverage().getBoolean("providedFieldsComplete"))
        assertEquals(records, coverage().getBoolean("providedRecordsTextComplete"))
        assertEquals(scope, coverage().getBoolean("allScopeTextProvided"))
        assertEquals(count, coverage().getInt("providedRecords"))
        assertEquals(0, coverage().getInt("attachmentsRead"))
        assertFalse(coverage().getBoolean("semanticallyVerified"))
        assertFalse(coverage().has("wholeScopeReviewed")); assertFalse(coverage().has("crossBatchIntegrated"))
    }
    private fun assertActionCount(provided: Int, total: Int, complete: Boolean) =
        assertCount(coverage().getJSONObject("actionEvents"), provided, total, complete)
    private fun assertKind(kind: ActionEvidenceKind, provided: Int, total: Int, complete: Boolean) =
        assertCount(coverage().getJSONObject("actionEvents").getJSONObject("byKind").getJSONObject(kind.name), provided, total, complete)
    private fun assertCount(value: JSONObject, provided: Int, total: Int, complete: Boolean) {
        assertEquals(provided, value.getInt("provided")); assertEquals(total, value.getInt("total"))
        assertEquals(complete, value.getBoolean("allScopeProvided"))
    }
    private class Provider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-action-input-coverage"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<JSONObject>()
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val root = JSONObject(request.payloadJson).also(requests::add)
            return BatchProposal(listOf(ProposedClaim("合成候选：仅核验本次资料覆盖，不证明实际执行或效果。",
                ids(root), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = prepare(request)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val root = JSONObject(request.original.payloadJson).also(requests::add)
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, ids(root)))
        }
        private fun ids(root: JSONObject): List<String> = listOf("evidence", "actionEvidence").flatMap { key ->
            root.getJSONArray(key).let { values -> (0 until values.length()).map { values.getJSONObject(it).getString("id") } }
        }
    }
}
