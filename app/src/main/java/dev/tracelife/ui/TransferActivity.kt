package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import dev.tracelife.transfer.ExportResult
import dev.tracelife.transfer.ExportScope
import dev.tracelife.transfer.InvalidBackupException
import dev.tracelife.transfer.ReadingExportOptions
import dev.tracelife.transfer.ReadingPackageKind
import dev.tracelife.transfer.RestorePreview
import dev.tracelife.transfer.RestoreResult
import dev.tracelife.transfer.StaleSnapshotException
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TransferActivity : ScreenActivity() {
    private var backupResult: Pair<ExportResult, String>? = null
    private var readingResult: Pair<ExportResult, String>? = null
    private var restoreSource: File? = null
    private var restoreSourceName: String? = null
    private var restorePreview: RestorePreview? = null
    private var restorePassword = ""

    override fun showHome() {
        beginScreen("home", "备份与导出")
        Ui.body(root, "备份、恢复与导出都在本机完成。备份口令与密钥由你持有，应用不保存口令。")
        card().also { card ->
            Ui.sectionTitle(card, "创建备份")
            Ui.note(card, "备份包含本机全部记录、模板、附件、分析任务、用户反馈、正式知识、行动与画像，并使用口令加密。")
            addButton(card, "创建备份", "navBackup") { backupResult = null; showBackup() }
        }
        card().also { card ->
            Ui.sectionTitle(card, "恢复备份")
            Ui.note(card, "恢复将备份内容写入本机资料库。本机已有资料（含内置模板）时，必须明确勾选替换才能继续；替换不等于跨设备双向同步。")
            addButton(card, "恢复备份", "navRestore") { showRestore() }
        }
        card().also { card ->
            Ui.sectionTitle(card, "导出阅读包／证据包")
            Ui.note(card, "阅读包是导出时刻的固定历史快照，不随以后的修改、删除或纠正自动更新；不含分析任务与用户反馈，不能用于恢复备份。")
            addButton(card, "导出阅读包／证据包", "navReading") { readingResult = null; showReading() }
        }
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
            Ui.sectionTitle(card, "备份说明")
            Ui.note(card, "备份包含本机全部记录、模板、附件、分析任务、用户反馈、正式知识、行动与画像，并使用口令加密。")
            Ui.note(card, "口令只用于本次加密，应用不保存；口令丢失后无法恢复备份，设备密钥不能替代。DeepSeek KEY 与设备密钥不进入备份。")
            Ui.note(card, "文件保存在应用专属外部目录；卸载应用会删除该目录，长期保存请自行复制到其他位置。每份备份使用新文件名，已有文件不会被覆盖。")
        }
        card().also { card ->
            Ui.sectionTitle(card, "备份口令")
            val password = fieldIn(card, "备份口令（12 至 1024 个字符）", maxLength = 1024)
            password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            Ui.primary(card, "创建备份", "createBackup") onCreate@{
                if (busy) return@onCreate
                val value = password.text.toString()
                if (value.length !in 12..1024) {
                    showError(IllegalArgumentException("备份口令须为 12 至 1024 个字符"))
                    return@onCreate
                }
                val chars = value.toCharArray()
                runIo({
                    try {
                        val directory = requireNotNull(getExternalFilesDir(null)) { "外部存储不可用，无法保存备份" }
                        val file = File(directory, "trace-life-backup-${timestamp()}.tlb")
                        app.dataTransfer.exportBackup(file, chars) to file.absolutePath
                    } finally {
                        chars.fill('\u0000')
                    }
                }) { (result, path) ->
                    backupResult = result to path
                    showBackup()
                }
            }
        }
        backupResult?.let { (result, path) ->
            card().also { card ->
                Ui.sectionTitle(card, "备份结果")
                Ui.body(card, "备份完成：记录 ${result.records} 条、附件 ${result.attachments} 个、文件 ${result.files} 个，共 ${result.bytes} 字节。")
                Ui.note(card, "保存位置：$path")
            }
        }
    }

    private fun showRestore() {
        beginScreen("restore", "恢复备份")
        card().also { card ->
            Ui.sectionTitle(card, "恢复说明")
            Ui.note(card, "恢复将备份内容写入本机资料库。本机已有资料（含内置模板）时，必须明确勾选替换才能继续；替换不等于跨设备双向同步。")
            Ui.note(card, "预览之后若本机有任何新写入，预览即失效，须重新预览并确认。口令错误、格式无效或附件缺失不会修改本机资料。")
            Ui.note(card, "恢复后：后台增量整理须重新启用，DeepSeek KEY 须重新配置（密钥不进入备份）。")
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
        val password = fieldIn(fileCard, "备份口令", restorePassword, maxLength = 1024)
        password.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val preview = restorePreview
        if (preview == null) {
            Ui.primary(fileCard, "预览备份内容", "previewBackup") onPreview@{
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
            Ui.body(card, "备份快照：${preview.snapshotId}\n导出时间：${preview.capturedAt}")
            Ui.body(card, "记录 ${preview.records} 条（共 ${preview.revisions} 个修订）· 模板 ${preview.templates} 个\n" +
                "附件 ${preview.attachments} 个（${preview.attachmentBytes / 1024} KiB）\n" +
                "删除标记：记录 ${preview.deletedRecords} 条、模板 ${preview.deletedTemplates} 个\n" +
                "分析任务 ${preview.analysisRuns} 个 · 用户反馈 ${preview.claimFeedback} 条\n" +
                "正式知识 ${preview.knowledge} 条 · 行动 ${preview.actions} 条 · 画像 ${preview.profiles} 条")
            if (preview.targetHasData) {
                chip(card, "本机已有资料，须明确替换", Ui.Tone.WARN)
                Ui.note(card, "本机当前已有资料（含内置模板），恢复前必须明确勾选替换。")
            } else {
                chip(card, "本机当前没有资料", Ui.Tone.OK)
            }
        }
        card().also { card ->
            Ui.sectionTitle(card, "确认恢复")
            val replace = checkIn(card, "替换现有资料（含内置模板）", false)
            Ui.dangerButton(card, "确认恢复", "confirmRestore") onRestore@{
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
                    if (replaceExisting) "本机现有资料（含内置模板）将被备份内容替换；目标库的删除标记与独立用户反馈按规则保留。此操作不能撤销。"
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
            Ui.note(card, "按删除规则排除记录 ${result.suppressedRecords} 条；为容量上限保守清理分析任务 ${result.capacityPurgedAnalysisRuns} 个。")
            Ui.note(card, "请重新配置 DeepSeek KEY，并在「设置」中明确重新启用后台增量整理（恢复后默认停用）。")
            addButton(card, "返回备份与导出", "restoreDone") { showHome() }
        }
    }

    private fun showReading() {
        beginScreen("reading", "导出阅读包／证据包")
        card().also { card ->
            Ui.sectionTitle(card, "导出说明")
            Ui.note(card, "阅读包是导出时刻的固定历史快照，不随以后的修改、删除或纠正自动更新；不含分析任务与用户反馈，不能用于恢复备份。")
            Ui.note(card, "知识包包含导出时有效的正式知识；证据包只含原始记录，不含知识、行动与画像，须注明任务问题。记录数量只描述资料覆盖，不代表成长、健康评分或生活完整程度。")
        }
        card().also { card ->
            Ui.sectionTitle(card, "导出选项")
            val kind = spinnerIn(card, listOf("知识包（含有效正式知识）", "证据包（仅原始记录）"), 0, "kind")
            val from = fieldIn(card, "起始日期（可留空，YYYY-MM-DD）")
            val through = fieldIn(card, "结束日期（可留空，YYYY-MM-DD）")
            val topic = fieldIn(card, "主题（填写一个主题，可留空）")
            val question = fieldIn(card, "任务问题（证据包必填，最多 2000 字）", multiline = true, maxLength = 2000)
            Ui.primary(card, "导出", "exportReading") onExport@{
                if (busy) return@onExport
                val kindValue = ReadingPackageKind.entries[kind.selectedItemPosition]
                val questionValue = question.text.toString().trim()
                if (kindValue == ReadingPackageKind.EVIDENCE && questionValue.isBlank()) {
                    showError(IllegalArgumentException("证据包必须填写任务问题"))
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
                    val directory = requireNotNull(getExternalFilesDir(null)) { "外部存储不可用，无法保存导出" }
                    val prefix = if (kindValue == ReadingPackageKind.KNOWLEDGE) "trace-life-knowledge" else "trace-life-evidence"
                    val file = File(directory, "$prefix-${timestamp()}.zip")
                    app.dataTransfer.exportReadingPackage(file, options) to file.absolutePath
                }) { (result, path) ->
                    readingResult = result to path
                    showReading()
                }
            }
        }
        readingResult?.let { (result, path) ->
            card().also { card ->
                Ui.sectionTitle(card, "导出结果")
                Ui.body(card, "导出完成：记录 ${result.records} 条、附件 ${result.attachments} 个、文件 ${result.files} 个，共 ${result.bytes} 字节。")
                Ui.note(card, "保存位置：$path")
                Ui.note(card, "该文件是固定历史快照，不含分析任务与反馈，不能用于恢复备份。")
            }
        }
    }

    private fun fieldIn(parent: LinearLayout, hint: String, value: String = "", multiline: Boolean = false, maxLength: Int = 2_000): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0
            minLines = if (multiline) 3 else 1
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(value)
            setFreezesText(false)
            isSaveEnabled = false
        }
        Ui.styleField(this, input)
        parent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
        input.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        return input
    }

    private fun checkIn(parent: LinearLayout, label: String, checked: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = checked
        Ui.styleCheck(this@TransferActivity, this)
        parent.addView(this)
    }

    private fun spinnerIn(parent: LinearLayout, items: List<String>, selected: Int, viewTag: String) = Spinner(this).apply {
        tag = viewTag
        adapter = ArrayAdapter(this@TransferActivity, android.R.layout.simple_spinner_item, items).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(selected)
        Ui.styleSpinner(this@TransferActivity, this)
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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

    private fun timestamp(): String = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())

    companion object {
        private const val BACKUP_REQUEST = 43
    }
}
