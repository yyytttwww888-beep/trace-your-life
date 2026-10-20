package dev.tracelife.ui

import android.content.Intent
import android.view.View
import android.widget.AdapterView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ReviewIssue
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeFilter
import dev.tracelife.knowledge.KnowledgeIndexPage
import dev.tracelife.knowledge.KnowledgeView
import dev.tracelife.MainActivity
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.transfer.StaleSnapshotException

class KnowledgeActivity : ScreenActivity() {
    private var filterFrom = ""
    private var filterThrough = ""
    private var filterTopic = ""
    private var includeInvalid = false
    private var category: KnowledgeCategory? = KnowledgeCategory.INSIGHT
    private var includeInvalidCheck: CheckBox? = null
    private val filterFields = linkedMapOf<String, EditText>()
    private var currentPage: KnowledgeIndexPage? = null
    private var detailId: String? = null
    private var detailRevision: Int? = null
    private var refreshOnResume = false

    override fun showHome() = showIndex()

    override fun onPause() {
        super.onPause()
        refreshOnResume = true
    }

    override fun onResume() {
        super.onResume()
        if (refreshOnResume && !busy) {
            refreshOnResume = false
            when (screen) {
                "index" -> loadIndex()
                "detail" -> detailId?.let { showDetail(it, detailRevision) }
            }
        }
    }

    override fun navigateBack() {
        if (busy) return
        when {
            screen == "history" -> detailId?.let { showDetail(it) } ?: showIndex()
            screen == "detail" && detailRevision != null -> detailId?.let { showDetail(it) } ?: showIndex()
            screen == "detail" -> showIndex()
            else -> finish()
        }
    }

    private fun showIndex() {
        detailId = null
        detailRevision = null
        beginScreen("index", "知识库", showBack = false)
        Ui.note(root, "已保存的发现、经验与记录观察，可以在这里回看依据。")
        primaryButton("分析记录", "analyzeRecords") { startActivity(Intent(this, ReflectionActivity::class.java)) }
        val categories = listOf(KnowledgeCategory.INSIGHT, KnowledgeCategory.KNOWLEDGE_NOTE, KnowledgeCategory.OBSERVATION, null)
        spinner(categories.map { it?.let(::categoryLabel) ?: "全部条目" }, categories.indexOf(category), "knowledgeCategory").also { choices ->
            choices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (category != categories[position]) {
                        category = categories[position]
                        loadIndex()
                    }
                }
            }
        }
        Ui.disclosure(root, "筛选知识", "knowledgeFilters", expanded = filterFrom.isNotBlank() || filterThrough.isNotBlank() || filterTopic.isNotBlank() || includeInvalid) { filters ->
            Ui.note(filters, "按分析所涉及的日期和主题查找。")
            filterFields.clear()
            filterFields["from"] = labeledFieldIn(filters, "从哪天起", "YYYY-MM-DD · 可留空", filterFrom)
            filterFields["through"] = labeledFieldIn(filters, "到哪天", "YYYY-MM-DD · 可留空", filterThrough)
            filterFields["topic"] = labeledFieldIn(filters, "主题", "可留空", filterTopic)
            includeInvalidCheck = checkIn(filters, "包含待更新与过期条目", includeInvalid)
            primaryIn(filters, "应用筛选", "applyFilter") {
                captureFilters()
                loadIndex()
            }
        }
        loadIndex()
    }

    private fun captureFilters() {
        filterFrom = filterFields["from"]?.text?.toString() ?: filterFrom
        filterThrough = filterFields["through"]?.text?.toString() ?: filterThrough
        filterTopic = filterFields["topic"]?.text?.toString() ?: filterTopic
        includeInvalid = includeInvalidCheck?.isChecked ?: includeInvalid
    }

    private fun loadIndex() {
        val filter = try {
            val from = filterFrom.takeIf { it.isNotBlank() }?.let { parseDate(it, "起始日期") }
            val through = filterThrough.takeIf { it.isNotBlank() }?.let { parseDate(it, "结束日期") }
            require(from == null || through == null || !through.isBefore(from)) { "结束日期不能早于起始日期" }
            KnowledgeFilter(
                from = from,
                through = through,
                topics = filterTopic.trim().ifEmpty { null }?.let { setOf(it) },
                category = category,
                includeInvalid = includeInvalid,
            )
        } catch (failure: IllegalArgumentException) {
            showError(failure)
            return
        }
        runIo({ readPage(app.knowledge.index(filter, PAGE_SIZE)) }) { result ->
            currentPage = result.page
            renderIndexPage(result)
        }
    }

    private fun loadNextPage() {
        val page = currentPage ?: return
        runIo({
            try {
                readPage(app.knowledge.next(page))
            } catch (stale: StaleSnapshotException) {
                null
            }
        }) { next ->
            if (next == null) {
                toast("内容有更新，已刷新列表")
                loadIndex()
            } else {
                currentPage = next.page
                renderIndexPage(next)
            }
        }
    }

    /** Only this page's bodies are read for local display; the model receives none of them. */
    private fun readPage(page: KnowledgeIndexPage): DisplayPage {
        val counts = app.knowledge.index(page.filter.copy(category = null, includeInvalid = false), limit = 1)
        val entries = page.items.map { item ->
            val entry = app.knowledge.get(item.id, item.revision)
                ?: throw StaleSnapshotException("知识已变化，请刷新列表")
            if (!entry.isCurrentRevision || entry.evidenceState != item.evidenceState)
                throw StaleSnapshotException("知识状态已变化，请刷新列表")
            entry
        }
        if (counts.source != page.source || counts.analysisGeneration != page.analysisGeneration ||
            app.repository.analysisSourceStamp() != page.source || app.repository.currentAnalysisGeneration() != page.analysisGeneration)
            throw StaleSnapshotException("知识已变化，请刷新列表")
        return DisplayPage(page, entries, counts.total, counts.omittedInvalid)
    }

    private fun renderIndexPage(result: DisplayPage) {
        val page = result.page
        root.findViewWithTag<View>("knowledgeResults")?.let { root.removeView(it) }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "knowledgeResults" }
        root.addView(results)
        Ui.note(results, "当前条目 ${result.currentCount} 条 · 待更新或过期 ${result.invalidCount} 条")
        if (result.invalidCount > 0 && !includeInvalid) {
            addButton(results, "查看待更新与历史条目", "showPendingKnowledge") {
                captureFilters()
                includeInvalid = true
                category = null
                showIndex()
            }
        }
        if (includeInvalid) Ui.note(results, "当前与待更新条目一同显示。待更新或过期的内容仅供回顾，不能作为当前结论。")
        Ui.sectionTitle(results, "${category?.let(::categoryLabel) ?: "全部条目"} · ${page.total}")
        if (page.items.isEmpty()) {
            val empty = Ui.card(results)
            emptyState(empty, when (category) {
                KnowledgeCategory.INSIGHT -> "这里还没有已保存的发现。\n点击「分析记录」，查看值得保留的经验、需要留意的问题和下一步建议；确认后再保存。"
                KnowledgeCategory.KNOWLEDGE_NOTE -> "这里还没有已保存的经验与知识。\n它们是可以再次使用的方法或经验，和记录观察分别保存。"
                KnowledgeCategory.OBSERVATION -> "这里还没有记录观察。\n观察描述发生过什么，不代表已经形成新的发现。"
                null -> "这个范围内还没有知识条目。\n可以调整筛选，或点击「分析记录」开始。"
            })
            if (result.currentCount > 0) Ui.note(empty, "其他类型的条目可在上方切换查看。")
        }
        result.entries.forEach { view ->
            val item = view.value
            val wording = item.claim.text.trim().replace(Regex("\\s+"), " ")
            val preview = if (wording.codePointCount(0, wording.length) > 160)
                wording.substring(0, wording.offsetByCodePoints(0, 160)) + "…" else wording
            listRow(
                results,
                "$preview\n${categoryLabel(item.category)}${item.topics.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = " · ") ?: ""} · ${evidenceStateLabel(view.evidenceState)}\n分析范围 ${item.scope.from} — ${item.scope.through}",
                "knowledge:${item.id}",
            ) {
                showDetail(item.id)
            }
        }
        page.nextOffset?.let { addButton(results, "下一页", "nextPage") { loadNextPage() } }
    }

    private fun showDetail(id: String, revision: Int? = null) {
        detailId = id
        detailRevision = revision
        beginScreen("detail", if (revision == null) "知识详情" else "历史版本 · $revision")
        runIo({
            requireNotNull(app.knowledge.get(id, revision)) { "知识条目不存在或已被删除" }
        }) { view ->
            val value = view.value
            Ui.card(root).also { card ->
                Ui.note(card, categoryLabel(value.category) + value.topics.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = " · ").orEmpty())
                if (!view.isCurrentRevision) Ui.note(card, "正在查看历史版本。")
                if (view.evidenceState != KnowledgeEvidenceState.CURRENT) Ui.note(card, "这条内容需要重新分析或核对，暂不作为当前结论。")
                Ui.body(card, value.claim.text)
                chipsRow(card).also { row ->
                    chip(row, evidenceStateLabel(view.evidenceState), evidenceStateTone(view.evidenceState))
                    chip(row, kindLabel(value.claim.kind), Ui.Tone.NEUTRAL)
                }
                Ui.note(card, "涉及 ${value.scope.from} — ${value.scope.through} 的记录")
                Ui.disclosure(card, "时间与版本", "knowledgeMetadata") { details ->
                    Ui.note(details, "日期表示分析范围，不是每件事的发生日期。时区 ${value.scope.zoneId}。")
                    Ui.note(details, "适用至 ${value.expiresAt?.toString() ?: "无固定期限"}")
                    Ui.note(details, "第 ${value.revision} 版 · 发布于 ${value.publishedAt}")
                }
            }
            Ui.disclosure(root, "核对结果与我的意见", "knowledgeReview") { card ->
                Ui.note(card, "核对仅依据当前资料，仍可能有遗漏。")
                chip(card, verdictLabel(value.review.verdict), verdictTone(value.review.verdict))
                chip(card, view.userDecision?.let { decisionLabel(it) } ?: "尚未反馈",
                    view.userDecision?.let { decisionTone(it) } ?: Ui.Tone.NEUTRAL)
                if (view.userDecision == ClaimFeedbackDecision.CORRECTED) {
                    Ui.note(card, "你的纠正单独保存在反馈中，没有覆盖这条知识的原文。")
                }
                if (value.review.issues.isNotEmpty()) Ui.note(card, "需要留意：${value.review.issues.joinToString("、") { issueLabel(it) }}")
                value.review.unknowns.forEach { Ui.note(card, "· $it") }
            }
            Ui.disclosure(root, "查看依据", "knowledgeEvidence") { card ->
                Ui.note(card, "核对引用的原文 · ${value.evidence.size} 处")
                Ui.note(card, "按需查看核对时读取的原文。来源发生变化后，需要重新分析。")
                val scanned = KnowledgeValidation.allEvidence(value).size - value.evidence.distinctBy { it.id }.size
                if (scanned > 0) Ui.note(card, "来源扫描另包含 $scanned 处原文；这里展示最终核对读取的部分。")
                val actions = KnowledgeValidation.allActionEvidence(value).size
                if (value.metricEvidence.isNotEmpty() || actions > 0) {
                    Ui.note(card, "另有 ${value.metricEvidence.size} 份统计依据、$actions 份行动依据。本页暂未展开这两类材料，原文数量不代表全部依据。")
                }
                value.evidence.forEachIndexed { index, receipt ->
                    val source = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    card.addView(source)
                    Ui.note(source, "来源 ${index + 1} · 第 ${receipt.revision} 版 · 第 ${receipt.start + 1}–${receipt.end} 字")
                    addButton(source, "查看这段原文", "readEvidence:${receipt.id}") { showEvidence(id, value.revision, receipt, source) }
                    Ui.disclosure(source, "引用位置", "knowledgeReceipt:${receipt.id}") { identity ->
                        Ui.note(identity, "记录 ${receipt.recordId}\n内容 ${receipt.field}\n引用 ${receipt.id}")
                    }
                }
                addButton(card, "查看原始记录", "navRecords") { startActivity(Intent(this, MainActivity::class.java)) }
            }
            card().also { actions ->
                addButton(actions, "版本历史", "history") { showHistory(id) }
                if (view.isCurrentRevision) {
                    Ui.disclosure(actions, "管理这条知识", "knowledgeManagement") { management ->
                        dangerIn(management, "永久删除", "deleteKnowledge") {
                            confirm("永久删除这条知识？", "这条知识及全部历史版本都会被删除，无法撤销。你独立写下的反馈会保留。", "永久删除") {
                                runIo({ app.knowledge.delete(id, value.revision) }) {
                                    toast("知识已删除")
                                    showIndex()
                                }
                            }
                        }
                    }
                } else {
                    addButton(actions, "查看最新版本", "currentRevision") { showDetail(id) }
                }
            }
        }
    }

    private fun showEvidence(id: String, revision: Int, receipt: EvidenceReceipt, parent: LinearLayout) {
        val resultTag = "evidenceText:${receipt.id}"
        parent.findViewWithTag<View>(resultTag)?.let(parent::removeView)
        runIo({
            val view = app.knowledge.get(id, revision)
            if (view == null || receipt !in view.value.evidence || view.evidenceState != KnowledgeEvidenceState.CURRENT) {
                "这份依据已发生变化、过期或不可用。请重新分析后查看当前原文。"
            } else {
                val fragment = app.repository.analysisSourceFragment(view.value.source, view.value.scope,
                    receipt.recordId, receipt.field, receipt.start, receipt.end - receipt.start)
                if (fragment.revision != receipt.revision || fragment.end != receipt.end ||
                    fragment.totalCodePoints != receipt.totalCodePoints || AnalysisRunCodec.sha256(fragment.text) != receipt.sha256)
                    throw StaleSnapshotException("引用与当前原文不一致，请重新分析")
                val latest = app.knowledge.get(id, revision)
                if (latest?.evidenceState != KnowledgeEvidenceState.CURRENT)
                    throw StaleSnapshotException("依据已变化，请重新分析")
                fragment.question?.let { "$it\n\n${fragment.text}" } ?: fragment.text
            }
        }) { wording ->
            Ui.body(parent, wording).tag = resultTag
        }
    }

    private fun showHistory(id: String, offset: Int = 0) {
        detailId = id
        beginScreen("history", "版本历史")
        runIo({ app.knowledge.history(id, offset, PAGE_SIZE) }) { revisions ->
            if (revisions.isEmpty()) emptyState(Ui.card(root), "没有更多版本了。")
            revisions.forEach { view ->
                listRow(
                    root,
                    "第 ${view.value.revision} 版${if (view.isCurrentRevision) " · 当前版本" else ""}\n${evidenceStateLabel(view.evidenceState)} · ${view.value.publishedAt}",
                    "revision:${view.value.revision}",
                ) {
                    showDetail(id, view.value.revision)
                }
            }
            if (offset > 0) button("上一页", "historyPrevious") { showHistory(id, (offset - PAGE_SIZE).coerceAtLeast(0)) }
            if (revisions.size == PAGE_SIZE) button("下一页", "historyNext") { showHistory(id, offset + PAGE_SIZE) }
        }
    }

    private fun chipsRow(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this)
    }

    private fun evidenceStateTone(state: KnowledgeEvidenceState) = when (state) {
        KnowledgeEvidenceState.CURRENT -> Ui.Tone.OK
        else -> Ui.Tone.WARN
    }

    private fun verdictTone(verdict: ReviewVerdict) = when (verdict) {
        ReviewVerdict.SUPPORTED -> Ui.Tone.OK
        ReviewVerdict.CONTRADICTED -> Ui.Tone.ERROR
        else -> Ui.Tone.WARN
    }

    private fun decisionTone(decision: ClaimFeedbackDecision) = when (decision) {
        ClaimFeedbackDecision.CONFIRMED -> Ui.Tone.OK
        ClaimFeedbackDecision.CORRECTED -> Ui.Tone.ACCENT
        ClaimFeedbackDecision.REJECTED -> Ui.Tone.ERROR
        ClaimFeedbackDecision.WITHDRAWN -> Ui.Tone.NEUTRAL
    }

    private fun categoryLabel(category: KnowledgeCategory) = when (category) {
        KnowledgeCategory.OBSERVATION -> "记录观察"
        KnowledgeCategory.KNOWLEDGE_NOTE -> "经验与知识"
        KnowledgeCategory.INSIGHT -> "发现"
    }

    private fun kindLabel(kind: ClaimKind) = when (kind) {
        ClaimKind.REPORTED_EXPERIENCE -> "自述经历"
        ClaimKind.POSSIBLE_INTERPRETATION -> "可能的解读"
    }

    private fun evidenceStateLabel(state: KnowledgeEvidenceState) = when (state) {
        KnowledgeEvidenceState.CURRENT -> "当前有效"
        KnowledgeEvidenceState.STALE -> "来源变化 · 待更新"
        KnowledgeEvidenceState.EXPIRED -> "已过期"
        KnowledgeEvidenceState.FEEDBACK_CHANGED -> "反馈变化 · 待更新"
        KnowledgeEvidenceState.REVIEW_CHANGED -> "核对变化 · 待更新"
    }

    private fun verdictLabel(verdict: ReviewVerdict) = when (verdict) {
        ReviewVerdict.SUPPORTED -> "现有资料支持"
        ReviewVerdict.UNSUPPORTED -> "未获支持"
        ReviewVerdict.CONTRADICTED -> "与证据矛盾"
        ReviewVerdict.INSUFFICIENT_CONTEXT -> "上下文不足"
    }

    private fun issueLabel(issue: ReviewIssue) = when (issue) {
        ReviewIssue.USER_REPORT_AS_FACT -> "把自述当作事实"
        ReviewIssue.SCOPE_OVERGENERALIZATION -> "范围过度泛化"
        ReviewIssue.CAUSAL_OVERREACH -> "因果推断过度"
        ReviewIssue.COUNTEREVIDENCE_OMISSION -> "遗漏反例"
        ReviewIssue.INSUFFICIENT_CONTEXT -> "上下文不足"
        ReviewIssue.OTHER -> "其他"
    }

    private fun decisionLabel(decision: ClaimFeedbackDecision) = when (decision) {
        ClaimFeedbackDecision.CONFIRMED -> "已确认"
        ClaimFeedbackDecision.CORRECTED -> "已纠正"
        ClaimFeedbackDecision.REJECTED -> "已否定"
        ClaimFeedbackDecision.WITHDRAWN -> "已撤回"
    }

    companion object {
        private const val PAGE_SIZE = 25
    }

    private data class DisplayPage(val page: KnowledgeIndexPage, val entries: List<KnowledgeView>, val currentCount: Int, val invalidCount: Int)
}
