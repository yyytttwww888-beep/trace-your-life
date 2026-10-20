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
import dev.tracelife.ui.Ui
import dev.tracelife.ui.AppNavigation
import dev.tracelife.ui.ReflectionActivity
import dev.tracelife.analysis.ReflectionRun
import dev.tracelife.analysis.ReflectionStatus
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
    private var resumedOnce = false
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
        if (savedInstanceState == null) {
            intent.getStringExtra(EXTRA_RECORD_ID)?.let {
                recordId = it
                historicalRevision = intent.getIntExtra(EXTRA_RECORD_REVISION, -1).takeIf { version -> version > 0 }
                screen = "detail"
            }
        }
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

    override fun onResume() {
        super.onResume()
        if (resumedOnce && screen == "records" && !busy) loadRecords()
        resumedOnce = true
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
        root = Ui.mount(
            this, title,
            onBack = if (name != "records") ({ navigateBack() }) else null,
            onMenu = if (name == "records") ({
                if (!busy) AppNavigation.show(this) { captureInputs(); showTemplates() }
            }) else null,
        )
    }

    private fun showRecords() {
        beginScreen("records", "Trace Life")
        Ui.screenTitle(root, "留下一点今天").apply {
            textSize = 28f
            setPadding(0, dp(20), 0, dp(8))
        }
        Ui.note(root, "一个念头，一件小事，都值得记下。")
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, dp(24))
            root.addView(this)
        }
        Ui.primary(actions, "写一条", "newRecord") {
            if (!busy) { editor = EditorState(); showEditor() }
        }.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, dp(10), 0)
        }
        Ui.button(actions, "做个复盘", "templates") { if (!busy) showTemplates() }
            .layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        Ui.card(root).apply { tag = "reflectionOverview" }
        Ui.sectionTitle(root, "最近记录")
        val filtered = filterFrom.isNotBlank() || filterThrough.isNotBlank() || filterTopic.isNotBlank() || includeArchived || !includeUnknown
        Ui.disclosure(root, if (filtered) "筛选 · 已设置" else "筛选记录", "recordFilters", expanded = filtered) { filters ->
            Ui.note(filters, "按事情发生的日期查找，也可以包含日期不确定的记录。")
            field("filterFrom", "YYYY-MM-DD，可留空", filterFrom, parent = filters, label = "从哪天")
            field("filterThrough", "YYYY-MM-DD，可留空", filterThrough, parent = filters, label = "到哪天")
            field("filterTopic", "例如：学习", filterTopic, parent = filters, label = "主题")
            archivedCheck = check("包含已归档记录", includeArchived, parent = filters)
            unknownCheck = check("包含日期不确定的记录", includeUnknown, parent = filters)
            addButton(filters, "应用筛选", "applyFilter") {
                captureInputs()
                offset = 0
                loadRecords()
            }
            Ui.textButton(filters, "清除筛选", "clearFilters") {
                if (!busy) {
                    filterFrom = ""; filterThrough = ""; filterTopic = ""
                    includeArchived = false; includeUnknown = true; offset = 0
                    showRecords()
                }
            }
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
        runIo({ repository.listRecords(filter) to runCatching { app.reflectionWorkflow.history(limit = 1).firstOrNull() } }) { (page, reflection) ->
            renderReflectionOverview(reflection)
            addText(results, if (page.unknownTimeCount > 0) "${page.total} 条记录 · 发生日期未知 ${page.unknownTimeCount} 条" else "${page.total} 条记录", 13f)
            if (page.items.isEmpty()) {
                Ui.emptyState(results, if (filterFrom.isNotBlank() || filterThrough.isNotBlank() || filterTopic.isNotBlank())
                    "没有找到符合条件的记录\n试试调整日期或主题。"
                else "这里会慢慢留下你的日常。\n从第一条记录开始。")
            }
            page.items.forEach { record ->
                val preview = record.draft.body.ifBlank { record.draft.answers.values.firstOrNull().orEmpty().ifBlank { "图片记录" } }
                Ui.recordRow(results, "${dateLabel(record)}${if (record.archived) " · 已归档" else ""}", preview.take(140),
                    record.draft.topics.joinToString("  "), "record:${record.id}") {
                    if (!busy) showDetail(record.id)
                }
            }
            if (offset > 0) addButton(results, "上一页", "previousPage") { offset = (offset - PAGE_SIZE).coerceAtLeast(0); loadRecords() }
            page.nextOffset?.let { next -> addButton(results, "下一页", "nextPage") { offset = next; loadRecords() } }
        }
    }

    private fun renderReflectionOverview(latest: Result<ReflectionRun?>) {
        val card = root.findViewWithTag<LinearLayout>("reflectionOverview") ?: return
        card.removeAllViews()
        Ui.sectionTitle(card, "分析与发现")
        val run = latest.getOrNull()
        if (latest.isFailure) {
            Ui.note(card, "暂时无法读取上次分析，可以进入分析页重新查看。")
        } else if (run == null) {
            Ui.note(card, "还没有综合分析。看看这些记录里，有哪些经验、问题和下一步。")
        } else {
            val label = when (run.status) {
                ReflectionStatus.ANALYZING -> if (run.totalRequests == 0) "尚未开始" else "尚有记录待分析"
                ReflectionStatus.INTEGRATING -> "正在汇总 · 需要继续"
                ReflectionStatus.READY -> "已有发现"
                ReflectionStatus.NO_TEXT -> "没有可分析的文字"
                ReflectionStatus.RETRY_NEEDED -> "上次未完成 · 可以重试"
                ReflectionStatus.NEEDS_RECOVERY -> "上次中断 · 等待恢复"
                ReflectionStatus.BUDGET_EXHAUSTED -> "已达请求上限"
                ReflectionStatus.DEPENDENCY_BLOCKED -> "依据或意见变化 · 需要更新"
                ReflectionStatus.STALE -> "有新记录或修改 · 需要更新"
                ReflectionStatus.EXPIRED -> "已过期 · 需要更新"
                ReflectionStatus.CONFLICT -> "内容变化 · 请重新查看"
            }
            Ui.note(card, "上次范围 ${run.scope.from} — ${run.scope.through}\n$label")
            val saved = run.findings.count { it.knowledgeId != null }
            Ui.note(card, "${run.findings.size} 项发现 · $saved 项已保存")
            val pending = run.findings.count { it.knowledgeId != null && it.knowledgeEvidenceState != dev.tracelife.knowledge.KnowledgeEvidenceState.CURRENT }
            if (pending > 0) Ui.note(card, "其中 $pending 项待更新，已保存的内容仍可在知识库回看。")
            if (run.status == ReflectionStatus.READY) {
                run.findings.firstOrNull { it.feedback?.feedback?.decision != dev.tracelife.storage.ClaimFeedbackDecision.REJECTED }
                    ?.let { Ui.body(card, it.text.take(180)) }
            }
            Ui.button(card, "查看上次分析", "latestReflection") {
                if (!busy) startActivity(Intent(this, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, run.id))
            }
        }
        Ui.primary(card, "分析记录", "analyzeRecords") {
            if (!busy) startActivity(Intent(this, ReflectionActivity::class.java))
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
            val contentCard = dev.tracelife.ui.Ui.card(root)
            dev.tracelife.ui.Ui.cardTitle(contentCard, dateLabel(record))
            if (record.archived) dev.tracelife.ui.Ui.chip(contentCard, "已归档", dev.tracelife.ui.Ui.Tone.NEUTRAL)

            dev.tracelife.ui.Ui.note(contentCard, "主题：${record.draft.topics.joinToString("、").ifEmpty { "未分类" }}")
            if (record.draft.body.isNotEmpty()) dev.tracelife.ui.Ui.body(contentCard, record.draft.body)
            detail.template?.let { template ->
                val templateCard = dev.tracelife.ui.Ui.card(root)
                dev.tracelife.ui.Ui.cardTitle(templateCard, "${template.draft.name} · 模板版本 ${template.version}")
                template.draft.questions.forEach { question ->
                    dev.tracelife.ui.Ui.fieldLabel(templateCard, question.prompt)
                    dev.tracelife.ui.Ui.body(templateCard, record.draft.answers[question.id]?.takeIf { it.isNotBlank() } ?: "未填写")
                }
            }
            detail.attachments.forEach { attachment ->
                dev.tracelife.ui.Ui.card(root).also { card -> dev.tracelife.ui.Ui.note(card, "图片：${attachment.displayName}（${attachment.sizeBytes / 1024} KiB）") }
            }
            Ui.disclosure(root, "记录信息", "recordInformation") { details ->
                Ui.note(details, "录入：${record.createdAt}\n最近修改：${record.updatedAt}\n时区：${record.draft.occurrence.zoneId}")
            }
            if (revision == null) {
                val reflection = Ui.card(root)
                Ui.sectionTitle(reflection, "从记录里发现一点什么")
                Ui.note(reflection, "结合这段时间的记录，找出值得留下的经验和可以改进的地方。")
                Ui.primary(reflection, "分析这段时间", "analyzeRecordPeriod") {
                    if (!busy) startActivity(Intent(this, ReflectionActivity::class.java).apply {
                        record.draft.occurrence.startDate?.let { putExtra(ReflectionActivity.EXTRA_FROM, it.toString()) }
                        record.draft.occurrence.endDate?.let { putExtra(ReflectionActivity.EXTRA_THROUGH, it.toString()) }
                    })
                }
                val actionsCard = dev.tracelife.ui.Ui.card(root)
                dev.tracelife.ui.Ui.primary(actionsCard, "编辑记录", "editRecord") { if (!busy) { editor = EditorState.from(record); showEditor() } }
                addButton(actionsCard, "修改历史", "history") {
                    runIo({ repository.history(id) }) { history ->
                        AlertDialog.Builder(this).setTitle("修订历史")
                            .setItems(history.map { "修订 ${it.revision} · ${it.updatedAt}${if (it.archived) " · 已归档" else ""}" }.toTypedArray()) { _, index ->
                                showDetail(id, history[index].revision)
                            }.setNegativeButton("关闭", null).show()
                    }
                }
                addButton(actionsCard, if (record.archived) "移回记录列表" else "归档记录", "archiveRecord") {
                    runIo({ repository.setArchived(id, record.revision, !record.archived) }) { showDetail(id) }
                }
                dev.tracelife.ui.Ui.dangerButton(actionsCard, "永久删除记录", "deleteRecord") {
                    if (!busy) confirm("永久删除这条记录？", "正文、回答及所有修订会被删除；没有其他记录引用的图片也会清理。此操作不能撤销。", "永久删除") {
                        runIo({ repository.deleteRecord(id, record.revision) }) { recordId = null; showRecords() }
                    }
                }
            }
        }
    }

    private fun showEditor() {
        val state = requireNotNull(editor)
        beginScreen("editor", if (state.expectedRevision == null) "写一条" else "编辑记录")
        if (state.template == null) renderEditor(null) else runIo({
            requireNotNull(repository.template(state.template.id, state.template.version)) { "这份复盘绑定的模板版本不可用" }
        }) { renderEditor(it) }
    }

    private fun renderEditor(template: TemplateVersion?) {
        val state = requireNotNull(editor)
        Ui.note(root, if (template == null) "不用写完整，先记下此刻想留住的。" else "挑想回答的问题写，其他的可以留空。")
        val contentCard = Ui.card(root)
        field("body", if (template == null) "今天发生了什么？" else "想补充些什么？", state.body, true, parent = contentCard).apply {
            minLines = if (template == null) 6 else 3
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            setPadding(dp(2), dp(10), dp(2), dp(10))
            textSize = 17f
            setLineSpacing(0f, 1.35f)
        }
        template?.let {
            val questions = Ui.card(root)
            Ui.cardTitle(questions, it.draft.name)
            Ui.note(questions, it.draft.description)
            it.draft.questions.forEach { question ->
                Ui.cardTitle(questions, question.prompt)
                if (question.hint.isNotEmpty()) Ui.note(questions, question.hint)
                answerFields[question.id] = field("answer:${question.id}", "慢慢想，慢慢写", state.answers[question.id].orEmpty(), true, parent = questions)
            }
        }
        Ui.primary(root, "保存记录", "saveRecord") { if (!busy) saveRecord() }
        val metadata = Ui.disclosure(root, "日期与主题", "recordMetadata", expanded = state.dateMode != 0 || state.topics.isNotBlank()) { }
        val dateCard = Ui.card(metadata)
        dev.tracelife.ui.Ui.sectionTitle(dateCard, "发生时间")
        dateSpinner = Spinner(this).apply {
            tag = "dateMode"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, listOf("某一天", "日期区间", "发生日期未知")).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(state.dateMode)
            dev.tracelife.ui.Ui.styleSpinner(this@MainActivity, this)
            dateCard.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(8)) })
        }
        val start = field("start", "发生／开始日期（YYYY-MM-DD）", state.start, parent = dateCard)
        val end = field("end", "结束日期（YYYY-MM-DD）", state.end, parent = dateCard)
        fun dateVisibility() {
            start.visibility = if (dateSpinner!!.selectedItemPosition == 2) View.GONE else View.VISIBLE
            end.visibility = if (dateSpinner!!.selectedItemPosition == 1) View.VISIBLE else View.GONE
        }
        dateSpinner!!.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = dateVisibility()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        dateVisibility()
        dev.tracelife.ui.Ui.note(dateCard, "时区：${state.zoneId}")
        field("topics", "多个主题用逗号隔开，例如：工作，学习", state.topics, parent = dateCard, label = "主题")
        val attachmentCard = Ui.disclosure(root, "图片${if (state.attachments.isEmpty()) "" else " · ${state.attachments.size} 张"}", "recordImages", expanded = state.attachments.isNotEmpty()) { }
        Ui.note(attachmentCard, "图片随记录保存，暂不识别内容。")
        state.attachments.forEach { id ->
            addButton(attachmentCard, "移除图片：${attachmentNames[id] ?: id.take(8)}", "removeAttachment:$id") {
                captureInputs()
                state.attachments.remove(id)
                // Old revisions keep their references; only unreferenced imports are reclaimed.
                runIo({ repository.recoverUnreferencedAttachments(state.attachments.toSet()) }) { showEditor() }
            }
        }
        addButton(attachmentCard, "选择图片附件", "addAttachment") {
            captureInputs()
            openDocument("image/*", IMAGE_REQUEST)
        }
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
        beginScreen("templates", "做个复盘")
        Ui.note(root, "回看一次经历，留下一点经验。")
        Ui.textButton(root, "添加自己的模板", "importTemplate") { if (!busy) { templateEditor = TemplateEditorState(); showTemplateEditor() } }
        val archived = check("显示已归档模板", showArchivedTemplates)
        archived.setOnCheckedChangeListener { _, checked -> showArchivedTemplates = checked; showTemplates() }
        runIo({ repository.listTemplates(showArchivedTemplates) }) { templates ->
            if (templates.isEmpty()) dev.tracelife.ui.Ui.emptyState(root, "暂无可用模板")
            templates.forEach { template ->
                val card = dev.tracelife.ui.Ui.card(root)
                dev.tracelife.ui.Ui.cardTitle(card, template.draft.name)
                if (template.archived) dev.tracelife.ui.Ui.chip(card, "已归档", dev.tracelife.ui.Ui.Tone.NEUTRAL)
                Ui.note(card, "${cadenceLabel(template.draft.cadence)} · ${template.draft.questions.size} 个可选问题")
                if (!template.archived) addButton(card, "开始复盘", "useTemplate:${template.id}") {
                    editor = EditorState(template = TemplateRef(template.id, template.version)).also { state ->
                        if (template.draft.cadence == Cadence.WEEKLY) {
                            state.dateMode = 1
                            state.start = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString()
                        }
                    }
                    showEditor()
                }
                val management = Ui.disclosure(card, "了解与管理模板", "templateManagement:${template.id}") { details ->
                    Ui.note(details, template.draft.description)
                    Ui.note(details, "版本 ${template.version}。修改模板只影响之后的新复盘。")
                }
                addButton(management, "编辑模板", "editTemplate:${template.id}") {
                    templateEditor = TemplateEditorState(template.id, template.version, template.draft.name, template.draft.cadence, templateMarkdown(template.draft))
                    showTemplateEditor()
                }
                addButton(management, if (template.archived) "恢复模板" else "归档模板", "archiveTemplate:${template.id}") {
                    runIo({ repository.archiveTemplate(template.id, !template.archived) }) { showTemplates() }
                }
                dev.tracelife.ui.Ui.dangerButton(management, "删除未使用的模板", "deleteTemplate:${template.id}") {
                    if (!busy) confirm("删除这份模板及全部版本？", "只有未被任何复盘引用的模板才能删除。已使用的模板可以归档。", "删除模板") {
                        runIo({ repository.deleteTemplate(template.id) }) { showTemplates() }
                    }
                }
            }
        }
    }

    private fun showTemplateEditor() {
        val state = requireNotNull(templateEditor)
        beginScreen("templateEditor", if (state.expectedVersion == null) "导入个人模板" else "编辑模板新版本")
        val card = dev.tracelife.ui.Ui.card(root)
        dev.tracelife.ui.Ui.note(card, "粘贴文字或导入 UTF-8 文本／Markdown（最多 256 KiB）。二级标题 ## 作为问题；也支持列表或自由说明。可修改原文，预览后再保存。")
        field("templateName", "例如：我的每周复盘", state.name, parent = card, label = "模板名称")
        dev.tracelife.ui.Ui.fieldLabel(card, "节奏")
        cadenceSpinner = Spinner(this).apply {
            tag = "cadence"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, listOf("每日", "每周", "事件")).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(state.cadence.ordinal)
            dev.tracelife.ui.Ui.styleSpinner(this@MainActivity, this)
            card.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(8)) })
        }
        field("markdown", "模板原文", state.markdown, true, parent = card, label = "模板原文")
        addButton(card, "从文件读取模板", "openTemplateFile") { captureInputs(); openDocument("text/*", TEMPLATE_REQUEST) }
        dev.tracelife.ui.Ui.primary(card, "预览问题并确认保存", "previewTemplate") {
            if (busy) return@primary
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
    private fun field(key: String, hint: String, value: String, multiline: Boolean = false, parent: LinearLayout = root, label: String? = null): EditText {
        if (label != null) dev.tracelife.ui.Ui.fieldLabel(parent, label)
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
        parent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(2), 0, dp(10))
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
    private fun check(label: String, checked: Boolean, parent: LinearLayout = root) = CheckBox(this).apply { text = label; isChecked = checked; dev.tracelife.ui.Ui.styleCheck(this@MainActivity, this); parent.addView(this) }
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
        const val EXTRA_RECORD_ID = "recordId"
        const val EXTRA_RECORD_REVISION = "recordRevision"
        private const val PAGE_SIZE = 20
        private const val TEMPLATE_REQUEST = 41
        private const val IMAGE_REQUEST = 42
    }
}
