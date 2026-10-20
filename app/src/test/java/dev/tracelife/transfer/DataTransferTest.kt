package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.model.Cadence
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.StoredAnalysisRun
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
import java.time.LocalDate
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile

/** Only synthetic fixtures are written by these end-to-end transfer tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DataTransferTest {
    private lateinit var context: Context
    private lateinit var source: LifeRepository
    private lateinit var target: LifeRepository
    private lateinit var sourceName: String
    private lateinit var targetName: String
    private lateinit var directory: File
    private val password = "synthetic recovery passphrase 2026".toCharArray()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        sourceName = "synthetic-transfer-source-${UUID.randomUUID()}.db"
        targetName = "synthetic-transfer-target-${UUID.randomUUID()}.db"
        source = LifeRepository(context, databaseName = sourceName)
        target = LifeRepository(context, databaseName = targetName)
        directory = File(context.cacheDir, "synthetic-transfer-${UUID.randomUUID()}").apply { check(mkdir()) }
    }

    @After fun tearDown() {
        if (::source.isInitialized) source.close()
        if (::target.isInitialized) target.close()
        if (::sourceName.isInitialized) context.deleteDatabase(sourceName)
        if (::targetName.isInitialized) context.deleteDatabase(targetName)
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun `portable backup restores original review history and managed photo from preview`() {
        val template = source.saveTemplate(TemplateDraft("合成复盘", Cadence.DAILY,
            questions = listOf(TemplateQuestion("learned", "合成问题：学到了什么？"))))
        val bytes = "synthetic photograph bytes".toByteArray()
        val attachment = source.importAttachment(bytes.inputStream(), "synthetic.jpg", "image/jpeg")
        val first = source.createRecord(RecordDraft(
            "合成原文", Occurrence(LocalDate.of(2026, 10, 16)), setOf("learning", "work"),
            TemplateRef(template.id, template.version), mapOf("learned" to "合成答案"), setOf(attachment.id),
        ))
        val second = source.updateRecord(first.id, first.revision, first.draft.copy(body = "合成修订", attachmentIds = emptySet()))
        source.saveTemplate(template.draft.copy(questions = listOf(TemplateQuestion("next", "合成新问题"))), template.id, 1)
        val file = File(directory, "full.tlb")
        val export = DataTransfer(context, source).exportBackup(file, password)
        assertEquals(1, export.records)
        assertEquals(1, export.attachments)
        val transfer = DataTransfer(context, target)
        val preview = transfer.previewBackup(file, password)
        assertFalse(preview.targetHasData)
        assertEquals(2, preview.revisions)
        assertEquals(bytes.size.toLong(), preview.attachmentBytes)
        val result = transfer.restoreBackup(file, password, preview)
        assertEquals(2, result.revisions)
        assertEquals(second, target.record(first.id))
        assertEquals(listOf(second, first), target.history(first.id))
        assertEquals(template, target.template(template.id, 1))
        assertArrayEquals(bytes, target.openAttachment(attachment.id).use { it.readBytes() })
        assertCleanStaging()
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".pending") })
    }

    @Test fun `restoring after preview refuses new target data and requires explicit replacement`() {
        source.createRecord(RecordDraft("合成备份记录"), "saved")
        val file = File(directory, "full.tlb")
        DataTransfer(context, source).exportBackup(file, password)
        val transfer = DataTransfer(context, target)
        val before = transfer.previewBackup(file, password)
        val local = target.createRecord(RecordDraft("合成新录入，不能被旧预览覆盖"), "local")
        assertThrows(StaleSnapshotException::class.java) { transfer.restoreBackup(file, password, before, true) }
        assertEquals(local, target.record(local.id))
        val fresh = transfer.previewBackup(file, password)
        assertTrue(fresh.targetHasData)
        assertThrows(IllegalStateException::class.java) { transfer.restoreBackup(file, password, fresh) }
        assertEquals(local, target.record(local.id))
        transfer.restoreBackup(file, password, fresh, replaceExisting = true)
        assertNotNull(target.record("saved"))
        assertNull(target.record("local"))
        assertCleanStaging()
    }

    @Test fun `changed backup after preview is rejected before replacing target`() {
        source.createRecord(RecordDraft("合成最初版本"), "saved")
        val file = File(directory, "full.tlb")
        DataTransfer(context, source).exportBackup(file, password)
        val transfer = DataTransfer(context, target)
        val preview = transfer.previewBackup(file, password)
        file.appendBytes(byteArrayOf(7))
        assertThrows(InvalidBackupException::class.java) { transfer.restoreBackup(file, password, preview) }
        assertEquals(0, target.listRecords().total)
        assertEquals(preview.targetGeneration, target.currentGeneration())
        assertCleanStaging()
    }

    @Test fun `preview cannot be reused for a different store with the same generation`() {
        val file = File(directory, "empty.tlb")
        DataTransfer(context, source).exportBackup(file, password)
        val preview = DataTransfer(context, target).previewBackup(file, password)
        assertEquals(source.currentGeneration(), target.currentGeneration())
        assertThrows(StaleSnapshotException::class.java) {
            DataTransfer(context, source).restoreBackup(file, password, preview)
        }
    }

    @Test fun `analysis only progress invalidates restore preview and encrypted backup keeps historical task identity`() {
        val saved = saveAnalysis(source, "synthetic-source-task")
        val file = File(directory, "analysis.tlb")
        DataTransfer(context, source).exportBackup(file, password)
        val transfer = DataTransfer(context, target)
        val preview = transfer.previewBackup(file, password)
        assertEquals(1, preview.analysisRuns)
        val local = saveAnalysis(target, "synthetic-local-task")
        assertEquals(preview.targetGeneration, target.currentGeneration())
        assertThrows(StaleSnapshotException::class.java) { transfer.restoreBackup(file, password, preview, true) }
        assertEquals(local, target.analysisRun(local.id))
        val fresh = transfer.previewBackup(file, password)
        assertTrue(fresh.targetHasData)
        assertThrows(IllegalStateException::class.java) { transfer.restoreBackup(file, password, fresh) }
        transfer.restoreBackup(file, password, fresh, replaceExisting = true)
        assertEquals(saved, target.analysisRun(saved.id))
        assertNull(target.analysisRun(local.id))
        assertNotEquals(saved.source.storeId, target.currentStoreId())
        assertCleanStaging()
    }

    @Test fun `known target deletion preserves unread task history and suppresses original during recovery`() {
        val record = source.createRecord(RecordDraft("合成资料：后来删除"), "synthetic-deleted-source")
        saveAnalysis(source, "synthetic-task-before-deletion")
        val file = File(directory, "before-deletion.tlb")
        DataTransfer(context, source).exportBackup(file, password)
        val transfer = DataTransfer(context, target)
        transfer.restoreBackup(file, password, transfer.previewBackup(file, password))
        assertEquals(1, target.analysisRuns(0, 100).size)
        target.deleteRecord(record.id, record.revision)
        val preview = transfer.previewBackup(file, password)
        assertEquals(1, preview.analysisRuns)
        transfer.restoreBackup(file, password, preview, replaceExisting = true)
        assertNull(target.record(record.id))
        assertEquals(1, target.analysisRuns(0, 100).size)
        assertEquals(dev.tracelife.analysis.AnalysisStatus.STALE,
            dev.tracelife.analysis.ControlledAnalysis(target).task("synthetic-task-before-deletion").status)
        assertCleanStaging()
    }

    @Test fun `existing destinations and wrong passwords preserve data and clean temporary files`() {
        val transfer = DataTransfer(context, source)
        val occupied = File(directory, "existing.tlb").apply { writeText("synthetic existing bytes") }
        assertThrows(IllegalArgumentException::class.java) { transfer.exportBackup(occupied, password) }
        assertEquals("synthetic existing bytes", occupied.readText())
        val file = File(directory, "full.tlb")
        source.createRecord(RecordDraft("合成私有内容"))
        transfer.exportBackup(file, password)
        assertThrows(InvalidBackupException::class.java) {
            DataTransfer(context, target).previewBackup(file, "synthetic incorrect password".toCharArray())
        }
        assertEquals(0, target.listRecords().total)
        assertCleanStaging()
    }

    @Test fun `reading package is published as a scoped zip without mutating source`() {
        source.createRecord(RecordDraft("合成范围内文字", topics = setOf("learning")), "in-scope")
        source.createRecord(RecordDraft("合成范围外文字", topics = setOf("sleep")), "outside")
        val generation = source.currentGeneration()
        val file = File(directory, "evidence.zip")
        val result = DataTransfer(context, source).exportReadingPackage(file, ReadingExportOptions(
            kind = ReadingPackageKind.EVIDENCE, scope = ExportScope(topics = setOf("learning")),
            question = "合成问题：最近学习了什么？",
        ))
        assertEquals(1, result.records)
        assertEquals(generation, source.currentGeneration())
        ZipFile(file).use { zip ->
            val texts = zip.entries().asSequence().filter { it.name.endsWith(".md") }
                .map { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }.toList()
            assertTrue(texts.any { "合成范围内文字" in it })
            assertTrue(texts.none { "合成范围外文字" in it })
        }
        assertCleanStaging()
    }

    @Test fun `recovering an interrupted transfer removes owned partial output and keeps published file`() {
        val staging = File(context.noBackupFilesDir, "transfer-staging").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val job = File(staging, "job-$id").apply { mkdir() }
        val pending = File(directory, ".trace-life-$id.pending").apply { writeText("synthetic partial export") }
        val completed = File(directory, "complete.tlb").apply { writeText("synthetic completed backup") }
        File(job, "pending-path").writeText(pending.canonicalPath)
        File(job, "plaintext").writeText("synthetic private staging data")
        DataTransfer(context, source)
        assertFalse(pending.exists())
        assertEquals("synthetic completed backup", completed.readText())
        assertFalse(job.exists())
    }

    private fun assertCleanStaging() {
        assertTrue(File(context.noBackupFilesDir, "transfer-staging").listFiles().orEmpty().isEmpty())
    }

    private fun saveAnalysis(repository: LifeRepository, id: String): StoredAnalysisRun {
        val time = Instant.parse("2026-10-16T03:04:05Z")
        val state = AnalysisRunState(AnalysisRequest("合成问题：期间内有哪些学习线索？",
            AnalysisQuery(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"), "Asia/Shanghai")))
        return repository.saveAnalysisRun(StoredAnalysisRun(id, 1,
            SourceStamp(repository.currentStoreId(), repository.currentGeneration()), time, time, null,
            AnalysisRunCodec.encode(state)), expectedVersion = null)
    }
}
