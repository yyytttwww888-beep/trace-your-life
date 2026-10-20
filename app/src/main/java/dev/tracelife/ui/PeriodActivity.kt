package dev.tracelife.ui

import android.app.AlertDialog
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
import java.time.LocalDate

class PeriodActivity : ScreenActivity() {
    private var report: PeriodReport? = null
    private var fromText = LocalDate.now().minusDays(6).toString()
    private var throughText = LocalDate.now().toString()
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
        beginScreen("scope", "回顾", showBack = false)
        val form = card()
        Ui.sectionTitle(form, "回看一段时间")
        Ui.note(form, "把这段时间的记录、复盘回答和已有知识放在一起，慢慢回看。")
        fromField = labeledFieldIn(form, "开始日期", "YYYY-MM-DD", fromText)
        throughField = labeledFieldIn(form, "结束日期", "YYYY-MM-DD", throughText)
        Ui.disclosure(form, "日期与范围设置", "periodDateSettings") { details ->
            zoneField = labeledFieldIn(details, "时区", value = zoneText)
            Ui.note(details, "一次可查看 1–366 天。资料过多时，请缩小日期范围。")
        }
        primaryIn(form, "查看回顾", "openReport") { openReport() }
        Ui.note(form, "这里只汇集已有材料，不生成新的判断。")
        primaryButton("分析这段时间", "analyzePeriod") {
            startActivity(android.content.Intent(this, ReflectionActivity::class.java)
                .putExtra(ReflectionActivity.EXTRA_FROM, fromField?.text?.toString().orEmpty())
                .putExtra(ReflectionActivity.EXTRA_THROUGH, throughField?.text?.toString().orEmpty()))
        }
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
        beginScreen("report", "这段时间")
        val overview = card()
        Ui.cardTitle(overview, "${current.scope.from} 至 ${current.scope.through}")
        val synthesisChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        overview.addView(synthesisChips)
        chip(synthesisChips, synthesisLabel(current.synthesisStatus).removeSuffix("。"), Ui.Tone.WARN)
        Ui.note(overview, "已有材料的汇集，供你回顾，不包含新的综合判断。")
        addButton(overview, "复盘回答", "answers") { answerOffset = 0; showAnswers() }
        addButton(overview, "这段时间的记录", "records") { recordOffset = 0; showRecords() }
        addButton(overview, "相关知识", "knowledge") { knowledgeOffset = 0; showKnowledgeList() }
        renderMetrics(current.metrics)
        val sections = card()
        Ui.cardTitle(sections, "复盘留下了什么")
        ReportSection.entries.forEach { section ->
            Ui.body(sections, "${sectionLabel(section)}：${current.sectionCounts[section] ?: 0}")
        }
        Ui.disclosure(root, "材料范围与遗漏", "periodCoverage") { knowledge ->
            Ui.note(knowledge, "纳入知识 ${current.knowledgeCount} 条；已失效 ${current.omittedInvalidKnowledge} 条；来源超出范围 ${current.omittedOutOfScopeKnowledge} 条；引用行动材料而未纳入 ${current.omittedActionEvidenceKnowledge} 条。")
            Ui.note(knowledge, "未读取附件 ${current.unreadAttachments} 个，无法读取的记录 ${current.unreadableRecords} 条。附件数量只统计可读取记录。")
            Ui.note(knowledge, "按复盘问题分组，不同模板的相似问题不一定含义相同；没有回答不代表事情没有发生。")
            Ui.note(knowledge, "整理于 ${current.evaluatedAt} · 时区 ${current.scope.zoneId}\n有效期至 ${current.validUntil?.toString() ?: "未设固定期限"}。资料变化或到期后需要重新打开。")
        }
        val actions = card()
        addButton(actions, "更换日期", "regenerate") { showHome() }
        primaryIn(actions, "导出这份回顾", "exportReport") { exportReport(current) }
    }

    private fun renderMetrics(metrics: RecordMetricsReport) {
        val metricsCard = card()
        Ui.cardTitle(metricsCard, "记录足迹")
        Ui.body(metricsCard, "${metrics.daysInScope} 天里，有 ${metrics.recordedDays} 天留下了明确日期的记录")
        Ui.note(metricsCard, "共 ${metrics.totalRecords} 条记录。数量只反映记录情况，不衡量成长。")
        Ui.disclosure(metricsCard, "按日期查看", "dailyRecordDetails") { details ->
            val recorded = metrics.daily.filter { it.records > 0 }
            if (recorded.isEmpty()) emptyState(details, "暂时没有可归到具体一天的记录。")
            else Ui.body(details, recorded.joinToString("\n") { "${it.date} · ${it.records} 条" })
            Ui.note(details, "仅明确到某一天的记录计入当天。日期区间 ${metrics.intervalRecords} 条，跨期 ${metrics.overlappingRecords} 条，日期未知 ${metrics.unknownTimeRecords} 条；不将这些记录分摊到每天。")
            Ui.note(details, "没有记录不代表没有经历。计算于 ${metrics.calculatedAt}。")
        }
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
        beginScreen("answers", "复盘回答")
        val groups = card()
        Ui.sectionTitle(groups, "分组")
        Ui.note(groups, "按问题分组，只展示你实际写下的回答。")
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
            if (page.items.isEmpty()) emptyState(card(), "该分组没有答案。")
            page.items.forEach { ref ->
                listRow(root, "${ref.question.prompt.take(60)}\n${occurrenceLabel(ref.occurrence)} · ${timeClassLabel(ref.timeClass)}", "answer:${ref.recordId}:${ref.field}") {
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
        append("\n分组：").append(sectionLabel(ref.section))
        append("\n记录 ").append(ref.recordId).append(" · 修订 ").append(ref.revision)
        append("\n模板 ").append(ref.template.id).append(" · 版本 ").append(ref.template.version)
        append("\n发生时间：").append(occurrenceLabel(ref.occurrence)).append(" · ").append(timeClassLabel(ref.timeClass))
        append("\n共 ").append(ref.codePoints).append(" 字。")
    }

    private fun showRecords() {
        val current = report ?: return showHome()
        beginScreen("records", "记录清单")
        runIo({ app.periodReports.records(current, recordOffset, PAGE_SIZE) }) { page ->
            Ui.note(root, "共 ${page.total} 条候选记录；发生日期未知 ${page.unknown} 条；跨期 ${page.overlap} 条。日期未知的资料无法归入所选周期；跨期记录只有部分时间在范围内。")
            if (page.items.isEmpty()) emptyState(card(), "当前范围没有记录。")
            page.items.forEach { record ->
                card().also { row ->
                    row.tag = "periodRecord:${record.id}"
                    Ui.body(row, occurrenceLabel(record.occurrence))
                    Ui.note(row, "${record.topics.joinToString("、").ifEmpty { "未分类" }} · ${timeClassLabel(record.timeClass)}${if (record.archived) " · 已归档" else ""}")
                }
            }
            if (recordOffset > 0) addButton(root, "上一页", "recordsPrev") { recordOffset = (recordOffset - PAGE_SIZE).coerceAtLeast(0); showRecords() }
            page.nextOffset?.let { next -> addButton(root, "下一页", "recordsNext") { recordOffset = next; showRecords() } }
        }
    }

    private fun showKnowledgeList() {
        val current = report ?: return showHome()
        beginScreen("knowledge", "相关知识")
        Ui.note(root, "这里列出与这段时间有关、来源仍有效的知识。日期表示适用范围，核对意见仍需结合原文判断。")
        runIo({ app.periodReports.knowledge(current, knowledgeOffset, PAGE_SIZE) }) { page ->
            Ui.note(root, "共 ${page.total} 条知识。")
            if (page.items.isEmpty()) emptyState(card(), "本范围没有纳入的知识。")
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
        Ui.cardTitle(detail, "知识")
        Ui.body(detail, value.claim.text)
        Ui.note(detail, "类型：${kindLabel(value.claim.kind)} · 分类：${categoryLabel(value.category)}")
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
        Ui.sectionTitle(evidence, "原文依据")
        Ui.note(evidence, "点开查看这段内容引用的原文。")
        val receipts = KnowledgeValidation.allEvidence(value)
        if (receipts.isEmpty()) emptyState(evidence, "本条知识没有记录原文证据引用。")
        receipts.forEachIndexed { index, receipt ->
            listRow(evidence, "原文 ${index + 1} · ${receipt.end - receipt.start} 字", "evidence:${receipt.id}") {
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
        beginScreen("fragment", "原文")
        Ui.disclosure(root, "来源信息", "fragmentSource") { details -> Ui.note(details, header) }
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
                Ui.note(root, "已显示 ${fragment.end} / ${fragment.totalCodePoints} 字")
                fragmentMore = Ui.button(root, "继续读取", "fragmentMore") { if (!busy) loadMoreFragment() }
            } else {
                Ui.note(root, "已显示全部 ${fragment.totalCodePoints} 字")
            }
        }
    }

    private fun synthesisLabel(status: ReportSynthesisStatus) = when (status) {
        ReportSynthesisStatus.NOT_GENERATED -> "已有材料"
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
