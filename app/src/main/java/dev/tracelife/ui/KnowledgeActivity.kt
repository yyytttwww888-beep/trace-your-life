package dev.tracelife.ui

import android.content.Intent
import android.text.InputFilter
import android.text.InputType
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
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "知识来源")
            Ui.note(card, "知识只能从核对页中已核对的陈述发布，本页不提供新建。")
            Ui.button(card, "前往核对与整理", "navReview") { if (!busy) startActivity(Intent(this, ReviewActivity::class.java)) }
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "筛选")
            Ui.note(card, "日期筛选定位分析适用范围，不是陈述的精确发生日期。目录不含正文，点开条目查看。")
            filterFields.clear()
            filterFields["from"] = fieldIn(card, "起始日期（可留空，YYYY-MM-DD）", filterFrom)
            filterFields["through"] = fieldIn(card, "结束日期（可留空，YYYY-MM-DD）", filterThrough)
            filterFields["topic"] = fieldIn(card, "主题（填写一个主题，可留空）", filterTopic)
            includeInvalidCheck = checkIn(card, "包含已失效条目", includeInvalid)
            Ui.button(card, "应用筛选", "applyFilter") {
                if (!busy) {
                    captureFilters()
                    loadIndex()
                }
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
                toast("知识目录已变化，已从首页重新读取")
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
        if (page.omittedInvalid > 0) summary.append("；另有 ${page.omittedInvalid} 条已失效未显示（勾选「包含已失效条目」可查看）")
        Ui.note(results, summary.toString())
        if (page.items.isEmpty()) Ui.note(results, "当前范围没有知识条目。")
        page.items.forEach { item ->
            listRow(
                results,
                "${categoryLabel(item.category)} · ${evidenceStateLabel(item.evidenceState)}\n主题：${item.topics.joinToString("、").ifEmpty { "未设置" }} · 适用范围 ${item.from} 至 ${item.through}",
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
        beginScreen("detail", if (revision == null) "知识详情" else "历史修订 · $revision")
        runIo({
            requireNotNull(app.knowledge.get(id, revision)) { "知识条目不存在或已被删除" }
        }) { view ->
            val value = view.value
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "条目")
                if (!view.isCurrentRevision) Ui.note(card, "这是历史修订，不是当前版本；当前状态请以最新修订为准。")
                chipsRow(card).also { row ->
                    chip(row, evidenceStateLabel(view.evidenceState), evidenceStateTone(view.evidenceState))
                    chip(row, categoryLabel(value.category), Ui.Tone.NEUTRAL)
                    chip(row, kindLabel(value.claim.kind), Ui.Tone.NEUTRAL)
                }
                Ui.body(card, "主题：${value.topics.joinToString("、").ifEmpty { "未设置" }}")
                Ui.sectionTitle(card, "正文")
                Ui.body(card, value.claim.text)
                Ui.note(card, "适用范围：${value.scope.from} 至 ${value.scope.through}（时区 ${value.scope.zoneId}）")
                Ui.note(card, "这是分析适用范围，不是陈述的精确发生日期。")
                Ui.note(card, "适用期限：${value.expiresAt?.toString() ?: "无固定期限"}")
                Ui.note(card, "发布于：${value.publishedAt} · 首次发布：${value.createdAt} · 修订 ${value.revision}")
            }
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "模型核对")
                Ui.note(card, "模型核对是有限证据下的质量检查，不是事实认证。")
                chipsRow(card).also { row ->
                    chip(row, "结论：${verdictLabel(value.review.verdict)}", verdictTone(value.review.verdict))
                    chip(row, "用户反馈决定：${view.userDecision?.let { decisionLabel(it) } ?: "无反馈记录"}",
                        view.userDecision?.let { decisionTone(it) } ?: Ui.Tone.NEUTRAL)
                }
                if (value.review.issues.isNotEmpty()) Ui.note(card, "问题：${value.review.issues.joinToString("、") { issueLabel(it) }}")
                if (value.review.unknowns.isNotEmpty()) {
                    Ui.note(card, "未知项：")
                    value.review.unknowns.forEach { Ui.note(card, "· $it") }
                }
            }
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "证据引用")
                Ui.note(card, "共 ${value.evidence.size} 条；本页不读取原文分片。")
                value.evidence.forEach { receipt ->
                    Ui.note(card, "证据 ${receipt.id}\n记录 ${receipt.recordId} · 修订 ${receipt.revision} · 字段 ${receipt.field}\n码点范围 [${receipt.start}, ${receipt.end})，该字段共 ${receipt.totalCodePoints} 码点")
                }
            }
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "操作")
                Ui.button(card, "查看修订历史", "history") { if (!busy) showHistory(id) }
                if (view.isCurrentRevision) {
                    Ui.dangerButton(card, "永久删除这条知识", "deleteKnowledge") {
                        if (!busy) confirm("永久删除这条知识？", "整个条目与全部修订会被永久删除并保留删除标记，同一 ID 不可复用。此操作不能撤销。用户独立的反馈文字不随知识删除。", "永久删除") {
                            runIo({ app.knowledge.delete(id, value.revision) }) {
                                toast("知识已删除")
                                showIndex()
                            }
                        }
                    }
                } else {
                    Ui.button(card, "查看最新修订", "currentRevision") { if (!busy) showDetail(id) }
                }
            }
        }
    }

    private fun showHistory(id: String, offset: Int = 0) {
        detailId = id
        beginScreen("history", "修订历史")
        runIo({ app.knowledge.history(id, offset, PAGE_SIZE) }) { revisions ->
            if (revisions.isEmpty()) note("没有更多修订。")
            revisions.forEach { view ->
                listRow(
                    root,
                    "修订 ${view.value.revision} · ${evidenceStateLabel(view.evidenceState)}${if (view.isCurrentRevision) " · 当前修订" else ""}\n发布于：${view.value.publishedAt}",
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

    private fun fieldIn(parent: LinearLayout, hint: String, value: String = ""): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(value)
            setFreezesText(false)
            isSaveEnabled = false
        }
        Ui.styleField(this, input)
        parent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
        input.filters = arrayOf(InputFilter.LengthFilter(2_000))
        return input
    }

    private fun checkIn(parent: LinearLayout, label: String, checked: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = checked
        Ui.styleCheck(this@KnowledgeActivity, this)
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
        KnowledgeCategory.KNOWLEDGE_NOTE -> "知识条目"
        KnowledgeCategory.INSIGHT -> "洞察"
    }

    private fun kindLabel(kind: ClaimKind) = when (kind) {
        ClaimKind.REPORTED_EXPERIENCE -> "自述经历"
        ClaimKind.POSSIBLE_INTERPRETATION -> "可能的解读"
    }

    private fun evidenceStateLabel(state: KnowledgeEvidenceState) = when (state) {
        KnowledgeEvidenceState.CURRENT -> "当前有效"
        KnowledgeEvidenceState.STALE -> "已失效（来源已变化，保守失效不自动重算）"
        KnowledgeEvidenceState.EXPIRED -> "已过期（适用期限已到）"
        KnowledgeEvidenceState.FEEDBACK_CHANGED -> "已失效（反馈已变化）"
        KnowledgeEvidenceState.REVIEW_CHANGED -> "已失效（核对已变化）"
    }

    private fun verdictLabel(verdict: ReviewVerdict) = when (verdict) {
        ReviewVerdict.SUPPORTED -> "有限证据下支持"
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
        ClaimFeedbackDecision.CORRECTED -> "已纠正（纠正文字独立保存在反馈中，不是本条正文）"
        ClaimFeedbackDecision.REJECTED -> "已否定"
        ClaimFeedbackDecision.WITHDRAWN -> "已撤回"
    }

    companion object {
        private const val PAGE_SIZE = 25
    }
}
