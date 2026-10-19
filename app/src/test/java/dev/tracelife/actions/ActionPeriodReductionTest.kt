package dev.tracelife.actions

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.reports.PeriodReductionReports
import dev.tracelife.storage.*
import org.json.JSONArray
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

/** Synthetic plans/reports only. These tests exercise real SQLite and bounded fake providers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionPeriodReductionTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var actions: ActionExperiments
    private lateinit var period: ActionPeriodReflection
    private val db = "synthetic-action-reduction-${UUID.randomUUID()}.db"
    private val date = LocalDate.parse("2026-10-19")
    private val clock = Clock.fixed(Instant.parse("2026-10-19T04:00:00Z"), ZoneOffset.UTC)
    private val scope = AnalysisQuery(date, date, "Asia/Shanghai")
    private val provider = Provider()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, db)
        attach()
    }
    private fun attach() {
        analysis = ControlledAnalysis(repository, clock)
        actions = ActionExperiments(repository, clock)
        period = ActionPeriodReflection(analysis)
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(db) }

    @Test fun `mixed sources form a bounded resumable tree and every original remains navigable`() {
        seed(5, 5)
        start()
        val leaves = period.process("run", provider, maxBatches = 20, inputsPerBatch = 1)
        assertEquals(10, leaves.progress.batches.size)
        val first = period.reduce("run", provider, maxRequests = 1, fanIn = 2)
        assertEquals(ReductionProcessingStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(1, first.modelRequestsThisPass)
        repository.close(); repository = LifeRepository(context, clock, db); attach()
        val result = period.reduce("run", provider, maxRequests = 20, fanIn = 2)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, result.status)
        assertEquals(8, result.modelRequestsThisPass)
        assertEquals(5, result.progress.originalActionEvents)
        assertEquals(5, result.progress.originalEvidenceFragments)
        assertEquals(0, period.reduce("run", provider).modelRequestsThisPass)
        assertEquals(ActionPeriodIntegration.TREE_INTEGRATED, period.inspect("run").integration)
        assertEquals(result.progress.root, period.inspect("run").root)
        provider.requests.filter { it.getString("purpose") == "REDUCTION" }.forEach {
            assertTrue(it.getBoolean("actionPeriod"))
            assertEquals(2, it.getJSONArray("derivedInputs").length())
            assertFalse(it.has("actionEvidence")); assertFalse(it.has("evidence"))
        }
        val reports = PeriodReductionReports(repository, analysis)
        val draft = reports.open("run", requireNotNull(result.progress.root))
        assertEquals(5, draft.originalActionEvents)
        val page = reports.actionEvidence(draft, limit = 2)
        assertEquals(2, page.items.size); assertEquals(2, page.nextOffset)
        val raw = reports.actionOriginal(draft, page.items.first().id)
        assertEquals("USER_AUTHORED", raw["sourceNature"])
        assertNotNull(raw["plan"])
        assertEquals(5, reports.evidence(draft).total)
        assertThrows(IllegalArgumentException::class.java) { reports.original(draft, page.items.first().id) }
    }

    @Test fun `action only tree scans complete chain and publishes final related subset with full proof`() {
        seed(3, 0)
        val target = tree()
        confirm(target)
        val scanner = Scanner { if (it.optString("actionId") == "action-2") ReductionRelevance.IRRELEVANT else ReductionRelevance.SUPPORT }
        val reviewer = Reviewer()
        val result = PeriodReductionReviewProcessor(analysis).process("run", target, scanner, reviewer, maxRequests = 10, batchSize = 2)
        assertEquals(ReductionReviewStatus.SUPPORTED, result.status)
        assertEquals(0, result.progress.originalFragments)
        assertEquals(3, result.progress.originalActionEvents)
        assertEquals(3, result.progress.scannedActionEvents)
        assertEquals(2, result.progress.finalRereadActionEvents)
        assertTrue(result.progress.allOriginalsScanned)
        val envelope = reviewer.requests.single()
        assertEquals(0, envelope.getJSONArray("evidence").length())
        assertEquals(2, envelope.getJSONArray("actionEvidence").length())
        assertTrue(envelope.getBoolean("actionPeriod"))
        val knowledge = publish(target)
        assertEquals(2, knowledge.actionEvidence.size)
        assertEquals(3, requireNotNull(knowledge.reductionProof).originalActionEvidence.size)
        assertEquals(3, KnowledgeValidation.allActionEvidence(knowledge).size)
        assertEquals(knowledge, KnowledgeCodec.decode(KnowledgeCodec.encode(knowledge)))
        assertEquals(analysis.task("run").state, AnalysisRunCodec.decode(analysis.task("run").stored.payload))
    }

    @Test fun `unprocessed action cannot disappear behind complete record coverage or a partial root`() {
        seed(3, 0); start()
        val refs = analysis.actionPage("run").entries
        refs.take(2).forEachIndexed { i, ref -> analysis.synthesizeActions("run", "leaf-$i", emptyList(), listOf(ref), provider) }
        val leaves = analysis.task("run").state.calls.map(ReductionGraph::nodeRef)
        analysis.reduce("run", "partial", leaves, provider)
        val root = ReductionGraph.nodeRef(analysis.task("run").state.calls.last())
        val target = analysis.reductionStatementRef("run", root, 0)
        assertThrows(AnalysisDependencyBlockedException::class.java) { analysis.reductionReviewContext("run", target) }
        assertEquals(ReductionProcessingStatus.NEEDS_INPUT_PROCESSING, period.reduce("run", provider).status)
        assertEquals(1, PeriodReductionProcessor(analysis).inspect("run").remainingActionEvents)
    }

    @Test fun `more than thirty two relevant mixed sources stop before final request`() {
        seed(18, 18)
        val target = tree(inputsPerBatch = 8)
        confirm(target)
        val reviewer = Reviewer()
        val result = PeriodReductionReviewProcessor(analysis).process("run", target, Scanner { ReductionRelevance.CONTEXT }, reviewer,
            maxRequests = 20, batchSize = 8)
        assertEquals(ReductionReviewStatus.RELATED_EVIDENCE_LIMIT, result.status)
        assertEquals(36, result.progress.relatedFragments)
        assertEquals(18, result.progress.originalActionEvents)
        assertTrue(reviewer.requests.isEmpty())
        assertThrows(AnalysisBudgetException::class.java) { analysis.reviewReduction("run", "oversized-final", target, reviewer) }
        assertTrue(reviewer.requests.isEmpty())
    }

    @Test fun `action counterevidence cannot become supported and missing context does not dispatch final`() {
        seed(2, 0)
        val target = tree()
        val contextScanner = Scanner { ReductionRelevance.NEEDS_CONTEXT }
        val reviewer = Reviewer()
        val contextResult = PeriodReductionReviewProcessor(analysis).process("run", target, contextScanner, reviewer, maxRequests = 10)
        assertEquals(ReductionReviewStatus.CONTEXT_REQUIRED, contextResult.status)
        assertTrue(reviewer.requests.isEmpty())
        confirm(target)
        val scanner = Scanner { if (it.optString("actionId") == "action-1") ReductionRelevance.COUNTEREVIDENCE else ReductionRelevance.SUPPORT }
        val result = PeriodReductionReviewProcessor(analysis).process("run", target, scanner, reviewer, maxRequests = 10)
        assertEquals(ReductionReviewStatus.RETRY_NEEDED, result.status)
        assertEquals("INVALID_RESPONSE", analysis.task("run").state.calls.last().failureCode)
        assertNull(analysis.currentReductionReview("run", target))
    }

    @Test fun `duplicate action scans and omission of registered action are rejected`() {
        seed(3, 0)
        val target = tree()
        val refs = analysis.reductionReviewContext("run", target).originalActionEvidence
        val scanner = Scanner { ReductionRelevance.SUPPORT }
        analysis.scanReduction("run", "one", target, listOf(refs.first().id), scanner)
        assertThrows(IllegalArgumentException::class.java) { analysis.scanReduction("run", "replacement", target, listOf(refs.first().id), scanner) }
        assertThrows(IllegalArgumentException::class.java) { analysis.reviewReduction("run", "too-early", target, Reviewer()) }
        val invalid = object : SemanticReductionScanProvider by scanner {
            override fun scanPrepared(request: PreparedModelRequest) = ReductionScanCompletion(ReductionScanResult(emptyList()))
        }
        assertThrows(AnalysisValidationException::class.java) { analysis.scanReduction("run", "missing", target, listOf(refs[1].id), invalid) }
        assertEquals("INVALID_RESPONSE", analysis.task("run").state.calls.last().failureCode)
    }

    @Test fun `rejected action leaf invalidates tree even if root cites another leaf`() {
        seed(2, 1)
        val target = tree()
        val leaf = analysis.task("run").state.calls.first { it.purpose == CallPurpose.ACTION_SYNTHESIS }
        val ref = analysis.claimRef("run", leaf.batchKey, 0)
        AnalysisFeedback(repository, clock).respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.REJECTED)
        assertEquals(ReductionProcessingStatus.DEPENDENCY_BLOCKED, PeriodReductionProcessor(analysis).inspect("run").status)
        assertThrows(AnalysisDependencyBlockedException::class.java) { analysis.reductionReviewContext("run", target) }
    }

    @Test fun `final keeps action semantics when all action sources were classified irrelevant`() {
        seed(2, 1)
        val target = tree(); confirm(target)
        val reviewer = Reviewer()
        val result = PeriodReductionReviewProcessor(analysis).process("run", target,
            Scanner { if (it.has("actionId")) ReductionRelevance.IRRELEVANT else ReductionRelevance.SUPPORT }, reviewer, maxRequests = 10)
        assertEquals(ReductionReviewStatus.SUPPORTED, result.status)
        assertEquals(0, reviewer.requests.single().getJSONArray("actionEvidence").length())
        assertTrue(reviewer.requests.single().getBoolean("actionPeriod"))
        val value = publish(target)
        assertTrue(value.actionEvidence.isEmpty())
        assertEquals(2, KnowledgeValidation.allActionEvidence(value).size)
    }

    @Test fun `new action during reduction prevents old result writeback`() {
        seed(2, 0); start(); period.process("run", provider, maxBatches = 10, inputsPerBatch = 1)
        val racing = object : AnalysisProvider by provider {
            override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                actions.create(draft("late"), "late")
                return ModelCompletion(provider.complete(request.original))
            }
        }
        val result = period.reduce("run", racing)
        assertEquals(ReductionProcessingStatus.STALE, result.status)
        assertFalse(analysis.task("run").state.calls.any { it.purpose == CallPurpose.REDUCTION && it.status == CallStatus.SUCCEEDED })
    }

    @Test fun `legacy tags and forged omission of action provenance cannot pass knowledge validation`() {
        seed(2, 1)
        val target = tree(); confirm(target)
        PeriodReductionReviewProcessor(analysis).process("run", target, Scanner { ReductionRelevance.SUPPORT }, Reviewer(), maxRequests = 10)
        val value = publish(target)
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeCodec.decode(JSONObject(KnowledgeCodec.encode(value)).put("formatVersion", 5).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.decode(JSONObject(analysis.task("run").stored.payload).put("formatVersion", 10).toString())
        }
        val proof = requireNotNull(value.reductionProof)
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeValidation.validate(value.copy(reductionProof = proof.copy(originalActionEvidence = proof.originalActionEvidence.dropLast(1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeValidation.validate(value.copy(actionEvidence = value.actionEvidence.dropLast(1)))
        }
    }

    private fun seed(actionCount: Int, recordCount: Int) {
        repeat(actionCount) { actions.create(draft("$it"), "action-$it") }
        repeat(recordCount) { repository.createRecord(RecordDraft("合成记录 $it：练习是否有效还需要观察。", Occurrence(date)), "record-$it") }
    }
    private fun draft(label: String) = ActionDraft("合成计划 $label", "合成午休后", "合成练习五分钟", "合成回看报告", date.plusDays(7), "Asia/Shanghai")
    private fun start() = period.start(AnalysisRequest("合成本周期对照计划与实际记录", scope,
        AnalysisLimits(maxInputBytes = 262_144, inputTokenLimit = 100_000, totalInputBytes = 16_777_216)), "run")
    private fun tree(inputsPerBatch: Int = 1): ReductionStatementRef {
        start(); period.process("run", provider, maxBatches = 100, inputsPerBatch = inputsPerBatch)
        val result = period.reduce("run", provider, maxRequests = 100)
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, result.status)
        return analysis.reductionStatementRef("run", requireNotNull(result.progress.root), 0)
    }
    private fun confirm(target: ReductionStatementRef) = AnalysisFeedback(repository, clock).respondReduction("run", target,
        analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
    private fun publish(target: ReductionStatementRef): StoredKnowledgeRevision {
        val review = requireNotNull(analysis.currentReductionReview("run", target))
        return KnowledgeLibrary(repository, clock).publishReduction("run", target, review.batchKey, analysis.task("run").stored.version)
    }

    private class Provider : AnalysisProvider {
        override val id = "synthetic-action-reduction"
        override val contextWindowTokens = 200_000
        val requests = mutableListOf<JSONObject>()
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val root = JSONObject(request.payloadJson).also(requests::add)
            val inputs = if (root.getString("purpose") == "REDUCTION") root.getJSONArray("derivedInputs").objects()
                else root.optJSONArray("evidence").objects() + root.optJSONArray("actionEvidence").objects()
            return BatchProposal(listOf(ProposedClaim("合成解释：计划表达意图，实际效果需结合用户报告和情境继续观察。",
                listOf(inputs.first().getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)), listOf("合成资料不覆盖全部生活"))
        }
    }
    private class Scanner(private val classify: (JSONObject) -> ReductionRelevance) : SemanticReductionScanProvider {
        override val id = "synthetic-action-scan"
        override val contextWindowTokens = 200_000
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            val root = JSONObject(request.original.payloadJson)
            assertTrue(root.getBoolean("actionPeriod"))
            val inputs = root.optJSONArray("evidence").objects() + root.optJSONArray("actionEvidence").objects()
            return ReductionScanCompletion(ReductionScanResult(inputs.map { ReductionFinding(it.getString("id"), classify(it), "合成分类依据") }))
        }
    }
    private class Reviewer : SemanticReviewProvider {
        override val id = "synthetic-action-final"
        override val contextWindowTokens = 200_000
        val requests = mutableListOf<JSONObject>()
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val root = JSONObject(request.original.payloadJson).also(requests::add)
            val inputs = root.optJSONArray("evidence").objects() + root.optJSONArray("actionEvidence").objects()
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(inputs.first().getString("id"))))
        }
    }
    private companion object {
        fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map(::getJSONObject)
    }
}
