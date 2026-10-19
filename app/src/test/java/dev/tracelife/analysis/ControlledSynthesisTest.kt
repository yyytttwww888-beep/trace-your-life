package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
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
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic-only behavior checks. Providers never load credentials or access the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ControlledSynthesisTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var feedback: AnalysisFeedback
    private lateinit var knowledge: KnowledgeLibrary
    private val database = "synthetic-synthesis-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-17")
    private val scope = AnalysisQuery(date.minusDays(5), date.plusDays(1), "Asia/Shanghai")
    private val selected = listOf(EvidenceSelection("expectation", "body"), EvidenceSelection("actual", "body"))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        knowledge = KnowledgeLibrary(repository, clock)
        repository.createRecord(RecordDraft("合成期望：本周希望通过完整练习掌握方法。", Occurrence(date.minusDays(2))), "expectation")
        repository.createRecord(RecordDraft("合成实际：只阅读方法，没有进行练习；但是休息以后精神更好。", Occurrence(date)), "actual")
    }

    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `synthesis compares registered original fragments and saves a distinct recoverable interpretation`() {
        start()
        val provider = Synthesizer()
        val proposal = analysis.synthesize("run", "synthesis", selected, provider)
        val input = JSONObject(provider.requests.single().payloadJson)
        assertEquals("SYNTHESIS", input.getString("purpose"))
        assertFalse(input.has("history"))
        assertFalse(input.has("instructions"))
        assertFalse(input.getJSONObject("coverage").getBoolean("crossBatchIntegrated"))
        assertFalse(input.getJSONObject("coverage").getBoolean("metricEvidenceProvided"))
        val evidence = input.getJSONArray("evidence")
        assertEquals(2, evidence.length())
        assertTrue(evidence.getJSONObject(0).getString("text").contains("希望通过完整练习"))
        assertTrue(evidence.getJSONObject(1).getString("text").contains("没有进行练习"))
        assertEquals(ClaimKind.POSSIBLE_INTERPRETATION, proposal.claims.single().kind)
        val saved = analysis.task("run").state.calls.single()
        assertEquals(CallPurpose.SYNTHESIS, saved.purpose)
        assertEquals(CallStatus.SUCCEEDED, saved.status)
        assertEquals(2, saved.evidence.size)
        assertEquals(9, JSONObject(analysis.task("run").stored.payload).getInt("formatVersion"))
        assertEquals(0L, AnalysisBatchProcessor(analysis).coverage("run").completedTextCodePoints)
        repository.close()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        assertEquals(saved, analysis.task("run").state.calls.single())
        assertEquals(proposal, analysis.synthesize("run", "synthesis", selected, provider))
        assertEquals(1, provider.requests.size)
    }

    @Test fun `model cannot relabel a new synthesis as a reported fact`() {
        start()
        val provider = Synthesizer(kind = ClaimKind.REPORTED_EXPERIENCE)
        assertThrows(AnalysisValidationException::class.java) { analysis.synthesize("run", "synthesis", selected, provider) }
        val call = analysis.task("run").state.calls.single()
        assertEquals(CallPurpose.SYNTHESIS, call.purpose)
        assertEquals(CallStatus.FAILED, call.status)
        assertEquals("INVALID_RESPONSE", call.failureCode)
        assertNull(call.proposal)
        assertTrue(call.inputBytes > 0)
        assertThrows(IllegalArgumentException::class.java) { analysis.claimRef("run", "synthesis", 0) }
    }

    @Test fun `scope duplicate selections and input budgets reject before dispatch without dropping evidence`() {
        repository.createRecord(RecordDraft("合成范围外原文", Occurrence(date.minusDays(20))), "outside")
        start()
        val provider = Synthesizer()
        assertThrows(IllegalArgumentException::class.java) {
            analysis.synthesize("run", "outside", selected + EvidenceSelection("outside", "body"), provider)
        }
        assertThrows(IllegalArgumentException::class.java) { analysis.synthesize("run", "duplicate", selected + selected, provider) }
        assertThrows(IllegalArgumentException::class.java) { analysis.synthesize("run", "empty", emptyList(), provider) }
        assertThrows(IllegalArgumentException::class.java) {
            analysis.synthesize("run", "too-many", List(33) { EvidenceSelection("expectation", "body", it) }, provider)
        }
        start(id = "small", limits = AnalysisLimits(maxInputBytes = 256))
        assertThrows(AnalysisBudgetException::class.java) { analysis.synthesize("small", "budget", selected, provider) }
        assertTrue(provider.requests.isEmpty())
        assertTrue(analysis.task("run").state.calls.isEmpty())
        assertTrue(analysis.task("small").state.calls.isEmpty())
    }

    @Test fun `prepared wire overhead and task-wide budgets include synthesis`() {
        start(limits = AnalysisLimits(maxCalls = 1))
        val expanded = object : AnalysisProvider by Synthesizer() {
            override fun prepare(request: ModelRequest) = PreparedModelRequest(request, "x".repeat(65_537), 100)
        }
        assertThrows(AnalysisBudgetException::class.java) { analysis.synthesize("run", "too-large", selected, expanded) }
        val provider = Synthesizer()
        analysis.synthesize("run", "synthesis", selected, provider)
        assertThrows(AnalysisBudgetException::class.java) { analysis.synthesize("run", "another", selected, provider) }
        assertEquals(1, provider.requests.size)
        assertEquals(1, analysis.task("run").state.calls.size)
    }

    @Test fun `review retains uncited synthesis countercontext and codec rejects its removal`() {
        start()
        val provider = Synthesizer(citeAll = false)
        analysis.synthesize("run", "synthesis", selected, provider)
        val ref = analysis.claimRef("run", "synthesis", 0)
        val reviewer = Reviewer()
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        val supplied = JSONObject(reviewer.requests.single().payloadJson).getJSONArray("evidence")
        assertEquals(2, supplied.length())
        assertTrue(supplied.getJSONObject(1).getString("text").contains("没有进行练习"))
        val state = analysis.task("run").state
        val check = state.calls.last()
        val omitted = check.copy(evidence = check.evidence.take(1))
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.encode(state.copy(calls = state.calls.dropLast(1) + omitted))
        }
        assertEquals(0L, AnalysisBatchProcessor(analysis).coverage("run").completedTextCodePoints)
    }

    @Test fun `synthesis requires user confirmation then fresh semantic review before publication`() {
        start()
        analysis.synthesize("run", "synthesis", selected, Synthesizer())
        val ref = analysis.claimRef("run", "synthesis", 0)
        val reviewer = Reviewer()
        analysis.reviewClaim("run", "before-confirmation", ref, provider = reviewer)
        assertThrows(IllegalArgumentException::class.java) {
            knowledge.publish("run", ref, "before-confirmation", KnowledgeCategory.INSIGHT, analysis.task("run").stored.version)
        }
        feedback.respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED,
            "合成用户意见：这只是待检验的解释。")
        assertNull(analysis.currentReview("run", ref))
        assertThrows(IllegalArgumentException::class.java) {
            knowledge.publish("run", ref, "before-confirmation", KnowledgeCategory.INSIGHT, analysis.task("run").stored.version)
        }
        analysis.reviewClaim("run", "after-confirmation", ref, provider = reviewer)
        val saved = knowledge.publish("run", ref, "after-confirmation", KnowledgeCategory.INSIGHT, analysis.task("run").stored.version)
        assertEquals(ClaimKind.POSSIBLE_INTERPRETATION, saved.claim.kind)
        assertEquals(2, saved.evidence.size)
        assertEquals(1, saved.feedbackVersion)
        assertEquals(1, knowledge.index().total)
    }

    @Test fun `automatic organizer waits for synthesis confirmation and then reviews all original context`() {
        start()
        analysis.synthesize("run", "synthesis", selected, Synthesizer())
        val organizer = AutomaticOrganizer(repository, analysis, AnalysisBatchProcessor(analysis), knowledge)
        assertEquals(ClaimDisposition.AWAITING_CONFIRMATION, organizer.inspect("run").claims.single().disposition)
        val ref = analysis.claimRef("run", "synthesis", 0)
        feedback.respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        val extractor = Synthesizer()
        val reviewer = Reviewer()
        val result = organizer.process("run", extractor, reviewer, maxModelRequests = 1)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(1, result.publishedThisPass)
        assertTrue(extractor.requests.isEmpty())
        assertEquals(2, JSONObject(reviewer.requests.single().payloadJson).getJSONArray("evidence").length())
        assertEquals(ClaimDisposition.PUBLISHED, organizer.inspect("run").claims.single().disposition)
    }

    @Test fun `codec reads legacy formats but synthesis cannot be smuggled into an older envelope`() {
        start()
        analysis.runBatch("run", "extract", selected, Synthesizer(kind = ClaimKind.REPORTED_EXPERIENCE))
        val current = analysis.task("run").stored.payload
        for (version in 1..4) {
            val legacy = legacy(current, version)
            val decoded = AnalysisRunCodec.decode(legacy)
            assertEquals(CallPurpose.EXTRACTION, decoded.calls.single().purpose)
            assertEquals(9, JSONObject(AnalysisRunCodec.encode(decoded)).getInt("formatVersion"))
            AnalysisRunCodec.validateStored(analysis.task("run").stored.copy(payload = legacy))
        }
        analysis.synthesize("run", "synthesis", selected, Synthesizer())
        val state = analysis.task("run").state
        val payload = analysis.task("run").stored.payload
        assertEquals(state, AnalysisRunCodec.decode(payload))
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.decode(JSONObject(payload).put("formatVersion", 3).toString())
        }
        val call = state.calls.last()
        val invalid = call.copy(proposal = BatchProposal(listOf(call.proposal!!.claims.single().copy(kind = ClaimKind.REPORTED_EXPERIENCE))))
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(state.copy(calls = state.calls.dropLast(1) + invalid)) }
    }

    @Test fun `legacy v3 task restores as historical state without being rewritten or made current`() {
        start()
        analysis.runBatch("run", "extract", selected, Synthesizer(kind = ClaimKind.REPORTED_EXPERIENCE))
        val task = analysis.task("run").stored
        val oldPayload = legacy(task.payload, 3)
        repository.saveAnalysisRun(task.copy(version = task.version + 1, payload = oldPayload), task.version)
        val workspace = File(context.cacheDir, "synthetic-synthesis-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val attachments = File(workspace, "attachments").apply { mkdir() }
        val targetName = "synthetic-synthesis-target-${UUID.randomUUID()}.db"
        val target = LifeRepository(context, clock, targetName)
        try {
            val snapshot = repository.captureSnapshot(attachments)
            target.restoreSnapshot(snapshot, attachments, target.currentGeneration())
            val restored = ControlledAnalysis(target, clock).task("run")
            assertEquals(AnalysisStatus.STALE, restored.status)
            assertEquals(oldPayload, restored.stored.payload)
            assertEquals(CallPurpose.EXTRACTION, restored.state.calls.single().purpose)
        } finally { target.close(); context.deleteDatabase(targetName); workspace.deleteRecursively() }
    }

    @Test fun `source edit during synthesis prevents the old interpretation from being committed`() {
        start()
        val provider = Synthesizer()
        provider.before = {
            val old = repository.record("actual")!!
            repository.updateRecord(old.id, old.revision, old.draft.copy(body = "合成修订：实际练习已经发生。"))
        }
        assertThrows(StaleSnapshotException::class.java) { analysis.synthesize("run", "synthesis", selected, provider) }
        assertEquals(AnalysisStatus.STALE, analysis.task("run").status)
        assertNull(analysis.task("run").state.calls.single().proposal)
        assertEquals(0, knowledge.index().total)
    }

    @Test fun `deleting feedback redacts its synthesis review and prevents automatic republishing`() {
        start()
        analysis.synthesize("run", "synthesis", selected, Synthesizer())
        val ref = analysis.claimRef("run", "synthesis", 0)
        val accepted = feedback.respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED,
            "合成独立反馈文字")
        analysis.reviewClaim("run", "review", ref, provider = Reviewer())
        val saved = knowledge.publish("run", ref, "review", KnowledgeCategory.INSIGHT, analysis.task("run").stored.version)
        feedback.delete(accepted.feedback.id, accepted.feedback.version)
        assertNull(knowledge.get(saved.id))
        val calls = analysis.task("run").state.calls
        assertEquals(CallPurpose.SYNTHESIS, calls.first().purpose)
        assertNotNull(calls.first().proposal)
        assertEquals("FEEDBACK_DELETED", calls.last().failureCode)
        assertNull(calls.last().review)
        val organizer = AutomaticOrganizer(repository, analysis, AnalysisBatchProcessor(analysis), knowledge)
        assertEquals(ClaimDisposition.FEEDBACK_DELETED, organizer.inspect("run").claims.single().disposition)
    }

    private fun start(id: String = "run", limits: AnalysisLimits = AnalysisLimits()) =
        analysis.start(AnalysisRequest("合成周期问题：期望与实际有何差距，可以试验什么？", scope, limits), id)

    private fun legacy(payload: String, version: Int): String = JSONObject(payload).apply {
        put("formatVersion", version)
        withoutProfileMaintenance()
        getJSONObject("request").remove("profileTarget")
        val calls = getJSONArray("calls")
        repeat(calls.length()) { index ->
            val call = calls.getJSONObject(index)
            listOf("metricEvidence", "reductionInputs", "reductionOutput", "redactedReductionIdentity", "reductionTarget", "reductionReviewTokens", "reductionScan", "reductionScans").forEach(call::remove)
            if (version < 3) listOf("purpose", "reviewTarget", "review", "feedbackVersion").forEach(call::remove)
            if (version < 2) listOf("tokenCountKind", "usage").forEach(call::remove)
        }
    }.toString()

    private class Synthesizer(private val kind: ClaimKind = ClaimKind.POSSIBLE_INTERPRETATION,
        private val citeAll: Boolean = true) : AnalysisProvider {
        override val id = "synthetic-synthesizer"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        var before: () -> Unit = {}
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            before()
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
            val ids = (0 until if (citeAll) evidence.length() else 1).map { evidence.getJSONObject(it).getString("id") }
            return BatchProposal(listOf(ProposedClaim("合成判断：就所给期望与实际片段，阅读到练习之间可能缺少明确触发条件；可尝试阅读后立即做一道例题，并在下次记录回看。", ids, kind)),
                listOf("合成局限：仅检查本次片段，不代表全周期或因果已确认。"))
        }
    }

    private class Reviewer : SemanticReviewProvider {
        override val id = "synthetic-reviewer"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests += request.original
            val id = JSONObject(request.original.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(id), unknowns = listOf("合成核对：解释仍待实际行动验证。")))
        }
    }
}
