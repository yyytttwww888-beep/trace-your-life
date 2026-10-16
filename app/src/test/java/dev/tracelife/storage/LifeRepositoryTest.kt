package dev.tracelife.storage

import android.content.Context
import android.database.sqlite.SQLiteException
import dev.tracelife.model.Cadence
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordFilter
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.templates.TemplateImporter
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
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** All record text and attachment bytes in this suite are synthetic fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LifeRepositoryTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var repository: LifeRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        name = "synthetic-${UUID.randomUUID()}.db"
        repository = LifeRepository(context, clock, name)
    }

    @After fun tearDown() {
        if (::repository.isInitialized) repository.close()
        if (::context.isInitialized && ::name.isInitialized) context.deleteDatabase(name)
    }

    @Test fun `record and references survive reopening while entry and occurrence time remain distinct`() {
        val template = repository.saveTemplate(templateDraft())
        val photo = repository.importAttachment(ByteArrayInputStream("synthetic image".toByteArray()), "synthetic.txt", "text/plain")
        val draft = RecordDraft(
            body = "合成资料：补记一段学习经历",
            occurrence = Occurrence(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"), "Asia/Shanghai"),
            topics = setOf("learning", "work"),
            template = TemplateRef(template.id, template.version), answers = mapOf("expectation" to "合成资料：完成实验"),
            attachmentIds = setOf(photo.id),
        )
        val saved = repository.createRecord(draft)
        repository.close()
        repository = LifeRepository(context, clock, name)
        assertEquals(saved, repository.record(saved.id))
        assertEquals(clock.instant(), repository.record(saved.id)!!.createdAt)
        assertEquals(draft.occurrence, repository.record(saved.id)!!.draft.occurrence)
        assertEquals(template, repository.template(template.id))
        assertArrayEquals("synthetic image".toByteArray(), repository.openAttachment(photo.id).use { it.readBytes() })
        assertEquals(listOf("TEMPLATE_CREATED", "RECORD_CREATED"), repository.pendingChanges().map { it.kind })
    }

    @Test fun `create is idempotent and never silently overwrites or revives identity`() {
        val draft = RecordDraft("合成资料：第一份内容")
        val first = repository.createRecord(draft, "synthetic-stable-id")
        assertEquals(first, repository.createRecord(draft, first.id))
        assertEquals(1, repository.pendingChanges().size)
        assertThrows(RevisionConflictException::class.java) { repository.createRecord(draft.copy(body = "合成资料：其他内容"), first.id) }
        repository.deleteRecord(first.id, first.revision)
        assertThrows(IllegalArgumentException::class.java) { repository.createRecord(draft, first.id) }
        assertEquals(setOf(first.id), repository.deletedRecordIds())
        assertEquals(listOf("RECORD_DELETED"), repository.pendingChanges().map { it.kind })
    }

    @Test fun `failed record writes leave neither partial relationships nor change events`() {
        val record = repository.createRecord(RecordDraft("合成资料：原文", topics = setOf("work")))
        val initialEvents = repository.pendingChanges()
        assertThrows(IllegalArgumentException::class.java) {
            repository.updateRecord(record.id, 1, record.draft.copy(body = "合成资料：无效更新", topics = setOf("sleep"), attachmentIds = setOf("missing")))
        }
        assertEquals(record, repository.record(record.id))
        assertEquals(listOf(record), repository.history(record.id))
        assertEquals(initialEvents, repository.pendingChanges())
        assertEquals(0, repository.listRecords(RecordFilter(topic = "sleep")).total)
        assertThrows(IllegalArgumentException::class.java) {
            repository.createRecord(RecordDraft("合成资料：模板无效", template = TemplateRef("absent", 1)), "failed")
        }
        assertNull(repository.record("failed"))
        assertEquals(initialEvents, repository.pendingChanges())
    }

    @Test fun `two repository instances cannot overwrite from the same old revision`() {
        val first = repository.createRecord(RecordDraft("合成资料：原文"))
        LifeRepository(context, clock, name).use { other ->
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val results = listOf(repository, other).mapIndexed { index, store -> pool.submit(Callable {
                    start.await()
                    runCatching { store.updateRecord(first.id, 1, RecordDraft("合成资料：并发版本$index")) }
                }) }
                start.countDown()
                val outcomes = results.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(1, outcomes.count { it.isSuccess })
                assertEquals(1, outcomes.count { it.exceptionOrNull() is RevisionConflictException })
                assertEquals(2, repository.record(first.id)!!.revision)
                assertEquals(2, repository.history(first.id).size)
                assertEquals(2, repository.pendingChanges().size)
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun `outbox insertion failure rolls back already written revision and relationships`() {
        val first = repository.createRecord(RecordDraft("合成资料：原文", topics = setOf("work")))
        LifeDatabase(context, name).use { helper ->
            helper.writableDatabase.execSQL("""CREATE TRIGGER synthetic_reject_outbox BEFORE INSERT ON change_events
                WHEN NEW.kind = 'RECORD_UPDATED' BEGIN SELECT RAISE(ABORT, 'synthetic outbox failure'); END""")
        }
        assertThrows(SQLiteException::class.java) {
            repository.updateRecord(first.id, 1, RecordDraft("合成资料：事务中已写的文字", topics = setOf("learning")))
        }
        assertEquals(first, repository.record(first.id))
        assertEquals(listOf(first), repository.history(first.id))
        assertEquals(0, repository.listRecords(RecordFilter(topic = "learning")).total)
        assertEquals(1, repository.pendingChanges().size)
    }

    @Test fun `fractional second updates sort after older whole second updates`() {
        val first = repository.createRecord(RecordDraft("合成资料：整秒写入"), "a-first")
        val laterClock = Clock.fixed(clock.instant().plusNanos(100_000_000), ZoneOffset.UTC)
        LifeRepository(context, laterClock, name).use { later ->
            val second = later.createRecord(RecordDraft("合成资料：小数秒写入"), "z-second")
            assertEquals(listOf(second.id, first.id), later.listRecords().items.map { it.id })
            assertEquals(second.id, later.listRecords(RecordFilter(limit = 1)).items.single().id)
            assertEquals(first.id, later.listRecords(RecordFilter(limit = 1, offset = 1)).items.single().id)
        }
    }

    @Test fun `archiving preserves revision history and stale deletion is refused`() {
        val first = repository.createRecord(RecordDraft("合成资料：原文"))
        val second = repository.updateRecord(first.id, 1, RecordDraft("合成资料：修订原文"))
        val archived = repository.setArchived(first.id, second.revision, true)
        assertEquals(3, archived.revision)
        assertTrue(archived.archived)
        assertFalse(repository.record(first.id, 1)!!.archived)
        assertEquals(0, repository.listRecords().total)
        assertEquals(1, repository.listRecords(RecordFilter(includeArchived = true)).total)
        assertThrows(RevisionConflictException::class.java) { repository.deleteRecord(first.id, 2) }
        assertNotNull(repository.record(first.id))
        repository.deleteRecord(first.id, 3)
        assertNull(repository.record(first.id))
        assertNull(repository.record(first.id, 1))
        assertTrue(repository.history(first.id).isEmpty())
        assertEquals(listOf("RECORD_DELETED"), repository.pendingChanges().map { it.kind })
        repository.close()
        repository = LifeRepository(context, clock, name)
        assertTrue(first.id in repository.deletedRecordIds())
    }

    @Test fun `old reviews retain old template questions and source even after template changes`() {
        val original = repository.saveTemplate(templateDraft())
        val review = repository.createRecord(RecordDraft(template = TemplateRef(original.id, original.version), answers = mapOf("expectation" to "合成资料：答案")))
        val changed = repository.saveTemplate(original.draft.copy(questions = listOf(TemplateQuestion("new-question", "合成资料：新问题")), sourceMarkdown = "# 合成资料：新源文"), original.id, 1)
        assertEquals(2, changed.version)
        assertEquals(original, repository.template(original.id, 1))
        assertEquals(TemplateRef(original.id, 1), repository.record(review.id)!!.draft.template)
        assertEquals(mapOf("expectation" to "合成资料：答案"), repository.record(review.id)!!.draft.answers)
        assertThrows(IllegalArgumentException::class.java) {
            repository.createRecord(RecordDraft(template = TemplateRef(changed.id, 2), answers = mapOf("expectation" to "合成资料：不能配错题目")))
        }
        assertThrows(RevisionConflictException::class.java) { repository.saveTemplate(templateDraft(), original.id, 1) }
        repository.archiveTemplate(original.id)
        assertTrue(repository.listTemplates().isEmpty())
        assertEquals(1, repository.listTemplates(includeArchived = true).size)
        assertThrows(IllegalArgumentException::class.java) { repository.deleteTemplate(original.id) }
        // Dropping a binding from the latest revision does not remove its historical reference.
        val unbound = repository.updateRecord(review.id, 1, RecordDraft("合成资料：改为随记"))
        assertThrows(IllegalArgumentException::class.java) { repository.deleteTemplate(original.id) }
        repository.deleteRecord(unbound.id, unbound.revision)
        repository.deleteTemplate(original.id)
        assertNull(repository.template(original.id, 1))
    }

    @Test fun `range query uses interval intersection and reports unknown dates separately`() {
        val span = repository.createRecord(RecordDraft("合成资料：跨周记录", Occurrence(date("2026-10-01"), date("2026-10-10")), setOf("work", "sleep")), "a-span")
        val point = repository.createRecord(RecordDraft("合成资料：点日期", Occurrence(date("2026-10-08")), setOf("sleep")), "b-point")
        val unknown = repository.createRecord(RecordDraft("合成资料：时间未知", topics = setOf("sleep")), "c-unknown")
        repository.createRecord(RecordDraft("合成资料：范围外", Occurrence(date("2026-09-01")), setOf("sleep")), "d-outside")
        repository.createRecord(RecordDraft("合成资料：其他主题", topics = setOf("learning")), "e-other")
        val filter = RecordFilter(from = date("2026-10-07"), through = date("2026-10-08"), topic = "sleep", limit = 2)
        val firstPage = repository.listRecords(filter)
        assertEquals(3, firstPage.total)
        assertEquals(1, firstPage.unknownTimeCount)
        assertEquals(listOf(span.id, point.id), firstPage.items.map { it.id })
        val next = repository.listRecords(filter.copy(offset = firstPage.nextOffset!!))
        assertEquals(listOf(unknown.id), next.items.map { it.id })
        assertNull(next.nextOffset)
        val knownOnly = repository.listRecords(filter.copy(includeUnknown = false))
        assertEquals(2, knownOnly.total)
        assertEquals(1, knownOnly.unknownTimeCount)
        assertEquals(0, repository.listRecords(filter.copy(topic = "sleep' OR 1=1 --")).total)
        assertThrows(IllegalArgumentException::class.java) { repository.listRecords(filter.copy(limit = 0)) }
    }

    @Test fun `dates outside the sortable supported range are rejected instead of matching unrelated records`() {
        repository.createRecord(RecordDraft("合成资料：当前记录", Occurrence(date("2026-10-16"))))
        assertThrows(IllegalArgumentException::class.java) {
            repository.listRecords(RecordFilter(from = LocalDate.of(10_000, 1, 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.listRecords(RecordFilter(through = LocalDate.of(-1, 1, 1)))
        }
    }

    @Test fun `all importer limits remain saveable including many questions and long prose`() {
        val many = TemplateImporter.preview("# 合成资料：100问题\n" + (1..100).joinToString("\n") { "## 合成资料：问题$it" })
        val savedMany = repository.saveTemplate(many)
        assertEquals(many, repository.template(savedMany.id)!!.draft)
        val longProse = TemplateImporter.preview("# 合成资料：长说明\n" + "synthetic ".repeat(23_000))
        val savedProse = repository.saveTemplate(longProse)
        assertEquals(longProse, repository.template(savedProse.id)!!.draft)
    }

    @Test fun `attachment shared by historical revisions and another record is only collected at last deletion`() {
        val attachment = repository.importAttachment(ByteArrayInputStream("synthetic shared bytes".toByteArray()), "../synthetic.txt", "text/plain")
        val a = repository.createRecord(RecordDraft(attachmentIds = setOf(attachment.id)))
        val b = repository.createRecord(RecordDraft("合成资料：共享附件", attachmentIds = setOf(attachment.id)))
        val a2 = repository.updateRecord(a.id, 1, RecordDraft("合成资料：最新修订不带附件"))
        assertThrows(IllegalArgumentException::class.java) { repository.discardAttachment(attachment.id) }
        repository.deleteRecord(b.id, 1)
        assertNotNull(repository.attachment(attachment.id))
        assertArrayEquals("synthetic shared bytes".toByteArray(), repository.openAttachment(attachment.id).use { it.readBytes() })
        repository.deleteRecord(a2.id, 2)
        assertNull(repository.attachment(attachment.id))
        assertThrows(java.io.FileNotFoundException::class.java) { repository.openAttachment(attachment.id) }
        assertTrue(managedFiles().none { it.name == "${attachment.id}.blob" })
    }

    @Test fun `failed and oversized imports leave no half file or metadata and unreferenced import can be discarded`() {
        val before = managedFiles().map { it.absolutePath }.toSet()
        val failing = object : InputStream() {
            var reads = 0
            override fun read(): Int = if (reads++ < 16) 'x'.code else throw IOException("synthetic read failure")
        }
        assertThrows(IOException::class.java) { repository.importAttachment(failing, "synthetic.bin", "application/octet-stream") }
        assertEquals(before, managedFiles().map { it.absolutePath }.toSet())
        val oversized = object : InputStream() {
            var remaining = LifeRepository.MAX_ATTACHMENT_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val size = minOf(remaining, length.toLong()).toInt()
                buffer.fill(0, offset, offset + size)
                remaining -= size
                return size
            }
        }
        assertThrows(IllegalArgumentException::class.java) { repository.importAttachment(oversized, "synthetic-large.bin", "application/octet-stream") }
        assertEquals(before, managedFiles().map { it.absolutePath }.toSet())
        val valid = repository.importAttachment(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "synthetic-small.bin", "application/octet-stream")
        assertEquals(3L, valid.sizeBytes)
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", valid.sha256)
        repository.discardAttachment(valid.id)
        assertNull(repository.attachment(valid.id))
        assertEquals(before, managedFiles().map { it.absolutePath }.toSet())
    }

    @Test fun `reopening replays durable attachment garbage and removes interrupted staging`() {
        val attachment = repository.importAttachment(ByteArrayInputStream("synthetic crash recovery".toByteArray()), "synthetic.txt", "text/plain")
        val file = managedFiles().single { it.name == "${attachment.id}.blob" }
        repository.close()
        // Simulate a process stopping after the database commit but before filesystem cleanup.
        LifeDatabase(context, name).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO attachment_garbage(id) VALUES (?)", arrayOf(attachment.id))
            db.execSQL("DELETE FROM attachments WHERE id = ?", arrayOf(attachment.id))
        }
        val interrupted = File(file.parentFile, ".pending.synthetic-interrupted").apply { writeText("synthetic partial bytes") }
        val orphan = File(file.parentFile, "synthetic-orphan.blob").apply { writeText("synthetic orphan bytes") }
        repository = LifeRepository(context, clock, name)
        assertFalse(file.exists())
        assertFalse(interrupted.exists())
        assertFalse(orphan.exists())
        assertNull(repository.attachment(attachment.id))
    }

    @Test fun `draft recovery retains restored and historical attachments while collecting interrupted imports`() {
        fun imported(label: String) = repository.importAttachment(ByteArrayInputStream("synthetic $label".toByteArray()), "$label.txt", "text/plain")
        val historical = imported("history")
        val draft = imported("draft")
        val abandoned = imported("abandoned")
        val first = repository.createRecord(RecordDraft(attachmentIds = setOf(historical.id)))
        repository.updateRecord(first.id, first.revision, RecordDraft("合成资料：历史修订仍引用附件"))
        repository.close()
        repository = LifeRepository(context, clock, name)
        // The retained set comes from the restored editor state, not a time-based heuristic.
        repository.recoverUnreferencedAttachments(setOf(draft.id))
        assertNotNull(repository.attachment(historical.id))
        assertNotNull(repository.attachment(draft.id))
        assertNull(repository.attachment(abandoned.id))
        assertTrue(managedFiles().none { it.name == "${abandoned.id}.blob" })
        assertThrows(IllegalArgumentException::class.java) { repository.recoverUnreferencedAttachments(setOf("invalid-restored-id")) }
        assertNotNull(repository.attachment(draft.id))
        repository.recoverUnreferencedAttachments(emptySet())
        assertNull(repository.attachment(draft.id))
        assertNotNull(repository.attachment(historical.id))
    }

    @Test fun `invalid empty records duplicate questions and unknown answers are refused`() {
        assertThrows(IllegalArgumentException::class.java) { repository.createRecord(RecordDraft()) }
        assertThrows(IllegalArgumentException::class.java) { repository.createRecord(RecordDraft(answers = mapOf("question" to "合成资料：无模板答案"))) }
        assertThrows(IllegalArgumentException::class.java) { repository.saveTemplate(templateDraft().copy(questions = listOf(TemplateQuestion("same", "合成资料：题目一"), TemplateQuestion("same", "合成资料：题目二")))) }
        assertTrue(repository.pendingChanges().isEmpty())
        assertTrue(repository.listTemplates().isEmpty())
    }

    private fun templateDraft() = TemplateDraft("合成资料：每日复盘", Cadence.DAILY, "合成资料：说明", listOf(
        TemplateQuestion("expectation", "合成资料：预期是什么？"),
        TemplateQuestion("learning", "合成资料：学到了什么？", "可不填写", "learning"),
    ), "# 合成资料：每日复盘\n## 预期是什么？\n## 学到了什么？")

    private fun date(value: String): LocalDate = LocalDate.parse(value)
    private fun managedFiles(): List<File> = File(context.filesDir, "attachments").walkTopDown().filter { it.isFile }.toList()
}
