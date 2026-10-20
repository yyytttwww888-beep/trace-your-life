package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.text.InputType
import dev.tracelife.transfer.CompletedExports
import dev.tracelife.transfer.DocumentExportDelivery
import dev.tracelife.transfer.ExportResult
import dev.tracelife.transfer.ExportScope
import dev.tracelife.transfer.InvalidBackupException
import dev.tracelife.transfer.ReadingExportOptions
import dev.tracelife.transfer.ReadingPackageKind
import dev.tracelife.transfer.RestorePreview
import dev.tracelife.transfer.RestoreResult
import dev.tracelife.transfer.StaleSnapshotException
import java.io.File

class TransferActivity : ScreenActivity() {
    private var backupResult: Pair<ExportResult, String>? = null
    private var readingResult: Pair<ExportResult, String>? = null
    private var restoreSource: File? = null
    private var restoreSourceName: String? = null
    private var restorePreview: RestorePreview? = null
    private var restorePassword = ""
    private var pendingSaveName: String? = null
    private var restoredScreen: String? = null
    private var completedOffset = 0
    private var requestedSaveName: String? = null
    private var lastSavedName: String? = null
    private val completedExports by lazy { CompletedExports(this) }

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        requestedSaveName = if (savedInstanceState == null) intent.getStringExtra(EXTRA_READY_EXPORT) else null
        pendingSaveName = savedInstanceState?.getString("pendingSaveName")
        lastSavedName = savedInstanceState?.getString("lastSavedName")
        restoredScreen = savedInstanceState?.getString("transferScreen")
        backupResult = restoreExport(savedInstanceState, "backup")
        readingResult = restoreExport(savedInstanceState, "reading")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("transferScreen", screen)
        outState.putString("pendingSaveName", pendingSaveName)
        outState.putString("lastSavedName", lastSavedName)
        saveExport(outState, "backup", backupResult)
        saveExport(outState, "reading", readingResult)
        super.onSaveInstanceState(outState)
    }

    private fun saveExport(state: Bundle, key: String, value: Pair<ExportResult, String>?) {
        value ?: return
        state.putBundle(key, Bundle().apply {
            putString("name", File(value.second).name)
            putString("snapshot", value.first.snapshotId)
            putInt("records", value.first.records)
            putInt("attachments", value.first.attachments)
            putInt("files", value.first.files)
            putLong("bytes", value.first.bytes)
        })
    }

    private fun restoreExport(state: Bundle?, key: String): Pair<ExportResult, String>? {
        val value = state?.getBundle(key) ?: return null
        val file = value.getString("name")?.let(completedExports::get) ?: return null
        return ExportResult(value.getString("snapshot") ?: return null, value.getInt("records"),
            value.getInt("attachments"), value.getInt("files"), value.getLong("bytes")) to file.absolutePath
    }

    override fun showHome() {
        val resume = restoredScreen
        restoredScreen = null
        if (resume == "backup" && backupResult != null) return showBackup()
        if (resume == "reading" && readingResult != null) return showReading()
        beginScreen("home", "备份与导出")
        Ui.note(root, "资料留在自己手里。备份和导出都在这台手机上完成。")
        card().also { card ->
            Ui.sectionTitle(card, "加密备份")
            Ui.note(card, "保存全部资料，用于以后恢复或换机。")
            addButton(card, "创建备份", "navBackup") { backupResult = null; showBackup() }
        }
        card().also { card ->
            Ui.sectionTitle(card, "恢复备份")
            Ui.note(card, "先预览备份内容，再决定是否替换本机资料。")
            addButton(card, "恢复备份", "navRestore") { showRestore() }
        }
        card().also { card ->
            Ui.sectionTitle(card, "导出文档")
            Ui.note(card, "把知识与原始资料带走，方便查阅。导出的文档不能用于恢复。")
            addButton(card, "选择导出内容", "navReading") { readingResult = null; showReading() }
        }
        if (pendingSaveName == null) renderCompletedExports()
    }

    override fun navigateBack() {
        if (busy) return
        when (screen) {
            "home" -> finish()
            "restore" -> { discardRestore(); showHome() }
            else -> showHome()
        }
    }

    override fun showError(failure: Throwable) {
        if (screen == "restore" && restorePreview != null &&
            (failure is StaleSnapshotException || failure is InvalidBackupException)) {
            AlertDialog.Builder(this).setTitle("操作未完成")
                .setMessage("${failure.message ?: "备份或本机资料已发生变化"}\n请重新预览备份并再次确认。")
                .setPositiveButton("知道了", null).show()
        } else super.showError(failure)
    }

    private fun showBackup() {
        beginScreen("backup", "创建备份")
        card().also { card ->
            Ui.sectionTitle(card, "把全部资料备份下来")
            Ui.note(card, "备份会加密保存全部记录、附件，以及整理和修改历史。DeepSeek 密钥不会进入备份。")
            Ui.note(card, "请记好口令。应用不会保存口令，丢失后无法恢复。")
            Ui.disclosure(card, "文件保存说明", "backupStorageDetails") { details ->
                Ui.note(details, "完整文件先在应用内生成，再通过系统文件选择器保存到你选的位置。完成另存前，卸载应用仍会删除这份文件。")
            }
        }
        card().also { card ->
            val password = labeledFieldIn(card, "备份口令", maxLength = 1024)
            password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            Ui.note(card, "至少 12 个字符，最多 1024 个。")
            primaryIn(card, "创建备份", "createBackup") onCreate@{
                if (busy) return@onCreate
                val value = password.text.toString()
                if (value.length !in 12..1024) {
                    showError(IllegalArgumentException("备份口令须为 12 至 1024 个字符"))
                    return@onCreate
                }
                val chars = value.toCharArray()
                runIo({
                    try {
                        val (result, file) = completedExports.create("backup") { app.dataTransfer.exportBackup(it, chars) }
                        result to file.absolutePath
                    } finally {
                        chars.fill('\u0000')
                    }
                }) { (result, path) ->
                    backupResult = result to path
                    showBackup()
                    openExportPicker(File(path).name)
                }
            }
        }
        backupResult?.let { (result, path) ->
            card().also { card ->
                Ui.sectionTitle(card, if (File(path).name == lastSavedName) "备份已另存" else "备份已生成，等待另存")
                Ui.body(card, "${result.records} 条记录 · ${result.attachments} 个附件 · ${result.bytes / 1024} KB")
                Ui.note(card, "完整文件已在应用内保留。选择一个应用之外的位置，换机时也能找回。")
                addButton(card, "另存为", "saveBackup") { openExportPicker(File(path).name) }
            }
        }
    }

    private fun showRestore() {
        beginScreen("restore", "恢复备份")
        card().also { card ->
            Ui.sectionTitle(card, "从备份找回资料")
            Ui.note(card, "选择文件并输入口令，先查看内容，再确认恢复。恢复可能替换现有资料，不能撤销。")
            Ui.disclosure(card, "恢复前须知", "restoreDetails") { details ->
                Ui.note(details, "预览后如果本机资料有变化，需要重新预览。文件或口令有误时，不会修改现有资料。")
                Ui.note(details, "恢复后请重新填写 DeepSeek 密钥，按需开启自动整理。恢复不提供双向同步。")
            }
        }
        val source = restoreSource
        if (source == null) {
            card().also { card ->
                Ui.sectionTitle(card, "选择备份文件")
                addButton(card, "选择备份文件", "pickBackup") { openBackupPicker() }
            }
            return
        }
        val fileCard = card()
        Ui.sectionTitle(fileCard, "备份文件与口令")
        Ui.note(fileCard, "已选择：${restoreSourceName ?: source.name}")
        val password = labeledFieldIn(fileCard, "备份口令", value = restorePassword, maxLength = 1024)
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val preview = restorePreview
        if (preview == null) {
            primaryIn(fileCard, "预览备份内容", "previewBackup") onPreview@{
                if (busy) return@onPreview
                val value = password.text.toString()
                if (value.isBlank()) {
                    showError(IllegalArgumentException("请输入备份口令"))
                    return@onPreview
                }
                restorePassword = value
                val chars = value.toCharArray()
                runIo({
                    try { app.dataTransfer.previewBackup(source, chars) } finally { chars.fill('\u0000') }
                }) { result ->
                    restorePreview = result
                    showRestore()
                }
            }
            return
        }
        card().also { card ->
            Ui.sectionTitle(card, "备份预览")
            Ui.note(card, "备份于 ${preview.capturedAt}")
            Ui.body(card, "${preview.records} 条记录 · ${preview.attachments} 个附件\n${preview.knowledge} 条知识 · ${preview.experienceThreads} 条经验线索\n${preview.actions} 个行动 · ${preview.profiles} 条画像")
            Ui.disclosure(card, "查看全部内容", "backupContents") { details ->
                Ui.note(details, "记录历史 ${preview.revisions} 个版本 · 模板 ${preview.templates} 个\n附件大小 ${preview.attachmentBytes / 1024} KB\n分析 ${preview.analysisRuns} 次（已归档 ${preview.archivedAnalysisRuns} 次）· 反馈 ${preview.claimFeedback} 条\n对话 ${preview.conversations} 个 · 问题 ${preview.conversationTurns} 个\n独立对照说明 ${preview.historicalComparisons} 条\n已删除记录 ${preview.deletedRecords} 条 · 已删除模板 ${preview.deletedTemplates} 个\n备份编号：${preview.snapshotId}")
            }
            if (preview.targetHasData) {
                chip(card, "本机已有资料，须明确替换", Ui.Tone.WARN)
                Ui.note(card, "继续恢复会替换现有资料和模板，请确认后再操作。")
            } else {
                chip(card, "本机当前没有资料", Ui.Tone.OK)
            }
        }
        card().also { card ->
            Ui.sectionTitle(card, "确认恢复")
            val replace = checkIn(card, "替换现有资料（含内置模板）", false)
            dangerIn(card, "确认恢复", "confirmRestore") onRestore@{
                if (busy) return@onRestore
                val value = password.text.toString()
                if (value.isBlank()) {
                    showError(IllegalArgumentException("请输入备份口令"))
                    return@onRestore
                }
                if (preview.targetHasData && !replace.isChecked) {
                    showError(IllegalArgumentException("本机已有资料（含内置模板），须明确勾选替换后才能恢复"))
                    return@onRestore
                }
                val replaceExisting = replace.isChecked
                confirm("确认恢复备份？",
                    if (replaceExisting) "本机现有资料和模板将被备份内容替换。已删除的内容不会因此找回，你已有的独立反馈会按规则保留。此操作不能撤销。"
                    else "备份内容将写入本机资料库。",
                    "确认恢复") {
                    restorePassword = value
                    val chars = value.toCharArray()
                    runIo({
                        try { app.dataTransfer.restoreBackup(source, chars, preview, replaceExisting = replaceExisting) }
                        finally { chars.fill('\u0000') }
                    }) { result ->
                        val finished = restoreSource
                        restoreSource = null
                        restoreSourceName = null
                        restorePreview = null
                        restorePassword = ""
                        finished?.let { file -> app.io.execute { file.delete() } }
                        showRestoreDone(result)
                    }
                }
            }
        }
    }

    private fun showRestoreDone(result: RestoreResult) {
        beginScreen("restoreDone", "恢复完成")
        card().also { card ->
            Ui.sectionTitle(card, "恢复结果")
            Ui.body(card, "记录 ${result.records} 条（共 ${result.revisions} 个修订）、模板 ${result.templates} 个、附件 ${result.attachments} 个。")
            Ui.note(card, "已排除曾删除的记录 ${result.suppressedRecords} 条；因容量限制未保留的整理任务 ${result.capacityPurgedAnalysisRuns} 个。")
            Ui.note(card, "请在「设置」中重新填写 DeepSeek 密钥，按需开启自动整理。")
            addButton(card, "返回备份与导出", "restoreDone") { showHome() }
        }
    }

    private fun showReading() {
        beginScreen("reading", "导出文档")
        card().also { card ->
            Ui.sectionTitle(card, "把内容带走")
            Ui.note(card, "导出一份可阅读的文档。文件不会随以后的修改自动更新，也不能用于恢复备份。")
            Ui.disclosure(card, "两种导出方式", "readingKinds") { details ->
                Ui.note(details, "知识包包含当前有效的知识与相关资料。证据包面向一个具体问题，只包含原始记录，不含知识、行动或画像。两者都不含整理任务与个人反馈。")
            }
        }
        card().also { card ->
            Ui.sectionTitle(card, "导出选项")
            Ui.fieldLabel(card, "导出类型")
            val kind = spinnerIn(card, listOf("知识与资料", "原始记录（针对一个问题）"), 0, "kind")
            val from = labeledFieldIn(card, "起始日期", hint = "YYYY-MM-DD")
            val through = labeledFieldIn(card, "结束日期", hint = "YYYY-MM-DD")
            Ui.note(card, "起始与结束日期可留空，表示不限制时间范围。")
            val topic = labeledFieldIn(card, "主题")
            Ui.note(card, "填写一个主题，可留空。")
            val question = labeledFieldIn(card, "这份资料用于什么问题？", multiline = true, maxLength = 2000)
            Ui.note(card, "只导出原始记录时必填，最多 2000 字。")
            primaryIn(card, "导出", "exportReading") onExport@{
                if (busy) return@onExport
                val kindValue = ReadingPackageKind.entries[kind.selectedItemPosition]
                val questionValue = question.text.toString().trim()
                if (kindValue == ReadingPackageKind.EVIDENCE && questionValue.isBlank()) {
                    showError(IllegalArgumentException("请说明这份原始资料用于什么问题"))
                    return@onExport
                }
                val options = try {
                    ReadingExportOptions(
                        kind = kindValue,
                        scope = ExportScope(
                            from = from.text.toString().takeIf { it.isNotBlank() }?.let { parseDate(it, "起始日期") },
                            through = through.text.toString().takeIf { it.isNotBlank() }?.let { parseDate(it, "结束日期") },
                            topics = topic.text.toString().trim().takeIf { it.isNotBlank() }?.let { setOf(it) },
                        ),
                        question = questionValue.takeIf { it.isNotBlank() },
                    )
                } catch (failure: IllegalArgumentException) {
                    showError(failure)
                    return@onExport
                }
                runIo({
                    val kindName = if (kindValue == ReadingPackageKind.KNOWLEDGE) "knowledge" else "evidence"
                    val (result, file) = completedExports.create(kindName) { app.dataTransfer.exportReadingPackage(it, options) }
                    result to file.absolutePath
                }) { (result, path) ->
                    readingResult = result to path
                    showReading()
                    openExportPicker(File(path).name)
                }
            }
        }
        readingResult?.let { (result, path) ->
            card().also { card ->
                Ui.sectionTitle(card, if (File(path).name == lastSavedName) "文档已另存" else "文档已生成，等待另存")
                Ui.body(card, "${result.records} 条记录 · ${result.attachments} 个附件 · ${result.bytes / 1024} KB")
                Ui.note(card, "完整文档已在应用内保留。另存后可交给其他工具查阅，不能用于恢复备份。")
                addButton(card, "另存为", "saveReading") { openExportPicker(File(path).name) }
            }
        }
    }

    private fun openBackupPicker() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, BACKUP_REQUEST)
        } catch (_: android.content.ActivityNotFoundException) {
            showError(IllegalStateException("未找到系统文件选择器，请检查手机的文件应用"))
        }
    }

    @Deprecated("Platform document picker compatibility")
    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_REQUEST) {
            val name = pendingSaveName
            pendingSaveName = null
            if (resultCode != RESULT_OK) {
                toast("已取消另存，完整文件仍保留在应用内")
                if (screen == "home") showHome()
                return
            }
            val uri = data?.data ?: return
            val file = name?.let(completedExports::get)
            if (file == null) {
                runIo({ runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false) }) { removed ->
                    showError(IllegalStateException(if (removed) "找不到完整导出文件，请返回重新生成"
                        else "找不到完整导出文件，请重新生成。目标位置可能留有空文件，请删除后重试。"))
                }
                return
            }
            runIo({ DocumentExportDelivery(contentResolver).save(file, uri, completedExports.expectedHash(file.name)) }) { result ->
                lastSavedName = file.name
                when (screen) { "backup" -> showBackup(); "reading" -> showReading(); else -> showHome() }
                AlertDialog.Builder(this).setTitle("已另存并校验")
                    .setMessage("${file.name}\n${result.bytes / 1024} KB\n已保存到你选择的位置，与应用内完整文件一致。")
                    .setPositiveButton("知道了", null).show()
            }
            return
        }
        if (resultCode != RESULT_OK || requestCode != BACKUP_REQUEST) return
        val uri = data?.data ?: return
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "所选备份文件"
        val previous = restoreSource
        runIo({
            previous?.delete()
            val temp = File(cacheDir, "restore-source-${System.currentTimeMillis()}.tlb")
            contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("无法读取所选文件")
            temp
        }) { file ->
            restoreSource = file
            restoreSourceName = name
            restorePreview = null
            showRestore()
        }
    }

    private fun discardRestore() {
        val source = restoreSource
        restoreSource = null
        restoreSourceName = null
        restorePreview = null
        restorePassword = ""
        source?.let { file -> app.io.execute { file.delete() } }
    }

    private fun renderCompletedExports() {
        val container = card()
        Ui.sectionTitle(container, "已生成的文件")
        Ui.note(container, "这里保留生成时的副本，可继续另存。删除副本不影响原始资料；卸载应用会删除这些本机副本。")
        runIo({ completedExports.list(completedOffset, 21) }) { files ->
            if (files.isEmpty()) emptyState(container, "生成备份或文档后，可在这里再次保存。")
            files.take(20).forEach { file ->
                Ui.body(container, file.name)
                addButton(container, "另存为 · ${file.length() / 1024} KB", "saveExport:${file.name}") { openExportPicker(file.name) }
                dangerIn(container, "删除本机副本", "removeExport:${file.name}") {
                    confirm("删除这份本机副本？", "原始资料与已另存的文件不受影响。如果尚未另存，这份导出文件将无法找回。", "删除") {
                        runIo({ completedExports.remove(file.name) }) { showHome() }
                    }
                }
            }
            if (completedOffset > 0) addButton(container, "上一页", "exportsPrevious") { completedOffset -= 20; showHome() }
            if (files.size > 20) addButton(container, "下一页", "exportsNext") { completedOffset += 20; showHome() }
            requestedSaveName?.let { name -> requestedSaveName = null; openExportPicker(name) }
        }
    }

    private fun openExportPicker(name: String) {
        if (pendingSaveName != null) return
        val file = completedExports.get(name) ?: return showError(IllegalStateException("文件已不存在，请重新生成"))
        try {
            pendingSaveName = name
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (file.extension == "zip") "application/zip" else "application/octet-stream"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                putExtra(Intent.EXTRA_TITLE, file.name)
            }, EXPORT_REQUEST)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingSaveName = null
            showError(IllegalStateException("未找到系统文件选择器。完整文件已保留，请检查手机的文件应用后重试。"))
        }
    }

    companion object {
        private const val EXTRA_READY_EXPORT = "readyExportName"
        fun saveCompleted(context: Context, name: String) {
            context.startActivity(Intent(context, TransferActivity::class.java).putExtra(EXTRA_READY_EXPORT, name))
        }
        private const val BACKUP_REQUEST = 43
        private const val EXPORT_REQUEST = 44
    }
}
