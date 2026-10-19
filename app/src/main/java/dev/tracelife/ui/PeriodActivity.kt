package dev.tracelife.ui

import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.metrics.RecordMetricsReport
import dev.tracelife.model.Occurrence
import dev.tracelife.reports.PeriodReport
import dev.tracelife.reports.ReportAnswerRef
import dev.tracelife.reports.ReportKnowledgeItem
import dev.tracelife.reports.ReportSection
import dev.tracelife.reports.ReportSynthesisStatus
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.SourceFragment
import dev.tracelife.storage.SourceTimeClass
import java.io.File
import java.time.ZoneId

class PeriodActivity : ScreenActivity() {
    private var report: PeriodReport? = null
    private var fromText = ""
    private var throughText = ""
    private var zoneText = ZoneId.systemDefault().id
    private var fromField: EditText? = null
    private var throughField: EditText? = null
    private var zoneField: EditText? = null
    private var answerSection: ReportSection? = null
    private var answerOffset = 0
    private var recordOffset = 0
    private var knowledgeOffset = 0
    private var fragmentLoader: ((Int) -> SourceFragment)? = null
    private var fragmentNextStart = 0
    private var fragmentReturn: (() -> Unit)? = null
    private var fragmentMore: Button? = null

    override fun showHome() {
        beginScreen("scope", "周期复盘 · 已有材料", showBack = false)
        val form = card()
        Ui.sectionTitle(form, "范围")
        Ui.note(form, "选择周期范围，应用会组织该范围内已保存的记录、复盘答案与当前知识。本页不提供新建周期综合任务，报告不是新的综合判断。")
        fromField = fieldIn(form, "起始日期（必填，YYYY-MM-DD）", fromText)
        throughField = fieldIn(form, "结束日期（必填，YYYY-MM-DD）", throughText)
        zoneField = fieldIn(form, "时区（默认设备时区）", zoneText)
        Ui.note(form, "范围支持 1–366 天；候选记录超过 10,000 条、答案超过 20,000 条或知识超过 10,000 条时会明确失败，请缩小范围。")
        primaryIn(form, "生成材料报告", "openReport") { openReport() }
    }

    override fun navigateBack() {
        if (busy) return
        when (screen) {
            "fragment" -> fragmentReturn?.invoke() ?: showHome()
            "knowledgeDetail" -> showKnowledgeList()
            "answers", "records", "knowledge" -> report?.let { showReport(it) } ?: showHome()
            "report" -> showHome()
            else -> finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val current = report
        if (current != null && screen != "scope") {
            runIo({ app.periodReports.assertCurrent(current) }) { }
        }
    }

    private fun openReport() {
        fromText = fromField?.text?.toString().orEmpty()
        throughText = throughField?.text?.toString().orEmpty()
        zoneText = zoneField?.text?.toString().orEmpty()
        val query = try {
            val from = parseDate(fromText, "起始日期")
            val through = parseDate(throughText, "结束日期")
            require(!through.isBefore(from)) { "结束日期不能早于起始日期" }
            val zoneInput = zoneText.trim().ifEmpty { ZoneId.systemDefault().id }
            val zone = try {
                ZoneId.of(zoneInput)
            } catch (failure: java.time.DateTimeException) {
                throw IllegalArgumentException("时区「$zoneInput」无效，例如 Asia/Shanghai 或 UTC", failure)
            }
            AnalysisQuery(from, through, zone.id)
        } catch (failure: IllegalArgumentException) {
            showError(failure)
            return
        }
        runIo({ app.periodReports.open(query) }) { opened ->
            report = opened
            answerSection = null
            answerOffset = 0
            recordOffset = 0
            knowledgeOffset = 0
            showReport(opened)
        }
    }

    private fun showReport(current: PeriodReport) {
        beginScreen("report", "周期材料报告")
        val overview = card()
        Ui.sectionTitle(overview, "报告概览")
        Ui.body(overview, "范围：${current.scope.from} 至 ${current.scope.through}（时区 ${current.scope.zoneId}）")
        Ui.note(overview, "生成时刻：${current.evaluatedAt}")
        Ui.note(overview, "有效期至：${current.validUntil?.toString() ?: "无期限限制"}。报告绑定最早的知识有效期；跨过期限后即使没有资料修改，本报告也会失效，需要重新生成。")
        val synthesisChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        overview.addView(synthesisChips)
        chip(synthesisChips, synthesisLabel(current.synthesisStatus).removeSuffix("。"), Ui.Tone.WARN)
        Ui.note(overview, "【如实标注】本报告只组织已有材料，不是新的周期综合，也不推断行动已执行；知识数量与答案数量不表示 AI 已读完周期资料。")
        renderMetrics(current.metrics)
        val sections = card()
        Ui.sectionTitle(sections, "答案分组计数")
        Ui.note(sections, "按模板问题意图分组；0 仅表示没有对应答案；分组不代表不同模板的问题语义完全相同")
        ReportSection.entries.forEach { section ->
            Ui.body(sections, "${sectionLabel(section)}：${current.sectionCounts[section] ?: 0}")
        }
        val knowledge = card()
        Ui.sectionTitle(knowledge, "知识候选")
        Ui.body(knowledge, "已纳入知识：${current.knowledgeCount} 条")
        Ui.body(knowledge, "因失效而省略的知识：${current.omittedInvalidKnowledge} 条")
        Ui.body(knowledge, "因证据越界而省略的知识：${current.omittedOutOfScopeKnowledge} 条")
        Ui.body(knowledge, "因引用行动证据而省略的知识：${current.omittedActionEvidenceKnowledge} 条")
        Ui.note(knowledge, "未读附件：${current.unreadAttachments} 个（只统计可枚举记录的附件，不代表识别覆盖）")
        Ui.note(knowledge, "无法读取的记录：${current.unreadableRecords} 条（这些记录的附件数量也未知）")
        val actions = card()
        Ui.sectionTitle(actions, "操作")
        addButton(actions, "答案查阅", "answers") { answerOffset = 0; showAnswers() }
        addButton(actions, "记录清单", "records") { recordOffset = 0; showRecords() }
        addButton(actions, "知识区", "knowledge") { knowledgeOffset = 0; showKnowledgeList() }
        Ui.divider(actions)
        primaryIn(actions, "导出本报告（ZIP）", "exportReport") { exportReport(current) }
        addButton(actions, "重新生成（返回范围表单）", "regenerate") { showHome() }
    }

    private fun renderMetrics(metrics: RecordMetricsReport) {
        val metricsCard = card()
        Ui.sectionTitle(metricsCard, "记录覆盖指标")
        Ui.note(metricsCard, "${metrics.definitionId} 版本 ${metrics.definitionVersion}，计算于 ${metrics.calculatedAt}")
        Ui.body(metricsCard, "范围内天数：${metrics.daysInScope}；有单日记录的天数：${metrics.recordedDays}")
        Ui.body(metricsCard, "候选记录总数：${metrics.totalRecords}")
        Ui.body(metricsCard, "精确到单日：${metrics.exactDateRecords}；日期区间：${metrics.intervalRecords}；跨期：${metrics.overlappingRecords}；发生日期未知：${metrics.unknownTimeRecords}")
        Ui.body(metricsCard, "未计入每日数的记录（区间或未知时间）：${metrics.missingExactIds.size} 条${if (metrics.missingExactIds.isEmpty()) "" else "\n${metrics.missingExactIds.joinToString("、")}"}")
        Ui.body(metricsCard, "每日记录数（只有精确到单日的记录计入每天；区间与未知时间不分摊）：")
        val recorded = metrics.daily.filter { it.records > 0 }
        if (recorded.isEmpty()) Ui.note(metricsCard, "范围内没有任何精确到单日的记录。") else Ui.body(metricsCard, recorded.joinToString("\n") { "${it.date}：${it.records} 条" })
        Ui.note(metricsCard, "无单日记录的天数：${metrics.daysInScope - metrics.recordedDays} 天。零值只表示当天没有已保存的精确日期记录，不代表没有睡眠、运动、学习或成长；记录数不代表成长。")
    }

    private fun exportReport(current: PeriodReport) {
        val directory = getExternalFilesDir(null) ?: filesDir
        val target = File(directory, "period-${current.scope.from}-${current.scope.through}-${System.currentTimeMillis()}.zip")
        runIo({ app.dataTransfer.exportPeriodReport(target, current) }) { result ->
            AlertDialog.Builder(this).setTitle("导出完成")
                .setMessage("文件：${target.absolutePath}\n记录 ${result.records} 条；附件 ${result.attachments} 个；文件 ${result.files} 个；共 ${result.bytes / 1024} KiB。\n导出是生成时的历史材料，不是完整恢复备份；导出期间任何资料变化都会导致导出被拒绝，届时请重新生成报告后再导出。")
                .setPositiveButton("知道了", null).show()
        }
    }

    private fun showAnswers() {
        val current = report ?: return showHome()
        beginScreen("answers", "答案查阅")
        val groups = card()
        Ui.sectionTitle(groups, "分组")
        Ui.note(groups, "答案按模板问题意图分组；未填写的答案不补全文字。原问题、提示与历史模板版本全部保留。")
        addButton(groups, "全部（${current.sectionCounts.values.sum()}）", "answersAll") {
            answerSection = null; answerOffset = 0; showAnswers()
        }
        ReportSection.entries.forEach { section ->
            addButton(groups, "${sectionLabel(section)}（${current.sectionCounts[section] ?: 0}）", "answers:$section") {
                answerSection = section; answerOffset = 0; showAnswers()
            }
        }
        Ui.note(groups, "当前分组：${answerSection?.let(::sectionLabel) ?: "全部"}")
        runIo({ app.periodReports.answers(current, answerSection, answerOffset, PAGE_SIZE) }) { page ->
            Ui.note(root, "共 ${page.total} 条答案。")
            if (page.items.isEmpty()) Ui.note(root, "该分组没有答案。")
            page.items.forEach { ref ->
                listRow(root, "${ref.question.prompt.take(60)}\n${occurrenceLabel(ref.occurrence)} · ${timeClassLabel(ref.timeClass)} · 记录修订 ${ref.revision} · ${ref.codePoints} 码点", "answer:${ref.recordId}:${ref.field}") {
                    openFragment(answerHeader(ref), { start -> app.periodReports.answer(current, ref, start, FRAGMENT_SIZE) }) { showAnswers() }
                }
            }
            if (answerOffset > 0) addButton(root, "上一页", "answersPrev") { answerOffset = (answerOffset - PAGE_SIZE).coerceAtLeast(0); showAnswers() }
            page.nextOffset?.let { next -> addButton(root, "下一页", "answersNext") { answerOffset = next; showAnswers() } }
        }
    }

    private fun answerHeader(ref: ReportAnswerRef): String = buildString {
        append("问题：").append(ref.question.prompt)
        if (ref.question.hint.isNotEmpty()) append("\n提示：").append(ref.question.hint)
        append("\n分组：").append(sectionLabel(ref.section)).append("（原问题意图：").append(ref.question.intent).append("）")
        append("\n记录 ").append(ref.recordId).append(" · 修订 ").append(ref.revision)
        append("\n模板 ").append(ref.template.id).append(" · 版本 ").append(ref.template.version)
        append("\n发生时间：").append(occurrenceLabel(ref.occurrence)).append(" · ").append(timeClassLabel(ref.timeClass))
        append("\n共 ").append(ref.codePoints).append(" 码点。")
    }

    private fun showRecords() {
        val current = report ?: return showHome()
        beginScreen("records", "记录清单")
        runIo({ app.periodReports.records(current, recordOffset, PAGE_SIZE) }) { page ->
            Ui.note(root, "共 ${page.total} 条候选记录；发生日期未知 ${page.unknown} 条；跨期 ${page.overlap} 条。日期未知的资料无法归入所选周期；跨期记录只有部分时间在范围内。")
            if (page.items.isEmpty()) Ui.note(root, "当前范围没有记录。")
            page.items.forEach { record ->
                listRow(root, "${occurrenceLabel(record.occurrence)} · 修订 ${record.revision}${if (record.archived) " · 已归档" else ""} · ${timeClassLabel(record.timeClass)}\nID：${record.id}\n主题：${record.topics.joinToString("、").ifEmpty { "未分类" }}", "periodRecord:${record.id}") { }
            }
            if (recordOffset > 0) addButton(root, "上一页", "recordsPrev") { recordOffset = (recordOffset - PAGE_SIZE).coerceAtLeast(0); showRecords() }
            page.nextOffset?.let { next -> addButton(root, "下一页", "recordsNext") { recordOffset = next; showRecords() } }
        }
    }

    private fun showKnowledgeList() {
        val current = report ?: return showHome()
        beginScreen("knowledge", "知识区")
        Ui.note(root, "纳入本报告的知识要求当前修订、来源、期限、最新核对与反馈有效，且每份核对证据都指向本报告候选记录的当前修订。日期筛选按分析范围交叠，不代表知识陈述的发生日期。模型核对支持只是有限证据下的质量检查，不代表事实认证。")
        runIo({ app.periodReports.knowledge(current, knowledgeOffset, PAGE_SIZE) }) { page ->
            Ui.note(root, "共 ${page.total} 条知识。")
            if (page.items.isEmpty()) Ui.note(root, "本范围没有纳入的知识。")
            page.items.forEach { item ->
                val value = item.view.value
                val times = item.evidenceTimeClasses.map(::timeClassLabel).joinToString("、").ifEmpty { "无" }
                listRow(root, "${value.claim.text.take(80)}\n${categoryLabel(value.category)} · 证据状态：${evidenceStateLabel(item.view.evidenceState)} · 证据时间：${times}", "knowledge:${value.id}") {
                    showKnowledgeDetail(item)
                }
            }
            if (knowledgeOffset > 0) addButton(root, "上一页", "knowledgePrev") { knowledgeOffset = (knowledgeOffset - PAGE_SIZE).coerceAtLeast(0); showKnowledgeList() }
            page.nextOffset?.let { next -> addButton(root, "下一页", "knowledgeNext") { knowledgeOffset = next; showKnowledgeList() } }
        }
    }

    private fun showKnowledgeDetail(item: ReportKnowledgeItem) {
        val view = item.view
        val value = view.value
        beginScreen("knowledgeDetail", "知识详情")
        val detail = card()
        Ui.sectionTitle(detail, "知识")
        Ui.body(detail, value.claim.text)
        Ui.note(detail, "类型：${kindLabel(value.claim.kind)} · 分类：${categoryLabel(value.category)}")
        Ui.note(detail, "知识 ID：${value.id} · 修订 ${value.revision}")
        Ui.note(detail, "主题：${value.topics.joinToString("、").ifEmpty { "未分类" }}")
        Ui.note(detail, "发布于：${value.publishedAt} · 有效期至：${value.expiresAt?.toString() ?: "无"}")
        val stateChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        detail.addView(stateChips)
        chip(stateChips, "证据状态：${evidenceStateLabel(view.evidenceState)}", when (view.evidenceState) {
            KnowledgeEvidenceState.CURRENT -> Ui.Tone.OK
            else -> Ui.Tone.WARN
        })
        chip(stateChips, "用户反馈：${view.userDecision?.let(::decisionLabel) ?: "无"}", when (view.userDecision) {
            ClaimFeedbackDecision.CONFIRMED -> Ui.Tone.OK
            ClaimFeedbackDecision.CORRECTED -> Ui.Tone.ACCENT
            ClaimFeedbackDecision.REJECTED -> Ui.Tone.ERROR
            ClaimFeedbackDecision.WITHDRAWN, null -> Ui.Tone.NEUTRAL
        })
        Ui.note(detail, "证据时间标注：${item.evidenceTimeClasses.map(::timeClassLabel).joinToString("、").ifEmpty { "无" }}")
        val evidence = card()
        Ui.sectionTitle(evidence, "证据引用")
        Ui.note(evidence, "只读取已登记的引用范围，不能借此扩展读取整条记录")
        val receipts = KnowledgeValidation.allEvidence(value)
        if (receipts.isEmpty()) Ui.note(evidence, "本条知识没有记录原文证据引用。")
        receipts.forEach { receipt ->
            listRow(evidence, "证据 ${receipt.id.take(8)}… · 记录 ${receipt.recordId.take(8)}… · 字段 ${receipt.field} · ${receipt.end - receipt.start}/${receipt.totalCodePoints} 码点", "evidence:${receipt.id}") {
                val current = report ?: return@listRow
                openFragment(
                    "知识证据 ${receipt.id}\n记录 ${receipt.recordId} · 修订 ${receipt.revision} · 字段 ${receipt.field}\n引用范围 ${receipt.start}–${receipt.end}（原文字段共 ${receipt.totalCodePoints} 码点）",
                    { start -> app.periodReports.evidence(current, value.id, receipt.id, start, FRAGMENT_SIZE) },
                ) { showKnowledgeDetail(item) }
            }
        }
    }

    private fun openFragment(header: String, loader: (Int) -> SourceFragment, back: () -> Unit) {
        fragmentLoader = loader
        fragmentReturn = back
        fragmentNextStart = 0
        fragmentMore = null
        beginScreen("fragment", "原文分片")
        val headerCard = card()
        Ui.sectionTitle(headerCard, "来源信息")
        Ui.note(headerCard, header)
        loadMoreFragment()
    }

    private fun loadMoreFragment() {
        val loader = fragmentLoader ?: return
        val start = fragmentNextStart
        runIo({ loader(start) }) { fragment ->
            fragmentMore?.isEnabled = false
            fragmentNextStart = fragment.end
            Ui.body(root, fragment.text)
            if (fragment.end < fragment.totalCodePoints) {
                Ui.note(root, "已读取 ${fragment.end} / ${fragment.totalCodePoints} 码点")
                fragmentMore = Ui.button(root, "继续读取", "fragmentMore") { if (!busy) loadMoreFragment() }
            } else {
                Ui.note(root, "已读取全部 ${fragment.totalCodePoints} 码点")
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
    private fun primaryIn(parent: LinearLayout, label: String, tag: String, action: () -> Unit) {
        Ui.primary(parent, label, tag) { if (!busy) action() }
    }

    private fun synthesisLabel(status: ReportSynthesisStatus) = when (status) {
        ReportSynthesisStatus.NOT_GENERATED -> "尚未生成新的周期综合。"
    }
    private fun sectionLabel(section: ReportSection) = when (section) {
        ReportSection.EXPECTATION -> "期望"
        ReportSection.ACTUAL -> "实际"
        ReportSection.FACTORS -> "因素"
        ReportSection.LEARNING -> "经验"
        ReportSection.ACTION -> "行动"
        ReportSection.FOLLOW_UP -> "回看"
        ReportSection.STATE -> "状态"
        ReportSection.OTHER -> "其他"
    }
    private fun timeClassLabel(timeClass: SourceTimeClass) = when (timeClass) {
        SourceTimeClass.WITHIN -> "范围内"
        SourceTimeClass.OVERLAP -> "跨期"
        SourceTimeClass.UNKNOWN -> "发生日期未知"
    }
    private fun occurrenceLabel(occurrence: Occurrence) = when {
        occurrence.startDate == null -> "发生日期未知"
        occurrence.startDate == occurrence.endDate -> occurrence.startDate.toString()
        else -> "${occurrence.startDate} 至 ${occurrence.endDate}"
    }
    private fun categoryLabel(category: KnowledgeCategory) = when (category) {
        KnowledgeCategory.OBSERVATION -> "自述观察"
        KnowledgeCategory.KNOWLEDGE_NOTE -> "知识笔记"
        KnowledgeCategory.INSIGHT -> "洞察"
    }
    private fun kindLabel(kind: ClaimKind) = when (kind) {
        ClaimKind.REPORTED_EXPERIENCE -> "自述经历"
        ClaimKind.POSSIBLE_INTERPRETATION -> "待确认的可能解释"
    }
    private fun evidenceStateLabel(state: KnowledgeEvidenceState) = when (state) {
        KnowledgeEvidenceState.CURRENT -> "有效"
        KnowledgeEvidenceState.STALE -> "来源已变化"
        KnowledgeEvidenceState.EXPIRED -> "已过期"
        KnowledgeEvidenceState.FEEDBACK_CHANGED -> "反馈已变化"
        KnowledgeEvidenceState.REVIEW_CHANGED -> "核对已变化"
    }
    private fun decisionLabel(decision: ClaimFeedbackDecision) = when (decision) {
        ClaimFeedbackDecision.CONFIRMED -> "已确认"
        ClaimFeedbackDecision.CORRECTED -> "已纠正"
        ClaimFeedbackDecision.REJECTED -> "已否定"
        ClaimFeedbackDecision.WITHDRAWN -> "已撤回"
    }

    companion object {
        private const val PAGE_SIZE = 25
        private const val FRAGMENT_SIZE = 1_024
    }
}
