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
class AnalysisRedactionCapacityTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private val database = "synthetic-redaction-capacity-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-19T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before fun setup() { context = RuntimeEnvironment.getApplication(); repository = LifeRepository(context, clock, database) }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `valid almost full legacy history yields a distinct capacity fallback signal without silently upgrading untouched history`() {
        val day = LocalDate.parse("2026-10-19")
        repository.createRecord(RecordDraft("合成原文：今天做了练习。", Occurrence(day)), "record")
        val analysis = ControlledAnalysis(repository, clock)
        analysis.start(AnalysisRequest("合成容量测试", AnalysisQuery(day, day, "Asia/Shanghai")), "run")
        analysis.runBatch("run", "extract", listOf(EvidenceSelection("record", "body")), object : AnalysisProvider {
            override val id = "synthetic-capacity-provider"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest) = BatchProposal(listOf(ProposedClaim("合成自述：做了练习。",
                listOf(JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")), ClaimKind.REPORTED_EXPERIENCE)))
        })
        val ref = analysis.claimRef("run", "extract", 0)
        val saved = AnalysisFeedback(repository, clock).respond("run", ref, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim("run", "review", ref, provider = object : SemanticReviewProvider {
            override val id = "synthetic-capacity-reviewer"
            override val contextWindowTokens = 100_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun reviewPrepared(request: PreparedModelRequest) = ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED,
                supportingEvidenceIds = listOf(JSONObject(request.original.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id"))))
        })
        val nearLimit = LegacyRedactionCapacityFixture.fill(analysis.task("run").stored)
        AnalysisRunCodec.validateStored(nearLimit)
        assertEquals(5, JSONObject(nearLimit.payload).getInt("formatVersion"))
        assertTrue(nearLimit.payload.toByteArray().size > AnalysisRunCodec.MAX_PAYLOAD_BYTES - 200)
        assertEquals(nearLimit, AnalysisRunCodec.redactDeletedFeedback(nearLimit, setOf("unrelated"), now))
        assertThrows(AnalysisRedactionCapacityException::class.java) {
            AnalysisRunCodec.redactDeletedFeedback(nearLimit, setOf(saved.feedback.id), now)
        }
    }
}
