package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
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

/** Synthetic public user-feedback workflows, using real SQLite and no credentials or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnalysisFeedbackTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var feedback: AnalysisFeedback
    private lateinit var databaseName: String
    private lateinit var claim: ClaimRef
    private val date = LocalDate.parse("2026-10-17")
    private val clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "synthetic-feedback-service-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, databaseName)
        analysis = ControlledAnalysis(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        repository.createRecord(RecordDraft("合成记录：今天尝试一次学习方法。", Occurrence(date)), "note")
        analysis.start(AnalysisRequest("整理合成学习记录", AnalysisQuery(date, date, "Asia/Shanghai")), "run")
        analysis.runBatch("run", "extract", listOf(EvidenceSelection("note", "body")), object : AnalysisProvider {
            override val id = "synthetic-extractor"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
            override fun complete(request: ModelRequest): BatchProposal {
                val ref = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim("合成AI文字：用户尝试一次方法。", listOf(ref), ClaimKind.REPORTED_EXPERIENCE)))
            }
        })
        claim = analysis.claimRef("run", "extract", 0)
    }

    @After fun tearDown() { repository.close(); context.deleteDatabase(databaseName) }

    @Test fun `confirmation stays separate from evidence and does not copy model text`() {
        val source = repository.analysisSourceStamp()
        val runVersion = analysis.task("run").stored.version
        val result = feedback.respond("run", claim, runVersion, ClaimFeedbackDecision.CONFIRMED)
        assertNull(result.feedback.userText)
        assertEquals(ClaimFeedbackDecision.CONFIRMED, result.feedback.decision)
        assertEquals(FeedbackEvidenceState.CURRENT, result.evidenceState)
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(runVersion + 1, result.currentRunVersion)
        assertEquals(ClaimKind.REPORTED_EXPERIENCE, analysis.task("run").state.calls.single().proposal!!.claims.single().kind)
        assertEquals(result, feedback.forClaim("run", claim))
        assertEquals(listOf(result), feedback.list())
    }

    @Test fun `stale displayed feedback cannot overwrite a newer rejection`() {
        val original = feedback.respond("run", claim, analysis.task("run").stored.version, ClaimFeedbackDecision.CONFIRMED)
        val rejected = feedback.update(original.feedback.id, 1, original.currentRunVersion, ClaimFeedbackDecision.REJECTED, "合成用户说明：这个解释不适用。")
        assertThrows(RevisionConflictException::class.java) {
            feedback.update(original.feedback.id, 1, original.currentRunVersion, ClaimFeedbackDecision.CONFIRMED, null)
        }
        assertEquals(2, rejected.feedback.version)
        assertEquals(ClaimFeedbackDecision.REJECTED, feedback.get(rejected.feedback.id)!!.feedback.decision)
    }

    @Test fun `source deletion preserves editable independent correction without reviving evidence`() {
        val result = feedback.respond("run", claim, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成用户独立文字：我只阅读了方法，尚未实践。")
        repository.deleteRecord("note", 1)
        assertNull(repository.analysisRun("run"))
        val orphan = feedback.get(result.feedback.id)!!
        assertEquals(FeedbackEvidenceState.TASK_UNAVAILABLE, orphan.evidenceState)
        assertEquals(result.feedback.userText, orphan.feedback.userText)
        val edited = feedback.update(orphan.feedback.id, 1, null, ClaimFeedbackDecision.CORRECTED, "合成用户独立文字：只阅读了简介。")
        assertEquals(FeedbackEvidenceState.TASK_UNAVAILABLE, edited.evidenceState)
        assertThrows(IllegalArgumentException::class.java) {
            feedback.update(edited.feedback.id, 2, null, ClaimFeedbackDecision.CONFIRMED, edited.feedback.userText)
        }
        repository.close()
        repository = LifeRepository(context, clock, databaseName)
        feedback = AnalysisFeedback(repository, clock)
        assertEquals(edited, feedback.get(edited.feedback.id))
    }

    @Test fun `changed source exposes stale feedback and allows withdrawal without approving old evidence`() {
        val result = feedback.respond("run", claim, analysis.task("run").stored.version, ClaimFeedbackDecision.REJECTED)
        repository.updateRecord("note", 1, RecordDraft("合成补充：只是阅读，尚未实践。", Occurrence(date)))
        val stale = feedback.get(result.feedback.id)!!
        assertEquals(FeedbackEvidenceState.STALE, stale.evidenceState)
        assertThrows(IllegalArgumentException::class.java) {
            feedback.update(stale.feedback.id, 1, stale.currentRunVersion, ClaimFeedbackDecision.CONFIRMED, null)
        }
        val withdrawn = feedback.update(stale.feedback.id, 1, stale.currentRunVersion, ClaimFeedbackDecision.WITHDRAWN, "合成用户说明：保留待重新核对。")
        assertEquals(FeedbackEvidenceState.STALE, withdrawn.evidenceState)
        assertEquals(ClaimFeedbackDecision.WITHDRAWN, withdrawn.feedback.decision)
        feedback.delete(withdrawn.feedback.id, 2)
        assertNull(feedback.get(withdrawn.feedback.id))
        assertTrue(feedback.list().isEmpty())
    }
}
