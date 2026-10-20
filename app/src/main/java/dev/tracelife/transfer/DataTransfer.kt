package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.dashboard.DashboardRequest
import dev.tracelife.dashboard.DashboardService
import dev.tracelife.dashboard.DashboardSnapshot
import dev.tracelife.model.RecordFilter
import dev.tracelife.metrics.RecordMetricEvidenceService
import dev.tracelife.reports.PeriodReport
import dev.tracelife.reports.PeriodReports
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A preview binds user intent to both the exact backup bytes and the target's current state. */
data class RestorePreview(
    val snapshotId: String,
    val capturedAt: Instant,
    val records: Int,
    val revisions: Int,
    val templates: Int,
    val attachments: Int,
    val attachmentBytes: Long,
    val deletedRecords: Int,
    val deletedTemplates: Int,
    val archiveSha256: String,
    val targetStoreId: String,
    val targetGeneration: Long,
    val targetHasData: Boolean,
    val analysisRuns: Int,
    val targetAnalysisGeneration: Long,
    val claimFeedback: Int,
    val knowledge: Int,
    val actions: Int,
    val profiles: Int,
)

/**
 * Trusted offline operations; call on a worker thread. No model may invoke this whole-store API.
 *
 * The caller owns the password and an unused destination path in an existing directory.
 * Exports publish atomically; an existing file is never deliberately replaced. Restoring requires
 * a preview of the same encrypted file and refuses writes made since that preview. Replacing an
 * initialized database (including built-in templates) requires explicit replaceExisting=true.
 *
 * File-based operations are the backend seam. A future document-provider UI must preserve these
 * commit semantics rather than treating an unfinished stream as a completed backup.
 */
class DataTransfer(context: Context, private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    private val staging = File(context.applicationContext.noBackupFilesDir, "transfer-staging").canonicalFile
    private val databaseDirectory = context.applicationContext.getDatabasePath("placeholder").parentFile!!.canonicalFile
    private val managedAttachments = File(context.applicationContext.filesDir, "attachments").canonicalFile
    private val privateExports = PrivateExportDestination.root(context)
    private val lock = locks.getOrPut(staging.path) { Any() }

    init { recoverInterruptedTransfers() }

    fun exportBackup(destination: File, password: CharArray): ExportResult = exportBackup(destination, password, null)
    fun exportBackup(destination: PrivateExportDestination, password: CharArray): ExportResult =
        exportBackup(destination.file, password, destination)

    private fun exportBackup(destination: File, password: CharArray, privateDestination: PrivateExportDestination?): ExportResult = workspace { directory ->
        val target = targetFor(destination, privateDestination)
        val attachments = File(directory, "attachments").apply { check(mkdir()) }
        val snapshot = repository.captureSnapshot(attachments)
        val archive = File(directory, "backup.tlb")
        val result = BackupArchive.write(snapshot, attachments, archive, password)
        publish(archive, target, directory) { adjacent, published ->
            if (privateDestination == null) repository.publishSnapshot(snapshot, adjacent, published)
            else {
                privateDestination.prepare(adjacent)
                repository.publishPrivateExport(dev.tracelife.storage.SourceStamp(snapshot.storeId, snapshot.generation),
                    snapshot.analysisGeneration, null, adjacent, privateDestination)
            }
        }
        result.copy(bytes = target.length())
    }

    fun exportReadingPackage(
        destination: File,
        options: ReadingExportOptions = ReadingExportOptions(),
    ): ExportResult = exportReadingPackage(destination, options, null)

    fun exportReadingPackage(destination: PrivateExportDestination, options: ReadingExportOptions = ReadingExportOptions()): ExportResult =
        exportReadingPackage(destination.file, options, destination)

    private fun exportReadingPackage(destination: File, options: ReadingExportOptions, privateDestination: PrivateExportDestination?): ExportResult = workspace { directory ->
        val target = targetFor(destination, privateDestination)
        val attachments = File(directory, "attachments").apply { check(mkdir()) }
        val snapshot = repository.captureSnapshot(attachments)
        val archive = File(directory, "knowledge.zip")
        val written = KnowledgeArchive.writeForPublication(snapshot, attachments, archive, options)
        publish(archive, target, directory) { adjacent, published ->
            if (privateDestination == null) repository.publishDerivedFile(dev.tracelife.storage.SourceStamp(snapshot.storeId, snapshot.generation),
                snapshot.analysisGeneration, written.validUntil, adjacent, published, validFrom = snapshot.capturedAt)
            else {
                privateDestination.prepare(adjacent)
                repository.publishPrivateExport(dev.tracelife.storage.SourceStamp(snapshot.storeId, snapshot.generation),
                    snapshot.analysisGeneration, written.validUntil, adjacent, privateDestination, validFrom = snapshot.capturedAt)
            }
        }
        written.result.copy(bytes = target.length())
    }

    /** Derived materials only: no whole-store snapshot, model call, record bodies or attachments. */
    fun exportPeriodReport(destination: File, scope: AnalysisQuery): ExportResult =
        exportPeriodReport(destination, PeriodReports(repository, clock).open(scope))

    /** Export exactly the report the caller reviewed; any intervening change requires reopening it. */
    fun exportPeriodReport(destination: File, report: PeriodReport): ExportResult = exportPeriodReport(destination, report, null)
    fun exportPeriodReport(destination: PrivateExportDestination, report: PeriodReport): ExportResult =
        exportPeriodReport(destination.file, report, destination)

    private fun exportPeriodReport(destination: File, report: PeriodReport, privateDestination: PrivateExportDestination?): ExportResult = workspace { directory ->
        val target = targetFor(destination, privateDestination)
        val reports = PeriodReports(repository, clock)
        reports.assertCurrent(report)
        val metricEvidence = RecordMetricEvidenceService(repository, clock).describe(report.metrics)
        val archive = File(directory, "period-report.zip")
        val result = PeriodReportArchive.write(reports, report, metricEvidence, archive)
        reports.assertCurrent(report)
        publish(archive, target, directory) { adjacent, published ->
            if (privateDestination == null) repository.publishDerivedFile(report.source, report.analysisGeneration, report.validUntil, adjacent, published)
            else {
                privateDestination.prepare(adjacent)
                repository.publishPrivateExport(report.source, report.analysisGeneration, report.validUntil, adjacent, privateDestination)
            }
        }
        result.copy(bytes = target.length())
    }

    /** Fixed dashboard values and paged metadata only; no model call or source body export. */
    fun exportDashboard(destination: File, request: DashboardRequest): ExportResult =
        exportDashboard(destination, DashboardService(repository, clock).open(request))

    /** Publishes the same dashboard the caller read; midnight and changed dependencies reject it. */
    fun exportDashboard(destination: File, dashboard: DashboardSnapshot): ExportResult = exportDashboard(destination, dashboard, null)
    fun exportDashboard(destination: PrivateExportDestination, dashboard: DashboardSnapshot): ExportResult =
        exportDashboard(destination.file, dashboard, destination)

    private fun exportDashboard(destination: File, dashboard: DashboardSnapshot, privateDestination: PrivateExportDestination?): ExportResult = workspace { directory ->
        val target = targetFor(destination, privateDestination)
        val dashboards = DashboardService(repository, clock)
        dashboards.assertCurrent(dashboard)
        val archive = File(directory, "dashboard.zip")
        val result = DashboardArchive.write(dashboards, dashboard, archive)
        dashboards.assertCurrent(dashboard)
        publish(archive, target, directory) { adjacent, published ->
            if (privateDestination == null) repository.publishDerivedFile(dashboard.source, dashboard.analysisGeneration, dashboard.validUntil, adjacent, published,
                validFrom = dashboard.evaluatedAt)
            else {
                privateDestination.prepare(adjacent)
                repository.publishPrivateExport(dashboard.source, dashboard.analysisGeneration, dashboard.validUntil, adjacent,
                    privateDestination, validFrom = dashboard.evaluatedAt)
            }
        }
        result.copy(bytes = target.length())
    }

    fun previewBackup(source: File, password: CharArray): RestorePreview = workspace { directory ->
        val copy = File(directory, "source.tlb")
        val digest = copyAndHash(source, copy)
        val attachments = File(directory, "attachments").apply { check(mkdir()) }
        val snapshot = BackupArchive.read(copy, attachments, password)
        val generation = repository.currentGeneration()
        val analysisGeneration = repository.currentAnalysisGeneration()
        val hasData = repository.listRecords(RecordFilter(includeArchived = true, limit = 1)).total > 0 ||
            repository.listTemplates(includeArchived = true).isNotEmpty() || repository.analysisRuns(0, 1).isNotEmpty() ||
            repository.claimFeedbacks(0, 1).isNotEmpty() || repository.knowledgeHeads(0, 1).isNotEmpty() ||
            repository.actionHeads(0, 1).isNotEmpty() || repository.profileHeads(0, 1).isNotEmpty()
        if (repository.currentGeneration() != generation || repository.currentAnalysisGeneration() != analysisGeneration) {
            throw StaleSnapshotException("资料在预览期间发生变化，请重新预览")
        }
        RestorePreview(
            snapshot.snapshotId, snapshot.capturedAt, snapshot.records.map { it.id }.distinct().size,
            snapshot.records.size, snapshot.templates.map { it.id }.distinct().size,
            snapshot.attachments.size, snapshot.attachments.sumOf { it.sizeBytes },
            snapshot.deletedRecords.size, snapshot.deletedTemplates.size, digest,
            repository.currentStoreId(), generation, hasData,
            snapshot.analysisRuns.size, analysisGeneration, snapshot.claimFeedback.size, snapshot.knowledgeHeads.size, snapshot.actionHeads.size, snapshot.profileHeads.size,
        )
    }

    fun restoreBackup(
        source: File,
        password: CharArray,
        preview: RestorePreview,
        replaceExisting: Boolean = false,
    ): RestoreResult = workspace { directory ->
        if (repository.currentStoreId() != preview.targetStoreId ||
            repository.currentGeneration() != preview.targetGeneration ||
            repository.currentAnalysisGeneration() != preview.targetAnalysisGeneration) {
            throw StaleSnapshotException("资料在预览后发生变化，请重新预览")
        }
        val copy = File(directory, "source.tlb")
        if (copyAndHash(source, copy) != preview.archiveSha256) {
            throw InvalidBackupException("备份文件在预览后发生变化，请重新预览")
        }
        val attachments = File(directory, "attachments").apply { check(mkdir()) }
        val snapshot = BackupArchive.read(copy, attachments, password)
        if (snapshot.snapshotId != preview.snapshotId) throw InvalidBackupException("备份快照与预览不一致")
        repository.restoreSnapshot(snapshot, attachments, preview.targetGeneration, replaceExisting,
            expectedAnalysisGeneration = preview.targetAnalysisGeneration)
    }

    /** Serializes with active transfers, including other DataTransfer instances in this process. */
    fun recoverInterruptedTransfers() = synchronized(lock) {
        check(staging.isDirectory || staging.mkdirs()) { "无法创建备份工作目录" }
        staging.listFiles()?.filter { it.name.startsWith("job-") }?.forEach {
            cleanWorkspace(it)
        }
    }

    private fun destinationFor(destination: File): File {
        val target = destination.canonicalFile
        require(target != privateExports && !target.path.startsWith(privateExports.path + File.separator)) {
            "私有导出目录必须通过独立所有权接口发布"
        }
        require(!target.exists()) { "目标文件已存在，请选择新文件名" }
        require(target.parentFile?.isDirectory == true) { "目标目录不存在" }
        require(target != staging && !target.path.startsWith(staging.path + File.separator)) {
            "最终文件不能保存在临时工作目录内"
        }
        require(listOf(databaseDirectory, managedAttachments).none {
            target == it || target.path.startsWith(it.path + File.separator)
        }) { "导出目标不能位于权威资料目录内" }
        return target
    }

    private fun targetFor(destination: File, privateDestination: PrivateExportDestination?): File {
        if (privateDestination == null) return destinationFor(destination)
        require(destination == privateDestination.file && !destination.exists()) { "私有导出目标已存在或所有权不匹配" }
        return destination
    }

    private fun publish(archive: File, target: File, directory: File, commit: (File, File) -> Unit) {
        val adjacent = File(target.parentFile, ".trace-life-${directory.name.removePrefix("job-")}.pending")
        check(adjacent.createNewFile()) { "临时导出文件已存在" }
        try {
            // Persist ownership before writing any content outside private storage. Recovery
            // removes only our per-job pending name, never the final destination.
            FileOutputStream(File(directory, "pending-path")).use {
                it.write(adjacent.canonicalPath.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            copyAndHash(archive, adjacent)
            commit(adjacent, target)
        } finally {
            adjacent.delete()
        }
    }

    private fun <T> workspace(block: (File) -> T): T = synchronized(lock) {
        check(staging.isDirectory || staging.mkdirs()) { "无法创建备份工作目录" }
        val directory = File(staging, "job-${UUID.randomUUID()}")
        check(directory.mkdir()) { "无法创建本次备份工作目录" }
        var failure: Throwable? = null
        try {
            block(directory)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { cleanWorkspace(directory) } catch (cleanup: Exception) {
                // A committed export/restore remains successful. Leave the private job and
                // its ownership journal for recovery; never misreport a commit as a rollback.
                failure?.addSuppressed(cleanup)
            }
        }
    }

    private fun cleanWorkspace(directory: File) {
        val journal = File(directory, "pending-path")
        if (journal.isFile) {
            check(journal.length() <= 8_192) { "中断导出登记损坏" }
            val pending = File(journal.readText()).canonicalFile
            check(pending.name == ".trace-life-${directory.name.removePrefix("job-")}.pending") {
                "中断导出登记不匹配"
            }
            check(!pending.exists() || pending.delete()) { "无法清理中断的导出文件" }
        }
        check(directory.deleteRecursively()) { "无法清理中断的数据转移" }
    }

    private fun copyAndHash(source: File, destination: File): String {
        require(source.isFile && source.length() <= MAX_ARCHIVE_BYTES) { "备份文件不存在或超过大小上限" }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    size += count
                    require(size <= MAX_ARCHIVE_BYTES) { "备份文件超过大小上限" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024 + 64L * 1024 * 1024
        private val locks = ConcurrentHashMap<String, Any>()
    }
}
