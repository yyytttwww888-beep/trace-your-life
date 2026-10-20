package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.metrics.RecordMetricProtocol
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
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

/** Synthetic-only evidence boundary tests: providers never access credentials or the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ControlledMetricAnalysisTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private val database = "synthetic-metric-analysis-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-12")
    private val scope = AnalysisQuery(day, day.plusDays(6), "Asia/Shanghai")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        repository.createRecord(RecordDraft("合成原文：此内容不应提供给指标模型。", Occurrence(day)), "known")
        repository.createRecord(RecordDraft("合成未知时间的内容。"), "unknown")
        repository.createRecord(RecordDraft("合成跨期内容。", Occurrence(day.minusDays(1), day.plusDays(1))), "overlap")
    }

    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `full program metric is ledgered but model receives no source manifest or text`() {
        start()
        val provider = MetricProvider()
        val proposal = analysis.analyzeMetrics("run", "metric", provider)
        val payload = JSONObject(provider.requests.single().payloadJson)
        assertEquals("METRIC_ANALYSIS", payload.getString("purpose"))
        assertEquals(0, payload.getJSONArray("evidence").length())
        val metric = payload.getJSONArray("metricEvidence").getJSONObject(0)
        assertFalse(metric.has("sources"))
        assertEquals(3, metric.getInt("sourceCount"))
        assertEquals(64, metric.getString("sourceManifestSha256").length)
        assertEquals(3, metric.getJSONObject("values").getInt("totalRecords"))
        assertEquals(1, metric.getJSONObject("values").getInt("recordedDays"))
        assertFalse(provider.requests.single().payloadJson.contains("合成原文"))
        assertFalse(payload.getJSONObject("coverage").getBoolean("originalEvidenceReadInThisCall"))
        val call = analysis.task("run").state.calls.single()
        assertEquals(CallPurpose.METRIC_ANALYSIS, call.purpose)
        assertTrue(call.evidence.isEmpty())
        assertEquals(listOf("known", "overlap", "unknown"), call.metricEvidence.single().sources.map { it.recordId })
        assertEquals(listOf(call.metricEvidence.single().id), proposal.claims.single().evidenceIds)
        assertEquals(0L, AnalysisBatchProcessor(analysis).coverage("run").completedTextCodePoints)
        assertEquals(12, JSONObject(analysis.task("run").stored.payload).getInt("formatVersion"))
    }

    @Test fun `empty period still has typed zero coverage and no fictitious raw evidence`() {
        start(query = scope.copy(recordIds = setOf("missing")))
        val provider = MetricProvider()
        analysis.analyzeMetrics("run", "metric", provider)
        val receipt = analysis.task("run").state.calls.single().metricEvidence.single()
        assertTrue(receipt.sources.isEmpty())
        assertEquals(0, receipt.values.totalRecords)
        assertEquals(0, receipt.values.recordedDays)
        assertEquals(setOf("missing"), receipt.values.missingExactIds)
        val ref = analysis.claimRef("run", "metric", 0)
        analysis.reviewClaim("run", "review", ref, provider = MetricReviewer())
        assertEquals(receipt, analysis.currentReview("run", ref)!!.metricEvidence.single())
    }

    @Test fun `retries are stable across time and restart and successful calls dispatch once`() {
        start()
        val provider = MetricProvider()
        val first = analysis.analyzeMetrics("run", "metric", provider)
        val saved = analysis.task("run").state.calls.single()
        repository.close()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, Clock.offset(clock, java.time.Duration.ofDays(1)))
        assertEquals(saved, analysis.task("run").state.calls.single())
        assertEquals(first, analysis.analyzeMetrics("run", "metric", provider))
        assertEquals(1, provider.requests.size)
    }

    @Test fun `failed attempt retains budget and retry must retain provider and exact metric request`() {
        start()
        val failure = object : AnalysisProvider by MetricProvider() {
            override fun complete(request: ModelRequest): BatchProposal = error("synthetic offline failure")
            override fun completePrepared(request: PreparedModelRequest): ModelCompletion = ModelCompletion(complete(request.original))
        }
        assertThrows(AnalysisValidationException::class.java) { analysis.analyzeMetrics("run", "metric", failure) }
        assertEquals(CallStatus.FAILED, analysis.task("run").state.calls.single().status)
        assertThrows(AnalysisConflictException::class.java) {
            analysis.analyzeMetrics("run", "metric", object : AnalysisProvider by MetricProvider() { override val id = "other-model" })
        }
        val provider = MetricProvider()
        analysis.analyzeMetrics("run", "metric", provider)
        val calls = analysis.task("run").state.calls
        assertEquals(listOf(1, 2), calls.map { it.attempt })
        assertEquals(1, calls.map { it.requestSha256 }.distinct().size)
        assertEquals(calls[0].metricEvidence, calls[1].metricEvidence)
    }

    @Test fun `interrupted metric calls require explicit recovery and preserve their receipt on retry`() {
        start()
        val stopped = MetricProvider(beforeReturn = { throw AssertionError("synthetic stopped worker") })
        assertThrows(AssertionError::class.java) { analysis.analyzeMetrics("run", "metric", stopped) }
        val pending = analysis.task("run").state.calls.single()
        assertEquals(CallStatus.IN_FLIGHT, pending.status)
        val provider = MetricProvider()
        assertThrows(AnalysisConflictException::class.java) { analysis.analyzeMetrics("run", "metric", provider) }
        assertTrue(provider.requests.isEmpty())
        analysis.recoverInterrupted("run")
        analysis.analyzeMetrics("run", "metric", provider)
        val calls = analysis.task("run").state.calls
        assertEquals(CallStatus.INTERRUPTED, calls.first().status)
        assertEquals(pending.metricEvidence, calls.last().metricEvidence)
        assertEquals(1, provider.requests.size)
    }

    @Test fun `unprovided metric or raw citation and reported fact label fail with ledger accounting`() {
        for ((index, provider) in listOf(
            MetricProvider(citation = "metric-v1-" + "a".repeat(64)),
            MetricProvider(citation = "a".repeat(64)),
            MetricProvider(kind = ClaimKind.REPORTED_EXPERIENCE),
        ).withIndex()) {
            val id = "run-$index"
            start(id)
            assertThrows(AnalysisValidationException::class.java) { analysis.analyzeMetrics(id, "metric", provider) }
            val call = analysis.task(id).state.calls.single()
            assertEquals("INVALID_RESPONSE", call.failureCode)
            assertTrue(call.inputBytes > 0)
            assertNull(call.proposal)
        }
    }

    @Test fun `metric review reuses a recomputed complete proof without accepting raw additions`() {
        start()
        analysis.analyzeMetrics("run", "metric", MetricProvider())
        val ref = analysis.claimRef("run", "metric", 0)
        val reviewer = MetricReviewer()
        assertThrows(IllegalArgumentException::class.java) {
            analysis.reviewClaim("run", "mixed", ref, listOf(EvidenceSelection("known", "body")), reviewer)
        }
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        val payload = JSONObject(reviewer.requests.single().payloadJson)
        assertEquals("RECORD_METRIC", payload.getString("evidenceType"))
        assertTrue(payload.getJSONObject("coverage").getBoolean("metricRecomputed"))
        assertEquals(0, payload.getJSONArray("evidence").length())
        val calls = analysis.task("run").state.calls
        assertEquals(calls.first().metricEvidence, calls.last().metricEvidence)
        assertEquals(ReviewVerdict.SUPPORTED, calls.last().review!!.verdict)
        analysis.reviewClaim("run", "review", ref, provider = reviewer)
        assertEquals(1, reviewer.requests.size)
    }

    @Test fun `source change and expiry block stale metric calculation and review before dispatch`() {
        start()
        val provider = MetricProvider()
        analysis.analyzeMetrics("run", "metric", provider)
        val ref = analysis.claimRef("run", "metric", 0)
        repository.createRecord(RecordDraft("合成后补记录", Occurrence(day)), "new")
        assertThrows(StaleSnapshotException::class.java) { analysis.analyzeMetrics("run", "next", provider) }
        val reviewer = MetricReviewer()
        assertThrows(StaleSnapshotException::class.java) { analysis.reviewClaim("run", "review", ref, provider = reviewer) }
        assertTrue(reviewer.requests.isEmpty())
        assertEquals(1, provider.requests.size)
        analysis.start(AnalysisRequest("合成过期任务", scope, expiresAt = clock.instant().plusSeconds(1)), "expiring")
        analysis = ControlledAnalysis(repository, Clock.offset(clock, java.time.Duration.ofSeconds(2)))
        assertThrows(StaleSnapshotException::class.java) { analysis.analyzeMetrics("expiring", "metric", provider) }
    }

    @Test fun `source changes during prepare or dispatch cannot save a successful metric result`() {
        start("preparing")
        val preparing = object : AnalysisProvider by MetricProvider() {
            override fun prepare(request: ModelRequest): PreparedModelRequest {
                repository.createRecord(RecordDraft("合成派发前变化", Occurrence(day)), "before-dispatch")
                return PreparedModelRequest(request, request.payloadJson, 100)
            }
        }
        assertThrows(StaleSnapshotException::class.java) { analysis.analyzeMetrics("preparing", "metric", preparing) }
        assertTrue(analysis.task("preparing").state.calls.isEmpty())
        start("dispatching")
        val dispatching = MetricProvider(beforeReturn = {
            repository.createRecord(RecordDraft("合成派发中变化", Occurrence(day)), "during-dispatch")
        })
        assertThrows(StaleSnapshotException::class.java) { analysis.analyzeMetrics("dispatching", "metric", dispatching) }
        assertEquals(CallStatus.IN_FLIGHT, analysis.task("dispatching").state.calls.single().status)
    }

    @Test fun `feedback change invalidates old review and refuses a late review result`() {
        start()
        analysis.analyzeMetrics("run", "metric", MetricProvider())
        val ref = analysis.claimRef("run", "metric", 0)
        analysis.reviewClaim("run", "first", ref, provider = MetricReviewer())
        val feedback = AnalysisFeedback(repository, clock)
        val confirmed = feedback.respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        assertNull(analysis.currentReview("run", ref))
        val reviewer = MetricReviewer(beforeReturn = {
            feedback.update(confirmed.feedback.id, confirmed.feedback.version, analysis.task("run").stored.version,
                ClaimFeedbackDecision.REJECTED, "合成用户意见")
        })
        assertThrows(RevisionConflictException::class.java) { analysis.reviewClaim("run", "racing", ref, provider = reviewer) }
        assertNull(analysis.currentReview("run", ref))
    }

    @Test fun `prepared wire input and shared cumulative budgets reject without truncating metrics`() {
        start("small", limits = AnalysisLimits(maxInputBytes = 256))
        val provider = MetricProvider()
        assertThrows(AnalysisBudgetException::class.java) { analysis.analyzeMetrics("small", "metric", provider) }
        assertTrue(provider.requests.isEmpty())
        start("expanded")
        val expanded = object : AnalysisProvider by provider {
            override fun prepare(request: ModelRequest) = PreparedModelRequest(request, "x".repeat(65_537), 100)
        }
        assertThrows(AnalysisBudgetException::class.java) { analysis.analyzeMetrics("expanded", "metric", expanded) }
        assertTrue(analysis.task("expanded").state.calls.isEmpty())
        start("limited", limits = AnalysisLimits(maxCalls = 1))
        analysis.analyzeMetrics("limited", "metric", provider)
        val ref = analysis.claimRef("limited", "metric", 0)
        assertThrows(AnalysisBudgetException::class.java) { analysis.reviewClaim("limited", "review", ref, provider = MetricReviewer()) }
        assertEquals(1, analysis.task("limited").state.calls.size)
    }

    @Test fun `metric source scope values and review proof cannot be swapped or omitted in persisted calls`() {
        start()
        analysis.analyzeMetrics("run", "metric", MetricProvider())
        val ref = analysis.claimRef("run", "metric", 0)
        analysis.reviewClaim("run", "review", ref, provider = MetricReviewer())
        val task = analysis.task("run")
        val metric = task.state.calls.first().metricEvidence.single()
        val forged = metric.copy(values = metric.values.copy(totalRecords = 9))
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.encode(task.state.copy(calls = listOf(task.state.calls.first().copy(metricEvidence = listOf(forged)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.encode(task.state.copy(calls = task.state.calls.dropLast(1) + task.state.calls.last().copy(metricEvidence = emptyList())))
        }
        val narrower = RecordMetricProtocol.calculate(repository, task.stored.source, scope.copy(recordIds = setOf("known")))
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.encode(task.state.copy(calls = listOf(task.state.calls.first().copy(metricEvidence = listOf(narrower)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.validateStored(task.stored.copy(source = task.stored.source.copy(generation = task.stored.source.generation + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.validateStored(task.stored, emptyList()) }
    }

    @Test fun `full metric ledger capacity is reserved before any new request can leave`() {
        start(query = scope.copy(through = day.plusDays(365)), limits = AnalysisLimits(totalInputBytes = 16_777_216))
        analysis.analyzeMetrics("run", "seed", MetricProvider())
        val task = analysis.task("run")
        val seed = task.state.calls.single().copy(proposal = BatchProposal(emptyList()))
        var largest = task.state.copy(calls = listOf(seed))
        for (size in 2..100) {
            val candidate = task.state.copy(calls = List(size) { seed.copy(batchKey = "historical-$it") })
            try { AnalysisRunCodec.encode(candidate); largest = candidate }
            catch (_: AnalysisPayloadCapacityException) { break }
        }
        assertTrue(largest.calls.size < 100)
        repository.saveAnalysisRun(task.stored.copy(version = task.stored.version + 1,
            payload = AnalysisRunCodec.encode(largest)), task.stored.version)
        val provider = MetricProvider()
        assertThrows(AnalysisPayloadCapacityException::class.java) { analysis.analyzeMetrics("run", "overflow", provider) }
        assertTrue(provider.requests.isEmpty())
        assertEquals(largest.calls.size, analysis.task("run").state.calls.size)
    }

    @Test fun `old v6 envelopes remain readable and cannot smuggle metric calls`() {
        start()
        val raw = object : AnalysisProvider by MetricProvider() {
            override fun complete(request: ModelRequest) = BatchProposal(emptyList())
            override fun completePrepared(request: PreparedModelRequest) = ModelCompletion(complete(request.original))
        }
        analysis.runBatch("run", "raw", listOf(EvidenceSelection("known", "body")), raw)
        val old = JSONObject(analysis.task("run").stored.payload).apply {
            put("formatVersion", 6)
            withoutProfileMaintenance()
            getJSONObject("request").remove("profileTarget")
            getJSONArray("calls").getJSONObject(0).remove("metricEvidence")
        }.toString()
        val restored = AnalysisRunCodec.decode(old)
        assertEquals(CallPurpose.EXTRACTION, restored.calls.single().purpose)
        assertTrue(restored.calls.single().metricEvidence.isEmpty())
        AnalysisRunCodec.validateStored(analysis.task("run").stored.copy(payload = old))
        analysis.analyzeMetrics("run", "metric", MetricProvider())
        val smuggled = JSONObject(analysis.task("run").stored.payload).apply {
            put("formatVersion", 6)
            withoutProfileMaintenance()
            getJSONObject("request").remove("profileTarget")
            val calls = getJSONArray("calls")
            repeat(calls.length()) { calls.getJSONObject(it).remove("metricEvidence") }
        }.toString()
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(smuggled) }
    }

    @Test fun `metric calls cannot become raw reduction leaves`() {
        start()
        analysis.analyzeMetrics("run", "first", MetricProvider())
        analysis.analyzeMetrics("run", "second", MetricProvider())
        val calls = analysis.task("run").state.calls
        assertThrows(IllegalArgumentException::class.java) { ReductionGraph.nodeRef(calls.first()) }
        assertEquals(0, PeriodReductionProcessor(analysis).inspect("run").leafCount)
    }

    private fun start(id: String = "run", query: AnalysisQuery = scope, limits: AnalysisLimits = AnalysisLimits()) =
        analysis.start(AnalysisRequest("合成任务：解释指定期间的记录覆盖，并保留缺口。", query, limits), id)

    private class MetricProvider(
        private val citation: String? = null,
        private val kind: ClaimKind = ClaimKind.POSSIBLE_INTERPRETATION,
        private val beforeReturn: () -> Unit = {},
    ) : AnalysisProvider {
        override val id = "synthetic-metric-provider"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request
            val metric = JSONObject(request.payloadJson).getJSONArray("metricEvidence").getJSONObject(0)
            beforeReturn()
            return BatchProposal(listOf(ProposedClaim("合成解释：记录覆盖仍有未知部分，不能据此判断生活活动。",
                listOf(citation ?: metric.getString("id")), kind)), listOf("合成局限：未提供原文内容。"))
        }
    }

    private class MetricReviewer(private val beforeReturn: () -> Unit = {}) : SemanticReviewProvider {
        override val id = "synthetic-metric-reviewer"
        override val contextWindowTokens = 100_000
        val requests = mutableListOf<ModelRequest>()
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests += request.original
            val metric = JSONObject(request.original.payloadJson).getJSONArray("metricEvidence").getJSONObject(0)
            beforeReturn()
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(metric.getString("id")), unknowns = listOf("合成局限：仅核对覆盖指标。")))
        }
    }
}
