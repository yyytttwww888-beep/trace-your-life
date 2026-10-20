package dev.tracelife.storage

import android.content.Context
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.ControlledAnalysis
import dev.tracelife.conversation.ConversationTurn
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.transfer.BackupArchive
import dev.tracelife.transfer.DataTransfer
import dev.tracelife.transfer.InvalidBackupException
import dev.tracelife.transfer.SnapshotJsonCodec
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic personal questions only; no model or network calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationStorageTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var name: String
    private lateinit var root: File
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-20")
    private val scope = AnalysisQuery(day.minusMonths(3), day, "Asia/Shanghai")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication(); name = "synthetic-conversation-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, name)
        root = File(context.cacheDir, "synthetic-conversation-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name); root.deleteRecursively() }

    @Test fun `questions survive restart with fixed scope pagination and idempotent retries`() {
        repository.createConversation("thread")
        val first = add("first")
        val second = add("second", position = 2, parent = first.id)
        assertEquals(first, repository.appendConversationTurn(first))
        assertThrows(IllegalArgumentException::class.java) { repository.appendConversationTurn(first.copy(question = "合成不同的问题")) }
        repository.close(); repository = LifeRepository(context, clock, name)
        assertEquals(listOf(first), repository.conversationTurns("thread", limit = 1))
        assertEquals(listOf(second), repository.conversationTurns("thread", offset = 1, limit = 1))
        assertEquals(second, repository.latestConversationTurn("thread"))
        assertEquals(listOf("thread"), repository.conversationPage().map { it.id })
        assertEquals(first, repository.conversationTurn(first.id))
    }

    @Test fun `history sorts within the same second and updating an older thread moves it first after restart and restore`() {
        val base = clock.instant()
        repository.createConversation("at-zero")
        clock.now = base.plusMillis(100); repository.createConversation("at-one")
        clock.now = base.plusMillis(900); repository.createConversation("at-nine")
        assertEquals(listOf("at-nine", "at-one", "at-zero"), repository.conversationPage().map { it.id })
        assertEquals(listOf("at-one"), repository.conversationPage(offset = 1, limit = 1).map { it.id })
        clock.now = base.plusMillis(950)
        val update = add("latest", thread = "at-zero")
        val expected = listOf("at-zero", "at-nine", "at-one")
        assertEquals(expected, repository.conversationPage().map { it.id })
        assertEquals(update.createdAt, repository.conversation("at-zero")!!.updatedAt)
        repository.close(); repository = LifeRepository(context, clock, name)
        assertEquals(expected, repository.conversationPage().map { it.id })
        val snapshot = repository.captureSnapshot(directory())
        repository.restoreSnapshot(snapshot, directory(), repository.currentGeneration(), true)
        assertEquals(expected, repository.conversationPage().map { it.id })
    }

    @Test fun `clock rollback refuses a backwards question without changing the independent question chain`() {
        repository.createConversation("thread")
        val first = add("first")
        val before = repository.conversation("thread")
        clock.now = clock.instant().minusSeconds(1)
        val candidate = prepare("backwards", "thread", 2, first.id)
        assertThrows(IllegalArgumentException::class.java) { repository.appendConversationTurn(candidate) }
        assertEquals(before, repository.conversation("thread"))
        assertEquals(listOf(first), repository.conversationTurns("thread"))
        assertNull(repository.conversationTurn(candidate.id))
    }

    @Test fun `deleted source and task keep independent questions while explicit conversation deletion erases answers`() {
        repository.createRecord(RecordDraft("合成学习记录", Occurrence(day)), "source")
        repository.createConversation("thread")
        val first = add("first")
        repository.deleteAnalysisRun(first.runId, repository.analysisRun(first.runId)!!.version)
        repository.deleteRecord("source", 1)
        assertEquals(first, repository.conversationTurn(first.id))
        assertEquals(first, repository.appendConversationTurn(first))
        val second = add("second", position = 2, parent = first.id)
        repository.deleteConversation("thread")
        assertNull(repository.conversation("thread")); assertNull(repository.conversationTurn(first.id))
        assertNull(repository.analysisRun(second.runId))
        assertThrows(IllegalArgumentException::class.java) { repository.createConversation("thread") }
        repository.deleteConversation("thread")
    }

    @Test fun `append rejects foreign parent mismatched scope and reused analysis ownership without partial writes`() {
        repository.createConversation("thread"); repository.createConversation("other")
        val first = add("first")
        val candidate = prepare("invalid", "other", 1, first.id)
        assertThrows(IllegalArgumentException::class.java) { repository.appendConversationTurn(candidate) }
        assertTrue(repository.conversationTurns("other").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { repository.appendConversationTurn(candidate.copy(parentTurnId = null, scope = scope.copy(through = day.minusDays(1)))) }
        assertThrows(IllegalArgumentException::class.java) { repository.appendConversationTurn(candidate.copy(parentTurnId = null, runId = first.runId)) }
        assertEquals(listOf(first), repository.conversationTurns("thread"))
    }

    @Test fun `encrypted backup retains questions references and deletion ledger and is portable`() {
        repository.createConversation("thread")
        val first = add("first"); add("second", position = 2, parent = first.id)
        repository.createConversation("erased"); repository.deleteConversation("erased")
        val attachments = directory()
        val snapshot = repository.captureSnapshot(attachments)
        val archive = File(root, "synthetic.tlb")
        val password = "synthetic only passphrase".toCharArray()
        BackupArchive.write(snapshot, attachments, archive, password)
        val decoded = BackupArchive.read(archive, directory(), password)
        assertEquals(snapshot, decoded)
        val targetName = "synthetic-restored-${UUID.randomUUID()}.db"
        LifeRepository(context, clock, targetName).use { target ->
            target.restoreSnapshot(decoded, directory(), target.currentGeneration())
            assertEquals(repository.conversationTurns("thread"), target.conversationTurns("thread"))
            assertEquals(snapshot.deletedConversations, target.captureSnapshot(directory()).deletedConversations)
        }
        context.deleteDatabase(targetName)
    }

    @Test fun `restoring old backup cannot resurrect deleted thread or its independently missing answer`() {
        repository.createConversation("thread")
        val turn = add("first")
        val snapshot = repository.captureSnapshot(directory())
        repository.deleteAnalysisRun(turn.runId, repository.analysisRun(turn.runId)!!.version)
        repository.deleteConversation("thread")
        repository.restoreSnapshot(snapshot, directory(), repository.currentGeneration(), true)
        assertNull(repository.conversation("thread")); assertNull(repository.analysisRun(turn.runId))
        assertTrue(repository.captureSnapshot(directory()).deletedConversations.any { it.id == "thread" })
    }

    @Test fun `local independent question chain wins over later backup chain and discarded answer cannot reappear`() {
        repository.createConversation("thread")
        val first = add("first")
        val old = repository.captureSnapshot(directory())
        val second = add("second", position = 2, parent = first.id)
        val later = repository.captureSnapshot(directory())
        val targetName = "synthetic-local-${UUID.randomUUID()}.db"
        LifeRepository(context, clock, targetName).use { target ->
            target.restoreSnapshot(old, directory(), target.currentGeneration())
            target.restoreSnapshot(later, directory(), target.currentGeneration(), true)
            assertEquals(listOf(first), target.conversationTurns("thread"))
            assertNull(target.analysisRun(second.runId))
            assertTrue(target.captureSnapshot(directory()).deletedAnalysisRuns.any { it.id == second.runId })
        }
        context.deleteDatabase(targetName)
    }

    @Test fun `conversation only target needs replacement and preview invalidates after new question`() {
        val snapshot = repository.captureSnapshot(directory())
        repository.createConversation("thread")
        assertThrows(IllegalStateException::class.java) { repository.restoreSnapshot(snapshot, directory(), repository.currentGeneration()) }
        val archive = File(root, "preview.tlb")
        val password = "synthetic only passphrase".toCharArray()
        BackupArchive.write(snapshot, directory(), archive, password)
        val transfer = DataTransfer(context, repository, clock)
        val preview = transfer.previewBackup(archive, password)
        assertTrue(preview.targetHasData)
        add("first")
        assertThrows(dev.tracelife.transfer.StaleSnapshotException::class.java) { transfer.restoreBackup(archive, password, preview, true) }
    }

    @Test fun `released backup version nine omits conversations and refuses newer fields`() {
        val snapshot = repository.captureSnapshot(directory())
        val encoded = ByteArrayOutputStream().also { SnapshotJsonCodec.write(snapshot, it) }.toString(Charsets.UTF_8)
        val json = JSONObject(encoded).put("formatVersion", 9).put("sourceSchemaVersion", 10)
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(json.toString().byteInputStream()) }
        listOf("conversations", "conversationTurns", "deletedConversations").forEach(json::remove)
        assertEquals(snapshot.copy(sourceSchemaVersion = 10), SnapshotJsonCodec.read(json.toString().byteInputStream()))
    }

    @Test fun `schema ten upgrades without rewriting records or analyses`() {
        repository.createRecord(RecordDraft("合成迁移记录", Occurrence(day)), "source")
        ControlledAnalysis(repository, clock).start(AnalysisRequest("合成分析", scope), "legacy")
        val source = repository.analysisSourceStamp(); val before = repository.analysisRun("legacy")
        val generation = repository.currentAnalysisGeneration()
        repository.close()
        LifeDatabase(context, name).use { helper ->
            removeVersionElevenForMigration(helper.writableDatabase); helper.writableDatabase.version = 10
        }
        repository = LifeRepository(context, clock, name)
        assertEquals(source, repository.analysisSourceStamp()); assertEquals(before, repository.analysisRun("legacy"))
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertTrue(repository.conversationPage().isEmpty()); repository.createConversation("new")
        assertEquals(11, repository.captureSnapshot(directory()).sourceSchemaVersion)
    }

    @Test fun `restore rejects wrong parent order tampered question and malformed payload atomically`() {
        repository.createConversation("thread")
        val first = add("first"); val second = add("second", position = 2, parent = first.id)
        val snapshot = repository.captureSnapshot(directory())
        listOf(
            snapshot.copy(conversationTurns = listOf(first.copy(parentTurnId = second.id), second)),
            snapshot.copy(conversationTurns = listOf(first.copy(question = "合成被替换问题"), second)),
            snapshot.copy(conversationTurns = listOf(first, second.copy(position = 3))),
        ).forEach { malformed -> assertThrows(InvalidBackupException::class.java) { repository.restoreSnapshot(malformed, directory(), repository.currentGeneration(), true) } }
        assertEquals(listOf(first, second), repository.conversationTurns("thread"))
    }

    private fun add(id: String, thread: String = "thread", position: Int = 1, parent: String? = null) =
        repository.appendConversationTurn(prepare(id, thread, position, parent))
    private fun prepare(id: String, thread: String, position: Int, parent: String?): ConversationTurn {
        val question = "合成问题 $id：学习方法有哪些变化？"
        val runId = "reflection-v1-conversation-$id"
        ControlledAnalysis(repository, clock).start(AnalysisRequest(question + "\n合成回答指引", scope, focusTopics = setOf("learning", "work")), runId)
        return ConversationTurn(id, thread, position, question, scope, setOf("learning"), parent, null, runId, clock.instant())
    }
    private class MutableClock(var now: Instant = Instant.parse("2026-10-20T04:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }
    private fun directory() = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
}
