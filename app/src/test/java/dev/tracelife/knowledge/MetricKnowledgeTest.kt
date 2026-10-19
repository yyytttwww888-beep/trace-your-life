package dev.tracelife.knowledge

import android.content.Context
import dev.tracelife.analysis.*
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic coverage explains the saved record population, never the user's activity or health. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MetricKnowledgeTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var feedback: AnalysisFeedback
    private lateinit var library: KnowledgeLibrary
    private val database = "synthetic-metric-knowledge-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-12")
    private val scope = AnalysisQuery(day, day.plusDays(6), "Asia/Shanghai")
    private val provider = Provider()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        library = KnowledgeLibrary(repository, clock)
    }

    @After fun tearDown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `confirmed metric interpretation publishes typed proof and complete source dependencies`() {
        seed()
        val claim = prepare()
        val saved = publish(claim)
        assertEquals(saved, publish(claim))
        assertEquals(KnowledgeCategory.INSIGHT, saved.category)
        assertEquals(ClaimKind.POSSIBLE_INTERPRETATION, saved.claim.kind)
        assertTrue(saved.evidence.isEmpty())
        assertTrue(KnowledgeValidation.allEvidence(saved).isEmpty())
        assertNull(saved.reductionProof)
        val metric = saved.metricEvidence.single()
        assertEquals(scope, metric.scope)
        assertEquals(3, metric.values.totalRecords)
        assertEquals(1, metric.values.exactDateRecords)
        assertEquals(1, metric.values.intervalRecords)
        assertEquals(1, metric.values.unknownTimeRecords)
        assertEquals(listOf("exact" to 1, "interval" to 1, "unknown" to 1), KnowledgeValidation.allSourceRevisions(saved))
        assertEquals(listOf(metric.id), saved.claim.evidenceIds)
        assertEquals(listOf(metric.id), saved.review.supportingEvidenceIds)
        assertEquals(saved, KnowledgeCodec.decode(KnowledgeCodec.encode(saved)))
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(saved.id)!!.evidenceState)
        assertEquals(1, library.index(KnowledgeFilter(topics = setOf("records"))).total)
        assertFalse(KnowledgeCodec.encode(saved).contains("独立用户文字"))
        assertTrue(feedback.forClaim("run", claim)!!.feedback.userText!!.contains("独立用户文字"))
    }

    @Test fun `empty period can publish its metric proof without inventing original evidence`() {
        val saved = publish(prepare())
        assertTrue(KnowledgeValidation.allSourceRevisions(saved).isEmpty())
        assertTrue(saved.evidence.isEmpty())
        assertEquals(0, saved.metricEvidence.single().values.totalRecords)
        assertEquals(7, saved.metricEvidence.single().values.daysInScope)
        assertEquals(7, saved.metricEvidence.single().values.daily.size)
        assertTrue(saved.metricEvidence.single().values.daily.all { it.records == 0 })
        KnowledgeValidation.validateEvidence(saved, emptyList())
        assertEquals(saved, KnowledgeCodec.decode(KnowledgeCodec.encode(saved)))
    }

    @Test fun `metric publication requires confirmation followed by fresh review and insight category`() {
        seed()
        val claim = prepare(confirm = false)
        assertThrows(IllegalArgumentException::class.java) { publish(claim) }
        confirm(claim)
        assertThrows(IllegalArgumentException::class.java) { publish(claim) }
        analysis.reviewClaim("run", "confirmed-review", claim, provider = provider)
        val saved = publish(claim, "confirmed-review")
        assertEquals(1, saved.feedbackVersion)
        listOf(KnowledgeCategory.OBSERVATION, KnowledgeCategory.KNOWLEDGE_NOTE).forEach { category ->
            assertThrows(IllegalArgumentException::class.java) {
                library.publish("run", claim, "confirmed-review", category, version())
            }
        }
    }

    @Test fun `later metric review invalidates prior support and cannot be bypassed by publishing old review`() {
        seed()
        val claim = prepare()
        val saved = publish(claim)
        provider.verdict = ReviewVerdict.UNSUPPORTED
        analysis.reviewClaim("run", "later-negative", claim, provider = provider)
        assertEquals(KnowledgeEvidenceState.REVIEW_CHANGED, library.get(saved.id)!!.evidenceState)
        assertEquals(1, library.index().omittedInvalid)
        assertThrows(IllegalArgumentException::class.java) { publish(claim, expectedRevision = 1) }
        assertThrows(IllegalArgumentException::class.java) { publish(claim, "later-negative", 1) }
        assertEquals(saved, repository.knowledgeRevision(saved.id))
    }

    @Test fun `new support creates a revision while current matching also binds the metric receipt`() {
        seed()
        val claim = prepare()
        val saved = publish(claim)
        analysis.reviewClaim("run", "later-support", claim, provider = provider)
        val updated = publish(claim, "later-support", 1)
        assertEquals(2, updated.revision)
        assertEquals(saved.metricEvidence, updated.metricEvidence)
        assertEquals(KnowledgeEvidenceState.REVIEW_CHANGED, library.get(saved.id, 1)!!.evidenceState)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(saved.id)!!.evidenceState)
        val state = analysis.task("run").state
        val missingReceipt = state.copy(calls = state.calls.map {
            if (it.batchKey == "later-support") it.copy(metricEvidence = emptyList()) else it
        })
        assertFalse(KnowledgeValidation.reviewMatchesState(updated, missingReceipt))
    }

    @Test fun `source addition invalidates metric knowledge even when old selected records remain unchanged`() {
        seed()
        val claim = prepare()
        val saved = publish(claim)
        repository.createRecord(RecordDraft("合成资料：后来补记的事件。", Occurrence(day.plusDays(2))), "late")
        assertEquals(KnowledgeEvidenceState.STALE, library.get(saved.id)!!.evidenceState)
        assertEquals(0, library.index().total)
        assertThrows(StaleSnapshotException::class.java) { publish(claim, expectedRevision = 1) }
        assertEquals(3, saved.metricEvidence.single().values.totalRecords)
    }

    @Test fun `expiry and user correction remain independent publication fences`() {
        seed()
        val claim = prepare(expiresAt = clock.now.plusSeconds(60))
        val saved = publish(claim)
        val response = feedback.forClaim("run", claim)!!.feedback
        feedback.update(response.id, response.version, version(), ClaimFeedbackDecision.CORRECTED,
            "合成独立用户文字：记录覆盖没有说明活动频率。")
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, library.get(saved.id)!!.evidenceState)
        analysis.reviewClaim("run", "corrected-review", claim, provider = provider)
        assertThrows(IllegalArgumentException::class.java) { publish(claim, "corrected-review", 1) }
        clock.now = clock.now.plusSeconds(60)
        assertEquals(KnowledgeEvidenceState.EXPIRED, library.get(saved.id)!!.evidenceState)
        assertTrue(feedback.forClaim("run", claim)!!.feedback.userText!!.contains("活动频率"))
    }

    @Test fun `feedback erasure removes metric knowledge and review text while preserving source records`() {
        seed()
        val claim = prepare()
        val saved = publish(claim)
        val response = feedback.forClaim("run", claim)!!.feedback
        feedback.delete(response.id, response.version)
        assertNull(library.get(saved.id))
        assertTrue(repository.knowledgeHistory(saved.id).isEmpty())
        assertEquals(3, repository.analysisSourcePage(repository.analysisSourceStamp(), scope, 0, 25).total)
        val erased = analysis.task("run").state.calls.single { it.batchKey == "review" }
        assertEquals("FEEDBACK_DELETED", erased.failureCode)
        assertNull(erased.review)
        assertThrows(IllegalArgumentException::class.java) { publish(claim) }
    }

    @Test fun `source deletion purges metric knowledge and keeps independent user text`() {
        seed()
        val claim = prepare()
        val saved = publish(claim)
        val text = feedback.forClaim("run", claim)!!.feedback.userText
        repository.deleteRecord("interval", 1)
        assertNull(library.get(saved.id))
        assertTrue(repository.knowledgeHistory(saved.id).isEmpty())
        assertEquals(text, feedback.forClaim("run", claim)!!.feedback.userText)
    }

    @Test fun `knowledge codec rejects unsupported versions missing metrics and falsified metric values`() {
        seed()
        val saved = publish(prepare())
        val payload = KnowledgeCodec.encode(saved)
        assertEquals(5, JSONObject(payload).getInt("formatVersion"))
        val invalid = listOf(
            JSONObject(payload).put("formatVersion", 6).toString(),
            JSONObject(payload).put("formatVersion", 2).toString(),
            JSONObject(payload).apply { remove("metricEvidence") }.toString(),
            JSONObject(payload).apply { put("formatVersion", 2); remove("metricEvidence") }.toString(),
            payload.replace("\"formatVersion\":5", "\"formatVersion\":5,\"formatVersion\":5"),
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { KnowledgeCodec.decode(it) } }
        val metric = saved.metricEvidence.single()
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeValidation.validate(saved.copy(metricEvidence = listOf(metric.copy(values = metric.values.copy(totalRecords = 99)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeValidation.validate(saved.copy(metricEvidence = listOf(metric.copy(scope = scope.copy(through = day)))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeValidation.validate(saved.copy(metricEvidence = listOf(metric.copy(source = metric.source.copy(generation = metric.source.generation + 1)))))
        }
    }

    @Test fun `historic metric proof requires exact source revisions and rechecks occurrence values`() {
        seed()
        val saved = publish(prepare())
        val sources = KnowledgeValidation.allSourceRevisions(saved).map { (id, revision) -> repository.record(id, revision)!! }
        KnowledgeValidation.validateEvidence(saved, sources)
        assertThrows(IllegalArgumentException::class.java) { KnowledgeValidation.validateEvidence(saved, sources.drop(1)) }
        val changedDates = sources.map { source ->
            if (source.id == "exact") source.copy(draft = source.draft.copy(occurrence = Occurrence(day.plusDays(1)))) else source
        }
        assertThrows(IllegalArgumentException::class.java) { KnowledgeValidation.validateEvidence(saved, changedDates) }
        val changedRevision = sources.map { if (it.id == "exact") it.copy(revision = 2) else it }
        assertThrows(IllegalArgumentException::class.java) { KnowledgeValidation.validateEvidence(saved, changedRevision) }
    }

    private fun seed() {
        repository.createRecord(RecordDraft("合成资料：一个精确日期。", Occurrence(day)), "exact")
        repository.createRecord(RecordDraft("合成资料：跨日记录只计一条。", Occurrence(day, day.plusDays(1))), "interval")
        repository.createRecord(RecordDraft("合成资料：日期未知。"), "unknown")
    }

    private fun prepare(confirm: Boolean = true, expiresAt: Instant? = null): ClaimRef {
        analysis.start(AnalysisRequest("解释合成记录覆盖及资料缺口，不推断活动或成长。", scope, expiresAt = expiresAt), "run")
        analysis.analyzeMetrics("run", "metrics", provider)
        return analysis.claimRef("run", "metrics", 0).also { claim ->
            if (confirm) confirm(claim)
            analysis.reviewClaim("run", "review", claim, provider = provider)
        }
    }

    private fun confirm(claim: ClaimRef) = feedback.respond("run", claim, version(), ClaimFeedbackDecision.CONFIRMED,
        "合成独立用户文字：只认同此期间的记录覆盖解释。")

    private fun publish(claim: ClaimRef, reviewKey: String = "review", expectedRevision: Int? = null) =
        library.publish("run", claim, reviewKey, KnowledgeCategory.INSIGHT, version(), expectedRevision, setOf("records"))

    private fun version() = analysis.task("run").stored.version

    private class Provider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-metric-provider"
        override val contextWindowTokens = 100_000
        var verdict = ReviewVerdict.SUPPORTED
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            val metric = JSONObject(request.payloadJson).getJSONArray("metricEvidence").getJSONObject(0)
            return BatchProposal(listOf(ProposedClaim("合成解释：这些统计只描述已保存记录的日期覆盖，无法推出活动或成长情况。",
                listOf(metric.getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val id = JSONObject(request.payloadJson).getJSONArray("metricEvidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(verdict, if (verdict == ReviewVerdict.SUPPORTED) listOf(id) else emptyList()))
        }
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T00:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
