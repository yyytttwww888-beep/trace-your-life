package dev.tracelife

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.tracelife.model.Attachment
import dev.tracelife.model.Cadence
import dev.tracelife.model.RecordFilter
import dev.tracelife.model.RecordRevision
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateRef
import dev.tracelife.model.TemplateVersion
import dev.tracelife.templates.TemplateImporter
import dev.tracelife.ui.EditorState
import dev.tracelife.ui.TemplateEditorState
import dev.tracelife.ui.parseDate
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Functional Android entry point. All authoritative reads and writes run on the app IO worker. */
class MainActivity : Activity() {
    private val app get() = application as TraceLifeApplication
    private val repository get() = app.repository
    private lateinit var root: LinearLayout
    private var screen = "records"
    private var busy = false
    private var generation = 0
    private var recordId: String? = null
    private var historicalRevision: Int? = null
    private var editor: EditorState? = null
    private var templateEditor: TemplateEditorState? = null
    private var editorFields = linkedMapOf<String, EditText>()
    private var answerFields = linkedMapOf<String, EditText>()
    private var dateSpinner: Spinner? = null
    private var cadenceSpinner: Spinner? = null
    private var filterFrom = ""
    private var filterThrough = ""
    private var filterTopic = ""
    private var includeArchived = false
    private var includeUnknown = true
    private var offset = 0
    private var archivedCheck: CheckBox? = null
    private var unknownCheck: CheckBox? = null
    private var showArchivedTemplates = false
    private var pendingDocumentUri: String? = null
    private var pendingDocumentRequest = 0
    private val attachmentNames = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            screen = state.getString("screen") ?: "records"
            recordId = state.getString("recordId")
            historicalRevision = state.getInt("historicalRevision", -1).takeIf { it >= 0 }
            editor = state.getBundle("editor")?.let(EditorState::restore)
            templateEditor = state.getBundle("templateEditor")?.let(TemplateEditorState::restore)
            filterFrom = state.getString("filterFrom").orEmpty()
            filterThrough = state.getString("filterThrough").orEmpty()
            filterTopic = state.getString("filterTopic").orEmpty()
            includeArchived = state.getBoolean("includeArchived")
            includeUnknown = state.getBoolean("includeUnknown", true)
            offset = state.getInt("offset")
            showArchivedTemplates = state.getBoolean("archivedTemplates")
            pendingDocumentUri = state.getString("pendingDocumentUri")
            pendingDocumentRequest = state.getInt("pendingDocumentRequest")
        }
        val retainedAttachments = editor?.attachments.orEmpty().toSet()
        app.io.execute {
            runCatching { repository.recoverUnreferencedAttachments(retainedAttachments) }.onFailure { failure ->
                runOnUiThread { if (!isDestroyed && !isFinishing) showError(failure) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                navigateBack()
            }
        }
        when (screen) {
            "editor" -> if (editor != null) showEditor() else showRecords()
            "templateEditor" -> if (templateEditor != null) showTemplateEditor() else showTemplates()
            "detail" -> recordId?.let { showDetail(it, historicalRevision) } ?: showRecords()
            "templates" -> showTemplates()
            else -> showRecords()
        }
        consumePendingDocument()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureInputs()
        outState.putString("screen", screen)
        outState.putString("recordId", recordId)
        outState.putInt("historicalRevision", historicalRevision ?: -1)
        outState.putBundle("editor", editor?.save())
        outState.putBundle("templateEditor", templateEditor?.save())
        outState.putString("filterFrom", filterFrom)
        outState.putString("filterThrough", filterThrough)
        outState.putString("filterTopic", filterTopic)
        outState.putBoolean("includeArchived", includeArchived)
        outState.putBoolean("includeUnknown", includeUnknown)
        outState.putInt("offset", offset)
        outState.putBoolean("archivedTemplates", showArchivedTemplates)
        outState.putString("pendingDocumentUri", pendingDocumentUri)
        outState.putInt("pendingDocumentRequest", pendingDocumentRequest)
        super.onSaveInstanceState(outState)
    }

    // API 33+ uses the dispatcher registered in onCreate; this is the older API entry point.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("For Android versions before predictive back")
    override fun onBackPressed() = navigateBack()

    private fun navigateBack() {
        if (busy) return
        when (screen) {
            "editor" -> confirm("放弃未保存的记录修改？", "已保存的历史版本不受影响。", "放弃修改") {
                val abandoned = editor?.attachments.orEmpty().toSet()
                editor = null
                app.io.execute { abandoned.forEach { id -> runCatching { repository.discardAttachment(id) } } }
                showRecords()
            }
            "templateEditor" -> confirm("放弃未保存的模板修改？", "本次输入尚未保存为模板版本。", "放弃修改") {
                templateEditor = null
                showTemplates()
            }
            "detail" -> if (historicalRevision != null) showDetail(requireNotNull(recordId)) else showRecords()
            "templates" -> showRecords()
            else -> finish()
        }
    }

    private fun beginScreen(name: String, title: String) {
        generation++
        screen = name
        editorFields.clear()
        answerFields.clear()
        dateSpinner = null
        cadenceSpinner = null
        archivedCheck = null
        unknownCheck = null
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply {
            addView(root)
            setBackgroundColor(dev.tracelife.ui.Ui.bg(this@MainActivity))
        }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        if (name != "records") dev.tracelife.ui.Ui.backRow(root) { navigateBack() }
        dev.tracelife.ui.Ui.screenTitle(root, title)
    }

    private fun showRecords() {
        beginScreen("records", "Trace Life")
        dev.tracelife.ui.Ui.card(root).also { card ->
            dev.tracelife.ui.Ui.sectionTitle(card, "记录")
            dev.tracelife.ui.Ui.primary(card, "新建随记", "newRecord") {
                if (!busy) {
                    editor = EditorState()
                    showEditor()
                }
            }
            dev.tracelife.ui.Ui.button(card, "复盘与模板", "templates") { if (!busy) showTemplates() }
        }
        dev.tracelife.ui.Ui.card(root).also { card ->
            dev.tracelife.ui.Ui.sectionTitle(card, "筛选")
            dev.tracelife.ui.Ui.note(card, "按发生日期筛选；补记使用事情发生的日期。没有记录不等于没有发生。")
        }
        field("filterFrom", "起始日期（可留空，YYYY-MM-DD）", filterFrom)
        field("filterThrough", "结束日期（可留空，YYYY-MM-DD）", filterThrough)
        field("filterTopic", "主题（填写一个主题，可留空）", filterTopic)
        archivedCheck = check("包含已归档记录", includeArchived)
        unknownCheck = check("同时展示发生日期未知的记录", includeUnknown)
        button("应用筛选", "applyFilter") {
            captureInputs()
            offset = 0
            loadRecords()
        }
        dev.tracelife.ui.Ui.card(root).also { card ->
            dev.tracelife.ui.Ui.sectionTitle(card, "更多功能")
            dev.tracelife.ui.Ui.button(card, "看板", "navDashboard") { startActivity(Intent(this, dev.tracelife.ui.DashboardActivity::class.java)) }
            dev.tracelife.ui.Ui.button(card, "核对与整理", "navReview") { startActivity(Intent(this, dev.tracelife.ui.ReviewActivity::class.java)) }
            dev.tracelife.ui.Ui.button(card, "知识库", "navKnowledge") { startActivity(Intent(this, dev.tracelife.ui.KnowledgeActivity::class.java)) }
            dev.tracelife.ui.Ui.button(card, "行动实验", "navActions") { startActivity(Intent(this, dev.tracelife.ui.ActionsActivity::class.java)) }
            dev.tracelife.ui.Ui.button(card, "周期复盘", "navPeriod") { startActivity(Intent(this, dev.tracelife.ui.PeriodActivity::class.java)) }
            dev.tracelife.ui.Ui.button(card, "个人画像", "navProfile") { startActivity(Intent(this, dev.tracelife.ui.ProfileActivity::class.java)) }
            dev.tracelife.ui.Ui.button(card, "备份与导出", "navTransfer") { startActivity(Intent(this, dev.tracelife.ui.TransferActivity::class.java)) }
            dev.tracelife.ui.Ui.button(card, "设置", "navSettings") { startActivity(Intent(this, dev.tracelife.ui.SettingsActivity::class.java)) }
        }
        loadRecords()
    }

    private fun loadRecords() {
        val filter = try {
            RecordFilter(
                from = filterFrom.takeIf { it.isNotBlank() }?.let { parseDate(it, "起始日期") },
                through = filterThrough.takeIf { it.isNotBlank() }?.let { parseDate(it, "结束日期") },
                topic = filterTopic.trim().ifEmpty { null },
                includeArchived = includeArchived,
                includeUnknown = includeUnknown,
                limit = PAGE_SIZE,
                offset = offset,
            ).also { require(it.from == null || it.through == null || !it.through.isBefore(it.from)) { "结束日期不能早于起始日期" } }
        } catch (failure: IllegalArgumentException) {
            showError(failure)
            return
        }
        val existing = root.findViewWithTag<View>("recordResults")
        if (existing != null) root.removeView(existing)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "recordResults" }
        root.addView(results)
        runIo({ repository.listRecords(filter) }) { page ->
            addText(results, "匹配 ${page.total} 条；发生日期未知 ${page.unknownTimeCount} 条。日期未知的资料无法归入所选周期。")
            if (page.items.isEmpty()) addText(results, "当前范围没有记录。")
            page.items.forEach { record ->
                val preview = record.draft.body.ifBlank { record.draft.answers.values.firstOrNull().orEmpty().ifBlank { "图片记录" } }
                addButton(results, "${dateLabel(record)}${if (record.archived) " · 已归档" else ""}\n${preview.take(100)}", "record:${record.id}") {
                    showDetail(record.id)
                }
            }
            if (offset > 0) addButton(results, "上一页", "previousPage") { offset = (offset - PAGE_SIZE).coerceAtLeast(0); loadRecords() }
            page.nextOffset?.let { next -> addButton(results, "下一页", "nextPage") { offset = next; loadRecords() } }
        }
    }

    private data class RecordDetail(val record: RecordRevision, val template: TemplateVersion?, val attachments: List<Attachment>)

    private fun showDetail(id: String, revision: Int? = null) {
        recordId = id
        historicalRevision = revision
        beginScreen("detail", if (revision == null) "记录详情" else "历史版本 · $revision")
        runIo({
            val record = requireNotNull(repository.record(id, revision)) { "记录不存在或已被删除" }
            RecordDetail(record, record.draft.template?.let { repository.template(it.id, it.version) }, record.draft.attachmentIds.mapNotNull(repository::attachment))
        }) { detail ->
            val record = detail.record
            text("${dateLabel(record)} · 修订 ${record.revision}${if (record.archived) " · 已归档" else ""}")
            text("录入：${record.createdAt}\n最近修订：${record.updatedAt}\n时区：${record.draft.occurrence.zoneId}")
            text("主题：${record.draft.topics.joinToString("、").ifEmpty { "未分类" }}")
            if (record.draft.body.isNotEmpty()) text(record.draft.body)
            detail.template?.let { template ->
                text("${template.draft.name} · 模板版本 ${template.version}", 18f)
                template.draft.questions.forEach { question ->
                    text(question.prompt, 16f)
                    text(record.draft.answers[question.id]?.takeIf { it.isNotBlank() } ?: "未填写")
                }
            }
            detail.attachments.forEach { attachment -> text("图片：${attachment.displayName}（${attachment.sizeBytes / 1024} KiB）") }
            if (revision == null) {
                button("编辑记录", "editRecord") { editor = EditorState.from(record); showEditor() }
                button("查看修订历史", "history") {
                    runIo({ repository.history(id) }) { history ->
                        AlertDialog.Builder(this).setTitle("修订历史")
                            .setItems(history.map { "修订 ${it.revision} · ${it.updatedAt}${if (it.archived) " · 已归档" else ""}" }.toTypedArray()) { _, index ->
                                showDetail(id, history[index].revision)
                            }.setNegativeButton("关闭", null).show()
                    }
                }
                button(if (record.archived) "恢复到活跃记录" else "归档记录", "archiveRecord") {
                    runIo({ repository.setArchived(id, record.revision, !record.archived) }) { showDetail(id) }
                }
                button("永久删除记录", "deleteRecord") {
                    confirm("永久删除这条记录？", "正文、回答及所有修订会被删除；没有其他记录引用的图片也会清理。此操作不能撤销。", "永久删除") {
                        runIo({ repository.deleteRecord(id, record.revision) }) { recordId = null; showRecords() }
                    }
                }
            }
        }
    }

    private fun showEditor() {
        val state = requireNotNull(editor)
        beginScreen("editor", if (state.expectedRevision == null) "新增记录" else "编辑记录")
        if (state.template == null) renderEditor(null) else runIo({
            requireNotNull(repository.template(state.template.id, state.template.version)) { "这份复盘绑定的模板版本不可用" }
        }) { renderEditor(it) }
    }

    private fun renderEditor(template: TemplateVersion?) {
        val state = requireNotNull(editor)
        text("可随手写，也可以只填写部分复盘问题。原文在本机保存。")
        text("发生时间")
        dateSpinner = spinner(listOf("某一天", "日期区间", "发生日期未知"), state.dateMode, "dateMode")
        val start = field("start", "发生／开始日期（YYYY-MM-DD）", state.start)
        val end = field("end", "结束日期（YYYY-MM-DD）", state.end)
        fun dateVisibility() {
            start.visibility = if (dateSpinner!!.selectedItemPosition == 2) View.GONE else View.VISIBLE
            end.visibility = if (dateSpinner!!.selectedItemPosition == 1) View.VISIBLE else View.GONE
        }
        dateSpinner!!.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = dateVisibility()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        dateVisibility()
        text("时区：${state.zoneId}")
        field("topics", "多个主题用逗号分隔，例如：工作，学习", state.topics)
        field("body", "随记／补充内容（可选）", state.body, true)
        template?.let {
            text("${it.draft.name} · 版本 ${it.version}", 18f)
            text(it.draft.description)
            text("所有问题均可跳过；编辑旧复盘仍使用当时的模板。")
            it.draft.questions.forEach { question ->
                text(question.prompt, 17f)
                if (question.hint.isNotEmpty()) text(question.hint)
                answerFields[question.id] = field("answer:${question.id}", "回答（可选）", state.answers[question.id].orEmpty(), true)
            }
        }
        text("附件图片（本阶段只保存原图）", 17f)
        state.attachments.forEach { id ->
            button("移除图片：${attachmentNames[id] ?: id.take(8)}", "removeAttachment:$id") {
                captureInputs()
                state.attachments.remove(id)
                // Old revisions keep their references; only unreferenced imports are reclaimed.
                runIo({ repository.recoverUnreferencedAttachments(state.attachments.toSet()) }) { showEditor() }
            }
        }
        button("选择图片附件", "addAttachment") {
            captureInputs()
            openDocument("image/*", IMAGE_REQUEST)
        }
        button("保存记录", "saveRecord") { saveRecord() }
    }

    private fun saveRecord() {
        captureInputs()
        val state = requireNotNull(editor)
        val draft = try { state.draft() } catch (failure: IllegalArgumentException) { showError(failure); return }
        runIo({
            val current = repository.record(state.id)
            if (current != null && current.draft == draft && (state.expectedRevision == null || current.revision == state.expectedRevision + 1)) current
            else if (state.expectedRevision == null) repository.createRecord(draft, state.id)
            else repository.updateRecord(state.id, state.expectedRevision, draft)
        }) { saved ->
            editor = null
            (application as TraceLifeApplication).let { app -> app.io.execute { app.backgroundScheduler.wake() } }
            Toast.makeText(this, "记录已保存", Toast.LENGTH_SHORT).show()
            showDetail(saved.id)
        }
    }

    private fun showTemplates() {
        beginScreen("templates", "复盘与模板")
        button("粘贴／导入个人模板", "importTemplate") { templateEditor = TemplateEditorState(); showTemplateEditor() }
        val archived = check("显示已归档模板", showArchivedTemplates)
        archived.setOnCheckedChangeListener { _, checked -> showArchivedTemplates = checked; showTemplates() }
        runIo({ repository.listTemplates(showArchivedTemplates) }) { templates ->
            if (templates.isEmpty()) text("暂无可用模板。")
            templates.forEach { template ->
                text("${template.draft.name} · ${cadenceLabel(template.draft.cadence)} · 版本 ${template.version}${if (template.archived) " · 已归档" else ""}", 18f)
                text(template.draft.description)
                if (!template.archived) button("使用「${template.draft.name}」复盘", "useTemplate:${template.id}") {
                    editor = EditorState(template = TemplateRef(template.id, template.version)).also { state ->
                        if (template.draft.cadence == Cadence.WEEKLY) {
                            state.dateMode = 1
                            state.start = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString()
                        }
                    }
                    showEditor()
                }
                button("编辑模板新版本", "editTemplate:${template.id}") {
                    templateEditor = TemplateEditorState(template.id, template.version, template.draft.name, template.draft.cadence, templateMarkdown(template.draft))
                    showTemplateEditor()
                }
                button(if (template.archived) "恢复模板" else "归档模板", "archiveTemplate:${template.id}") {
                    runIo({ repository.archiveTemplate(template.id, !template.archived) }) { showTemplates() }
                }
                button("删除未使用的模板", "deleteTemplate:${template.id}") {
                    confirm("删除这份模板及全部版本？", "只有未被任何复盘引用的模板才能删除。已使用的模板可以归档。", "删除模板") {
                        runIo({ repository.deleteTemplate(template.id) }) { showTemplates() }
                    }
                }
            }
        }
    }

    private fun showTemplateEditor() {
        val state = requireNotNull(templateEditor)
        beginScreen("templateEditor", if (state.expectedVersion == null) "导入个人模板" else "编辑模板新版本")
        text("粘贴文字或导入 UTF-8 文本／Markdown（最多 256 KiB）。二级标题 ## 作为问题；也支持列表或自由说明。可修改原文，预览后再保存。")
        field("templateName", "模板名称", state.name)
        cadenceSpinner = spinner(listOf("每日", "每周", "事件"), state.cadence.ordinal, "cadence")
        field("markdown", "模板原文", state.markdown, true)
        button("从文件读取模板", "openTemplateFile") { captureInputs(); openDocument("text/*", TEMPLATE_REQUEST) }
        button("预览问题并确认保存", "previewTemplate") {
            captureInputs()
            runIo({
                require(state.name.isNotBlank()) { "请填写模板名称" }
                val original = state.expectedVersion?.let { repository.template(state.id, it)?.draft }
                if (original != null && original.sourceMarkdown == state.markdown) {
                    original.copy(name = state.name.trim(), cadence = state.cadence)
                } else {
                    val parsed = TemplateImporter.preview(state.markdown, state.name, state.cadence).copy(name = state.name.trim())
                    preserveQuestionIdentity(parsed, original)
                }
            }) { draft ->
                val preview = buildString {
                append(draft.name).append(" · ").append(cadenceLabel(draft.cadence)).append("\n\n")
                append(draft.description).append("\n\n")
                draft.questions.forEachIndexed { index, question -> append(index + 1).append(". ").append(question.prompt).append("\n").append(question.hint).append("\n\n") }
                append("所有问题均可跳过。保存新版本不会替换已有复盘绑定的问题。")
            }
                AlertDialog.Builder(this).setTitle("模板预览").setMessage(preview)
                .setNegativeButton("返回调整", null)
                .setPositiveButton("确认保存") { _, _ -> saveTemplate(draft) }.show()
            }
        }
    }

    private fun saveTemplate(draft: TemplateDraft) {
        val state = requireNotNull(templateEditor)
        runIo({
            val current = repository.template(state.id)
            if (current != null && current.draft == draft && (state.expectedVersion == null || current.version == state.expectedVersion + 1)) current
            else repository.saveTemplate(draft, state.id, state.expectedVersion)
        }) {
            templateEditor = null
            Toast.makeText(this, "模板已保存", Toast.LENGTH_SHORT).show()
            showTemplates()
        }
    }

    private fun openDocument(mime: String, request: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (request == TEMPLATE_REQUEST) "*/*" else mime
                if (request == TEMPLATE_REQUEST) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/octet-stream"))
            }, request)
        } catch (_: android.content.ActivityNotFoundException) {
            showError(IllegalStateException("未找到系统文件选择器，请检查手机的文件应用"))
        }
    }

    @Deprecated("Platform document picker compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        pendingDocumentUri = uri.toString()
        pendingDocumentRequest = requestCode
        consumePendingDocument()
    }

    private fun consumePendingDocument() {
        if (busy) return
        val uri = pendingDocumentUri?.let(android.net.Uri::parse) ?: return
        val requestCode = pendingDocumentRequest
        if (requestCode == TEMPLATE_REQUEST && templateEditor != null) {
            runIo({
                contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        require(output.size() + size <= TemplateImporter.MAX_IMPORT_BYTES) { "模板不能超过 256 KiB" }
                        output.write(buffer, 0, size)
                    }
                    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray())).toString().removePrefix("\uFEFF")
                } ?: throw IllegalArgumentException("无法读取所选文件")
            }) { markdown ->
                pendingDocumentUri = null
                templateEditor!!.markdown = markdown
                showTemplateEditor()
            }
        } else if (requestCode == IMAGE_REQUEST && editor != null) {
            runIo({
                val mime = contentResolver.getType(uri).orEmpty()
                require(mime.startsWith("image/")) { "请选择图片文件" }
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: "图片"
                contentResolver.openInputStream(uri)?.use { input -> repository.importAttachment(input, name, mime) }
                    ?: throw IllegalArgumentException("无法读取所选图片")
            }) { attachment ->
                pendingDocumentUri = null
                editor!!.attachments.add(attachment.id)
                attachmentNames[attachment.id] = attachment.displayName
                showEditor()
            }
        }
    }

    private fun captureInputs() {
        when (screen) {
            "records" -> {
                filterFrom = editorFields["filterFrom"]?.text?.toString() ?: filterFrom
                filterThrough = editorFields["filterThrough"]?.text?.toString() ?: filterThrough
                filterTopic = editorFields["filterTopic"]?.text?.toString() ?: filterTopic
                includeArchived = archivedCheck?.isChecked ?: includeArchived
                includeUnknown = unknownCheck?.isChecked ?: includeUnknown
            }
            "editor" -> editor?.let { state ->
                state.body = editorFields["body"]?.text?.toString() ?: state.body
                state.start = editorFields["start"]?.text?.toString() ?: state.start
                state.end = editorFields["end"]?.text?.toString() ?: state.end
                state.topics = editorFields["topics"]?.text?.toString() ?: state.topics
                state.dateMode = dateSpinner?.selectedItemPosition ?: state.dateMode
                answerFields.forEach { (id, field) -> state.answers[id] = field.text.toString() }
            }
            "templateEditor" -> templateEditor?.let { state ->
                state.name = editorFields["templateName"]?.text?.toString() ?: state.name
                state.markdown = editorFields["markdown"]?.text?.toString() ?: state.markdown
                state.cadence = cadenceSpinner?.selectedItemPosition?.let { Cadence.entries[it] } ?: state.cadence
            }
        }
    }

    private fun <T> runIo(work: () -> T, success: (T) -> Unit) {
        if (busy) return
        busy = true
        setEnabled(root, false)
        val requestGeneration = generation
        app.io.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isDestroyed || isFinishing || requestGeneration != generation) return@runOnUiThread
                busy = false
                setEnabled(root, true)
                result.fold({ value ->
                    success(value)
                    consumePendingDocument()
                }) {
                    pendingDocumentUri = null
                    showError(it)
                }
            }
        }
    }

    private fun showError(failure: Throwable) {
        val message = when (failure) {
            is RevisionConflictException -> "资料已经发生修改。你的输入仍保留，请返回查看最新版本后再编辑。"
            is java.nio.charset.CharacterCodingException -> "文件不是有效的 UTF-8 文本，请转换编码后再导入。"
            is java.io.IOException -> "文件读写失败，输入已保留。请检查剩余存储空间与文件是否可读。"
            is IllegalArgumentException, is IllegalStateException -> failure.message ?: "操作未完成，输入已保留。"
            else -> "操作未完成，输入已保留。请稍后重试。"
        }
        AlertDialog.Builder(this).setTitle("操作未完成").setMessage(message).setPositiveButton("知道了", null).show()
    }

    private fun confirm(title: String, message: String, positive: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消", null).setPositiveButton(positive) { _, _ -> action() }.show()
    }

    private fun text(value: String, size: Float = 15f) = addText(root, value, size)
    private fun addText(parent: LinearLayout, value: String, size: Float = 15f) {
        when {
            size >= 17f -> dev.tracelife.ui.Ui.sectionTitle(parent, value)
            size >= 15f -> dev.tracelife.ui.Ui.body(parent, value)
            else -> dev.tracelife.ui.Ui.note(parent, value)
        }
    }
    private fun button(label: String, tag: String, action: () -> Unit) = addButton(root, label, tag, action)
    private fun addButton(parent: LinearLayout, label: String, viewTag: String, action: () -> Unit) {
        dev.tracelife.ui.Ui.button(parent, label, viewTag) { if (!busy) action() }
    }
    private fun field(key: String, hint: String, value: String, multiline: Boolean = false): EditText {
        val input = EditText(this).apply {
            tag = key
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0
            minLines = if (multiline) 3 else 1
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(value)
            setFreezesText(false)
            isSaveEnabled = false // DraftState owns restoration, including dynamically created questions.
        }
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
        dev.tracelife.ui.Ui.styleField(this, input)
        editorFields[key] = input
        input.filters = arrayOf(InputFilter { source, start, end, destination, replaceStart, replaceEnd ->
            val length = destination.length - (replaceEnd - replaceStart) + end - start
            val isRecordText = key == "body" || key.startsWith("answer:")
            val otherRecordLength = if (isRecordText) editorFields.filterKeys { it != key && (it == "body" || it.startsWith("answer:")) }
                .values.sumOf { it.text.length } else 0
            val limit = when {
                isRecordText -> 200_000 - otherRecordLength
                key == "markdown" -> TemplateImporter.MAX_IMPORT_BYTES
                key == "topics" -> 2_500
                else -> 200
            }
            val tooManyBytes = key == "markdown" && length <= limit &&
                (destination.subSequence(0, replaceStart).toString() + source.subSequence(start, end) + destination.subSequence(replaceEnd, destination.length))
                    .toByteArray(Charsets.UTF_8).size > TemplateImporter.MAX_IMPORT_BYTES
            if ((length > limit || tooManyBytes) && length >= destination.length) {
                Toast.makeText(this, if (key == "markdown") "模板原文最多 256 KiB，请拆分后导入" else "输入已达到长度上限，请分为多条记录或缩短字段", Toast.LENGTH_LONG).show()
                destination.subSequence(replaceStart, replaceEnd)
            } else null
        })
        return input
    }
    private fun check(label: String, checked: Boolean) = CheckBox(this).apply { text = label; isChecked = checked; dev.tracelife.ui.Ui.styleCheck(this@MainActivity, this); root.addView(this) }
    private fun spinner(items: List<String>, selected: Int, viewTag: String) = Spinner(this).apply {
        tag = viewTag
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(selected)
        dev.tracelife.ui.Ui.styleSpinner(this@MainActivity, this)
        root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
    }
    private fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setEnabled(view.getChildAt(i), enabled)
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun dateLabel(record: RecordRevision): String = record.draft.occurrence.let {
        when {
            it.startDate == null -> "发生日期未知"
            it.startDate == it.endDate -> it.startDate.toString()
            else -> "${it.startDate} 至 ${it.endDate}"
        }
    }
    private fun cadenceLabel(cadence: Cadence) = when (cadence) { Cadence.DAILY -> "每日"; Cadence.WEEKLY -> "每周"; Cadence.EVENT -> "事件" }
    private fun templateMarkdown(draft: TemplateDraft): String = draft.sourceMarkdown.ifEmpty {
        buildString {
            append("# ").append(draft.name).append("\n\n").append(draft.description).append("\n")
            draft.questions.forEach { append("\n## ").append(it.prompt).append("\n").append(it.hint).append("\n") }
        }
    }
    private fun preserveQuestionIdentity(parsed: TemplateDraft, original: TemplateDraft?): TemplateDraft {
        if (original == null) return parsed
        val available = original.questions.groupBy { it.prompt.trim() to it.hint.trim() }.mapValues { it.value.toMutableList() }
        val matched = parsed.questions.map { question ->
            available[question.prompt.trim() to question.hint.trim()]?.let { matches ->
                if (matches.isEmpty()) null else matches.removeAt(0)
            }
        }
        val assigned = matched.mapNotNull { it?.id }.toMutableSet()
        return parsed.copy(questions = parsed.questions.mapIndexed { index, question ->
            matched[index]?.let { question.copy(id = it.id, intent = it.intent) } ?: run {
                var id = question.id
                while (!assigned.add(id)) id += "-new"
                question.copy(id = id)
            }
        })
    }
    companion object {
        private const val PAGE_SIZE = 20
        private const val TEMPLATE_REQUEST = 41
        private const val IMAGE_REQUEST = 42
    }
}
