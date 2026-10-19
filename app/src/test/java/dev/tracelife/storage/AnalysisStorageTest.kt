package dev.tracelife.storage

import android.content.Context
import android.database.sqlite.SQLiteException
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.AnalysisCall
import dev.tracelife.analysis.AnalysisLimits
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.CallStatus
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.model.Cadence
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.transfer.StaleSnapshotException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic records, analysis payloads and attachment bytes only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AnalysisStorageTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var repository: LifeRepository
    private lateinit var workspace: File
    private val clock = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC)
    private val query = AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        name = "synthetic-analysis-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, name)
        workspace = File(context.cacheDir, "synthetic-analysis-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(name)
        workspace.deleteRecursively()
    }

    @Test fun `metadata pagination includes unclassified and mistagged records with unknown and overlapping dates`() {
        dated("within", "2026-10-14")
        repository.createRecord(RecordDraft("合成资料：跨周记录", Occurrence(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-13")), setOf("unrelated")), "overlap")
        repository.createRecord(RecordDraft("合成资料：时间未填写"), "unknown")
        dated("outside", "2026-10-11")
        dated("archived", "2026-10-16")
        repository.setArchived("archived", 1, true)
        val stamp = repository.analysisSourceStamp()
        val first = repository.analysisSourcePage(stamp, query, 0, 1)
        assertEquals(3, first.total)
        assertEquals(1, first.unknown)
        assertEquals(1, first.overlap)
        assertEquals("overlap", first.items.single().id)
        assertEquals(SourceTimeClass.OVERLAP, first.items.single().timeClass)
        val second = repository.analysisSourcePage(stamp, query, first.nextOffset!!, 1)
        assertEquals("within", second.items.single().id)
        assertTrue(second.items.single().topics.isEmpty())
        val third = repository.analysisSourcePage(stamp, query, second.nextOffset!!, 1)
        assertEquals(SourceTimeClass.UNKNOWN, third.items.single().timeClass)
        assertNull(third.nextOffset)
        assertEquals(4, repository.analysisSourcePage(stamp, query.copy(includeArchived = true), 0, 100).total)
        assertEquals(listOf("within"), repository.analysisSourcePage(stamp, query.copy(recordIds = setOf("within", "missing", "outside")), 0, 100).items.map { it.id })
    }

    @Test fun `field inventory and Unicode slices keep body answers and unread attachments distinct`() {
        val template = repository.saveTemplate(template())
        val attachment = repository.importAttachment(ByteArrayInputStream("synthetic bytes".toByteArray()), "synthetic.txt", "text/plain")
        repository.createRecord(RecordDraft("合😀成🚀尾", template = TemplateRef(template.id, 1),
            answers = mapOf("q" to "答😀案", "empty" to ""), attachmentIds = setOf(attachment.id)), "unicode")
        val stamp = repository.analysisSourceStamp()
        val fields = repository.analysisSourceFields(stamp, query, "unicode")
        assertEquals(listOf("body", "answer:q", "attachment:${attachment.id}"), fields.map { it.key })
        assertEquals(listOf(5, 3, 0), fields.map { it.codePoints })
        assertEquals(SourceFieldKind.ATTACHMENT, fields.last().kind)
        assertEquals(attachment.id, fields.last().attachmentId)
        val fragment = repository.analysisSourceFragment(stamp, query, "unicode", "body", 1, 3)
        assertEquals("😀成🚀", fragment.text)
        assertEquals(1, fragment.start)
        assertEquals(4, fragment.end)
        assertEquals(5, fragment.totalCodePoints)
        val tail = repository.analysisSourceFragment(stamp, query, "unicode", "body", 4, 4096)
        assertEquals("尾", tail.text)
        assertEquals(5, tail.end)
        val answer = repository.analysisSourceFragment(stamp, query, "unicode", "answer:q", 1, 1)
        assertEquals("😀", answer.text)
        assertEquals("合成资料：问题？", answer.question)
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourceFragment(stamp, query, "unicode", "attachment:${attachment.id}", 0, 1) }
    }

    @Test fun `direct field reads cannot bypass date ID archive snapshot or chunk bounds`() {
        dated("inside", "2026-10-14")
        dated("outside", "2026-10-11")
        val stamp = repository.analysisSourceStamp()
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourceFields(stamp, query, "outside") }
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourceFields(stamp, query.copy(recordIds = setOf("outside")), "inside") }
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourceFragment(stamp, query, "inside", "body", -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourceFragment(stamp, query, "inside", "body", 0, 4097) }
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourceFragment(stamp, query, "inside", "body", Int.MAX_VALUE, 1) }
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourcePage(stamp, query, 0, 101) }
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourcePage(stamp, query.copy(recordIds = emptySet()), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourcePage(stamp, query.copy(from = LocalDate.of(0, 1, 1)), 0, 1) }
        repository.setArchived("inside", 1, true)
        assertThrows(StaleSnapshotException::class.java) { repository.analysisSourcePage(stamp, query, 0, 1) }
        assertThrows(StaleSnapshotException::class.java) { repository.analysisSourceFields(stamp, query, "inside") }
        assertThrows(StaleSnapshotException::class.java) { repository.analysisSourceFragment(stamp, query, "inside", "body", 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { repository.analysisSourceFields(repository.analysisSourceStamp(), query, "inside") }
    }

    @Test fun `embedded NUL is reported unreadable rather than silently completing only prefix`() {
        repository.createRecord(RecordDraft("合成资料\u0000关键反例"), "nul")
        val stamp = repository.analysisSourceStamp()
        assertEquals(1, repository.analysisSourcePage(stamp, query, 0, 1).total)
        assertThrows(UnreadableSourceException::class.java) { repository.analysisSourceFields(stamp, query, "nul") }
        assertThrows(UnreadableSourceException::class.java) { repository.analysisSourceFragment(stamp, query, "nul", "body", 0, 10) }
    }

    @Test fun `durable analysis CAS survives reopening without changing source generation`() {
        dated("inside", "2026-10-14")
        val run = run()
        val source = repository.analysisSourceStamp()
        val before = repository.currentAnalysisGeneration()
        assertEquals(run, repository.saveAnalysisRun(run, null))
        assertEquals(source, repository.analysisSourceStamp())
        assertTrue(repository.currentAnalysisGeneration() > before)
        LifeRepository(context, clock, name).use { other ->
            val next = run.copy(version = 2)
            other.saveAnalysisRun(next, 1)
            assertThrows(RevisionConflictException::class.java) { repository.saveAnalysisRun(next, 1) }
            assertThrows(RevisionConflictException::class.java) { repository.saveAnalysisRun(run, null) }
        }
        repository.close()
        repository = LifeRepository(context, clock, name)
        assertEquals(2, repository.analysisRun(run.id)!!.version)
        assertEquals(listOf(run.id), repository.analysisRuns(0, 1).map { it.id })
        assertTrue(repository.analysisRuns(1, 1).isEmpty())
        repository.updateRecord("inside", 1, RecordDraft("合成资料：修订"))
        assertThrows(StaleSnapshotException::class.java) { repository.saveAnalysisRun(run.copy(version = 3), 2) }
        assertEquals(2, repository.analysisRun(run.id)!!.version)
    }

    @Test fun `analysis writes invalidate export and restore preview without staling source plan`() {
        val snapshot = repository.captureSnapshot(emptyDirectory("snapshot"))
        val sourceGeneration = repository.currentGeneration()
        val analysisGeneration = repository.currentAnalysisGeneration()
        repository.saveAnalysisRun(run(), null)
        val stage = File(workspace, "synthetic.stage").apply { writeText("synthetic backup") }
        val destination = File(workspace, "synthetic.backup")
        assertThrows(StaleSnapshotException::class.java) { repository.publishSnapshot(snapshot, stage, destination) }
        assertFalse(destination.exists())
        assertThrows(StaleSnapshotException::class.java) {
            repository.restoreSnapshot(snapshot, emptyDirectory("empty"), sourceGeneration, true, analysisGeneration)
        }
        assertThrows(IllegalStateException::class.java) { repository.restoreSnapshot(snapshot, emptyDirectory("empty-2"), sourceGeneration) }
        assertEquals(1, repository.analysisRuns(0, 100).size)
    }

    @Test fun `record or template deletion purges all saved analyses in the same transaction`() {
        dated("inside", "2026-10-14")
        val first = run()
        repository.saveAnalysisRun(first, null)
        LifeDatabase(context, name).use { helper ->
            helper.writableDatabase.execSQL("CREATE TRIGGER synthetic_reject_purge BEFORE DELETE ON analysis_runs BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        }
        assertThrows(SQLiteException::class.java) { repository.deleteRecord("inside", 1) }
        assertNotNull(repository.record("inside"))
        assertEquals(first, repository.analysisRun(first.id))
        LifeDatabase(context, name).use { it.writableDatabase.execSQL("DROP TRIGGER synthetic_reject_purge") }
        repository.deleteRecord("inside", 1)
        assertNull(repository.analysisRun(first.id))
        val template = repository.saveTemplate(template())
        val second = run("second")
        repository.saveAnalysisRun(second, null)
        repository.deleteTemplate(template.id)
        assertNull(repository.analysisRun(second.id))
        assertThrows(StaleSnapshotException::class.java) { repository.saveAnalysisRun(second.copy(version = 2), 1) }
    }

    @Test fun `restore preserves historical run identity and extra local deletions suppress imported analyses`() {
        dated("inside", "2026-10-14")
        val run = run()
        repository.saveAnalysisRun(run, null)
        val files = emptyDirectory("snapshot")
        val snapshot = repository.captureSnapshot(files)
        val targetName = "synthetic-target-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                target.restoreSnapshot(snapshot, files, target.currentGeneration())
                assertEquals(run, target.analysisRun(run.id))
                assertNotEquals(run.source, target.analysisSourceStamp())
                assertThrows(StaleSnapshotException::class.java) { target.saveAnalysisRun(run.copy(version = 2), 1) }
                target.deleteRecord("inside", 1)
                target.restoreSnapshot(snapshot, files, target.currentGeneration())
                assertNull(target.record("inside"))
                assertTrue(target.analysisRuns(0, 100).isEmpty())
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `bounded saved analysis count rejects overflow without changing generations`() {
        repeat(100) { repository.saveAnalysisRun(run("synthetic-$it"), null) }
        val source = repository.analysisSourceStamp()
        val before = repository.currentAnalysisGeneration()
        assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisRun(run("overflow"), null) }
        assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisRun(run("huge").copy(payload = "x".repeat(2 * 1024 * 1024 + 1)), null) }
        assertEquals(before, repository.currentAnalysisGeneration())
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(100, repository.analysisRuns(0, 100).size)
        repository.deleteAnalysisRun("synthetic-0", 1)
        repository.saveAnalysisRun(run("overflow"), null)
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(100, repository.analysisRuns(0, 100).size)
        assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisRun(run("synthetic-0"), null) }
    }

    @Test fun `task deletion uses CAS and tombstones prevent old backup or in flight update resurrection`() {
        dated("inside", "2026-10-14")
        val deleted = run("delete-me")
        val kept = run("keep-me")
        repository.saveAnalysisRun(deleted, null)
        repository.saveAnalysisRun(kept, null)
        val files = emptyDirectory("before-task-delete")
        val before = repository.captureSnapshot(files)
        val stamp = repository.analysisSourceStamp()
        assertThrows(RevisionConflictException::class.java) { repository.deleteAnalysisRun(deleted.id, 2) }
        repository.deleteAnalysisRun(deleted.id, 1)
        assertEquals(stamp, repository.analysisSourceStamp())
        assertNull(repository.analysisRun(deleted.id))
        assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisRun(deleted.copy(version = 2), 1) }
        assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisRun(deleted, null) }
        repository.restoreSnapshot(before, files, repository.currentGeneration(), true)
        assertNull(repository.analysisRun(deleted.id))
        assertEquals(kept, repository.analysisRun(kept.id))
        assertEquals(listOf(deleted.id), repository.captureSnapshot(emptyDirectory("after-task-delete")).deletedAnalysisRuns.map { it.id })
        repository.close()
        repository = LifeRepository(context, clock, name)
        assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisRun(run(deleted.id), null) }
        // Historical tasks remain removable after restore changes the source stamp.
        repository.deleteAnalysisRun(kept.id, 1)
        assertTrue(repository.analysisRuns(0, 100).isEmpty())
    }

    @Test fun `in flight response reservations cannot be consumed by another saved task`() {
        dated("synthetic-source", "2026-10-14")
        val pending = (0 until 8).map { largeRun("synthetic-reserved-$it", 48, true) }
        pending.forEach { repository.saveAnalysisRun(it, null) }
        val occupied = pending.sumOf { it.payload.toByteArray(Charsets.UTF_8).size.toLong() }
        val reserved = pending.sumOf { AnalysisRunCodec.reservedResponseBytes(it).toLong() }
        val remainingWithoutReserve = 16L * 1024 * 1024 - occupied
        val remainingWithReserve = remainingWithoutReserve - reserved
        val candidate = (1..48).asSequence().map { largeRun("synthetic-contender", it, false) }.first {
            it.payload.toByteArray(Charsets.UTF_8).size > remainingWithReserve
        }
        assertTrue(candidate.payload.toByteArray(Charsets.UTF_8).size < remainingWithoutReserve)
        val before = repository.currentAnalysisGeneration()
        assertThrows(IllegalArgumentException::class.java) { repository.saveAnalysisRun(candidate, null) }
        assertEquals(before, repository.currentAnalysisGeneration())
        // The first task can still commit its output while every other reservation remains held.
        val first = pending.first()
        val state = AnalysisRunCodec.decode(first.payload)
        val completed = state.copy(calls = state.calls.dropLast(1) + state.calls.last().copy(
            status = CallStatus.SUCCEEDED, finishedAt = clock.instant(), proposal = largeProposal()))
        val finished = first.copy(version = 2, payload = AnalysisRunCodec.encode(completed))
        repository.saveAnalysisRun(finished, 1)
        assertEquals(finished, repository.analysisRun(first.id))
        repository.deleteAnalysisRun(pending.last().id, 1)
        assertEquals(candidate, repository.saveAnalysisRun(candidate, null))
    }

    @Test fun `version two migration preserves source identity generation and existing records`() {
        val record = dated("inside", "2026-10-14")
        val source = repository.analysisSourceStamp()
        repository.close()
        LifeDatabase(context, name).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("DROP TABLE dashboard_preferences")
            db.execSQL("DROP TABLE profile_heads")
            db.execSQL("DROP TABLE profile_revisions")
            db.execSQL("DROP TABLE deleted_profiles")
            db.execSQL("DROP TABLE action_heads")
            db.execSQL("DROP TABLE action_revisions")
            db.execSQL("DROP TABLE deleted_actions")
            db.execSQL("DROP TABLE knowledge_heads")
            db.execSQL("DROP TABLE knowledge_revisions")
            db.execSQL("DROP TABLE deleted_knowledge")
            db.execSQL("DROP TABLE claim_feedback")
            db.execSQL("DROP TABLE deleted_claim_feedback")
            db.execSQL("DROP TABLE analysis_runs")
            db.execSQL("DROP TABLE deleted_analysis_runs")
            // Recreate exactly the released v2 store_state while keeping all source tables/triggers.
            db.execSQL("DROP TABLE store_state")
            db.execSQL("CREATE TABLE store_state (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), store_id TEXT NOT NULL, generation INTEGER NOT NULL CHECK(generation >= 0))")
            db.execSQL("INSERT INTO store_state VALUES(1, ?, ?)", arrayOf<Any>(source.storeId, source.generation))
            db.version = 2
        }
        repository = LifeRepository(context, clock, name)
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(record, repository.record(record.id))
        assertEquals(0L, repository.currentAnalysisGeneration())
        repository.saveAnalysisRun(run(), null)
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(1L, repository.currentAnalysisGeneration())
    }

    private fun dated(id: String, date: String) = repository.createRecord(RecordDraft("合成资料：$id", Occurrence(LocalDate.parse(date))), id)
    private fun template() = TemplateDraft("合成资料：模板", Cadence.DAILY, questions = listOf(
        TemplateQuestion("q", "合成资料：问题？"), TemplateQuestion("empty", "合成资料：空答案？")))
    private fun emptyDirectory(name: String) = File(workspace, name).apply { check(mkdirs()) }
    private fun run(id: String = "synthetic-run") = StoredAnalysisRun(id, 1, repository.analysisSourceStamp(), clock.instant(), clock.instant(), null,
        AnalysisRunCodec.encode(AnalysisRunState(AnalysisRequest("合成资料：本周观察", query))))

    private fun largeProposal() = BatchProposal(emptyList(), List(20) { "synthetic:" + "x".repeat(1_990) })
    private fun largeRun(id: String, finishedCalls: Int, inFlight: Boolean): StoredAnalysisRun {
        val hash = AnalysisRunCodec.sha256("合")
        val ref = EvidenceReceipt(AnalysisRunCodec.evidenceId("synthetic-source", 1, "body", 0, 1, hash),
            "synthetic-source", 1, "body", 0, 1, "合成资料：synthetic-source".length, hash)
        val calls = (0 until finishedCalls).map {
            AnalysisCall("synthetic-batch-$it", 1, "synthetic-provider", "a".repeat(64), 1, 1, listOf(ref), clock.instant(),
                CallStatus.SUCCEEDED, clock.instant(), proposal = largeProposal())
        }.toMutableList()
        if (inFlight) calls += AnalysisCall("synthetic-pending", 1, "synthetic-provider", "b".repeat(64), 1, 1,
            listOf(ref), clock.instant(), CallStatus.IN_FLIGHT)
        val state = AnalysisRunState(AnalysisRequest("合成资料：保存容量测试", query, AnalysisLimits(maxOutputBytes = 65_536)), calls)
        return run(id).copy(payload = AnalysisRunCodec.encode(state))
    }
}
