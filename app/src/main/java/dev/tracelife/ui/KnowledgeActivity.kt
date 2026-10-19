package dev.tracelife.ui

import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ReviewIssue
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeFilter
import dev.tracelife.knowledge.KnowledgeIndexPage
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.transfer.StaleSnapshotException

class KnowledgeActivity : ScreenActivity() {
    private var filterFrom = ""
    private var filterThrough = ""
    private var filterTopic = ""
    private var includeInvalid = false
    private var includeInvalidCheck: CheckBox? = null
    private val filterFields = linkedMapOf<String, EditText>()
    private var currentPage: KnowledgeIndexPage? = null
    private var detailId: String? = null
    private var detailRevision: Int? = null

    override fun showHome() = showIndex()

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
        Ui.disclosure(root, "筛选知识", "knowledgeFilters", expanded = filterFrom.isNotBlank() || filterThrough.isNotBlank() || filterTopic.isNotBlank() || includeInvalid) { filters ->
            Ui.note(filters, "按分析所涉及的日期和主题查找。")
            filterFields.clear()
            filterFields["from"] = labeledFieldIn(filters, "从哪天起", "YYYY-MM-DD · 可留空", filterFrom)
            filterFields["through"] = labeledFieldIn(filters, "到哪天", "YYYY-MM-DD · 可留空", filterThrough)
            filterFields["topic"] = labeledFieldIn(filters, "主题", "可留空", filterTopic)
            includeInvalidCheck = checkIn(filters, "也看已失效的条目", includeInvalid)
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
                includeInvalid = includeInvalid,
            )
        } catch (failure: IllegalArgumentException) {
            showError(failure)
            return
        }
        runIo({ app.knowledge.index(filter, PAGE_SIZE) }) { page ->
            currentPage = page
            renderIndexPage(page)
        }
    }

    private fun loadNextPage() {
        val page = currentPage ?: return
        runIo({
            try {
                app.knowledge.next(page)
            } catch (stale: StaleSnapshotException) {
                null
            }
        }) { next ->
            if (next == null) {
                toast("内容有更新，已刷新列表")
                loadIndex()
            } else {
                currentPage = next
                renderIndexPage(next)
            }
        }
    }

    private fun renderIndexPage(page: KnowledgeIndexPage) {
        root.findViewWithTag<View>("knowledgeResults")?.let { root.removeView(it) }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "knowledgeResults" }
        root.addView(results)
        val summary = StringBuilder("共 ${page.total} 条")
        if (page.omittedInvalid > 0) summary.append(" · ${page.omittedInvalid} 条已失效，筛选中可查看")
        Ui.note(results, summary.toString())
        if (page.items.isEmpty()) {
            val empty = Ui.card(results)
            emptyState(empty, "还没有积累到这里。\n从日常记录中整理出的观察与经验，会保存在知识库。")
            addButton(empty, "去整理记录", "navReview") { startActivity(Intent(this, ReviewActivity::class.java)) }
        } else {
            Ui.disclosure(results, "整理更多记录", "knowledgeOrganize") { organize ->
                Ui.note(organize, "整理并核对记录后，可以保存新的知识。")
                addButton(organize, "去整理记录", "navReview") { startActivity(Intent(this, ReviewActivity::class.java)) }
            }
        }
        page.items.forEach { item ->
            listRow(
                results,
                "${item.topics.joinToString("、").ifEmpty { "未分类" }} · ${categoryLabel(item.category)}\n${item.from} — ${item.through} · ${evidenceStateLabel(item.evidenceState)}",
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
                Ui.note(card, "${categoryLabel(value.category)} · ${value.topics.joinToString("、").ifEmpty { "未分类" }}")
                if (!view.isCurrentRevision) Ui.note(card, "正在查看历史版本。")
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
            Ui.disclosure(root, "来源线索 · ${value.evidence.size} 处", "knowledgeEvidence") { card ->
                Ui.note(card, "保留原始记录的位置，便于追溯。本页只展示引用位置。")
                value.evidence.forEachIndexed { index, receipt ->
                    Ui.note(card, "来源 ${index + 1} · 第 ${receipt.revision} 版 · 第 ${receipt.start + 1}–${receipt.end} 字")
                    Ui.disclosure(card, "查看引用标识", "knowledgeReceipt:${receipt.id}") { identity ->
                        Ui.note(identity, "记录 ${receipt.recordId}\n内容 ${receipt.field}\n引用 ${receipt.id}")
                    }
                }
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
        KnowledgeCategory.OBSERVATION -> "观察"
        KnowledgeCategory.KNOWLEDGE_NOTE -> "知识"
        KnowledgeCategory.INSIGHT -> "洞察"
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
}
