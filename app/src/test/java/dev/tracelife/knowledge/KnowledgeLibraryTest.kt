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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KnowledgeLibraryTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var feedback: AnalysisFeedback
    private lateinit var library: KnowledgeLibrary
    private val name = "synthetic-library-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val date = LocalDate.parse("2026-10-17")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        analysis = ControlledAnalysis(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        library = KnowledgeLibrary(repository, clock)
        repository.createRecord(RecordDraft("合成资料：我记录了一次学习练习，并尝试把问题拆成小步骤。", Occurrence(date)), "source")
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name) }

    @Test fun `published revisions retain original wording and bounded navigation across classifications`() {
        val claim = prepare()
        val first = publish(claim)
        assertEquals(first, publish(claim))
        val index = library.index(KnowledgeFilter(topics = setOf("learning")), limit = 1)
        assertEquals(listOf(first.id), index.items.map { it.id })
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(first.id)!!.evidenceState)
        val second = library.publish("run", claim, "review", KnowledgeCategory.KNOWLEDGE_NOTE,
            analysis.task("run").stored.version, first.revision, setOf("learning"))
        assertEquals(2, second.revision)
        val history = library.history(first.id)
        assertEquals(setOf(1, 2), history.map { it.value.revision }.toSet())
        assertEquals(1, history.count { it.isCurrentRevision })
        assertTrue(history.all { it.value.claim.text == first.claim.text })
        assertThrows(StaleSnapshotException::class.java) {
            // A page token cannot be reused after another publication changed the directory.
            library.next(index.copy(nextOffset = 1))
        }
        library.delete(first.id, second.revision)
        assertNull(library.get(first.id))
        assertThrows(IllegalArgumentException::class.java) { publish(claim) }
    }

    @Test fun `user correction invalidates current publication and deletion removes its derived text`() {
        val claim = prepare()
        val saved = publish(claim)
        val corrected = feedback.respond("run", claim, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成用户独立文字：一次尝试不说明已掌握。")
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, library.get(saved.id)!!.evidenceState)
        assertEquals(0, library.index().total)
        assertEquals(1, library.index().omittedInvalid)
        assertEquals(1, library.index(KnowledgeFilter(includeInvalid = true)).total)
        analysis.reviewClaim("run", "after-correction", claim, provider = reviewer)
        assertThrows(IllegalArgumentException::class.java) {
            library.publish("run", claim, "after-correction", KnowledgeCategory.OBSERVATION,
                analysis.task("run").stored.version, saved.revision)
        }
        feedback.delete(corrected.feedback.id, corrected.feedback.version)
        assertNull(library.get(saved.id))
    }

    @Test fun `interpretation requires independent confirmation and a review with that feedback version`() {
        val claim = prepare(ClaimKind.POSSIBLE_INTERPRETATION)
        assertThrows(IllegalArgumentException::class.java) { publish(claim, KnowledgeCategory.INSIGHT) }
        feedback.respond("run", claim, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED,
            "合成用户独立说明：仅适用于这一次练习。")
        assertThrows(IllegalArgumentException::class.java) { publish(claim, KnowledgeCategory.INSIGHT) }
        analysis.reviewClaim("run", "confirmed-review", claim, provider = reviewer)
        val saved = library.publish("run", claim, "confirmed-review", KnowledgeCategory.INSIGHT,
            analysis.task("run").stored.version)
        repository.updateRecord("source", 1, RecordDraft("合成资料修订：实际结果需要重新理解。", Occurrence(date)))
        assertEquals(KnowledgeEvidenceState.STALE, library.get(saved.id)!!.evidenceState)
        assertTrue(feedback.forClaim("run", claim)!!.feedback.userText!!.contains("独立说明"))
    }

    @Test fun `expiry is checked without new writes and old evaluation time stays explicit`() {
        val claim = prepare(expiresAt = clock.instant().plusSeconds(60))
        val saved = publish(claim)
        val before = library.index()
        clock.now = clock.now.plusSeconds(60)
        assertEquals(KnowledgeEvidenceState.EXPIRED, library.get(saved.id)!!.evidenceState)
        assertEquals(0, library.index().total)
        assertTrue(before.evaluatedAt < clock.instant())
    }

    @Test fun `later review invalidates a publication without changing original evidence or feedback`() {
        val claim = prepare()
        val saved = publish(claim)
        analysis.reviewClaim("run", "later-review", claim, provider = reviewer)
        assertEquals(KnowledgeEvidenceState.REVIEW_CHANGED, library.get(saved.id)!!.evidenceState)
        assertEquals(0, library.index().total)
        val updated = library.publish("run", claim, "later-review", KnowledgeCategory.OBSERVATION,
            analysis.task("run").stored.version, saved.revision)
        assertEquals(2, updated.revision)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(saved.id)!!.evidenceState)
    }

    private fun prepare(kind: ClaimKind = ClaimKind.REPORTED_EXPERIENCE, expiresAt: Instant? = null): ClaimRef {
        analysis.start(AnalysisRequest("整理合成学习记录", AnalysisQuery(date, date, "Asia/Shanghai"), expiresAt = expiresAt), "run")
        analysis.runBatch("run", "extract", listOf(EvidenceSelection("source", "body")), object : AnalysisProvider {
            override val id = "synthetic-extractor"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
            override fun complete(request: ModelRequest): BatchProposal {
                val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成陈述：用户尝试将学习问题拆成小步骤。", listOf(id), kind)))
            }
        })
        return analysis.claimRef("run", "extract", 0).also { analysis.reviewClaim("run", "review", it, provider = reviewer) }
    }

    private fun publish(claim: ClaimRef, category: KnowledgeCategory = KnowledgeCategory.OBSERVATION) =
        library.publish("run", claim, "review", category, analysis.task("run").stored.version, topics = setOf("learning"))

    private val reviewer = object : SemanticReviewProvider {
        override val id = "synthetic-reviewer"
        override val contextWindowTokens = 100_000
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(id)))
        }
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-17T00:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
