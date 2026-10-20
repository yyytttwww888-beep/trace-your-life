package dev.tracelife.storage

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.*
import dev.tracelife.model.*
import dev.tracelife.transfer.*
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

/** Synthetic end-to-end scope, retention and deletion journeys; no model network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ScopedKnowledgeLifecycleTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var library: KnowledgeLibrary
    private val name = "synthetic-scope-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC)
    private val first = LocalDate.parse("2026-10-05")
    private val second = LocalDate.parse("2026-10-20")
    private val scope = AnalysisQuery(first, first.plusDays(6), "Asia/Shanghai")
    private lateinit var directory: File

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        open()
        directory = File(context.cacheDir, "synthetic-scope-${UUID.randomUUID()}").apply { mkdirs() }
    }
    private fun open() {
        repository = LifeRepository(context, clock, name)
        analysis = ControlledAnalysis(repository, clock)
        library = KnowledgeLibrary(repository, clock)
    }
    @After fun close() { repository.close(); context.deleteDatabase(name); directory.deleteRecursively() }

    @Test fun `unrelated next week retains knowledge original provenance review and export`() {
        record("learning", first)
        val knowledge = publish("learning-run", "learning")
        val originalStamp = knowledge.source
        record("exercise", second)
        assertNotEquals(originalStamp, repository.analysisSourceStamp())
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(knowledge.id)!!.evidenceState)
        assertEquals(1, library.index().total)
        assertEquals(originalStamp, analysis.task("learning-run").stored.source)
        assertEquals("合成资料：learning", repository.analysisSourceFragment(originalStamp, scope, "learning", "body", 0, 100).text)
        analysis.reviewClaim("learning-run", "review-again", analysis.claimRef("learning-run", "extract", 0), provider = reviewer)
        library.publish("learning-run", analysis.claimRef("learning-run", "extract", 0), "review-again", KnowledgeCategory.OBSERVATION,
            analysis.task("learning-run").stored.version, knowledge.revision)
        val snapshot = snapshot()
        val exported = PublishedKnowledgeExport.select(snapshot, snapshot.records, snapshot.records, ReadingExportOptions())
        assertEquals(listOf(knowledge.id), exported.entries.map { it.value.id })
        assertNull(snapshot.copy().currentKnowledgeSourceRevisions)
        repository.close(); open()
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(knowledge.id)!!.evidenceState)
    }

    @Test fun `backfill and unknown dates invalidate whole range but exact identity excludes unrelated records`() {
        record("learning", first)
        val current = analysis.start(AnalysisRequest("合成完整周期", scope), "period")
        val exact = analysis.start(AnalysisRequest("合成精确记录", scope.copy(recordIds = setOf("learning"))), "exact")
        record("backfill", first.plusDays(2))
        assertEquals(AnalysisStatus.STALE, analysis.task(current.stored.id).status)
        assertEquals(AnalysisStatus.READY, analysis.task(exact.stored.id).status)
        val next = analysis.start(AnalysisRequest("合成范围", scope), "before-unknown")
        repository.createRecord(RecordDraft("合成资料：日期未知"), "unknown")
        assertEquals(AnalysisStatus.STALE, analysis.task(next.stored.id).status)
        assertEquals(AnalysisStatus.READY, analysis.task(exact.stored.id).status)
    }

    @Test fun `date changes invalidate both old and new scopes while unrelated scopes survive`() {
        record("moving", first)
        val old = analysis.start(AnalysisRequest("合成旧周期", scope), "old")
        val newScope = scope.copy(from = second, through = second.plusDays(6))
        val new = analysis.start(AnalysisRequest("合成新周期", newScope), "new")
        val distant = analysis.start(AnalysisRequest("合成未来周期", scope.copy(from = second.plusMonths(2), through = second.plusMonths(2))), "distant")
        repository.updateRecord("moving", 1, RecordDraft("合成资料：改日期", Occurrence(second)))
        assertEquals(AnalysisStatus.STALE, analysis.task(old.stored.id).status)
        assertEquals(AnalysisStatus.STALE, analysis.task(new.stored.id).status)
        assertEquals(AnalysisStatus.READY, analysis.task(distant.stored.id).status)
    }

    @Test fun `archived edits do not affect excluded scope but unarchive does`() {
        record("archived", first)
        repository.setArchived("archived", 1, true)
        val excluded = analysis.start(AnalysisRequest("合成排除归档", scope), "excluded")
        val included = analysis.start(AnalysisRequest("合成包含归档", scope.copy(includeArchived = true)), "included")
        repository.updateRecord("archived", 2, RecordDraft("合成资料：归档中修订", Occurrence(first)))
        assertEquals(AnalysisStatus.READY, analysis.task(excluded.stored.id).status)
        assertEquals(AnalysisStatus.STALE, analysis.task(included.stored.id).status)
        repository.setArchived("archived", 3, false)
        assertEquals(AnalysisStatus.STALE, analysis.task(excluded.stored.id).status)
    }

    @Test fun `delete unrelated draft and unused template preserve knowledge and analysis`() {
        record("learning", first)
        record("draft", second)
        val knowledge = publish("learning-run", "learning")
        repository.deleteRecord("draft", 1)
        val template = repository.saveTemplate(TemplateDraft("合成未使用模板", Cadence.DAILY,
            questions = listOf(TemplateQuestion("q", "合成问题"))))
        repository.deleteTemplate(template.id)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(knowledge.id)!!.evidenceState)
        assertNotNull(repository.analysisRun("learning-run"))
        assertEquals(1, library.index().total)
    }

    @Test fun `delete actual source purges dependents preserves other knowledge and independent wording`() {
        record("learning", first); record("exercise", second)
        val learning = publish("learning-run", "learning")
        val exercise = publish("exercise-run", "exercise", scope.copy(from = second, through = second))
        val claim = analysis.claimRef("learning-run", "extract", 0)
        val feedback = AnalysisFeedback(repository, clock).respond("learning-run", claim, analysis.task("learning-run").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成独立文字：不能将一次经历当作常态")
        repository.deleteRecord("learning", 1)
        assertNull(library.get(learning.id)); assertNull(repository.analysisRun("learning-run"))
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(exercise.id)!!.evidenceState)
        assertEquals(feedback.feedback.userText, repository.claimFeedback(feedback.feedback.id)!!.userText)
        assertTrue(snapshot().deletedAnalysisRuns.any { it.id == "learning-run" })
    }

    @Test fun `dependent cleanup failure rolls back source deletion and scope invalidation together`() {
        record("learning", first)
        val knowledge = publish("learning-run", "learning")
        val source = repository.analysisSourceStamp()
        LifeDatabase(context, name).use { it.writableDatabase.execSQL(
            "CREATE TRIGGER synthetic_fail_cleanup BEFORE DELETE ON analysis_runs BEGIN SELECT RAISE(ABORT, 'synthetic'); END") }
        assertThrows(android.database.sqlite.SQLiteException::class.java) { repository.deleteRecord("learning", 1) }
        assertNotNull(repository.record("learning"))
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(knowledge.id)!!.evidenceState)
        assertNotNull(repository.analysisRun("learning-run"))
    }

    @Test fun `source deletion cancels inflight completion and cannot restore its copied prose`() {
        record("learning", first)
        analysis.start(AnalysisRequest("合成在途任务", scope), "running")
        assertThrows(Exception::class.java) {
            analysis.runBatch("running", "extract", listOf(EvidenceSelection("learning", "body")), extractor {
                repository.deleteRecord("learning", 1)
            })
        }
        assertNull(repository.analysisRun("running"))
        assertTrue(snapshot().deletedAnalysisRuns.any { it.id == "running" })
    }

    @Test fun `unconsumed deleted source invalidates scope without deleting unrelated task history`() {
        record("learning", first)
        analysis.start(AnalysisRequest("合成尚未读取", scope), "empty")
        repository.deleteRecord("learning", 1)
        assertNotNull(repository.analysisRun("empty"))
        assertEquals(AnalysisStatus.STALE, analysis.task("empty").status)
    }

    @Test fun `restoring with extra local deletion preserves unrelated proof and fences every imported stamp`() {
        record("learning", first); record("exercise", second)
        val learning = publish("learning-run", "learning")
        val exercise = publish("exercise-run", "exercise", scope.copy(from = second, through = second))
        val before = snapshot()
        repository.deleteRecord("learning", 1)
        repository.restoreSnapshot(before, emptyDirectory(), repository.currentGeneration(), true)
        assertNull(repository.analysisRun("learning-run")); assertNull(library.get(learning.id))
        assertNotNull(repository.analysisRun("exercise-run")); assertNotNull(library.get(exercise.id))
        assertEquals(KnowledgeEvidenceState.STALE, library.get(exercise.id)!!.evidenceState)
        assertFalse(repository.analysisSourceCurrent(exercise.source, exercise.scope))
        assertNull(repository.record("learning"))
    }

    @Test fun `version eight upgrade preserves valid tasks but never revives already stale ones`() {
        record("learning", first)
        analysis.start(AnalysisRequest("合成旧任务", scope), "stale")
        repository.updateRecord("learning", 1, RecordDraft("合成资料：来源变化", Occurrence(first)))
        analysis.start(AnalysisRequest("合成当前任务", scope), "current")
        repository.close()
        LifeDatabase(context, name).use { helper ->
            removeVersionNineForMigration(helper.writableDatabase)
            helper.writableDatabase.version = 8
        }
        open()
        record("exercise", second)
        assertEquals(AnalysisStatus.STALE, analysis.task("stale").status)
        assertEquals(AnalysisStatus.READY, analysis.task("current").status)
    }

    @Test fun `template head changes preserve bound old version and bound question changes invalidate it`() {
        val template = repository.saveTemplate(TemplateDraft("合成模板", Cadence.DAILY,
            questions = listOf(TemplateQuestion("q", "合成原问题"))))
        repository.createRecord(RecordDraft("合成资料：绑定模板", Occurrence(first), template = TemplateRef(template.id, 1), answers = mapOf("q" to "合成答案")), "templated")
        val run = analysis.start(AnalysisRequest("合成模板任务", scope), "template-run")
        repository.saveTemplate(template.draft.copy(name = "合成新版模板"), template.id, 1)
        assertEquals(AnalysisStatus.READY, analysis.task(run.stored.id).status)
        assertThrows(IllegalArgumentException::class.java) { repository.deleteTemplate(template.id) }
        LifeDatabase(context, name).use { helper -> helper.writableDatabase.execSQL(
            "UPDATE template_questions SET prompt = ? WHERE template_id = ? AND version = 1", arrayOf("合成原版本变化", template.id)) }
        assertEquals(AnalysisStatus.STALE, analysis.task(run.stored.id).status)
    }

    @Test fun `attachment imports are independent until linked and bound metadata edits invalidate scope`() {
        record("learning", first)
        val unbound = repository.importAttachment("synthetic attachment".byteInputStream(), "synthetic.txt", "text/plain")
        val run = analysis.start(AnalysisRequest("合成来源任务", scope), "before-link")
        repository.importAttachment("synthetic unused".byteInputStream(), "unused.txt", "text/plain")
        assertEquals(AnalysisStatus.READY, analysis.task(run.stored.id).status)
        repository.updateRecord("learning", 1, RecordDraft("合成资料：附件已绑定", Occurrence(first), attachmentIds = setOf(unbound.id)))
        assertEquals(AnalysisStatus.STALE, analysis.task(run.stored.id).status)
        val bound = analysis.start(AnalysisRequest("合成附件任务", scope), "bound")
        LifeDatabase(context, name).use { it.writableDatabase.execSQL(
            "UPDATE attachments SET display_name = ? WHERE id = ?", arrayOf("synthetic-renamed.txt", unbound.id)) }
        assertEquals(AnalysisStatus.STALE, analysis.task(bound.stored.id).status)
    }

    @Test fun `deleting a moved record erases old-revision machine text without touching another task`() {
        record("learning", first); record("exercise", second)
        val learning = publish("learning-run", "learning")
        val exercise = publish("exercise-run", "exercise", scope.copy(from = second, through = second, recordIds = setOf("exercise")))
        repository.updateRecord("learning", 1, RecordDraft("合成资料：后来移动日期", Occurrence(second)))
        assertEquals(KnowledgeEvidenceState.STALE, library.get(learning.id)!!.evidenceState)
        repository.deleteRecord("learning", 2)
        assertNull(library.get(learning.id)); assertNull(repository.analysisRun("learning-run"))
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(exercise.id)!!.evidenceState)
        assertTrue(repository.history("learning").isEmpty())
    }

    @Test fun `stale task cannot narrow its scope to escape a changed range`() {
        record("learning", first)
        val task = analysis.start(AnalysisRequest("合成完整范围", scope), "immutable-scope")
        record("backfill", first)
        val narrowed = task.state.copy(request = task.state.request.copy(scope = scope.copy(recordIds = setOf("learning"))))
        assertThrows(IllegalArgumentException::class.java) {
            repository.saveAnalysisRun(task.stored.copy(version = 2, payload = AnalysisRunCodec.encode(narrowed)), 1)
        }
        assertEquals(AnalysisStatus.STALE, analysis.task(task.stored.id).status)
    }

    private fun record(id: String, date: LocalDate) = repository.createRecord(RecordDraft("合成资料：$id", Occurrence(date)), id)
    private fun emptyDirectory() = File(directory, UUID.randomUUID().toString()).apply { mkdirs() }
    private fun snapshot() = repository.captureSnapshot(emptyDirectory())
    private fun publish(id: String, record: String, query: AnalysisQuery = scope): StoredKnowledgeRevision {
        analysis.start(AnalysisRequest("合成资料整理", query), id)
        analysis.runBatch(id, "extract", listOf(EvidenceSelection(record, "body")), extractor())
        val claim = analysis.claimRef(id, "extract", 0)
        analysis.reviewClaim(id, "review", claim, provider = reviewer)
        return library.publish(id, claim, "review", KnowledgeCategory.OBSERVATION, analysis.task(id).stored.version)
    }
    private fun extractor(callback: () -> Unit = {}) = object : AnalysisProvider {
        override val id = "synthetic-scope-extractor"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            callback()
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(listOf(ProposedClaim("合成观察：用户进行了一次练习。", listOf(evidence), ClaimKind.REPORTED_EXPERIENCE)))
        }
    }
    private val reviewer = object : SemanticReviewProvider {
        override val id = "synthetic-scope-reviewer"
        override val contextWindowTokens = 100_000
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidence)))
        }
    }
}
