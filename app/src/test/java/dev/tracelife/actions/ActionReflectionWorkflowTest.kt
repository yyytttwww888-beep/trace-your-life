package dev.tracelife.actions

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.*
import dev.tracelife.transfer.StaleSnapshotException
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
import java.time.*
import java.util.UUID

/** Synthetic cases exercise the phone orchestration against real SQLite and strict model gates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionReflectionWorkflowTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var workflow: ActionReflectionWorkflow
    private lateinit var analysis: ControlledAnalysis
    private lateinit var actions: ActionExperiments
    private val database = "synthetic-action-phone-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-20")
    private val scope = AnalysisQuery(day.minusDays(6), day, "Asia/Shanghai")
    private val provider = SyntheticActionReflectionProvider()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        attach()
    }
    private fun attach() {
        analysis = ControlledAnalysis(repository, clock)
        actions = ActionExperiments(repository, clock)
        workflow = ActionReflectionWorkflow(repository, analysis, AnalysisFeedback(repository, clock), KnowledgeLibrary(repository, clock))
    }
    @After fun teardown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `generation and reduction share a finite allowance and restart never calls model`() {
        seed()
        val initial = workflow.start(scope)
        val first = workflow.process(initial.task.stored.id, provider, maxRequests = 2, inputsPerBatch = 1)
        assertEquals(2, first.requests)
        assertFalse(first.run.canReview)
        assertEquals(ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED, first.run.progress.integration)
        assertThrows(IllegalArgumentException::class.java) {
            workflow.respond(first.run.task.stored.id, first.run.findings.first().target, first.run.task.stored.version, ClaimFeedbackDecision.CONFIRMED)
        }
        val reduced = workflow.process(initial.task.stored.id, provider, maxRequests = 1, inputsPerBatch = 1)
        assertEquals(1, reduced.requests)
        assertEquals(ActionPeriodIntegration.TREE_INTEGRATED, reduced.run.progress.integration)
        assertTrue(reduced.run.canReview)
        repository.close(); repository = LifeRepository(context, clock, database); attach()
        assertEquals(reduced.run.findings, workflow.inspect(initial.task.stored.id).findings)
        assertEquals(0, workflow.process(initial.task.stored.id, provider, maxRequests = 1).requests)
        assertEquals(3, provider.calls)
    }

    @Test fun `single call finding requires confirmation then rereads plan and records before one save`() {
        seed()
        val id = workflow.start(scope).task.stored.id
        val run = workflow.process(id, provider).run
        val target = run.findings.single().target
        assertThrows(IllegalArgumentException::class.java) { workflow.reviewAndSave(id, target, provider, provider) }
        workflow.respond(id, target, run.task.stored.version, ClaimFeedbackDecision.CONFIRMED, "合成确认：保留执行未知的限制。")
        val saved = workflow.reviewAndSave(id, target, provider, provider, maxRequests = 1, topics = setOf("学习"))
        assertEquals("SAVED", saved.status)
        assertEquals(1, saved.requests)
        assertNotNull(saved.run.findings.single().saved)
        val request = provider.reviews.single()
        assertEquals(1, request.getJSONArray("evidence").length())
        assertEquals(1, request.getJSONArray("actionEvidence").length())
        assertEquals(0, workflow.reviewAndSave(id, target, provider, provider).requests)
        assertEquals(1, repository.knowledgeHeads().size)
    }

    @Test fun `cross batch result pauses after scan and saves only following current final original check`() {
        seed()
        val id = workflow.start(scope).task.stored.id
        val run = workflow.process(id, provider, maxRequests = 3, inputsPerBatch = 1).run
        val target = run.findings.single().target
        assertTrue(target is ActionReflectionTarget.Reduction)
        workflow.respond(id, target, run.task.stored.version, ClaimFeedbackDecision.CONFIRMED)
        val scan = workflow.reviewAndSave(id, target, provider, provider, maxRequests = 1)
        assertEquals(1, scan.requests)
        assertEquals("PASS_LIMIT_REACHED", scan.status)
        assertNull(scan.run.findings.single().saved)
        val final = workflow.reviewAndSave(id, target, provider, provider, maxRequests = 1)
        assertEquals("SAVED", final.status)
        assertEquals(1, final.requests)
        assertEquals(1, final.run.findings.single().reviewProgress!!.finalRereadActionEvents)
        assertEquals(1, final.run.findings.single().reviewProgress!!.finalRereadFragments)
    }

    @Test fun `counterexample and unknown execution cannot become supported knowledge`() {
        seed()
        val original = actions.get("synthetic-plan")!!
        actions.review(original.value.id, 1, "synthetic-review", ActionReview(ActionExecution.UNKNOWN,
            "合成反例：只记得打开练习，实际是否完成仍不清楚，不能认为改善。", ActionDecision.DEFER, day), day.plusDays(7))
        provider.verdict = ReviewVerdict.CONTRADICTED
        provider.counterevidence = true
        val id = workflow.start(scope).task.stored.id
        val run = workflow.process(id, provider, maxRequests = 10, inputsPerBatch = 1).run
        val finding = run.findings.single()
        workflow.respond(id, finding.target, run.task.stored.version, ClaimFeedbackDecision.CONFIRMED)
        val result = workflow.reviewAndSave(id, finding.target, provider, provider, maxRequests = 3)
        assertNull(result.run.findings.single().saved)
        assertEquals(ReviewVerdict.CONTRADICTED, result.run.findings.single().verdict)
        assertTrue(result.run.findings.single().reviewProgress!!.counterevidenceFragments > 0)
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `failed request ends this pass and explicit retry preserves previous inputs`() {
        seed()
        val id = workflow.start(scope).task.stored.id
        provider.fail = true
        val failed = workflow.process(id, provider, maxRequests = 3)
        assertEquals(1, failed.requests)
        assertEquals("RETRY_NEEDED", failed.status)
        assertTrue(failed.run.findings.isEmpty())
        provider.fail = false
        assertEquals(1, workflow.process(id, provider, maxRequests = 3).requests)
        assertEquals(2, provider.calls)
    }

    @Test fun `source reading follows fixed action revisions and rejects changed period`() {
        seed()
        val id = workflow.start(scope).task.stored.id
        val run = workflow.process(id, provider).run
        val finding = run.findings.single()
        val sources = workflow.sources(id, finding.target)
        assertEquals(1, sources.records.size); assertEquals(1, sources.actions.size)
        val body = workflow.actionOriginal(id, sources.actions.single().id, finding.target)
        assertNull(body["review"])
        assertTrue(workflow.original(id, finding.target, sources.records.single().id).text.contains("合成"))
        actions.review("synthetic-plan", 1, "late-review", ActionReview(ActionExecution.NOT_EXECUTED,
            "合成回看：本周未执行", ActionDecision.DEFER, day), day.plusDays(7))
        assertFalse(workflow.inspect(id).canReview)
        assertThrows(StaleSnapshotException::class.java) { workflow.actionOriginal(id, sources.actions.single().id, finding.target) }
        assertThrows(IllegalArgumentException::class.java) { workflow.respond(id, finding.target, run.task.stored.version, ClaimFeedbackDecision.CONFIRMED) }
    }

    @Test fun `archive keeps knowledge readable but blocks new requests and confirmation`() {
        seed()
        val id = workflow.start(scope).task.stored.id
        val run = workflow.process(id, provider).run
        repository.archiveAnalysisRun(id, run.task.stored.version)
        assertFalse(workflow.inspect(id).canReview)
        assertEquals(run.findings.map { it.text }, workflow.inspect(id).findings.map { it.text })
        assertThrows(IllegalArgumentException::class.java) { workflow.process(id, provider) }
        assertEquals(1, provider.calls)
    }

    @Test fun `extra partial leaf cannot borrow another complete calls whole period status`() {
        seed()
        val id = workflow.start(scope).task.stored.id
        workflow.process(id, provider)
        analysis.synthesizeActions(id, "synthetic-additional-leaf", listOf(EvidenceSelection("synthetic-record", "body")), emptyList(), provider)
        val partial = workflow.inspect(id)
        assertFalse(partial.canReview)
        assertEquals(ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED, partial.progress.integration)
        val target = partial.findings.last().target
        assertThrows(IllegalArgumentException::class.java) { workflow.respond(id, target, partial.task.stored.version, ClaimFeedbackDecision.CONFIRMED) }
        val integrated = workflow.process(id, provider, maxRequests = 1)
        assertEquals(1, integrated.requests)
        assertTrue(integrated.run.canReview)
        assertTrue(integrated.run.findings.single().target is ActionReflectionTarget.Reduction)
    }

    @Test fun `source change keeps saved complete root discoverable instead of falling back to leaf summaries`() {
        seed()
        val id = workflow.start(scope).task.stored.id
        val run = workflow.process(id, provider, maxRequests = 3, inputsPerBatch = 1).run
        val target = run.findings.single().target
        workflow.respond(id, target, run.task.stored.version, ClaimFeedbackDecision.CONFIRMED)
        val saved = workflow.reviewAndSave(id, target, provider, provider).run.findings.single().saved!!
        val record = repository.record("synthetic-record")!!
        repository.updateRecord(record.id, record.revision, record.draft.copy(body = "合成修正：后来确认本期没有执行。"))
        val stale = workflow.inspect(id)
        assertFalse(stale.canReview)
        assertEquals(target, stale.findings.single().target)
        assertEquals(saved.value.id, stale.findings.single().saved!!.value.id)
        assertEquals(dev.tracelife.knowledge.KnowledgeEvidenceState.STALE, stale.findings.single().saved!!.evidenceState)
    }

    private fun seed() {
        actions.create(ActionDraft("合成练习", "学习开始时", "先写一个可运行的例子", "记录是否完成及问题", day.plusDays(7), "Asia/Shanghai"), "synthetic-plan")
        repository.createRecord(RecordDraft("合成记录：今天只打开练习，还没有记录是否完成。", Occurrence(day)), "synthetic-record")
    }
}

/** Fixtures are openly synthetic: verdicts are scripted, never reported as measured model accuracy. */
internal class SyntheticActionReflectionProvider : AnalysisProvider, SemanticReviewProvider, SemanticReductionScanProvider {
    override val id = "synthetic-action-phone-provider"
    override val contextWindowTokens = 100_000
    var calls = 0
    var verdict = ReviewVerdict.SUPPORTED
    var counterevidence = false
    var fail = false
    val reviews = mutableListOf<JSONObject>()
    override fun countInputTokens(request: ModelRequest) = 100
    override fun complete(request: ModelRequest): BatchProposal {
        calls++
        if (fail) error("synthetic service failure")
        val json = JSONObject(request.payloadJson)
        val refs = if (json.optString("purpose") == "REDUCTION") json.getJSONArray("derivedInputs").let { values ->
            (0 until values.length()).map { values.getJSONObject(it).getString("id") }
        } else ids(json)
        return BatchProposal(listOf(ProposedClaim("合成发现：目前执行情况仍未知，可以在下次回看补充实际过程。", refs, ClaimKind.POSSIBLE_INTERPRETATION)),
            listOf("合成限制：没有回看不等于未执行。"))
    }
    override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
    override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
        calls++
        val json = JSONObject(request.original.payloadJson); reviews += json
        return ReviewCompletion(ClaimReview(verdict,
            supportingEvidenceIds = if (verdict == ReviewVerdict.SUPPORTED) ids(json) else emptyList(),
            contradictingEvidenceIds = if (verdict == ReviewVerdict.CONTRADICTED) ids(json) else emptyList()))
    }
    override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
    override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
        calls++
        return ReductionScanCompletion(ReductionScanResult(ids(JSONObject(request.original.payloadJson)).map {
            ReductionFinding(it, if (counterevidence) ReductionRelevance.COUNTEREVIDENCE else ReductionRelevance.CONTEXT,
                "合成分类：保留执行未知的原始限制")
        }))
    }
    private fun ids(json: JSONObject) = listOf("evidence", "actionEvidence").flatMap { key ->
        json.optJSONArray(key)?.let { values -> (0 until values.length()).map { values.getJSONObject(it).getString("id") } }.orEmpty()
    }
}
