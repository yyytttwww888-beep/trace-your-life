package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import dev.tracelife.MainActivity
import dev.tracelife.actions.*
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Explicit phone journey over existing action evidence, reduction and original-review gates. */
class ActionReflectionActivity : ScreenActivity() {
    private val workflow by lazy { ActionReflectionWorkflow(app.repository, app.analysis, app.analysisFeedback, app.knowledge) }
    private var runId: String? = null
    private var selected: ActionReflectionTarget? = null
    private var fromText = LocalDate.now().minusDays(6).toString()
    private var throughText = LocalDate.now().toString()
    private var fromField: EditText? = null
    private var throughField: EditText? = null
    private var zoneText = ZoneId.systemDefault().id
    private var includeArchived = false
    private var recordIds: Set<String>? = null
    private var zoneField: EditText? = null
    private var archiveCheck: CheckBox? = null
    private var exactCheck: CheckBox? = null
    private var historyOffset = 0
    private var sourceOffset = 0
    private var actionOffset = 0
    private var recordOffset = 0
    private var restoredScreen = "scope"
    private var current: ActionReflectionRun? = null

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        fromText = savedInstanceState?.getString("from") ?: intent.getStringExtra(EXTRA_FROM) ?: fromText
        throughText = savedInstanceState?.getString("through") ?: intent.getStringExtra(EXTRA_THROUGH) ?: throughText
        zoneText = savedInstanceState?.getString("zone") ?: intent.getStringExtra(EXTRA_ZONE_ID) ?: zoneText
        includeArchived = savedInstanceState?.getBoolean("includeArchived") ?: intent.getBooleanExtra(EXTRA_INCLUDE_ARCHIVED, false)
        recordIds = (if (savedInstanceState != null) savedInstanceState.getStringArrayList("recordIds") else intent.getStringArrayListExtra(EXTRA_RECORD_IDS))?.toSet()
        runId = if (savedInstanceState != null) savedInstanceState.getString("runId") else
            intent.getStringExtra(EXTRA_RUN_ID) ?: if (!intent.hasExtra(EXTRA_FROM) && !intent.hasExtra(EXTRA_THROUGH)) preferences.getString("runId", null) else null
        selected = savedInstanceState?.getBundle("target")?.let(::targetFromBundle)
        historyOffset = savedInstanceState?.getInt("historyOffset") ?: 0
        sourceOffset = savedInstanceState?.getInt("sourceOffset") ?: 0
        actionOffset = savedInstanceState?.getInt("actionOffset") ?: 0
        recordOffset = savedInstanceState?.getInt("recordOffset") ?: 0
        restoredScreen = savedInstanceState?.getString("screen") ?: if (runId == null) "scope" else "run"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (screen == "scope") captureDates()
        outState.putString("from", fromText); outState.putString("through", throughText)
        outState.putString("zone", zoneText); outState.putBoolean("includeArchived", includeArchived)
        recordIds?.let { outState.putStringArrayList("recordIds", ArrayList(it)) }
        outState.putString("runId", runId); outState.putString("screen", screen)
        outState.putInt("historyOffset", historyOffset); outState.putInt("sourceOffset", sourceOffset)
        outState.putInt("actionOffset", actionOffset); outState.putInt("recordOffset", recordOffset)
        selected?.let { outState.putBundle("target", targetBundle(it)) }
        super.onSaveInstanceState(outState)
    }

    override fun showHome() {
        val id = runId
        if (id == null || restoredScreen == "scope") showScope()
        else if (selected != null && restoredScreen in setOf("finding", "sources", "original")) showFinding(id, requireNotNull(selected))
        else if (restoredScreen == "materials") showMaterials(id)
        else showRun(id)
    }

    override fun onResume() {
        super.onResume()
        if (busy) return
        val id = runId ?: return
        when (screen) {
            "run" -> showRun(id)
            "finding" -> selected?.let { showFinding(id, it) }
        }
    }

    override fun navigateBack() {
        if (busy) return
        when (screen) {
            "original" -> if (selected != null) showSources(requireNotNull(runId), requireNotNull(selected)) else showMaterials(requireNotNull(runId))
            "sources" -> showFinding(requireNotNull(runId), requireNotNull(selected))
            "finding", "materials" -> showRun(requireNotNull(runId))
            "run" -> showScope()
            else -> finish()
        }
    }

    private fun showScope() {
        beginScreen("scope", "行动复盘")
        val form = card()
        Ui.cardTitle(form, "计划之后，发生了什么")
        Ui.note(form, "把原计划、你写下的执行回看和这段时间的记录放在一起，看看哪些做法值得继续或调整。")
        fromField = labeledFieldIn(form, "开始日期", "YYYY-MM-DD", fromText).apply { tag = "actionReflectionFrom" }
        throughField = labeledFieldIn(form, "结束日期", "YYYY-MM-DD", throughText).apply { tag = "actionReflectionThrough" }
        Ui.disclosure(form, "范围设置", "actionReflectionScopeSettings") { settings ->
            zoneField = labeledFieldIn(settings, "时区", value = zoneText).apply { tag = "actionReflectionZone" }
            archiveCheck = checkIn(settings, "包括已归档的记录", includeArchived)
            exactCheck = recordIds?.let { ids -> checkIn(settings, "只使用原范围指定的 ${ids.size} 条记录", true) }
            Ui.note(settings, "行动材料仍按整个日期范围定位，包含期间执行回看与期初计划。")
        }
        Ui.note(form, "没有回看表示情况还不清楚，不会算成未完成。")
        primaryIn(form, "开始行动复盘", "startActionReflection") {
            captureDates()
            val scope = try {
                val from = parseDate(fromText, "开始日期")
                val through = parseDate(throughText, "结束日期")
                require(ChronoUnit.DAYS.between(from, through) in 0..365) { "请选择 1 至 366 天" }
                val zone = try { ZoneId.of(zoneText) } catch (_: java.time.DateTimeException) { throw IllegalArgumentException("时区无效，例如 Asia/Shanghai") }
                AnalysisQuery(from, through, zone.id, includeArchived, recordIds)
            } catch (failure: IllegalArgumentException) { showError(failure); return@primaryIn }
            confirm("对照这段时间的行动", "本次选中的记录、计划与回看会分批发送给 DeepSeek。每次点击最多发送 3 次请求，可能产生费用。原始记录会保留，发现需要你确认后再核对保存。", "开始") {
                withProvider {
                    runIo({ workflow.start(scope) }) { run ->
                        remember(run.task.stored.id)
                        continueRun(run.task.stored.id)
                    }
                }
            }
        }
        section("之前的行动复盘")
        runIo({ workflow.history(historyOffset, PAGE_SIZE + 1) }) { tasks ->
            if (tasks.isEmpty()) emptyState(card(), "还没有行动复盘。")
            tasks.take(PAGE_SIZE).forEach { task ->
                val scope = task.state.request.scope
                listRow(root, "${scope.from} 至 ${scope.through}\n${task.stored.archivedAt?.let { "已归档" } ?: taskStatus(task.status)}",
                    "actionReflectionRun:${task.stored.id}") { selected = null; remember(task.stored.id); showRun(task.stored.id) }
            }
            if (historyOffset > 0) button("上一页", "actionReflectionHistoryPrevious") { historyOffset = (historyOffset - PAGE_SIZE).coerceAtLeast(0); captureDates(); showScope() }
            if (tasks.size > PAGE_SIZE) button("下一页", "actionReflectionHistoryNext") { historyOffset += PAGE_SIZE; captureDates(); showScope() }
        }
    }

    private fun showRun(id: String) {
        remember(id); selected = null
        beginScreen("run", "行动复盘")
        runIo({ workflow.inspect(id) }, ::renderRun)
    }

    private fun renderRun(run: ActionReflectionRun) {
        current = run
        beginScreen("run", "行动复盘")
        val id = run.task.stored.id
        val progress = run.progress
        val summary = card()
        val scope = run.task.state.request.scope
        Ui.cardTitle(summary, "${scope.from} 至 ${scope.through}")
        Ui.note(summary, "时区：${scope.zoneId} · ${if (scope.includeArchived) "纳入已归档记录" else "仅未归档记录"}")
        Ui.note(summary, scope.recordIds?.let { "记录限定：指定的 ${it.size} 条。行动仍按完整日期范围定位。" }
            ?: "记录范围：这段时间的全部已存记录；日期未知的材料另行说明。")
        chip(summary, if (run.task.stored.archivedAt != null) "已归档" else integrationLabel(run), if (run.canReview) Ui.Tone.OK else Ui.Tone.WARN)
        progress.recordCoverage?.let { coverage ->
            Ui.note(summary, "本期记录：已读 ${coverage.completedTextCodePoints} / ${coverage.textCodePoints} 字。")
            if (coverage.hasGaps) Ui.note(summary, "范围中仍有未知日期、附件或无法读取的资料；当前结果保留这些缺口。")
        }
        Ui.note(summary, "行动材料：已读 ${progress.processedActionEvents} / ${progress.actionEvents} 份。一个行动的不同修订不算多次独立尝试。")
        Ui.note(summary, "材料整理完成仍不代表生活资料完整。执行情况来自你写下的回看。")
        addButton(summary, "查看原计划、回看与记录", "actionReflectionMaterials") {
            actionOffset = 0; recordOffset = 0; selected = null; showMaterials(id)
        }
        if (run.task.stored.archivedAt == null && run.task.status == AnalysisStatus.IN_FLIGHT) {
            addButton(summary, "恢复中断进度", "recoverActionReflection") {
                confirm("恢复中断进度", "这会将未完成请求标为中断，不会立即重新发送。之后由你点击继续。", "恢复") {
                    runIo({ workflow.recover(id) }, ::renderRun)
                }
            }
        } else if (run.task.stored.archivedAt == null && run.task.status !in setOf(AnalysisStatus.STALE, AnalysisStatus.EXPIRED) &&
            progress.integration in setOf(ActionPeriodIntegration.INCOMPLETE, ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED)) {
            primaryIn(summary, if (progress.integration == ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED) "继续汇总发现" else "继续分析", "continueActionReflection") {
                withProvider { continueRun(id) }
            }
            Ui.note(summary, "每次点击最多 3 次请求；失败后停止，已完成部分保留。")
        }
        if (run.task.stored.archivedAt != null) addButton(summary, "管理归档", "manageArchivedActionReflection") {
            startActivity(Intent(this, TaskArchiveActivity::class.java))
        }
        button("重新选择时间", "newActionReflection") { showScope() }
        section(if (run.canReview) "这次发现" else "已整理的部分")
        if (run.findings.isEmpty()) emptyState(card(), if (progress.integration == ActionPeriodIntegration.NO_INPUTS)
            "这段时间没有可读取的记录或行动材料。" else "还没有可展示的发现。继续整理，或先补充记录与回看。")
        run.findings.forEachIndexed { index, finding ->
            listRow(root, "${finding.text}\n${findingLabel(finding)}", "actionReflectionFinding:$index") {
                showFinding(id, finding.target)
            }
        }
        if (run.unknowns.isNotEmpty()) Ui.disclosure(root, "还不清楚的地方", "actionReflectionUnknowns") { container ->
            run.unknowns.forEach { Ui.body(container, it) }
        }
        Ui.disclosure(root, "请求与保存情况", "actionReflectionRequests") { container ->
            Ui.note(container, "已登记 ${run.task.state.calls.size} 次请求；本任务最多 ${run.task.state.request.limits.maxCalls} 次。生成、汇总、扫描和核对共用任务额度。")
            run.task.state.calls.lastOrNull()?.takeIf { it.status == CallStatus.FAILED }?.let {
                Ui.note(container, "上次请求未完成，原因：${it.failureCode ?: "未知"}。已完成内容不会重复发送。")
            }
        }
    }

    private fun continueRun(id: String) {
        beginScreen("run", "正在对照材料")
        note("原始记录已保存。本轮最多 3 次请求，完成后再决定是否继续。")
        runIo({ workflow.process(id, app.deepSeekProvider, maxRequests = REQUESTS_PER_PASS) }) { pass ->
            toast("本轮 ${pass.requests} 次请求 · ${passLabel(pass.status)}")
            renderRun(pass.run)
        }
    }

    private fun showFinding(id: String, target: ActionReflectionTarget) {
        runId = id; selected = target
        beginScreen("finding", "这次发现")
        runIo({ workflow.inspect(id) }) { run ->
            current = run
            val finding = run.findings.singleOrNull { it.target == target }
            if (finding == null) { note("这条发现已变化，请返回重新打开。"); return@runIo }
            val detail = card()
            Ui.body(detail, finding.text)
            chip(detail, findingLabel(finding), if (finding.saved?.evidenceState == KnowledgeEvidenceState.CURRENT) Ui.Tone.OK else Ui.Tone.NEUTRAL)
            finding.feedback?.feedback?.userText?.let { Ui.fieldLabel(detail, "我的说明"); Ui.body(detail, it) }
            finding.verdict?.let { Ui.note(detail, "原文核对：${verdictLabel(it)}。这是一项质量检查，不是事实认证。") }
            finding.reviewProgress?.let { review ->
                Ui.note(detail, "已扫描记录 ${review.scannedFragments}/${review.originalFragments} 段、行动 ${review.scannedActionEvents}/${review.originalActionEvents} 份。")
                Ui.note(detail, "已发现反例 ${review.counterevidenceFragments} 项；最后重读记录 ${review.finalRereadFragments} 段、行动 ${review.finalRereadActionEvents} 份。")
                if (review.status in setOf(ReductionReviewStatus.RELATED_EVIDENCE_LIMIT, ReductionReviewStatus.CONTEXT_REQUIRED,
                        ReductionReviewStatus.NO_RELEVANT_EVIDENCE, ReductionReviewStatus.NOT_SUPPORTED))
                    Ui.note(detail, passLabel(review.status.name))
            }
            button("查看全部依据", "actionReflectionSources") { sourceOffset = 0; showSources(id, target) }
            finding.saved?.let { saved ->
                button("打开已保存的知识", "actionReflectionSaved") {
                    startActivity(Intent(this, KnowledgeActivity::class.java).putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID, saved.value.id))
                }
            }
            if (!run.canReview) {
                note("本期材料尚未汇总、来源已变化或任务已归档，不能继续确认保存。部分发现不能代替完整周期判断。")
                return@runIo
            }
            if (finding.verdict != null && finding.verdict != ReviewVerdict.SUPPORTED)
                note("这次核对没有通过，不能保存。可以补充纠正，或在新增相关记录与回看后重新复盘。")
            if ((finding.verdict == null || finding.verdict == ReviewVerdict.SUPPORTED) &&
                finding.saved?.evidenceState != KnowledgeEvidenceState.CURRENT && finding.feedback?.feedback?.decision !in
                setOf(ClaimFeedbackDecision.REJECTED, ClaimFeedbackDecision.CORRECTED, ClaimFeedbackDecision.WITHDRAWN)) {
                primaryButton(if (finding.feedback?.feedback?.decision == ClaimFeedbackDecision.CONFIRMED) "继续核对并保存" else "确认后核对保存", "keepActionReflectionFinding") {
                    confirmSave(run, finding)
                }
                note("保存前会重新核对原计划、回看和记录。跨批结果先扫描全部来源，再核对相关原文；不支持或资料不足时保留草稿。")
            }
            button("补充纠正", "correctActionReflectionFinding") { correctFinding(run, finding) }
            button("这条不符合我的情况", "rejectActionReflectionFinding") {
                confirm("不采用这条发现", "这个决定会保留，并使已保存的关联内容重新等待核对。", "不采用") {
                    runIo({ workflow.respond(id, target, run.task.stored.version, ClaimFeedbackDecision.REJECTED,
                        finding.feedback?.feedback?.userText) }) { showFinding(id, target) }
                }
            }
        }
    }

    private fun confirmSave(run: ActionReflectionRun, finding: ActionReflectionFinding) {
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val targetId = dev.tracelife.storage.KnowledgeValidation.idFor(run.task.stored.id, ActionReflectionWorkflow.claimRef(finding.target))
        val savedTopics = if (preferences.getString("topicsTarget", null) == targetId) preferences.getString("topics", "").orEmpty() else ""
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        Ui.note(form, "你确认这段描述符合本次情况。接下来最多 3 次请求，核对支持后才保存；未完成时保留进度。")
        val topics = fieldIn(form, "主题，用逗号分隔（可留空）", savedTopics, maxLength = 2_000).apply { tag = "actionReflectionTopics" }
        AlertDialog.Builder(this).setTitle("确认并核对原文").setView(form).setNegativeButton("取消", null)
            .setPositiveButton("确认并核对") { _, _ ->
                val selectedTopics = topics.text.toString().split(',', '，', '、').map(String::trim).filter(String::isNotEmpty).toSet()
                if (selectedTopics.size > 30 || selectedTopics.any { it.length > 80 }) {
                    showError(IllegalArgumentException("最多 30 个主题，每个不超过 80 字")); return@setPositiveButton
                }
                preferences.edit().putString("topicsTarget", targetId).putString("topics", selectedTopics.joinToString("、")).apply()
                withProvider {
                    runIo({
                        if (finding.feedback?.feedback?.decision != ClaimFeedbackDecision.CONFIRMED)
                            workflow.respond(run.task.stored.id, finding.target, run.task.stored.version, ClaimFeedbackDecision.CONFIRMED,
                                finding.feedback?.feedback?.userText)
                        workflow.reviewAndSave(run.task.stored.id, finding.target, app.deepSeekProvider, app.deepSeekProvider,
                            maxRequests = REQUESTS_PER_PASS, topics = selectedTopics)
                    }) { pass ->
                        toast("本轮 ${pass.requests} 次请求 · ${passLabel(pass.status)}")
                        showFinding(run.task.stored.id, finding.target)
                    }
                }
            }.show()
    }

    private fun correctFinding(run: ActionReflectionRun, finding: ActionReflectionFinding) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val note = fieldIn(form, "写下哪里不准确、你实际的情况", finding.feedback?.feedback?.userText.orEmpty(), true, 2_000)
        val dialog = AlertDialog.Builder(this).setTitle("补充纠正").setView(form).setNegativeButton("取消", null).setPositiveButton("保存说明", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = note.text.toString().trim()
                if (value.isEmpty()) { note.error = "请写下需要纠正的情况"; return@setOnClickListener }
                dialog.dismiss()
                runIo({ workflow.respond(run.task.stored.id, finding.target, run.task.stored.version, ClaimFeedbackDecision.CORRECTED, value) }) {
                    showFinding(run.task.stored.id, finding.target)
                }
            }
        }
        dialog.show()
    }

    private fun showMaterials(id: String) {
        selected = null
        beginScreen("materials", "本期材料")
        note("期初计划、期间调整、执行回看分别保留。计划本身不能说明执行与效果。")
        runIo({
            val pageSize = app.analysis.task(id).state.request.limits.pageSize
            Triple(app.analysis.actionPage(id, actionOffset), app.analysis.evidencePage(id, recordOffset).page, pageSize)
        }) { (actions, records, pageSize) ->
            section("原计划与执行回看")
            if (actions.total == 0) emptyState(card(), "这段时间没有行动材料。可先在行动页面记录计划或回看。")
            actions.entries.forEach { receipt ->
                listRow(root, "${kindLabel(receipt.kind)} · 版本 ${receipt.revision}", "actionReflectionMaterial:${receipt.id}") {
                    showActionOriginal(id, receipt.id, null)
                }
            }
            if (actionOffset > 0) button("上一页行动", "actionMaterialsPrevious") { actionOffset = (actionOffset - pageSize).coerceAtLeast(0); showMaterials(id) }
            actions.nextOffset?.let { next -> button("下一页行动", "actionMaterialsNext") { actionOffset = next; showMaterials(id) } }
            section("本期记录")
            note("${records.total} 条记录，其中 ${records.unknown} 条日期未知、${records.overlap} 条跨期。")
            records.items.forEach { item ->
                listRow(root, "${item.occurrence.startDate ?: "日期未知"} · ${item.topics.joinToString("、").ifEmpty { "未分类" }}", "actionReflectionRecord:${item.id}") {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORD_ID, item.id)
                        .putExtra(MainActivity.EXTRA_RECORD_REVISION, item.revision))
                }
            }
            if (recordOffset > 0) button("上一页记录", "actionRecordsPrevious") { recordOffset = (recordOffset - pageSize).coerceAtLeast(0); showMaterials(id) }
            records.nextOffset?.let { next -> button("下一页记录", "actionRecordsNext") { recordOffset = next; showMaterials(id) } }
        }
    }

    private fun showSources(id: String, target: ActionReflectionTarget) {
        selected = target
        beginScreen("sources", "这条发现的依据")
        note("这里包含形成这条发现时的全部上下文。引用只是依据定位，是否支持仍需核对。")
        runIo({ workflow.sources(id, target, sourceOffset, PAGE_SIZE) }) { page ->
            note("共 ${page.total} 份材料")
            page.actions.forEach { receipt -> listRow(root, "${kindLabel(receipt.kind)} · 版本 ${receipt.revision}", "actionReflectionOriginal:${receipt.id}") {
                showActionOriginal(id, receipt.id, target)
            } }
            page.records.forEach { receipt -> listRow(root, "记录原文 · ${receipt.end - receipt.start} 字", "actionReflectionOriginal:${receipt.id}") {
                showRecordOriginal(id, target, receipt, 0)
            } }
            if (sourceOffset > 0) button("上一页", "actionSourcesPrevious") { sourceOffset = (sourceOffset - PAGE_SIZE).coerceAtLeast(0); showSources(id, target) }
            page.nextOffset?.let { next -> button("下一页", "actionSourcesNext") { sourceOffset = next; showSources(id, target) } }
        }
    }

    private fun showRecordOriginal(id: String, target: ActionReflectionTarget, receipt: EvidenceReceipt, start: Int) {
        beginScreen("original", "记录原文")
        runIo({ workflow.original(id, target, receipt.id, start) }) { fragment ->
            note("记录版本 ${receipt.revision} · 已登记片段 ${receipt.start}–${receipt.end}")
            text(fragment.text)
            val next = fragment.end - receipt.start
            note("当前显示片段 ${start + 1}–$next / ${receipt.end - receipt.start} 字")
            if (start > 0) button("上一段", "actionOriginalPrevious") { showRecordOriginal(id, target, receipt, (start - 1_024).coerceAtLeast(0)) }
            if (next < receipt.end - receipt.start) button("继续读取", "actionOriginalNext") { showRecordOriginal(id, target, receipt, next) }
        }
    }

    private fun showActionOriginal(id: String, evidenceId: String, target: ActionReflectionTarget?) {
        selected = target
        beginScreen("original", "行动原文")
        runIo({ workflow.actionOriginal(id, evidenceId, target) }) { body ->
            note("这些是你保存的计划与执行自述，保存于 ${body["savedAt"]}。")
            (body["plan"] as? Map<*, *>)?.let { renderPlan("原计划", it) }
            (body["reviewedPlan"] as? Map<*, *>)?.let { renderPlan("这次回看针对的旧计划", it) }
            val review = body["review"] as? Map<*, *>
            if (review == null) {
                val c = card(); Ui.cardTitle(c, "执行回看"); Ui.body(c, "这份材料没有本期执行回看，执行与效果仍未知。")
            } else {
                val c = card(); Ui.cardTitle(c, "执行回看")
                Ui.body(c, when (review["execution"]) { "EXECUTED" -> "自述已执行"; "NOT_EXECUTED" -> "自述未执行"; else -> "执行情况仍未知" })
                Ui.body(c, review["observedText"].toString())
                Ui.note(c, "回看日期：${review["reviewedOn"]} · ${decisionLabel(review["decision"].toString())}")
            }
            (body["nextPlan"] as? Map<*, *>)?.let { renderPlan("之后选择的新计划", it) }
            note(if (body["planStatus"] == "STOPPED") "这份修订的计划已停止。停止不等于未执行。" else "这份修订的计划仍开放。开放不等于已执行。")
        }
    }

    private fun renderPlan(title: String, plan: Map<*, *>) {
        val c = card(); Ui.cardTitle(c, title)
        Ui.body(c, plan["title"].toString())
        listOf("trigger" to "什么时候做", "behavior" to "准备怎么做", "expectedSignal" to "怎样看出效果").forEach { (key, label) ->
            Ui.fieldLabel(c, label); Ui.body(c, plan[key].toString())
        }
        Ui.note(c, "安排回看：${plan["reviewOn"]}")
    }

    private fun captureDates() {
        fromText = fromField?.text?.toString() ?: fromText; throughText = throughField?.text?.toString() ?: throughText
        zoneText = zoneField?.text?.toString()?.trim()?.ifEmpty { ZoneId.systemDefault().id } ?: zoneText
        includeArchived = archiveCheck?.isChecked ?: includeArchived
        if (exactCheck?.isChecked == false) recordIds = null
    }
    private fun remember(id: String) { runId = id; getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putString("runId", id).apply() }
    private fun withProvider(action: () -> Unit) {
        runIo({ app.deepSeekCredentials.isConfigured() }) { configured ->
            if (configured) action() else confirm("先配置分析服务", "需要在设置中填写你的 DeepSeek 密钥。记录与已有进度仍保存在手机上。", "前往设置") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }
    private fun findingLabel(finding: ActionReflectionFinding): String = when {
        finding.saved?.evidenceState == KnowledgeEvidenceState.CURRENT -> "已保存"
        finding.saved != null -> "已保存 · 待更新"
        finding.feedback?.feedback?.decision == ClaimFeedbackDecision.REJECTED -> "已否定"
        finding.feedback?.feedback?.decision == ClaimFeedbackDecision.CORRECTED -> "已纠正"
        finding.feedback?.feedback?.decision == ClaimFeedbackDecision.WITHDRAWN -> "已撤回"
        finding.verdict != null -> verdictLabel(finding.verdict)
        finding.feedback?.feedback?.decision == ClaimFeedbackDecision.CONFIRMED -> "已确认 · 待核对"
        else -> "待确认与核对"
    }
    private fun integrationLabel(run: ActionReflectionRun) = when {
        run.task.status in setOf(AnalysisStatus.STALE, AnalysisStatus.EXPIRED, AnalysisStatus.IN_FLIGHT) -> taskStatus(run.task.status)
        else -> when (run.progress.integration) {
            ActionPeriodIntegration.INCOMPLETE -> if (run.task.status == AnalysisStatus.NEEDS_RETRY) "请求未成功" else "材料还未读完"
            ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED -> "等待汇总发现"
            ActionPeriodIntegration.NO_INPUTS -> "没有可分析的材料"
            else -> "发现已整理 · 等待核对"
        }
    }
    private fun taskStatus(status: AnalysisStatus) = when (status) {
        AnalysisStatus.STALE -> "来源已变化"; AnalysisStatus.EXPIRED -> "已过期"; AnalysisStatus.IN_FLIGHT -> "有中断请求待恢复"
        AnalysisStatus.NEEDS_RETRY -> "请求未成功"; AnalysisStatus.READY -> "等待分析"; AnalysisStatus.NEEDS_REVIEW -> "已有发现"
    }
    private fun verdictLabel(verdict: ReviewVerdict) = when (verdict) {
        ReviewVerdict.SUPPORTED -> "原文支持"; ReviewVerdict.UNSUPPORTED -> "依据不足"
        ReviewVerdict.CONTRADICTED -> "存在矛盾"; ReviewVerdict.INSUFFICIENT_CONTEXT -> "需要更多上下文"
    }
    private fun kindLabel(kind: ActionEvidenceKind) = when (kind) {
        ActionEvidenceKind.OPENING_PLAN -> "期初计划"; ActionEvidenceKind.PLAN_CHANGE -> "期间计划与调整"; ActionEvidenceKind.USER_REVIEW -> "执行回看"
    }
    private fun decisionLabel(value: String) = when (value) { "CONTINUE" -> "继续"; "ADJUST" -> "调整"; "STOP" -> "停止"; "DEFER" -> "延期"; else -> "保留决定" }
    private fun passLabel(value: String) = when (value) {
        "SAVED" -> "已保存"; "SUPPORTED" -> "原文支持"; "RETRY_NEEDED" -> "请求未成功，等待你重试"
        "BUDGET_EXHAUSTED", "RELATED_EVIDENCE_LIMIT" -> "资料超过本次核对额度，未保存；请缩小时间范围重新复盘"
        "UNSUPPORTED", "NOT_SUPPORTED" -> "依据尚不足，未保存"; "CONTRADICTED" -> "存在矛盾，未保存"
        "CONTEXT_REQUIRED", "INSUFFICIENT_CONTEXT", "NO_RELEVANT_EVIDENCE" -> "资料不足，未保存"
        "STALE", "CONFLICT", "DEPENDENCY_BLOCKED" -> "来源或意见变化，请重新打开"
        "CANCELLED" -> "已暂停"; "NEEDS_RECOVERY" -> "需要显式恢复中断请求"
        "EXPIRED" -> "任务已过期"; "TASK_UNAVAILABLE" -> "任务已删除"
        else -> "进度已保留"
    }
    private fun targetBundle(target: ActionReflectionTarget) = Bundle().apply {
        when (target) {
            is ActionReflectionTarget.Claim -> { putString("kind", "claim"); putString("batch", target.ref.batchKey); putInt("index", target.ref.claimIndex); putString("hash", target.ref.claimSha256) }
            is ActionReflectionTarget.Reduction -> { putString("kind", "reduction"); putString("batch", target.ref.root.batchKey); putInt("attempt", target.ref.root.attempt); putString("output", target.ref.root.outputSha256); putInt("index", target.ref.index); putString("hash", target.ref.statementSha256) }
        }
    }
    private fun targetFromBundle(value: Bundle): ActionReflectionTarget? = runCatching {
        if (value.getString("kind") == "claim") ActionReflectionTarget.Claim(ClaimRef(requireNotNull(value.getString("batch")), value.getInt("index"), requireNotNull(value.getString("hash"))))
        else ActionReflectionTarget.Reduction(ReductionStatementRef(ReductionNodeRef(requireNotNull(value.getString("batch")), value.getInt("attempt"), requireNotNull(value.getString("output"))), value.getInt("index"), requireNotNull(value.getString("hash"))))
    }.getOrNull()
    companion object {
        const val EXTRA_FROM = "actionReflectionFrom"
        const val EXTRA_THROUGH = "actionReflectionThrough"
        const val EXTRA_RUN_ID = "actionReflectionRunId"
        const val EXTRA_ZONE_ID = "actionReflectionZoneId"
        const val EXTRA_INCLUDE_ARCHIVED = "actionReflectionIncludeArchived"
        const val EXTRA_RECORD_IDS = "actionReflectionRecordIds"
        private const val PREFERENCES = "action-reflection-navigation"
        private const val PAGE_SIZE = 20
        private const val REQUESTS_PER_PASS = 3
    }
}
