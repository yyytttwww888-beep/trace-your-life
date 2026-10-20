package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
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
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReflectionWorkflowTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var workflow: ReflectionWorkflow
    private val database = "synthetic-reflection-workflow-${UUID.randomUUID()}.db"
    private val day = LocalDate.parse("2026-10-20")
    private val scope = AnalysisQuery(day.minusDays(6), day, "Asia/Shanghai")
    private val provider = SyntheticProvider()

    @Before fun setUp() { context = RuntimeEnvironment.getApplication(); open() }
    @After fun tearDown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `start and inspect are offline while execution synthesizes several originals and publishes nothing`() {
        seed(3)
        val started = workflow.start(scope)
        assertEquals(ReflectionStatus.ANALYZING, started.status)
        assertEquals(0, started.totalRequests)
        assertTrue(started.findings.isEmpty())
        assertEquals(started, workflow.inspect(started.id))
        assertTrue(provider.requests.isEmpty())
        val updates = mutableListOf<ReflectionRun>()
        val done = workflow.process(started.id, provider, onProgress = updates::add)
        assertEquals(ReflectionStatus.READY, done.status)
        assertEquals(1, done.modelRequestsThisPass)
        assertEquals(listOf("SYNTHESIS"), purposes())
        assertEquals(3, evidence(provider.requests.single()).size)
        assertEquals(1, done.run.findings.size)
        assertEquals(done.run.taskVersion, updates.single().taskVersion)
        assertNull(done.run.findings.single().feedback)
        assertNull(done.run.findings.single().knowledgeId)
        assertTrue(repository.knowledgeHeads().isEmpty())
        assertEquals(0L, AnalysisBatchProcessor(analysis).coverage(started.id).completedTextCodePoints)
        val question = JSONObject(provider.requests.single().payloadJson).getString("question")
        assertTrue(question.contains("值得关注："))
        assertTrue(question.contains("不强凑三类"))
        assertEquals(0, workflow.process(started.id, provider).modelRequestsThisPass)
    }

    @Test fun `shared pass budget crosses synthesis and reduction and restarts from durable progress`() {
        seed(9)
        val started = workflow.start(scope)
        val first = workflow.process(started.id, provider, maxRequests = 2)
        assertEquals(2, first.modelRequestsThisPass)
        assertEquals(ReflectionStatus.ANALYZING, first.status)
        assertEquals(2, first.run.synthesizedBatches)
        assertFalse(first.run.canReviewFindings)
        val partial = first.run.findings.first().target
        assertThrows(IllegalArgumentException::class.java) {
            workflow.respond(started.id, partial, first.run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            workflow.reviewAndSave(started.id, partial, provider, provider)
        }
        repository.close(); open()
        assertEquals(first.run, workflow.history().single())
        val synthesisOnly = workflow.process(started.id, provider, maxRequests = 1)
        assertEquals(1, synthesisOnly.modelRequestsThisPass)
        assertEquals(ReflectionStatus.INTEGRATING, synthesisOnly.status)
        assertEquals(3, synthesisOnly.run.synthesizedBatches)
        val root = workflow.process(started.id, provider, maxRequests = 1)
        assertEquals(1, root.modelRequestsThisPass)
        assertEquals(ReflectionStatus.READY, root.status)
        assertTrue(root.run.canReviewFindings)
        assertEquals(4, root.run.totalRequests)
        assertTrue(root.run.findings.single().target is ReflectionTarget.Reduction)
        assertThrows(IllegalArgumentException::class.java) {
            workflow.respond(started.id, partial, root.run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        }
        assertEquals(listOf("SYNTHESIS", "SYNTHESIS", "SYNTHESIS", "REDUCTION"), purposes())
        assertFalse(provider.requests.last().payloadJson.contains("合成原文标记"))
        assertTrue(JSONObject(provider.requests.last().payloadJson).getString("question").contains("值得留下："))
        assertEquals(9, workflow.evidence(started.id, root.run.findings.single().target).total)
    }

    @Test fun `one pass uses the same cap across both generation stages`() {
        seed(5)
        val started = workflow.start(scope)
        val pass = workflow.process(started.id, provider, maxRequests = 3)
        assertEquals(3, pass.modelRequestsThisPass)
        assertEquals(listOf("SYNTHESIS", "SYNTHESIS", "REDUCTION"), purposes())
        assertEquals(ReflectionStatus.READY, pass.status)
        assertEquals(2, pass.run.synthesizedBatches)
        assertEquals(1, pass.run.reducedBatches)
    }

    @Test fun `empty findings and missing time remain completed material rather than personal failure`() {
        seed(5)
        repository.createRecord(RecordDraft("合成未知日期：还不能得出经验。"), "unknown")
        provider.empty = true
        val done = workflow.process(workflow.start(scope).id, provider)
        assertEquals(ReflectionStatus.READY, done.status)
        assertTrue(done.run.findings.isEmpty())
        assertEquals(1, done.run.coverage!!.unknownTimeRecords)
        assertEquals(0L, done.run.coverage.remainingTextFragments)
        assertTrue(done.run.unknowns.contains("合成：没有足够依据形成判断。"))
        assertEquals(2, done.run.synthesizedBatches)
        assertEquals(1, done.run.reducedBatches)
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `confirmation and a current original reread are required before saving and denial prevents publication`() {
        seed(2)
        val run = workflow.process(workflow.start(scope).id, provider).run
        val finding = run.findings.single()
        assertThrows(IllegalArgumentException::class.java) {
            workflow.reviewAndSave(run.id, finding.target, provider, provider)
        }
        assertEquals(1, provider.requests.size)
        val confirmed = workflow.respond(run.id, finding.target, run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        assertTrue(repository.knowledgeHeads().isEmpty())
        val saved = workflow.reviewAndSave(run.id, finding.target, provider, provider, topics = setOf("学习"))
        assertEquals(1, saved.modelRequestsThisPass)
        assertEquals(ReviewVerdict.SUPPORTED, saved.run.findings.single().reviewVerdict)
        assertNotNull(saved.run.findings.single().knowledgeId)
        assertEquals(2, evidence(provider.requests.last()).size)
        assertEquals(setOf("学习"), repository.knowledgeRevision(saved.run.findings.single().knowledgeId!!)!!.topics)
        assertEquals(0, workflow.reviewAndSave(run.id, finding.target, provider, provider).modelRequestsThisPass)
        val rejected = workflow.respond(run.id, finding.target, saved.run.taskVersion,
            ClaimFeedbackDecision.REJECTED, confirmed.findings.single().feedback!!.feedback.version)
        assertNull(rejected.findings.single().knowledgeId)
        assertThrows(IllegalArgumentException::class.java) {
            workflow.reviewAndSave(run.id, finding.target, provider, provider)
        }
        assertEquals(2, provider.requests.size)
    }

    @Test fun `confirmed reduction scans and reviews over bounded resumable passes then publishes the exact text`() {
        seed(9)
        val run = workflow.process(workflow.start(scope).id, provider).run
        val finding = run.findings.single()
        assertTrue(finding.target is ReflectionTarget.Reduction)
        workflow.respond(run.id, finding.target, run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        val first = workflow.reviewAndSave(run.id, finding.target, provider, provider, maxRequests = 1)
        assertEquals(1, first.modelRequestsThisPass)
        assertEquals(8, first.run.findings.single().reviewProgress!!.scannedFragments)
        assertNull(first.run.findings.single().knowledgeId)
        repository.close(); open()
        val second = workflow.reviewAndSave(run.id, finding.target, provider, provider, maxRequests = 1)
        assertEquals(9, second.run.findings.single().reviewProgress!!.scannedFragments)
        assertNull(second.run.findings.single().knowledgeId)
        val saved = workflow.reviewAndSave(run.id, finding.target, provider, provider, maxRequests = 1)
        assertEquals(1, saved.modelRequestsThisPass)
        assertEquals(ReviewVerdict.SUPPORTED, saved.run.findings.single().reviewVerdict)
        assertEquals(finding.text, repository.knowledgeRevision(saved.run.findings.single().knowledgeId!!)!!.claim.text)
        assertEquals(listOf("REDUCTION_SCAN", "REDUCTION_SCAN", "REDUCTION_REVIEW"), purposes().takeLast(3))
        assertEquals(9, evidence(provider.requests.last()).size)
    }

    @Test fun `rejecting one complete single batch finding leaves other findings independently reviewable`() {
        seed(2)
        provider.claimCount = 2
        val run = workflow.process(workflow.start(scope).id, provider).run
        val rejected = workflow.respond(run.id, run.findings.first().target, run.taskVersion, ClaimFeedbackDecision.REJECTED)
        assertEquals(ReflectionStatus.READY, rejected.status)
        assertTrue(rejected.canReviewFindings)
        val retained = rejected.findings.last().target
        workflow.respond(run.id, retained, rejected.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        val saved = workflow.reviewAndSave(run.id, retained, provider, provider)
        assertEquals(ReflectionStatus.READY, saved.run.status)
        assertEquals(ClaimFeedbackDecision.REJECTED, saved.run.findings.first().feedback!!.feedback.decision)
        assertNull(saved.run.findings.first().knowledgeId)
        assertNotNull(saved.run.findings.last().knowledgeId)
        assertEquals(1, repository.knowledgeHeads().size)
    }

    @Test fun `missing review context stays visible without saving or endless automatic requests`() {
        seed(5)
        val run = workflow.process(workflow.start(scope).id, provider).run
        val target = run.findings.single().target
        workflow.respond(run.id, target, run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        provider.relevance = ReductionRelevance.NEEDS_CONTEXT
        val stopped = workflow.reviewAndSave(run.id, target, provider, provider)
        assertEquals(1, stopped.modelRequestsThisPass)
        assertEquals(ReductionReviewStatus.CONTEXT_REQUIRED, stopped.run.findings.single().reviewProgress!!.status)
        assertNull(stopped.run.findings.single().knowledgeId)
        assertEquals(0, workflow.reviewAndSave(run.id, target, provider, provider).modelRequestsThisPass)
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `denied reduction cannot start a scan and unsupported confirmed claim is not saved`() {
        seed(5)
        val reduced = workflow.process(workflow.start(scope).id, provider).run
        val root = reduced.findings.single().target
        val denied = workflow.respond(reduced.id, root, reduced.taskVersion, ClaimFeedbackDecision.REJECTED)
        assertEquals(ClaimFeedbackDecision.REJECTED, denied.findings.single().feedback!!.feedback.decision)
        val count = provider.requests.size
        assertThrows(IllegalArgumentException::class.java) {
            workflow.reviewAndSave(reduced.id, root, provider, provider)
        }
        assertEquals(count, provider.requests.size)
        val ordinary = workflow.process(workflow.start(scope.copy(recordIds = setOf("note-0"))).id, provider).run
        val claim = ordinary.findings.single().target
        workflow.respond(ordinary.id, claim, ordinary.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        provider.verdict = ReviewVerdict.UNSUPPORTED
        val checked = workflow.reviewAndSave(ordinary.id, claim, provider, provider)
        assertEquals(ReviewVerdict.UNSUPPORTED, checked.run.findings.single().reviewVerdict)
        assertNull(checked.run.findings.single().knowledgeId)
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `explicit recovery does not issue a request and failures stop until the next user pass`() {
        seed(2)
        val run = workflow.start(scope)
        provider.crash = true
        assertThrows(SyntheticCrash::class.java) { workflow.process(run.id, provider) }
        repository.close(); open()
        assertEquals(ReflectionStatus.NEEDS_RECOVERY, workflow.inspect(run.id).status)
        assertEquals(0, workflow.process(run.id, provider).modelRequestsThisPass)
        val recovered = workflow.recover(run.id)
        assertEquals(ReflectionStatus.RETRY_NEEDED, recovered.status)
        assertEquals(1, provider.requests.size)
        provider.crash = false
        provider.fail = true
        val failed = workflow.process(run.id, provider)
        assertEquals(1, failed.modelRequestsThisPass)
        assertEquals(ReflectionStatus.RETRY_NEEDED, failed.status)
        provider.fail = false
        assertEquals(ReflectionStatus.READY, workflow.process(run.id, provider).status)
        assertEquals(3, provider.requests.size)
    }

    @Test fun `current originals paginate by receipt while source changes retain only historical findings`() {
        seed(5)
        val run = workflow.process(workflow.start(scope).id, provider).run
        val target = run.findings.single().target
        val first = workflow.evidence(run.id, target, limit = 2)
        assertEquals(5, first.total)
        assertEquals(2, first.nextOffset)
        assertEquals(2, workflow.evidence(run.id, target, first.nextOffset!!, 2).items.size)
        val receipt = first.items.first()
        val fragment = workflow.original(run.id, target, receipt.id, maxCodePoints = 3)
        assertEquals(3, fragment.end - fragment.start)
        val next = workflow.original(run.id, target, receipt.id, start = fragment.end - receipt.start, maxCodePoints = 3)
        assertEquals(fragment.end, next.start)
        assertThrows(IllegalArgumentException::class.java) {
            workflow.original(run.id, target, receipt.id, start = receipt.end - receipt.start)
        }
        repository.createRecord(RecordDraft("合成：新增记录使旧分析成为历史。", Occurrence(day)), "new")
        val historical = workflow.history().single()
        assertEquals(ReflectionStatus.STALE, historical.status)
        assertFalse(historical.canReviewFindings)
        assertEquals(run.findings.single().text, historical.findings.single().text)
        assertEquals(0, workflow.process(run.id, provider).modelRequestsThisPass)
        assertThrows(StaleSnapshotException::class.java) { workflow.evidence(run.id, target) }
    }

    @Test fun `history filters unrelated tasks before limiting and total task budget cannot be exceeded`() {
        seed(5)
        val run = workflow.start(scope, AnalysisLimits(maxCalls = 1))
        repeat(3) { analysis.start(AnalysisRequest("合成无关提取", scope), "unrelated-$it") }
        assertEquals(run.id, workflow.history(limit = 1).single().id)
        val limited = workflow.process(run.id, provider, maxRequests = 5)
        assertEquals(1, limited.modelRequestsThisPass)
        assertEquals(ReflectionStatus.BUDGET_EXHAUSTED, limited.status)
        assertEquals(1, limited.run.synthesizedBatches)
        assertTrue(limited.run.coverage!!.remainingTextFragments > 0)
        assertEquals(ReflectionStatus.BUDGET_EXHAUSTED, workflow.inspect(run.id).status)
    }

    @Test fun `history shows the newest reflection before older ones and unrelated tasks`() {
        seed(1)
        val earlier = workflow.start(scope)
        analysis.start(AnalysisRequest("合成其他流程", scope), "unrelated")
        val latest = workflow.start(scope.copy(from = day.minusDays(2)))
        assertEquals(latest.id, workflow.history(limit = 1).single().id)
        assertEquals(listOf(latest.id, earlier.id), workflow.history().map { it.id })
        assertEquals(earlier.id, workflow.history(limit = 1, offset = 1).single().id)
        assertTrue(workflow.history(limit = 1, offset = 2).isEmpty())
    }

    private fun open() {
        repository = LifeRepository(context, databaseName = database)
        analysis = ControlledAnalysis(repository)
        workflow = ReflectionWorkflow(repository, analysis)
    }

    private fun seed(count: Int) = repeat(count) {
        repository.createRecord(RecordDraft("合成原文标记$it：先做一个小练习，再回看卡在哪里；仅供测试。", Occurrence(day)), "note-$it")
    }

    private fun purposes() = provider.requests.map { JSONObject(it.payloadJson).getString("purpose") }
    private fun evidence(request: ModelRequest): List<JSONObject> = JSONObject(request.payloadJson).getJSONArray("evidence").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject)
    }

    private class SyntheticCrash : Error("Synthetic interrupted worker")

    private inner class SyntheticProvider : AnalysisProvider, SemanticReviewProvider, SemanticReductionScanProvider {
        override val id = "synthetic-reflection-workflow-v1"
        override val contextWindowTokens = 128_000
        val requests = mutableListOf<ModelRequest>()
        var empty = false
        var claimCount = 1
        var fail = false
        var crash = false
        var relevance = ReductionRelevance.SUPPORT
        var verdict = ReviewVerdict.SUPPORTED
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            if (crash) throw SyntheticCrash()
            if (fail) error("Synthetic provider failure")
            val json = JSONObject(request.payloadJson)
            val refs = json.getJSONArray(if (json.getString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            return BatchProposal(if (empty) emptyList() else List(claimCount) { index -> ProposedClaim(
                "值得留下：合成方法${index + 1}中先尝试小练习再查卡点可留作下次尝试，效果仍需回看。",
                (0 until refs.length()).map { refs.getJSONObject(it).getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION) },
                listOf("合成：没有足够依据形成判断。"))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests += request.original
            return ReviewCompletion(ClaimReview(verdict,
                if (verdict == ReviewVerdict.SUPPORTED) evidence(request.original).map { it.getString("id") } else emptyList(),
                unknowns = if (verdict == ReviewVerdict.UNSUPPORTED) listOf("合成资料不足以支持这条发现。") else emptyList()))
        }
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            requests += request.original
            return ReductionScanCompletion(ReductionScanResult(evidence(request.original).map {
                ReductionFinding(it.getString("id"), relevance, "合成材料的相关性说明")
            }))
        }
    }
}
