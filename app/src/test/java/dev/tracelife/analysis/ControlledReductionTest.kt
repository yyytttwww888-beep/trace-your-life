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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic records and deterministic providers; no credentials or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ControlledReductionTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var feedback: AnalysisFeedback
    private val database = "synthetic-reduction-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-19")
    private val scope = AnalysisQuery(date.minusDays(6), date, "Asia/Shanghai")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        (1..4).forEach { repository.createRecord(RecordDraft("合成原文🧪 $it：尝试练习，结果尚不确定。", Occurrence(date)), "record-$it") }
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `derived drafts keep all parent batches unknowns and context without pretending to read original evidence`() {
        start()
        val first = leaf("first", listOf(1, 2))
        val second = leaf("second", listOf(3), empty = true)
        val accepted = respond("first", ClaimFeedbackDecision.CONFIRMED, "PRIVATE_SYNTHETIC_FEEDBACK")
        assertNotNull(accepted)
        val provider = Provider()
        val output = analysis.reduce("run", "root", listOf(first, second), provider)
        val body = JSONObject(provider.requests.single().payloadJson)
        assertEquals("REDUCTION", body.getString("purpose"))
        assertFalse(body.has("evidence"))
        assertFalse(body.has("history"))
        assertFalse(body.toString().contains("PRIVATE_SYNTHETIC_FEEDBACK"))
        assertFalse(body.toString().contains("合成原文"))
        val inputs = body.getJSONArray("derivedInputs")
        assertEquals(2, inputs.length())
        assertEquals(0, inputs.getJSONObject(1).getJSONObject("content").getJSONArray("claims").length())
        assertTrue(inputs.getJSONObject(1).getJSONObject("content").getJSONArray("unknowns").length() > 0)
        assertEquals(2, inputs.getJSONObject(0).getJSONObject("provenance").getInt("originalFragments"))
        assertFalse(inputs.getJSONObject(0).has("feedbackTokens"))
        assertFalse(inputs.getJSONObject(0).has("originalEvidence"))
        val call = analysis.task("run").state.calls.last()
        assertEquals(CallPurpose.REDUCTION, call.purpose)
        assertNull(call.proposal)
        assertTrue(call.evidence.isEmpty())
        assertEquals(output, call.reductionOutput)
        assertEquals(5, JSONObject(analysis.task("run").stored.payload).getInt("formatVersion"))
        assertEquals(listOf(first, second), analysis.reductionNode("run", "root").leafRefs)
        assertEquals(3, analysis.reductionNode("run", "root").originalEvidence.size)
        assertThrows(IllegalArgumentException::class.java) { analysis.claimRef("run", "root", 0) }
        assertEquals(output, analysis.reduce("run", "root", listOf(first, second), provider))
        assertEquals(1, provider.requests.size)
    }

    @Test fun `higher layers preserve raw lineage even when a digest cites only one parent`() {
        start()
        val a = leaf("a", listOf(1, 2))
        val b = leaf("b", listOf(3), empty = true)
        val c = leaf("c", listOf(4))
        analysis.reduce("run", "middle", listOf(a, b), Provider(citeOne = true))
        val middle = analysis.reductionNode("run", "middle").ref
        val provider = Provider(citeOne = true)
        analysis.reduce("run", "root", listOf(middle, c), provider)
        val root = analysis.reductionNode("run", "root")
        assertEquals(listOf(a, b, c), root.leafRefs)
        assertEquals(4, root.originalEvidence.size)
        assertEquals(2, root.inputs.size)
        assertEquals(2, root.inputs.flatMap { it.feedbackTokens }.size)
        val material = JSONObject(provider.requests.single().payloadJson).getJSONArray("derivedInputs").getJSONObject(0)
        assertTrue(material.getJSONObject("content").has("statements"))
        assertEquals(2, material.getJSONObject("provenance").getInt("leafBatches"))
        assertFalse(material.has("originalEvidence"))
        assertFalse(material.has("feedbackTokens"))
    }

    @Test fun `duplicates ancestors extraction and modified node identities reject before dispatch`() {
        start()
        val a = leaf("a", listOf(1))
        val b = leaf("b", listOf(2))
        val provider = Provider()
        assertThrows(IllegalArgumentException::class.java) { analysis.reduce("run", "same", listOf(a, a), provider) }
        assertThrows(IllegalArgumentException::class.java) { analysis.reduce("run", "changed", listOf(a.copy(outputSha256 = "0".repeat(64)), b), provider) }
        analysis.reduce("run", "middle", listOf(a, b), provider)
        val middle = analysis.reductionNode("run", "middle").ref
        assertThrows(IllegalArgumentException::class.java) { analysis.reduce("run", "overlap", listOf(middle, a), provider) }
        analysis.runBatch("run", "extract", listOf(EvidenceSelection("record-3", "body")), Provider())
        val extraction = analysis.task("run").state.calls.last()
        assertThrows(IllegalArgumentException::class.java) { ReductionGraph.nodeRef(extraction) }
        assertEquals(1, provider.requests.size)
        assertEquals(4, analysis.task("run").state.calls.size)
    }

    @Test fun `original citation IDs and reported facts cannot become derived statements`() {
        start()
        val a = leaf("a", listOf(1))
        val b = leaf("b", listOf(2))
        val original = analysis.task("run").state.calls.first().evidence.first().id
        assertThrows(AnalysisValidationException::class.java) { analysis.reduce("run", "raw-citation", listOf(a, b), Provider(forcedId = original)) }
        assertThrows(AnalysisValidationException::class.java) { analysis.reduce("run", "reported", listOf(a, b), Provider(kind = ClaimKind.REPORTED_EXPERIENCE)) }
        analysis.task("run").state.calls.takeLast(2).forEach {
            assertEquals("INVALID_RESPONSE", it.failureCode)
            assertNull(it.reductionOutput)
            assertNull(it.proposal)
            assertTrue(it.inputBytes > 0)
        }
    }

    @Test fun `full prepared wire and cumulative budgets reject without consuming an attempt`() {
        start(limits = AnalysisLimits(maxCalls = 3))
        val a = leaf("a", listOf(1))
        val b = leaf("b", listOf(2))
        val provider = Provider()
        val expanded = object : AnalysisProvider by provider {
            override fun prepare(request: ModelRequest) = PreparedModelRequest(request, "x".repeat(65_537), 1)
        }
        assertThrows(AnalysisBudgetException::class.java) { analysis.reduce("run", "expanded", listOf(a, b), expanded) }
        assertEquals(2, analysis.task("run").state.calls.size)
        analysis.reduce("run", "root", listOf(a, b), provider)
        assertThrows(AnalysisBudgetException::class.java) { analysis.reduce("run", "extra", listOf(a, b), provider) }
        assertEquals(1, provider.requests.size)
        assertEquals(3, analysis.task("run").state.calls.size)
    }

    @Test fun `provider failures consume budget and identical retry recovers without duplicate successful dispatch`() {
        start()
        val children = listOf(leaf("a", listOf(1)), leaf("b", listOf(2)))
        val provider = Provider()
        provider.before = { throw AnalysisProviderException(ProviderFailureCode.TIMEOUT) }
        assertThrows(AnalysisValidationException::class.java) { analysis.reduce("run", "root", children, provider) }
        val failed = analysis.task("run").state.calls.last()
        assertEquals("PROVIDER_TIMEOUT", failed.failureCode)
        provider.before = {}
        val output = analysis.reduce("run", "root", children, provider)
        assertEquals(output, analysis.reduce("run", "root", children, provider))
        assertEquals(2, provider.requests.size)
        assertEquals(2, analysis.task("run").state.calls.last().attempt)
        assertEquals(failed.inputBytes, analysis.task("run").state.calls.last().inputBytes)
    }

    @Test fun `new confirmations stale existing descendants but allow rebuilding with new dependency keys`() {
        start()
        val children = listOf(leaf("a", listOf(1)), leaf("b", listOf(2)))
        val provider = Provider()
        analysis.reduce("run", "root-v0", children, provider)
        val old = analysis.reductionNode("run", "root-v0").ref
        respond("a", ClaimFeedbackDecision.CONFIRMED)
        assertThrows(AnalysisConflictException::class.java) { analysis.reductionDependencies("run", old) }
        assertThrows(AnalysisConflictException::class.java) { analysis.reductionNode("run", "root-v0") }
        assertThrows(AnalysisConflictException::class.java) { analysis.reduce("run", "root-v0", children, provider) }
        analysis.reduce("run", "root-v1", children, provider)
        assertEquals(1, analysis.reductionNode("run", "root-v1").inputs.first().feedbackTokens.single().version)
        assertEquals(2, provider.requests.size)
    }

    @Test fun `rejected corrected withdrawn and deleted leaf feedback block current access and dispatch`() {
        listOf(ClaimFeedbackDecision.REJECTED, ClaimFeedbackDecision.CORRECTED, ClaimFeedbackDecision.WITHDRAWN).forEach { decision ->
            val run = decision.name
            start(id = run)
            val a = leaf("a", listOf(1), run = run)
            val b = leaf("b", listOf(2), run = run)
            val provider = Provider()
            analysis.reduce(run, "root", listOf(a, b), provider)
            respond("a", decision, "合成独立纠正文", run)
            assertThrows(AnalysisDependencyBlockedException::class.java) { analysis.reductionNode(run, "root") }
            assertThrows(AnalysisDependencyBlockedException::class.java) { analysis.reduce(run, "new", listOf(a, b), provider) }
            assertEquals(1, provider.requests.size)
        }
        start()
        val children = listOf(leaf("a", listOf(1)), leaf("b", listOf(2)))
        val accepted = respond("a", ClaimFeedbackDecision.CONFIRMED)
        feedback.delete(accepted.feedback.id, accepted.feedback.version)
        assertThrows(AnalysisDependencyBlockedException::class.java) { analysis.reduce("run", "deleted", children, Provider()) }
    }

    @Test fun `feedback changes during prepare or dispatch cannot publish a successful reduction`() {
        start()
        val children = listOf(leaf("a", listOf(1)), leaf("b", listOf(2)))
        val prepareProvider = object : AnalysisProvider by Provider() {
            override fun prepare(request: ModelRequest): PreparedModelRequest {
                respond("a", ClaimFeedbackDecision.CONFIRMED)
                return PreparedModelRequest(request, request.payloadJson, 100)
            }
        }
        assertThrows(AnalysisConflictException::class.java) { analysis.reduce("run", "prepare", children, prepareProvider) }
        assertEquals(2, analysis.task("run").state.calls.size)
        val provider = Provider()
        provider.before = { respond("b", ClaimFeedbackDecision.REJECTED) }
        assertThrows(AnalysisDependencyBlockedException::class.java) { analysis.reduce("run", "dispatch", children, provider) }
        val pending = analysis.task("run").state.calls.last()
        assertEquals(CallStatus.IN_FLIGHT, pending.status)
        assertNull(pending.reductionOutput)
        assertTrue(pending.inputBytes > 0)
    }

    @Test fun `source changes and expiry reject current drafts`() {
        start(expiresAt = now.plusSeconds(60))
        val children = listOf(leaf("a", listOf(1)), leaf("b", listOf(2)))
        analysis.reduce("run", "root", children, Provider())
        val later = ControlledAnalysis(repository, Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC))
        assertThrows(StaleSnapshotException::class.java) { later.reductionNode("run", "root") }
        val record = repository.record("record-1")!!
        repository.updateRecord(record.id, record.revision, record.draft.copy(body = "合成新修订"))
        assertThrows(StaleSnapshotException::class.java) { analysis.reductionNode("run", "root") }
    }

    @Test fun `codec rejects future edges forged hashes missing feedback tokens and old-version reduction`() {
        start()
        val children = listOf(leaf("a", listOf(1)), leaf("b", listOf(2)))
        analysis.reduce("run", "root", children, Provider())
        val state = analysis.task("run").state
        val root = state.calls.last()
        assertEquals(state, AnalysisRunCodec.decode(AnalysisRunCodec.encode(state)))
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(state.copy(calls = listOf(root) + state.calls.dropLast(1))) }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(state.copy(calls = state.calls.dropLast(1) + root.copy(
            reductionInputs = root.reductionInputs.map { it.copy(requestSha256 = "0".repeat(64)) }))) }
        val input = root.reductionInputs.first()
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.encode(state.copy(calls = state.calls.dropLast(1) + root.copy(
            reductionInputs = listOf(ReductionGraph.input(input.node, input.requestSha256, emptyList())) + root.reductionInputs.drop(1)))) }
        val old = JSONObject(AnalysisRunCodec.encode(state)).apply {
            put("formatVersion", 4)
            val calls = getJSONArray("calls")
            repeat(calls.length()) { calls.getJSONObject(it).apply { remove("reductionInputs"); remove("reductionOutput") } }
        }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(old.toString()) }
        val oldLeaves = JSONObject(AnalysisRunCodec.encode(state.copy(calls = state.calls.dropLast(1)))).apply {
            put("formatVersion", 4)
            val calls = getJSONArray("calls")
            repeat(calls.length()) { calls.getJSONObject(it).apply { remove("reductionInputs"); remove("reductionOutput") } }
        }
        assertEquals(state.calls.dropLast(1), AnalysisRunCodec.decode(oldLeaves.toString()).calls)
    }

    private fun start(id: String = "run", limits: AnalysisLimits = AnalysisLimits(), expiresAt: Instant? = null) =
        analysis.start(AnalysisRequest("合成分层周期复盘", scope, limits, expiresAt = expiresAt), id)

    private fun leaf(key: String, records: List<Int>, empty: Boolean = false, run: String = "run"): ReductionNodeRef {
        analysis.synthesize(run, key, records.map { EvidenceSelection("record-$it", "body") }, Provider(empty = empty, citeOne = true))
        return ReductionGraph.nodeRef(analysis.task(run).state.calls.last())
    }
    private fun respond(batch: String, decision: ClaimFeedbackDecision, text: String? = null, run: String = "run") =
        feedback.respond(run, analysis.claimRef(run, batch, 0), analysis.task(run).stored.version, decision, text)

    private class Provider(private val empty: Boolean = false, private val citeOne: Boolean = false,
        private val forcedId: String? = null, private val kind: ClaimKind = ClaimKind.POSSIBLE_INTERPRETATION) : AnalysisProvider {
        override val id = "synthetic-reduction"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        var before: () -> Unit = {}
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            before()
            val body = JSONObject(request.payloadJson)
            val input = body.getJSONArray(if (body.optString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            val ids = forcedId?.let(::listOf) ?: (0 until if (citeOne) 1 else input.length()).map { input.getJSONObject(it).getString("id") }
            return BatchProposal(if (empty) emptyList() else listOf(ProposedClaim("合成暂定解释：目标到实践之间可能存在触发条件的差距。", ids, kind)),
                listOf("合成未知：尚需回读原文和保留反例。"))
        }
    }
}
