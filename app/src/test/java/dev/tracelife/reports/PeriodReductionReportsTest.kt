package dev.tracelife.reports

import android.content.Context
import dev.tracelife.analysis.*
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
class PeriodReductionReportsTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var reports: PeriodReductionReports
    private val database = "synthetic-draft-report-${UUID.randomUUID()}.db"
    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-18")
    private var calls = 0
    private val provider = object : AnalysisProvider {
        override val id = "synthetic-draft-reader"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            calls++
            val json = JSONObject(request.payloadJson)
            val items = json.getJSONArray(if (json.optString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            return BatchProposal(listOf(ProposedClaim("合成草稿：需要回查原文。",
                (0 until items.length()).map { items.getJSONObject(it).getString("id") }, ClaimKind.POSSIBLE_INTERPRETATION)),
                listOf("合成缺口：没有设备资料"))
        }
    }

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        reports = PeriodReductionReports(repository, analysis)
        repository.createRecord(RecordDraft("合成🙂资料：原文尾部不在本次片段内", Occurrence(date)), "one")
        repository.createRecord(RecordDraft("合成二：保留不同情况", Occurrence(null)), "two")
        repository.createRecord(RecordDraft("合成🙂另段资料不在初始草稿来源链", Occurrence(date)), "other")
        analysis.start(AnalysisRequest("合成周期草稿", AnalysisQuery(date, date, "Asia/Shanghai"),
            limits = AnalysisLimits(fragmentCodePoints = 4), expiresAt = now.plusSeconds(10)), "run")
        listOf("one", "two").forEach { id -> analysis.synthesize("run", "leaf-$id", listOf(EvidenceSelection(id, "body")), provider) }
        val leaves = analysis.task("run").state.calls.map(ReductionGraph::nodeRef)
        analysis.reduce("run", "root", leaves, provider)
    }

    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    private fun draft(): PeriodDraft = reports.open("run", ReductionGraph.nodeRef(analysis.task("run").state.calls.last()))

    @Test fun `draft reads are bounded to original lineage and do not call the model`() {
        val before = calls
        val draft = draft()
        assertEquals(2, draft.leafCount)
        assertEquals(2, draft.originalFragments)
        assertFalse(draft.originalsSemanticallyReviewed)
        assertEquals("合成草稿：需要回查原文。", reports.body(draft).statements.single().text)
        val page = reports.evidence(draft, limit = 1)
        assertEquals(2, page.total)
        assertEquals(1, page.items.size)
        val second = reports.evidence(draft, page.nextOffset!!, 1)
        assertEquals(1, second.items.size)
        assertNull(second.nextOffset)
        val ref = (page.items + second.items).single { it.recordId == "one" }
        assertEquals("合成🙂资", reports.original(draft, ref.id, maxCodePoints = 4_096).text)
        assertEquals("🙂", reports.original(draft, ref.id, start = 2, maxCodePoints = 1).text)
        assertThrows(IllegalArgumentException::class.java) { reports.original(draft, ref.id, start = 4) }
        assertThrows(IllegalArgumentException::class.java) { reports.original(draft, "f".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { reports.evidence(draft, limit = 101) }
        assertTrue(reports.evidence(draft, Int.MAX_VALUE).items.isEmpty())
        assertEquals(before, calls)
    }

    @Test fun `feedback source changes and expiry prevent reading a retained draft handle`() {
        val draft = draft()
        val expired = PeriodReductionReports(repository, ControlledAnalysis(repository, Clock.fixed(now.plusSeconds(10), ZoneOffset.UTC)))
        assertThrows(IllegalStateException::class.java) { expired.evidence(draft) }
        val claim = analysis.claimRef("run", "leaf-one", 0)
        AnalysisFeedback(repository, clock).respond("run", claim, analysis.task("run").stored.version,
            ClaimFeedbackDecision.REJECTED, "合成用户纠正，仍独立保存")
        assertThrows(IllegalStateException::class.java) { reports.evidence(draft) }
        assertThrows(IllegalStateException::class.java) { reports.body(draft) }
        repository.createRecord(RecordDraft("合成补记", Occurrence(date)), "new")
        assertThrows(IllegalStateException::class.java) { reports.original(draft, draft.evidenceIndex.first().id) }
        assertEquals(3, calls)
    }

    @Test fun `fabricated node identity cannot open a draft and task deletion removes its navigation`() {
        val ref = ReductionGraph.nodeRef(analysis.task("run").state.calls.last())
        assertThrows(IllegalArgumentException::class.java) { reports.open("run", ref.copy(outputSha256 = "a".repeat(64))) }
        val draft = reports.open("run", ref)
        analysis.delete("run", analysis.task("run").stored.version)
        assertThrows(AnalysisTaskUnavailableException::class.java) { reports.evidence(draft) }
    }

    @Test fun `source changes alone invalidate a draft and new task progress requires reopening`() {
        val draft = draft()
        analysis.synthesize("run", "other-leaf", listOf(EvidenceSelection("other", "body", 2)), provider)
        assertThrows(AnalysisConflictException::class.java) { reports.body(draft) }
        val reopened = reports.open("run", draft.node)
        val outsideRef = analysis.task("run").state.calls.last().evidence.single()
        assertThrows(IllegalArgumentException::class.java) { reports.original(reopened, outsideRef.id) }
        assertEquals("合成草稿：需要回查原文。", reports.body(reopened).statements.single().text)
        repository.createRecord(RecordDraft("合成新补记", Occurrence(date)), "added")
        assertThrows(IllegalStateException::class.java) { reports.body(reopened) }
        assertThrows(IllegalStateException::class.java) { reports.original(reopened, reopened.evidenceIndex.first().id) }
    }
}
