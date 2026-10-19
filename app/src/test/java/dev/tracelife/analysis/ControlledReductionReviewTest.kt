package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ControlledReductionReviewTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var feedback: AnalysisFeedback
    private val database = "synthetic-reduction-review-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-19T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-19")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        (1..40).forEach { repository.createRecord(RecordDraft("合成原文🧪$it：对练习效果仍需保留情境限定。", Occurrence(date)), "record-$it") }
        analysis.start(AnalysisRequest("合成周期核对", AnalysisQuery(date, date, "Asia/Shanghai"),
            AnalysisLimits(maxInputBytes = 262_144, inputTokenLimit = 100_000, totalInputBytes = 16_777_216)), "run")
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `forty original fragments are scanned exactly once and final independently rereads all relevant originals`() {
        val target = tree()
        confirm(target)
        val scanner = Scanner { id -> if (id == "record-1") ReductionRelevance.SUPPORT else if (id == "record-40") ReductionRelevance.CONTEXT else ReductionRelevance.IRRELEVANT }
        scanAll(target, scanner)
        val reviewer = Reviewer()
        val result = analysis.reviewReduction("run", "final", target, reviewer)
        assertEquals(ReviewVerdict.SUPPORTED, result.verdict)
        val body = JSONObject(reviewer.requests.single().payloadJson)
        assertEquals("REDUCTION_REVIEW", body.getString("purpose"))
        assertEquals(2, body.getJSONArray("evidence").length())
        assertEquals(40, body.getJSONObject("coverage").getInt("originalFragments"))
        assertEquals(38, body.getJSONObject("coverage").getInt("omittedAsIrrelevant"))
        assertTrue(body.getJSONArray("evidence").toString().contains("合成原文"))
        val task = analysis.task("run")
        val final = requireNotNull(analysis.currentReductionReview("run", target))
        val proof = ReductionReviewProtocol.proof(task.state, final)
        assertEquals(40, proof.originalEvidence.size)
        assertEquals(2, proof.scans.size)
        assertEquals(2, proof.leafRefs.size)
        assertEquals(3, proof.dependencyTokens.size)
        assertNull(final.proposal)
        assertEquals(result, analysis.reviewReduction("run", "final", target, reviewer))
        assertEquals(1, reviewer.requests.size)
    }

    @Test fun `one unscanned fragment prevents final and duplicate scans cannot replace an inconvenient classification`() {
        val target = tree()
        val refs = analysis.reductionReviewContext("run", target).originalEvidence
        val scanner = Scanner { ReductionRelevance.SUPPORT }
        analysis.scanReduction("run", "scan-a", target, refs.take(20).map { it.id }, scanner)
        assertThrows(IllegalArgumentException::class.java) { analysis.scanReduction("run", "replace", target, listOf(refs.first().id), scanner) }
        analysis.scanReduction("run", "scan-b", target, refs.drop(20).dropLast(1).map { it.id }, scanner)
        val reviewer = Reviewer()
        assertThrows(IllegalArgumentException::class.java) { analysis.reviewReduction("run", "final", target, reviewer) }
        assertTrue(reviewer.requests.isEmpty())
        assertEquals(2, scanner.requests.size)
    }

    @Test fun `needs context prevents final and counterevidence cannot be upgraded to supported`() {
        val target = tree()
        scanAll(target, Scanner { if (it == "record-40") ReductionRelevance.NEEDS_CONTEXT else ReductionRelevance.IRRELEVANT })
        val reviewer = Reviewer()
        assertThrows(IllegalArgumentException::class.java) { analysis.reviewReduction("run", "needs-context", target, reviewer) }
        assertTrue(reviewer.requests.isEmpty())
        confirm(target)
        scanAll(target, Scanner { if (it == "record-40") ReductionRelevance.COUNTEREVIDENCE else if (it == "record-1") ReductionRelevance.SUPPORT else ReductionRelevance.IRRELEVANT }, "new")
        assertThrows(AnalysisValidationException::class.java) { analysis.reviewReduction("run", "bad-final", target, reviewer) }
        assertEquals("INVALID_RESPONSE", analysis.task("run").state.calls.last().failureCode)
        assertNull(analysis.currentReductionReview("run", target))
        val negative = analysis.reviewReduction("run", "negative-final", target, Reviewer(ReviewVerdict.CONTRADICTED))
        assertEquals(ReviewVerdict.CONTRADICTED, negative.verdict)
    }

    @Test fun `all irrelevant or more than thirty two relevant fragments do not dispatch final`() {
        val target = tree()
        scanAll(target, Scanner { ReductionRelevance.IRRELEVANT })
        val reviewer = Reviewer()
        assertThrows(IllegalArgumentException::class.java) { analysis.reviewReduction("run", "empty", target, reviewer) }
        confirm(target)
        scanAll(target, Scanner { ReductionRelevance.CONTEXT }, "new")
        assertThrows(AnalysisBudgetException::class.java) { analysis.reviewReduction("run", "too-large", target, reviewer) }
        assertTrue(reviewer.requests.isEmpty())
    }

    @Test fun `scan rejects missing duplicate foreign findings and keeps consumed budget`() {
        val target = tree()
        val refs = analysis.reductionReviewContext("run", target).originalEvidence.take(2)
        val valid = Scanner { ReductionRelevance.CONTEXT }
        listOf<(ReductionScanResult) -> ReductionScanResult>(
            { it.copy(findings = it.findings.dropLast(1)) },
            { it.copy(findings = listOf(it.findings.first(), it.findings.first())) },
            { it.copy(findings = it.findings.map { finding -> finding.copy(receiptId = "0".repeat(64)) }) },
        ).forEachIndexed { index, mutate ->
            val invalid = object : SemanticReductionScanProvider by valid {
                override fun scanPrepared(request: PreparedModelRequest) = valid.scanPrepared(request).let { it.copy(result = mutate(it.result)) }
            }
            assertThrows(AnalysisValidationException::class.java) { analysis.scanReduction("run", "invalid-$index", target, refs.map { it.id }, invalid) }
            assertEquals("INVALID_RESPONSE", analysis.task("run").state.calls.last().failureCode)
            assertTrue(analysis.task("run").state.calls.last().inputBytes > 0)
        }
    }

    @Test fun `target feedback version changes invalidate scans while independent user text is never transmitted`() {
        val target = tree()
        scanAll(target, Scanner { if (it == "record-1") ReductionRelevance.SUPPORT else ReductionRelevance.IRRELEVANT })
        feedback.respond("run", ReductionGraph.feedbackRef(target), analysis.task("run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, "PRIVATE_SYNTHETIC_ROOT_FEEDBACK")
        assertTrue(analysis.reductionReviewContext("run", target).scans.isEmpty())
        val scanner = Scanner { if (it == "record-1") ReductionRelevance.SUPPORT else ReductionRelevance.IRRELEVANT }
        scanAll(target, scanner, "fresh")
        val reviewer = Reviewer()
        analysis.reviewReduction("run", "final", target, reviewer)
        assertFalse((scanner.requests + reviewer.requests).any { it.payloadJson.contains("PRIVATE_SYNTHETIC_ROOT_FEEDBACK") })
    }

    @Test fun `all intermediate statement feedback is a dependency even if final draft did not cite it`() {
        val target = tree(nested = true)
        val middle = analysis.task("run").state.calls.single { it.batchKey == "middle" }
        val middleTarget = ReductionGraph.statementRef(middle, 0)
        assertTrue(analysis.reductionReviewContext("run", target).dependencyTokens.any { it.claim == ReductionGraph.feedbackRef(middleTarget) && it.version == 0 })
        feedback.respond("run", ReductionGraph.feedbackRef(middleTarget), analysis.task("run").stored.version, ClaimFeedbackDecision.REJECTED)
        assertThrows(AnalysisDependencyBlockedException::class.java) { analysis.reductionReviewContext("run", target) }
    }

    @Test fun `deleting root feedback erases draft scans and final but preserves identity and accounting`() {
        val target = tree()
        val accepted = confirm(target)
        scanAll(target, Scanner { if (it == "record-1") ReductionRelevance.SUPPORT else ReductionRelevance.IRRELEVANT })
        analysis.reviewReduction("run", "final", target, Reviewer())
        val before = analysis.task("run").state.calls.sumOf { it.inputBytes }
        feedback.delete(accepted.feedback.id, accepted.feedback.version)
        val state = analysis.task("run").state
        val root = state.calls.single { it.batchKey == "root" }
        assertNull(root.reductionOutput)
        assertNotNull(root.redactedReductionIdentity)
        assertTrue(ReductionGraph.matchesFeedbackTarget(state, ReductionGraph.feedbackRef(target)))
        assertEquals(before, state.calls.sumOf { it.inputBytes })
        state.calls.filter { it.purpose in setOf(CallPurpose.REDUCTION_SCAN, CallPurpose.REDUCTION_REVIEW) }.forEach {
            assertEquals("FEEDBACK_DELETED", it.failureCode)
            assertNull(it.reductionScan)
            assertNull(it.review)
        }
        assertEquals(state, AnalysisRunCodec.decode(AnalysisRunCodec.encode(state)))
        val unused = object : AnalysisProvider {
            override val id = "synthetic-draft"
            override val contextWindowTokens = 200_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest): BatchProposal = error("must not resurrect")
        }
        assertThrows(AnalysisDependencyBlockedException::class.java) {
            analysis.reduce("run", "root", root.reductionInputs.map { it.node }, unused)
        }
    }

    @Test fun `legacy v5 graph keeps its identities but any previously unfenced intermediate feedback invalidates it`() {
        tree(nested = true)
        val task = analysis.task("run")
        val calls = mutableListOf<AnalysisCall>()
        task.state.calls.forEach { call ->
            if (call.purpose != CallPurpose.REDUCTION) calls += call else {
                val inputs = call.reductionInputs.map { old ->
                    val parent = calls.single { it.batchKey == old.node.batchKey && it.attempt == old.node.attempt }
                    val node = ReductionGraph.nodeRef(parent)
                    ReductionGraph.input(node, parent.requestSha256, ReductionGraph.feedbackClaims(calls, node, false).map { ReductionFeedbackToken(it, 0) }, false)
                }
                val ids = call.reductionInputs.zip(inputs).associate { (old, new) -> old.id to new.id }
                calls += call.copy(reductionInputs = inputs, reductionOutput = requireNotNull(call.reductionOutput).let { output ->
                    output.copy(statements = output.statements.map { it.copy(inputIds = it.inputIds.map(ids::getValue)) })
                })
            }
        }
        val old = JSONObject(AnalysisRunCodec.encode(task.state.copy(calls = calls))).apply {
            put("formatVersion", 5)
            getJSONObject("request").remove("profileTarget")
            val array = getJSONArray("calls")
            repeat(array.length()) { index -> array.getJSONObject(index).apply {
                listOf("metricEvidence", "redactedReductionIdentity", "reductionTarget", "reductionReviewTokens", "reductionScan", "reductionScans").forEach(::remove)
                val inputs = getJSONArray("reductionInputs")
                repeat(inputs.length()) { inputs.getJSONObject(it).remove("includeDerivedFeedback") }
            } }
        }.toString()
        assertEquals(calls, AnalysisRunCodec.decode(old).calls)
        repository.saveAnalysisRun(task.stored.copy(version = task.stored.version + 1, payload = old), task.stored.version)
        val root = ReductionGraph.statementRef(calls.last(), 0)
        assertEquals(40, analysis.reductionReviewContext("run", root).originalEvidence.size)
        val intermediate = ReductionGraph.statementRef(calls.single { it.batchKey == "middle" }, 0)
        feedback.respond("run", ReductionGraph.feedbackRef(intermediate), analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        assertThrows(AnalysisConflictException::class.java) { analysis.reductionReviewContext("run", root) }
    }

    @Test fun `prepared overhead and feedback changes during dispatch cannot retain a successful scan`() {
        val target = tree()
        val refs = analysis.reductionReviewContext("run", target).originalEvidence.take(2).map { it.id }
        val scanner = Scanner { ReductionRelevance.CONTEXT }
        val expanded = object : SemanticReductionScanProvider by scanner {
            override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, "x".repeat(262_145), 1)
        }
        val before = analysis.task("run").state.calls.size
        assertThrows(AnalysisBudgetException::class.java) { analysis.scanReduction("run", "budget", target, refs, expanded) }
        assertEquals(before, analysis.task("run").state.calls.size)
        scanner.before = { confirm(target) }
        assertThrows(AnalysisConflictException::class.java) { analysis.scanReduction("run", "race", target, refs, scanner) }
        assertEquals(CallStatus.IN_FLIGHT, analysis.task("run").state.calls.last().status)
        assertNull(analysis.task("run").state.calls.last().reductionScan)
    }

    @Test fun `v5 failed and interrupted reductions resume with new protocol key without refunding historic cost`() {
        tree()
        val template = analysis.task("run").state
        val leafCalls = template.calls.dropLast(1)
        val oldRoot = template.calls.last()
        val children = leafCalls.map(ReductionGraph::nodeRef)
        fun refValue(ref: ReductionNodeRef) = listOf(ref.batchKey, ref.attempt, ref.outputSha256)
        val manifest = AnalysisRunCodec.sha256(AnalysisRunCodec.json(children.map(::refValue)))
        val oldKey = "period-reduction-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(manifest, children.map { ref ->
            listOf(refValue(ref), ReductionGraph.feedbackClaims(leafCalls, ref, false).map {
                listOf(it.batchKey, it.claimIndex, it.claimSha256, 0)
            })
        })))
        val legacyInputs = oldRoot.reductionInputs.map { ReductionGraph.input(it.node, it.requestSha256, it.feedbackTokens, false) }
        for (status in listOf(CallStatus.FAILED, CallStatus.INTERRUPTED)) {
            val id = status.name
            val fresh = analysis.start(template.request, id)
            val failed = oldRoot.copy(batchKey = oldKey, status = status, reductionInputs = legacyInputs,
                reductionOutput = null, failureCode = if (status == CallStatus.FAILED) "PROVIDER_FAILED" else "INTERRUPTED")
            val payload = asV5(template.copy(calls = leafCalls + failed))
            repository.saveAnalysisRun(fresh.stored.copy(version = 2, payload = payload), 1)
            val cost = analysis.task(id).state.calls.sumOf { it.inputBytes }
            val provider = object : AnalysisProvider {
                override val id = "synthetic-upgrade"
                override val contextWindowTokens = 200_000
                var calls = 0
                override fun countInputTokens(request: ModelRequest) = 100
                override fun complete(request: ModelRequest): BatchProposal {
                    calls++
                    val inputs = JSONObject(request.payloadJson).getJSONArray("derivedInputs")
                    return BatchProposal(listOf(ProposedClaim("合成升级归并草稿", listOf(inputs.getJSONObject(0).getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)))
                }
            }
            val completed = PeriodReductionProcessor(analysis).process(id, provider)
            assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, completed.status)
            assertEquals(1, provider.calls)
            val saved = analysis.task(id).state.calls
            assertEquals(failed, saved[2])
            assertTrue(saved.last().batchKey.startsWith("period-reduction-v2-"))
            assertEquals(cost + saved.last().inputBytes, saved.sumOf { it.inputBytes })
        }
    }

    @Test fun `successful v5 reduction is reused without a paid upgrade call`() {
        tree()
        val task = analysis.task("run")
        val root = task.state.calls.last()
        val inputs = root.reductionInputs.map { ReductionGraph.input(it.node, it.requestSha256, it.feedbackTokens, false) }
        val ids = root.reductionInputs.zip(inputs).associate { (a, b) -> a.id to b.id }
        val legacy = root.copy(reductionInputs = inputs, reductionOutput = requireNotNull(root.reductionOutput).let { output ->
            output.copy(statements = output.statements.map { it.copy(inputIds = it.inputIds.map(ids::getValue)) })
        })
        repository.saveAnalysisRun(task.stored.copy(version = task.stored.version + 1,
            payload = asV5(task.state.copy(calls = task.state.calls.dropLast(1) + legacy))), task.stored.version)
        val provider = object : AnalysisProvider {
            override val id = "unused-upgrade-provider"
            override val contextWindowTokens = 200_000
            override fun countInputTokens(request: ModelRequest): Int = error("must reuse")
            override fun complete(request: ModelRequest): BatchProposal = error("must reuse")
        }
        assertEquals(ReductionProcessingStatus.TREE_INTEGRATED, PeriodReductionProcessor(analysis).process("run", provider).status)
        assertEquals(task.state.calls.size, analysis.task("run").state.calls.size)
    }

    private fun asV5(state: AnalysisRunState): String = JSONObject(AnalysisRunCodec.encode(state)).apply {
        put("formatVersion", 5)
        getJSONObject("request").remove("profileTarget")
        val calls = getJSONArray("calls")
        repeat(calls.length()) { index -> calls.getJSONObject(index).apply {
            listOf("metricEvidence", "redactedReductionIdentity", "reductionTarget", "reductionReviewTokens", "reductionScan", "reductionScans").forEach(::remove)
            val inputs = getJSONArray("reductionInputs")
            repeat(inputs.length()) { inputs.getJSONObject(it).remove("includeDerivedFeedback") }
        } }
    }.toString()

    private fun tree(nested: Boolean = false): ReductionStatementRef {
        val groups = if (nested) listOf((1..10).toList(), (11..25).toList(), (26..40).toList()) else listOf((1..20).toList(), (21..40).toList())
        val provider = object : AnalysisProvider {
            override val id = "synthetic-draft"
            override val contextWindowTokens = 200_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest): BatchProposal {
                val json = JSONObject(request.payloadJson)
                val refs = json.getJSONArray(if (json.optString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
                return BatchProposal(listOf(ProposedClaim("合成判断：某些情境下练习可能更容易开始，仍需回看。",
                    listOf(refs.getJSONObject(0).getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)), listOf("合成未知：不证明因果。"))
            }
        }
        val leaves = groups.mapIndexed { index, group ->
            analysis.synthesize("run", "leaf-$index", group.map { EvidenceSelection("record-$it", "body") }, provider)
            ReductionGraph.nodeRef(analysis.task("run").state.calls.last())
        }
        val children = if (nested) {
            analysis.reduce("run", "middle", leaves.take(2), provider)
            listOf(analysis.reductionNode("run", "middle").ref, leaves.last())
        } else leaves
        analysis.reduce("run", "root", children, provider)
        return analysis.reductionStatementRef("run", analysis.reductionNode("run", "root").ref, 0)
    }
    private fun confirm(target: ReductionStatementRef) = feedback.respond("run", ReductionGraph.feedbackRef(target),
        analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
    private fun scanAll(target: ReductionStatementRef, scanner: Scanner, prefix: String = "scan") {
        analysis.reductionReviewContext("run", target).originalEvidence.chunked(20).forEachIndexed { index, refs ->
            analysis.scanReduction("run", "$prefix-$index", target, refs.map { it.id }, scanner)
        }
    }
    private class Scanner(val classify: (String) -> ReductionRelevance) : SemanticReductionScanProvider {
        override val id = "synthetic-scanner"
        override val contextWindowTokens = 200_000
        val requests = mutableListOf<ModelRequest>()
        var before: () -> Unit = {}
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            requests += request.original
            before()
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            return ReductionScanCompletion(ReductionScanResult((0 until evidence.length()).map { index -> evidence.getJSONObject(index).let {
                ReductionFinding(it.getString("id"), classify(it.getString("recordId")), "合成相关性理由，仍可能漏判。")
            } }, listOf("合成扫描说明：未读取附件。")))
        }
    }
    private class Reviewer(private val verdict: ReviewVerdict = ReviewVerdict.SUPPORTED) : SemanticReviewProvider {
        override val id = "synthetic-final-review"
        override val contextWindowTokens = 200_000
        val requests = mutableListOf<ModelRequest>()
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests += request.original
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            val id = evidence.getJSONObject(0).getString("id")
            return ReviewCompletion(if (verdict == ReviewVerdict.SUPPORTED) ClaimReview(verdict, supportingEvidenceIds = listOf(id), unknowns = listOf("合成核对：仅有限资料下的可能解释。"))
                else ClaimReview(verdict, contradictingEvidenceIds = listOf(evidence.getJSONObject(evidence.length() - 1).getString("id"))))
        }
    }
}
