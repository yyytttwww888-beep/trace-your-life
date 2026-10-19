package dev.tracelife.storage

import android.content.Context
import android.database.sqlite.SQLiteException
import dev.tracelife.model.Cadence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordFilter
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.transfer.InvalidBackupException
import dev.tracelife.transfer.RepositorySnapshot
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
import java.time.ZoneOffset
import java.util.UUID

/** Every record and attachment in these transfer fixtures is synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SnapshotTransferTest {
    private lateinit var context: Context
    private lateinit var source: LifeRepository
    private lateinit var target: LifeRepository
    private lateinit var sourceName: String
    private lateinit var targetName: String
    private lateinit var workspace: File
    private val clock = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        sourceName = "synthetic-source-${UUID.randomUUID()}.db"
        targetName = "synthetic-target-${UUID.randomUUID()}.db"
        source = LifeRepository(context, clock, sourceName)
        target = LifeRepository(context, clock, targetName)
        workspace = File(context.cacheDir, "synthetic-transfer-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        if (::source.isInitialized) source.close()
        if (::target.isInitialized) target.close()
        if (::context.isInitialized && ::sourceName.isInitialized) context.deleteDatabase(sourceName)
        if (::context.isInitialized && ::targetName.isInitialized) context.deleteDatabase(targetName)
        if (::workspace.isInitialized) workspace.deleteRecursively()
    }

    @Test fun `full snapshot restores old revisions template bindings and attachment bytes in another store`() {
        val firstTemplate = source.saveTemplate(template(), "synthetic-template")
        val attachment = source.importAttachment(ByteArrayInputStream("synthetic bytes".toByteArray()), "synthetic.txt", "text/plain")
        val record = source.createRecord(RecordDraft("合成资料：原文", topics = setOf("learning"), template = TemplateRef(firstTemplate.id, 1),
            answers = mapOf("expectation" to "合成资料：原问题答案"), attachmentIds = setOf(attachment.id)), "synthetic-record")
        source.saveTemplate(template().copy(questions = listOf(TemplateQuestion("new", "合成资料：新问题"))), firstTemplate.id, 1)
        source.updateRecord(record.id, 1, RecordDraft("合成资料：修订", topics = setOf("work")))
        source.setArchived(record.id, 2, true)
        source.archiveTemplate(firstTemplate.id)
        val deletedRecord = source.createRecord(RecordDraft("合成资料：已删除"), "synthetic-deleted")
        source.deleteRecord(deletedRecord.id, 1)
        val deletedTemplate = source.saveTemplate(template(), "synthetic-deleted-template")
        source.deleteTemplate(deletedTemplate.id)
        val bytes = emptyDirectory("snapshot")
        val snapshot = source.captureSnapshot(bytes)
        val targetBefore = target.captureSnapshot(emptyDirectory("target-before"))
        val result = target.restoreSnapshot(snapshot, bytes, target.currentGeneration())
        assertEquals(1, result.records)
        assertEquals(3, result.revisions)
        assertEquals(1, result.templates)
        assertEquals(1, result.attachments)
        assertEquals(source.history(record.id), target.history(record.id))
        assertEquals(source.template(firstTemplate.id, 1), target.template(firstTemplate.id, 1))
        assertEquals(source.template(firstTemplate.id, 2), target.template(firstTemplate.id, 2))
        assertArrayEquals("synthetic bytes".toByteArray(), target.openAttachment(attachment.id).use { it.readBytes() })
        assertTrue(deletedRecord.id in target.deletedRecordIds())
        assertThrows(IllegalArgumentException::class.java) { target.saveTemplate(template(), deletedTemplate.id) }
        val restored = target.captureSnapshot(emptyDirectory("roundtrip"))
        assertEquals(targetBefore.storeId, restored.storeId)
        assertNotEquals(snapshot.storeId, restored.storeId)
        assertEquals(snapshot.records, restored.records)
        assertEquals(snapshot.templates, restored.templates)
        assertEquals(snapshot.attachments, restored.attachments)
        assertEquals(snapshot.deletedRecords, restored.deletedRecords)
        assertEquals(snapshot.deletedTemplates, restored.deletedTemplates)
        assertEquals(snapshot.changes, restored.changes)
        target.close()
        target = LifeRepository(context, clock, targetName)
        assertEquals(source.history(record.id), target.history(record.id))
    }

    @Test fun `capture omits editor imports and includes files referenced only by old revisions`() {
        val old = source.importAttachment(ByteArrayInputStream("synthetic historical".toByteArray()), "historical.txt", "text/plain")
        val draft = source.importAttachment(ByteArrayInputStream("synthetic unsaved".toByteArray()), "unsaved.txt", "text/plain")
        val record = source.createRecord(RecordDraft(attachmentIds = setOf(old.id)))
        source.updateRecord(record.id, 1, RecordDraft("合成资料：新修订无附件"))
        val files = emptyDirectory("capture")
        val snapshot = source.captureSnapshot(files)
        assertEquals(listOf(old.id), snapshot.attachments.map { it.id })
        assertEquals(listOf("${old.id}.blob"), files.list()!!.toList())
        assertNotNull(source.attachment(draft.id))
    }

    @Test fun `restoring older backup unions target deletion markers and suppresses old content`() {
        source.createRecord(RecordDraft("合成资料：旧备份内容"), "deleted-locally")
        source.createRecord(RecordDraft("合成资料：保留"), "retained")
        val files = emptyDirectory("older")
        val snapshot = source.captureSnapshot(files)
        target.createRecord(RecordDraft("合成资料：本机曾保存"), "deleted-locally")
        target.deleteRecord("deleted-locally", 1)
        val result = target.restoreSnapshot(snapshot, files, target.currentGeneration())
        assertEquals(1, result.suppressedRecords)
        assertNull(target.record("deleted-locally"))
        assertNotNull(target.record("retained"))
        assertTrue("deleted-locally" in target.deletedRecordIds())
        assertEquals(listOf("RECORD_DELETED"), target.pendingChanges().filter { it.entityId == "deleted-locally" }.map { it.kind })
        assertThrows(IllegalArgumentException::class.java) { target.createRecord(RecordDraft("合成资料：不得复活"), "deleted-locally") }
        target.captureSnapshot(emptyDirectory("after-delete-merge"))
    }

    @Test fun `deleted target template conflicting with retained review rejects restore`() {
        source.saveTemplate(template(), "same-template")
        source.createRecord(RecordDraft(template = TemplateRef("same-template", 1), answers = mapOf("expectation" to "合成资料：答案")))
        val files = emptyDirectory("template-conflict")
        val snapshot = source.captureSnapshot(files)
        target.saveTemplate(template(), "same-template")
        target.deleteTemplate("same-template")
        val before = target.currentGeneration()
        assertThrows(InvalidBackupException::class.java) { target.restoreSnapshot(snapshot, files, before) }
        assertEquals(before, target.currentGeneration())
        assertTrue(target.listRecords().items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { target.saveTemplate(template(), "same-template") }
    }

    @Test fun `newer local tombstone replaces stale deletion event once during restore`() {
        source.createRecord(RecordDraft("合成资料：旧删除"), "same-deleted-record")
        source.deleteRecord("same-deleted-record", 1)
        val files = emptyDirectory("merged-newer-deletion")
        val snapshot = source.captureSnapshot(files)
        target.createRecord(RecordDraft("合成资料：较新资料"), "same-deleted-record")
        target.updateRecord("same-deleted-record", 1, RecordDraft("合成资料：第二修订"))
        target.deleteRecord("same-deleted-record", 2)
        target.restoreSnapshot(snapshot, files, target.currentGeneration())
        val restored = target.captureSnapshot(emptyDirectory("merged-newer-result"))
        assertEquals(2, restored.deletedRecords.single().lastRevision)
        assertEquals(3, restored.changes.single().revision)
        assertEquals("RECORD_DELETED", restored.changes.single().kind)
        assertNull(target.record("same-deleted-record"))
    }

    @Test fun `nonempty target including startup templates requires explicit replacement`() {
        source.createRecord(RecordDraft("合成资料：备份记录"))
        val files = emptyDirectory("replacement")
        val snapshot = source.captureSnapshot(files)
        target.saveTemplate(template(), "synthetic-builtin")
        val before = target.currentGeneration()
        assertThrows(IllegalStateException::class.java) { target.restoreSnapshot(snapshot, files, before) }
        assertNotNull(target.template("synthetic-builtin"))
        assertEquals(before, target.currentGeneration())
        assertEquals(1, target.restoreSnapshot(snapshot, files, before, replaceExisting = true).records)
        assertNull(target.template("synthetic-builtin"))
    }

    @Test fun `malformed chains missing references and tampered bytes never change target`() {
        val imported = source.importAttachment(ByteArrayInputStream("synthetic authentic".toByteArray()), "synthetic.txt", "text/plain")
        val record = source.createRecord(RecordDraft("合成资料：有附件", attachmentIds = setOf(imported.id)))
        source.updateRecord(record.id, 1, RecordDraft("合成资料：版本二", attachmentIds = setOf(imported.id)))
        val files = emptyDirectory("invalid")
        val snapshot = source.captureSnapshot(files)
        val previous = target.createRecord(RecordDraft("合成资料：现有资料"))
        val before = target.currentGeneration()
        assertThrows(InvalidBackupException::class.java) { target.restoreSnapshot(snapshot.copy(records = snapshot.records.drop(1)), files, before, true) }
        val missingTemplate = snapshot.copy(records = snapshot.records.map { it.copy(draft = it.draft.copy(template = TemplateRef("missing", 1))) })
        assertThrows(InvalidBackupException::class.java) { target.restoreSnapshot(missingTemplate, files, before, true) }
        File(files, "${imported.id}.blob").writeText("synthetic corrupted")
        assertThrows(InvalidBackupException::class.java) { target.restoreSnapshot(snapshot, files, before, true) }
        assertEquals(before, target.currentGeneration())
        assertEquals(previous, target.record(previous.id))
        assertNull(target.record(record.id))
    }

    @Test fun `stale restore token refuses changes made since user reviewed target`() {
        source.createRecord(RecordDraft("合成资料：备份"))
        val files = emptyDirectory("stale")
        val snapshot = source.captureSnapshot(files)
        val generation = target.currentGeneration()
        val later = target.createRecord(RecordDraft("合成资料：后来新增"))
        assertThrows(StaleSnapshotException::class.java) { target.restoreSnapshot(snapshot, files, generation, true) }
        assertEquals(later, target.record(later.id))
    }

    @Test fun `transaction failure preserves old database and files and removes restored orphan files`() {
        val incoming = source.importAttachment(ByteArrayInputStream("synthetic incoming".toByteArray()), "new.txt", "text/plain")
        source.createRecord(RecordDraft("synthetic reject restore", attachmentIds = setOf(incoming.id)))
        val files = emptyDirectory("transaction")
        val snapshot = source.captureSnapshot(files)
        val oldAttachment = target.importAttachment(ByteArrayInputStream("synthetic old".toByteArray()), "old.txt", "text/plain")
        val oldRecord = target.createRecord(RecordDraft("合成资料：原资料", attachmentIds = setOf(oldAttachment.id)))
        LifeDatabase(context, targetName).use { helper ->
            helper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_reject_restore BEFORE INSERT ON record_revisions
                WHEN NEW.body = 'synthetic reject restore' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END""")
        }
        val generation = target.currentGeneration()
        val oldFile = File(context.filesDir, "attachments").walkTopDown().first { it.name == "${oldAttachment.id}.blob" }
        assertThrows(SQLiteException::class.java) { target.restoreSnapshot(snapshot, files, generation, true) }
        assertEquals(generation, target.currentGeneration())
        assertEquals(oldRecord, target.record(oldRecord.id))
        assertArrayEquals("synthetic old".toByteArray(), target.openAttachment(oldAttachment.id).use { it.readBytes() })
        assertFalse(File(oldFile.parentFile, "${incoming.id}.blob").exists())
    }

    @Test fun `snapshot publication checks store generation paths and destination before atomic link`() {
        val record = source.createRecord(RecordDraft("合成资料：发布"))
        val snapshot = source.captureSnapshot(emptyDirectory("publish-snapshot"))
        val stage = File(workspace, "output.stage").apply { writeText("synthetic encrypted backup") }
        val destination = File(workspace, "output.backup")
        assertThrows(StaleSnapshotException::class.java) { target.publishSnapshot(snapshot, stage, destination) }
        source.updateRecord(record.id, 1, RecordDraft("合成资料：发布前更改"))
        assertThrows(StaleSnapshotException::class.java) { source.publishSnapshot(snapshot, stage, destination) }
        assertFalse(destination.exists())
        assertTrue(stage.exists())
        val current = source.captureSnapshot(emptyDirectory("current-snapshot"))
        destination.writeText("synthetic existing export")
        assertThrows(IllegalArgumentException::class.java) { source.publishSnapshot(current, stage, destination) }
        assertEquals("synthetic existing export", destination.readText())
        destination.delete()
        assertThrows(IllegalArgumentException::class.java) { source.publishSnapshot(current, stage, context.getDatabasePath("bad.backup")) }
        source.publishSnapshot(current, stage, destination)
        assertEquals("synthetic encrypted backup", destination.readText())
        assertTrue(stage.delete()) // The staging file remains caller-owned after atomic publication.
        assertEquals("synthetic encrypted backup", destination.readText())
    }

    @Test fun `generation advances only for committed changes and survives reopen`() {
        val before = source.currentGeneration()
        source.listRecords(RecordFilter())
        source.captureSnapshot(emptyDirectory("read-only"))
        assertEquals(before, source.currentGeneration())
        val record = source.createRecord(RecordDraft("合成资料：写入"))
        val after = source.currentGeneration()
        assertTrue(after > before)
        source.createRecord(record.draft, record.id)
        assertEquals(after, source.currentGeneration())
        assertThrows(IllegalArgumentException::class.java) { source.updateRecord(record.id, 1, RecordDraft("合成资料：失败", attachmentIds = setOf("missing"))) }
        assertEquals(after, source.currentGeneration())
        source.close()
        source = LifeRepository(context, clock, sourceName)
        assertEquals(after, source.currentGeneration())
    }

    @Test fun `capture checks SQLite byte budget before materializing oversized row`() {
        val record = source.createRecord(RecordDraft("合成资料：预算检查"))
        LifeDatabase(context, sourceName).use { helper ->
            // SQLite synthesizes these bytes; do not allocate a huge Kotlin String or cursor row.
            helper.writableDatabase.execSQL("UPDATE record_revisions SET body = zeroblob(?) WHERE record_id = ?",
                arrayOf<Any>(64 * 1024 * 1024 + 1, record.id))
        }
        val directory = emptyDirectory("over-budget")
        val failure = assertThrows(IllegalArgumentException::class.java) { source.captureSnapshot(directory) }
        assertTrue(failure.message!!.contains("64 MiB"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `version one migration preserves data and reconstructs deleted template tombstone`() {
        val record = source.createRecord(RecordDraft("合成资料：版本一资料"))
        val deleted = source.saveTemplate(template(), "synthetic-v1-deleted")
        source.deleteTemplate(deleted.id)
        source.close()
        // Remove v2/v3/v4/v5/v6 additions to recreate the released v1 schema and its ID-only outbox.
        LifeDatabase(context, sourceName).use { helper ->
            val db = helper.writableDatabase
            val triggers = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'generation_%'", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            triggers.forEach { db.execSQL("DROP TRIGGER $it") }
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
            db.execSQL("DROP TABLE store_state")
            db.execSQL("DROP TABLE deleted_templates")
            db.version = 1
        }
        source = LifeRepository(context, clock, sourceName)
        assertEquals(record, source.record(record.id))
        assertThrows(IllegalArgumentException::class.java) { source.saveTemplate(template(), deleted.id) }
        val migrated = source.captureSnapshot(emptyDirectory("migrated"))
        assertEquals(listOf(deleted.id), migrated.deletedTemplates.map { it.id })
        val baseline = source.currentGeneration()
        source.updateRecord(record.id, 1, RecordDraft("合成资料：升级后编辑"))
        assertTrue(source.currentGeneration() > baseline)
    }

    private fun emptyDirectory(name: String) = File(workspace, name).apply { check(mkdirs()) }
    private fun template() = TemplateDraft("合成资料：模板", Cadence.DAILY, questions = listOf(TemplateQuestion("expectation", "合成资料：预期？")), sourceMarkdown = "# 合成资料：模板\n## 合成资料：预期？")
}
