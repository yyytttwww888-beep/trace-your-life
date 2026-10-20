package dev.tracelife.ui

import android.content.Intent
import android.os.Bundle
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
import dev.tracelife.knowledge.KnowledgeContinuity
import dev.tracelife.knowledge.ExperienceSummary
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeFilter
import dev.tracelife.knowledge.KnowledgeJourney
import dev.tracelife.knowledge.KnowledgeJourneyPage
import dev.tracelife.knowledge.KnowledgeShelf
import dev.tracelife.knowledge.RelatedActions
import dev.tracelife.knowledge.TopicOverview
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
    private var includeInvalid = true
    private var shelf = KnowledgeShelf.FINDINGS
    private val journey get() = KnowledgeJourney(app.repository)
    private var topicLimit = 12
    private var topics = emptyList<TopicOverview>()
    private var experienceThreads = emptyList<ExperienceSummary>()
    private var includeInvalidCheck: CheckBox? = null
    private val filterFields = linkedMapOf<String, EditText>()
    private var currentPage: KnowledgeJourneyPage? = null
    private var detailId: String? = null
    private var detailRevision: Int? = null
    private var refreshOnResume = false

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        filterFrom = savedInstanceState?.getString("from").orEmpty()
        filterThrough = savedInstanceState?.getString("through").orEmpty()
        filterTopic = savedInstanceState?.getString("topic").orEmpty()
        includeInvalid = savedInstanceState?.getBoolean("includeInvalid", true) ?: true
        shelf = savedInstanceState?.getString("shelf")?.let(KnowledgeShelf::valueOf) ?: KnowledgeShelf.FINDINGS
        detailId = if (savedInstanceState != null) savedInstanceState.getString("detail") else intent.getStringExtra(EXTRA_KNOWLEDGE_ID)
        detailRevision = (savedInstanceState?.getInt("revision", -1)
            ?: intent.getIntExtra(EXTRA_KNOWLEDGE_REVISION, -1)).takeIf { it > 0 }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureFilters()
        outState.putString("from", filterFrom)
        outState.putString("through", filterThrough)
        outState.putString("topic", filterTopic)
        outState.putString("shelf", shelf.name)
        outState.putBoolean("includeInvalid", includeInvalid)
        outState.putString("detail", detailId)
        outState.putInt("revision", detailRevision ?: -1)
        super.onSaveInstanceState(outState)
    }

    override fun showHome() {
        detailId?.let { showDetail(it, detailRevision) } ?: showIndex()
    }

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
        Ui.note(root, "从一个主题回到经历、留下的经验，以及后来尝试的结果。")
        primaryButton("分析记录", "analyzeRecords") { startActivity(Intent(this, ReflectionActivity::class.java)) }
        val shelves = KnowledgeShelf.entries
        spinner(shelves.map(::shelfLabel), shelves.indexOf(shelf), "knowledgeCategory").also { choices ->
            choices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (shelf != shelves[position]) {
                        shelf = shelves[position]
                        loadIndex()
                    }
                }
            }
        }
        Ui.disclosure(root, "筛选知识", "knowledgeFilters", expanded = filterFrom.isNotBlank() || filterThrough.isNotBlank() || filterTopic.isNotBlank()) { filters ->
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
                category = null,
                includeInvalid = includeInvalid,
            )
        } catch (failure: IllegalArgumentException) {
            showError(failure)
            return
        }
        runIo({
            val overview = journey.topics()
            val page = journey.page(filter, shelf, limit = PAGE_SIZE)
            Triple(overview, page, KnowledgeContinuity(app.repository).page(topic = filterTopic.trim().ifBlank { null }, limit = 3).items)
        }) { (overview, page, threads) ->
            experienceThreads = threads
            topics = overview
            currentPage = page
            renderIndexPage(page)
        }
    }

    private fun loadNextPage() {
        val page = currentPage ?: return
        runIo({
            try {
                journey.page(page.filter, page.shelf, requireNotNull(page.nextOffset), page.limit, page)
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

    private fun renderIndexPage(page: KnowledgeJourneyPage) {
        root.findViewWithTag<View>("knowledgeResults")?.let { root.removeView(it) }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "knowledgeResults" }
        root.addView(results)
        renderTopics(results)
        renderExperienceThreads(results)
        Ui.note(results, "已保存 ${page.currentCount + page.pendingCount} 条 · 当前条目 ${page.currentCount} 条 · 待更新或过期 ${page.pendingCount} 条")
        if (page.pendingCount > 0 && !includeInvalid) {
            addButton(results, "查看待更新与历史条目", "showPendingKnowledge") {
                captureFilters()
                includeInvalid = true
                shelf = KnowledgeShelf.ALL
                showIndex()
            }
        }
        if (includeInvalid && page.pendingCount > 0) Ui.note(results, "保存过的内容始终留在这里。标为待更新的内容供回顾，暂不作为当前结论。")
        Ui.sectionTitle(results, "${shelfLabel(shelf)} · ${page.total}")
        if (page.entries.isEmpty()) {
            val empty = Ui.card(results)
            emptyState(empty, when (shelf) {
                KnowledgeShelf.FINDINGS -> "这里还没有已保存的发现。\n点击「分析记录」，查看值得保留的经验、需要留意的问题和下一步建议；确认后再保存。"
                KnowledgeShelf.EXPERIENCE -> "这里还没有已保存的经验与知识。\n主动分析中「值得留下」的经验与知识条目都会出现在这里。"
                KnowledgeShelf.OBSERVATIONS -> "这里还没有记录观察。\n观察描述发生过什么，不代表已经形成新的发现。"
                KnowledgeShelf.ALL -> "这个范围内还没有知识条目。\n可以调整筛选，或点击「分析记录」开始。"
            })
            if (page.currentCount > 0) Ui.note(empty, "其他类型的条目可在上方切换查看。")
        }
        page.entries.forEach { view ->
            val item = view.value
            val wording = item.claim.text.trim().replace(Regex("\\s+"), " ")
            val preview = if (wording.codePointCount(0, wording.length) > 160)
                wording.substring(0, wording.offsetByCodePoints(0, 160)) + "…" else wording
            listRow(
                results,
                "$preview\n${readingLabel(item.category, item.claim.text)}${item.topics.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = " · ") ?: ""} · ${evidenceStateLabel(view.evidenceState)}\n分析范围 ${item.scope.from} — ${item.scope.through}",
                "knowledge:${item.id}",
            ) {
                showDetail(item.id)
            }
        }
        if (page.offset > 0) addButton(results, "回到第一页", "firstPage") { loadIndex() }
        page.nextOffset?.let { addButton(results, "下一页", "nextPage") { loadNextPage() } }
        if (filterTopic.isNotBlank()) renderTopicRecords(results, filterTopic)
    }

    private fun renderTopics(parent: LinearLayout) {
        val card = Ui.card(parent)
        Ui.sectionTitle(card, if (filterTopic.isBlank()) "按主题回看" else filterTopic)
        if (filterTopic.isNotBlank()) {
            topics.firstOrNull { it.name == filterTopic }?.let {
                Ui.note(card, "${it.records} 条经历 · ${it.saved} 条已保存 · ${it.pending} 条待更新${if (it.experienceThreads > 0) " · ${it.experienceThreads} 条经验线索" else ""}")
            }
            Ui.note(card, "下方并列查看这个主题留下的理解与原始经历；同一条资料可以属于多个主题。")
            addButton(card, "所有主题", "allKnowledgeTopics") {
                filterTopic = ""
                showIndex()
            }
        } else {
            if (topics.isEmpty()) Ui.note(card, "记录或保存发现时添加主题，就能在这里逐渐串起相关经历。")
            topics.take(topicLimit).forEach { topic ->
                listRow(card, "${topic.name}\n${topic.records} 条经历 · ${topic.saved} 条已保存${if (topic.pending > 0) " · ${topic.pending} 条待更新" else ""}${if (topic.experienceThreads > 0) " · ${topic.experienceThreads} 条经验线索" else ""}", "knowledgeTopic:${topic.name}") {
                    filterTopic = topic.name
                    shelf = KnowledgeShelf.ALL
                    showIndex()
                }
            }
            if (topics.size > topicLimit) addButton(card, "更多主题", "moreKnowledgeTopics") {
                topicLimit += 12
                currentPage?.let(::renderIndexPage)
            }
        }
    }

    private fun renderExperienceThreads(parent: LinearLayout) {
        val card = Ui.card(parent)
        Ui.sectionTitle(card, "持续积累的经验")
        if (experienceThreads.isEmpty()) Ui.note(card, "在知识详情里建立一条经验线索，下周的新发现可以继续加入同一条经验。")
        experienceThreads.forEach { item ->
            listRow(card, "${item.thread.title}\n${item.thread.version} 次积累 · ${item.current.knowledge?.let { evidenceStateLabel(it.evidenceState) } ?: "相关知识已删除"}", "experience:${item.thread.id}") {
                startActivity(Intent(this, ExperienceActivity::class.java).putExtra(ExperienceActivity.EXTRA_THREAD_ID, item.thread.id))
            }
        }
        addButton(card, "查看经验线索", "experienceThreads") {
            startActivity(Intent(this, ExperienceActivity::class.java).putExtra(ExperienceActivity.EXTRA_TOPIC, filterTopic))
        }
    }

    private fun renderTopicRecords(parent: LinearLayout, topic: String, offset: Int = 0) {
        parent.findViewWithTag<View>("topicRecords")?.let(parent::removeView)
        val card = Ui.card(parent).apply { tag = "topicRecords" }
        Ui.sectionTitle(card, "相关经历")
        runIo({ journey.records(topic, offset) }) { page ->
            Ui.note(card, "此主题的全部经历 · 共 ${page.total} 条 · 按最近补充或修改排列")
            if (page.items.isEmpty()) Ui.note(card, "这个主题还没有单独标记的原始记录，可从知识详情查看它引用的记录。")
            page.items.forEach { record ->
                val occurrence = record.draft.occurrence
                val date = occurrence.startDate?.let { if (it == occurrence.endDate) "$it" else "$it — ${occurrence.endDate}" } ?: "发生日期未定"
                val preview = record.draft.body.ifBlank { record.draft.answers.values.firstOrNull().orEmpty() }.take(140)
                listRow(card, "$date\n$preview", "topicRecord:${record.id}") {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORD_ID, record.id))
                }
            }
            if (offset > 0) addButton(card, "较新的经历", "topicRecordsPrevious") { renderTopicRecords(parent, topic, (offset - 10).coerceAtLeast(0)) }
            page.nextOffset?.let { next -> addButton(card, "较早的经历", "topicRecordsNext") { renderTopicRecords(parent, topic, next) } }
        }
    }

    private fun renderRelatedActions(parent: LinearLayout, id: String, related: RelatedActions, offset: Int = 0) {
        parent.findViewWithTag<View>("relatedActionResults")?.let(parent::removeView)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "relatedActionResults" }
        parent.addView(results)
        Ui.sectionTitle(results, "由此开始的尝试 · ${related.total}")
        if (related.items.isEmpty()) Ui.note(results, "还没有关联行动。选一个具体做法，之后把实际结果留在回看里。")
        related.items.forEach { action ->
            val value = action.value
            val result = value.review?.let { "${it.reviewedOn} 回看：${it.observedText.take(140)}" }
                ?: "当前计划尚未回看 · ${value.draft.reviewOn} 回看"
            listRow(results, "${value.draft.title}\n$result", "knowledgeAction:${value.id}") {
                startActivity(Intent(this, ActionsActivity::class.java).putExtra(ActionsActivity.EXTRA_ACTION_ID, value.id))
            }
        }
        if (offset > 0) addButton(results, "上一页行动", "previousKnowledgeActions") {
            val previous = (offset - 10).coerceAtLeast(0)
            runIo({ journey.relatedActions(id, previous) }) { renderRelatedActions(parent, id, it, previous) }
        }
        related.nextOffset?.let { next -> addButton(results, "更多关联行动", "moreKnowledgeActions") {
            runIo({ journey.relatedActions(id, next) }) { renderRelatedActions(parent, id, it, next) }
        } }
    }

    private fun showDetail(id: String, revision: Int? = null) {
        detailId = id
        detailRevision = revision
        beginScreen("detail", if (revision == null) "知识详情" else "历史版本 · $revision")
        runIo({
            Triple(requireNotNull(app.knowledge.get(id, revision)) { "知识条目不存在或已被删除" }, journey.relatedActions(id),
                KnowledgeContinuity(app.repository).page(knowledgeId = id, limit = 10))
        }) { (view, relatedActions, threads) ->
            val value = view.value
            Ui.card(root).also { card ->
                Ui.note(card, readingLabel(value.category, value.claim.text) + value.topics.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = " · ").orEmpty())
                if (!view.isCurrentRevision) Ui.note(card, "正在查看历史版本。")
                if (view.evidenceState != KnowledgeEvidenceState.CURRENT) Ui.note(card, "这条内容需要重新分析或核对，暂不作为当前结论。")
                Ui.body(card, value.claim.text)
                chipsRow(card).also { row ->
                    chip(row, evidenceStateLabel(view.evidenceState), evidenceStateTone(view.evidenceState))
                    chip(row, kindLabel(value.claim.kind), Ui.Tone.NEUTRAL)
                }
                Ui.note(card, "涉及 ${value.scope.from} — ${value.scope.through} 的记录")
                value.topics.forEach { topic ->
                    addButton(card, "回到「$topic」主题", "knowledgeDetailTopic:$topic") {
                        filterTopic = topic
                        shelf = KnowledgeShelf.ALL
                        showIndex()
                    }
                }
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
                    addButton(source, "打开分析时的记录", "openEvidenceRecord:${receipt.recordId}") {
                        startActivity(Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_RECORD_ID, receipt.recordId)
                            .putExtra(MainActivity.EXTRA_RECORD_REVISION, receipt.revision))
                    }
                    Ui.disclosure(source, "引用位置", "knowledgeReceipt:${receipt.id}") { identity ->
                        Ui.note(identity, "记录 ${receipt.recordId}\n内容 ${receipt.field}\n引用 ${receipt.id}")
                    }
                }
            }
            val continuity = card()
            Ui.sectionTitle(continuity, "这条经验的变化")
            Ui.note(continuity, "把不同时间留下的理解连接起来，保留补充、例外和改变想法的过程。")
            threads.items.forEach { item ->
                listRow(continuity, "${item.thread.title} · ${item.thread.version} 次积累", "experience:${item.thread.id}") {
                    startActivity(Intent(this, ExperienceActivity::class.java).putExtra(ExperienceActivity.EXTRA_THREAD_ID, item.thread.id))
                }
            }
            if (threads.nextOffset != null) addButton(continuity, "全部相关经验", "allRelatedExperiences") {
                startActivity(Intent(this, ExperienceActivity::class.java).putExtra(ExperienceActivity.EXTRA_RELATED_KNOWLEDGE_ID, id))
            }
            if (view.isCurrentRevision && view.evidenceState == KnowledgeEvidenceState.CURRENT) {
                addButton(continuity, "建立或加入经验线索", "connectExperience") {
                    startActivity(Intent(this, ExperienceActivity::class.java)
                        .putExtra(ExperienceActivity.EXTRA_KNOWLEDGE_ID, id)
                        .putExtra(ExperienceActivity.EXTRA_KNOWLEDGE_REVISION, value.revision))
                }
            } else Ui.note(continuity, "待更新内容仍在形成过程中保留；新关联须选择当前有效的知识。")
            val followUp = card()
            renderRelatedActions(followUp, id, relatedActions)
            if (view.isCurrentRevision && view.evidenceState == KnowledgeEvidenceState.CURRENT) {
                primaryIn(followUp, "据此尝试一个行动", "actionFromKnowledge") {
                    startActivity(Intent(this, ActionsActivity::class.java)
                        .putExtra(ActionsActivity.EXTRA_ORIGIN_ID, id)
                        .putExtra(ActionsActivity.EXTRA_ORIGIN_REVISION, value.revision))
                }
            } else Ui.note(followUp, "已关联的行动和回看会继续保留；重新核对这条知识后，可以据此发起新的尝试。")
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

    private fun shelfLabel(shelf: KnowledgeShelf) = when (shelf) {
        KnowledgeShelf.FINDINGS -> "发现"
        KnowledgeShelf.EXPERIENCE -> "经验与知识"
        KnowledgeShelf.OBSERVATIONS -> "记录观察"
        KnowledgeShelf.ALL -> "全部条目"
    }

    private fun readingLabel(category: KnowledgeCategory, text: String): String =
        if (category == KnowledgeCategory.INSIGHT && text.startsWith("值得留下：")) "值得留下的经验" else categoryLabel(category)

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
        const val EXTRA_KNOWLEDGE_ID = "knowledgeId"
        const val EXTRA_KNOWLEDGE_REVISION = "knowledgeRevision"
        private const val PAGE_SIZE = 25
    }

}
