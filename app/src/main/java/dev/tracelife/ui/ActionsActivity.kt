package dev.tracelife.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import dev.tracelife.actions.ActionDecision
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExecution
import dev.tracelife.actions.ActionFilter
import dev.tracelife.actions.ActionOrigin
import dev.tracelife.actions.ActionOriginState
import dev.tracelife.actions.ActionPage
import dev.tracelife.actions.ActionReview
import dev.tracelife.actions.ActionStatus
import dev.tracelife.actions.ActionView
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.transfer.StaleSnapshotException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ActionsActivity : ScreenActivity() {
    private var filterIndex = 0
    private val pageStack = ArrayDeque<ActionPage>()
    private var actionId: String? = null
    private var current: ActionView? = null
    private var createId: String? = null
    private var reviewOperationId: String? = null
    private var stopOperationId: String? = null
    private var historyOffset = 0
    private var createOrigin: ActionOrigin? = null
    private var restoreCreate = false
    private var restoreList = false
    private var createValues = Bundle()
    private val createFields = linkedMapOf<String, EditText>()

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        restoreList = savedInstanceState?.getString("screen") == "list"
        actionId = savedInstanceState?.getString("actionId") ?: intent.getStringExtra(EXTRA_ACTION_ID)
        createId = savedInstanceState?.getString("createId")
        createValues = savedInstanceState?.getBundle("createValues") ?: Bundle()
        restoreCreate = savedInstanceState?.getBoolean("creating") ?: intent.hasExtra(EXTRA_ORIGIN_ID)
        val originId = if (savedInstanceState != null) savedInstanceState.getString("originId") else intent.getStringExtra(EXTRA_ORIGIN_ID)
        val originRevision = savedInstanceState?.getInt("originRevision") ?: intent.getIntExtra(EXTRA_ORIGIN_REVISION, -1)
        createOrigin = originId?.let { ActionOrigin(it, originRevision) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen)
        outState.putString("actionId", actionId)
        outState.putString("createId", createId)
        outState.putBoolean("creating", screen == "create")
        outState.putString("originId", createOrigin?.knowledgeId)
        outState.putInt("originRevision", createOrigin?.revision ?: -1)
        if (screen == "create") createFields.forEach { (key, field) -> createValues.putString(key, field.text.toString()) }
        outState.putBundle("createValues", createValues)
        super.onSaveInstanceState(outState)
    }

    override fun showHome() {
        if (restoreCreate) showCreate(createOrigin)
        else if (restoreList) showList()
        else actionId?.let(::showDetail) ?: showList()
    }

    override fun navigateBack() {
        if (busy) return
        when (screen) {
            "create", "detail" -> showList()
            "edit", "review", "stop", "history" -> actionId?.let(::showDetail) ?: showList()
            else -> finish()
        }
    }

    private fun currentFilter() = when (filterIndex) {
        1 -> ActionFilter(status = ActionStatus.OPEN)
        2 -> ActionFilter(dueOnly = true)
        else -> ActionFilter()
    }

    private fun showList() {
        beginScreen("list", "行动", showBack = false)
        note("从一件小事开始，下次回来看看结果。")
        primaryButton("新建行动", "newAction") {
            createId = null
            createValues = Bundle()
            showCreate()
        }
        Ui.disclosure(root, "筛选行动", "actionFilters") { filters ->
            spinnerIn(filters, listOf("全部", "进行中", "待回看"), filterIndex, "filter").also { filterSpinner ->
                filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position != filterIndex) { filterIndex = position; loadFirstPage() }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            }
        }
        loadFirstPage()
    }

    private fun loadFirstPage() {
        runIo({ app.actions.list(currentFilter()) }) { page ->
            pageStack.clear()
            pageStack.addLast(page)
            renderPage(page)
        }
    }

    private fun goNext(page: ActionPage) {
        runIo({
            try { app.actions.next(page) to false }
            catch (_: StaleSnapshotException) { app.actions.list(page.filter, page.pageSize) to true }
        }) { (next, fresh) ->
            if (fresh) {
                pageStack.clear()
                toast("行动有更新，已刷新列表")
            }
            pageStack.addLast(next)
            renderPage(next)
        }
    }

    private fun goPrevious() {
        if (pageStack.size > 1) pageStack.removeLast()
        pageStack.lastOrNull()?.let(::renderPage)
    }

    private fun renderPage(page: ActionPage) {
        root.findViewWithTag<View>("actionResults")?.let(root::removeView)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "actionResults" }
        root.addView(results)
        Ui.note(results, "共 ${page.total} 项")
        if (page.items.isEmpty()) emptyState(Ui.card(results), "这里还没有行动。\n选一件想尝试的小事，给它安排一次回看。")
        page.items.forEach { item ->
            val state = if (item.due) "待回看" else statusLabel(item.status)
            listRow(results, "${item.title}\n$state · ${item.reviewOn} 回看", "action:${item.id}") {
                showDetail(item.id)
            }
        }
        if (pageStack.size > 1) addButton(results, "上一页", "previousPage") { goPrevious() }
        if (page.nextOffset != null) addButton(results, "下一页", "nextPage") { goNext(page) }
    }

    private fun showCreate(origin: ActionOrigin? = null) {
        createOrigin = origin
        if (createId == null) createId = UUID.randomUUID().toString()
        beginScreen("create", "新建行动")
        if (origin != null) {
            runIo({
                val view = requireNotNull(app.knowledge.get(origin.knowledgeId, origin.revision)) { "原知识已删除，不能继续关联创建行动" }
                require(view.isCurrentRevision && view.evidenceState == KnowledgeEvidenceState.CURRENT) { "这条知识需要更新，请先重新核对，再据此发起行动" }
                view.value.claim.text
            }) { text ->
                val reference = card()
                Ui.sectionTitle(reference, "这次尝试从这里开始")
                Ui.body(reference, text)
                Ui.note(reference, "这段发现作为来源保留。下面由你决定具体做法，保存后才表示采纳。")
                renderCreateForm()
            }
        } else renderCreateForm()
    }

    private fun renderCreateForm() {
        val form = card()
        Ui.sectionTitle(form, "计划")
        Ui.note(form, "写下想尝试的做法，以及准备如何观察结果。")
        val title = labeledFieldIn(form, "想尝试什么", maxLength = 200)
        val trigger = labeledFieldIn(form, "什么时候做", multiline = true, maxLength = 2_000)
        val behavior = labeledFieldIn(form, "准备怎么做", multiline = true, maxLength = 4_000)
        val signal = labeledFieldIn(form, "怎样看出效果", multiline = true, maxLength = 2_000)
        val reviewOn = labeledFieldIn(form, "回看日期", "YYYY-MM-DD")
        lateinit var zone: EditText
        Ui.disclosure(form, "时区设置", "actionTimeZone") { settings ->
            zone = labeledFieldIn(settings, "时区", value = ZoneId.systemDefault().id)
        }
        createFields.clear()
        createFields.putAll(mapOf("title" to title, "trigger" to trigger, "behavior" to behavior,
            "signal" to signal, "reviewOn" to reviewOn, "zone" to zone))
        createFields.forEach { (key, field) -> createValues.getString(key)?.let(field::setText) }
        primaryIn(form, "保存行动", "saveAction") {
            val draft = try { draftFrom(title, trigger, behavior, signal, reviewOn, zone, createOrigin) }
            catch (failure: IllegalArgumentException) { showError(failure); return@primaryIn }
            runIo({ app.actions.create(draft, requireNotNull(createId)) }) { view ->
                toast("行动已保存")
                createId = null
                createOrigin = null
                createValues = Bundle()
                showDetail(view.value.id)
            }
        }
    }

    private fun showDetail(id: String) {
        actionId = id
        beginScreen("detail", "行动详情")
        runIo({ requireNotNull(app.actions.get(id)) { "行动不存在或已删除" } }) { view ->
            current = view
            val value = view.value
            val draft = value.draft
            val plan = card()
            Ui.cardTitle(plan, draft.title)
            val statusChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            plan.addView(statusChips)
            chip(statusChips, statusLabel(value.status), if (value.status == ActionStatus.OPEN) Ui.Tone.ACCENT else Ui.Tone.NEUTRAL)
            if (value.status == ActionStatus.OPEN && view.due) chip(statusChips, "待回看", Ui.Tone.WARN)
            Ui.fieldLabel(plan, "什么时候做")
            Ui.body(plan, draft.trigger)
            Ui.fieldLabel(plan, "准备怎么做")
            Ui.body(plan, draft.behavior)
            Ui.fieldLabel(plan, "怎样看出效果")
            Ui.body(plan, draft.expectedSignal)
            Ui.body(plan, "回看日期：${draft.reviewOn}")
            val reviewCard = card()
            Ui.sectionTitle(reviewCard, "这份计划的回看")
            val review = value.review
            if (review == null) {
                emptyState(reviewCard, "这份计划还没有回看记录。过往版本可在历史中查看。")
            } else {
                Ui.body(reviewCard, "回看日期：${review.reviewedOn} · 决定：${decisionLabel(review.decision)}")
                Ui.body(reviewCard, "执行情况：${executionLabel(review.execution)}")
                Ui.body(reviewCard, "观察结果：${review.observedText}")
            }
            if (value.status == ActionStatus.OPEN) {
                primaryButton("记录这次回看", "reviewAction") { showReview() }
                button("调整计划", "editAction") { showEdit() }
            }
            button("查看历史", "actionHistory") { historyOffset = 0; showHistory() }
            Ui.disclosure(root, "来源与版本", "actionMetadata") { metadata ->
                chip(metadata, originLabel(view.originState), when (view.originState) {
                    ActionOriginState.CURRENT -> Ui.Tone.OK
                    ActionOriginState.STALE -> Ui.Tone.WARN
                    else -> Ui.Tone.NEUTRAL
                })
                if (view.originState == ActionOriginState.STALE || view.originState == ActionOriginState.UNKNOWN)
                    Ui.note(metadata, "来源变化不影响你写下的计划与回看。")
                draft.origin?.let { origin ->
                    Ui.note(metadata, "采用时的知识 · 第 ${origin.revision} 版")
                    addButton(metadata, "查看这次尝试的起点", "actionOriginKnowledge") {
                        startActivity(Intent(this, KnowledgeActivity::class.java)
                            .putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID, origin.knowledgeId)
                            .putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_REVISION, origin.revision))
                    }
                }
                Ui.note(metadata, "第 ${value.revision} 版 · 时区 ${draft.zoneId}")
                Ui.note(metadata, "创建于 ${value.createdAt}\n更新于 ${value.updatedAt}")
            }
            Ui.disclosure(root, "管理行动", "actionManagement") { management ->
                if (value.status == ActionStatus.OPEN) {
                    dangerIn(management, "停止行动", "stopAction") { showStop() }
                }
                dangerIn(management, "永久删除", "deleteAction") {
                    confirm("永久删除这个行动？", "计划及全部历史版本、回看结果都会被删除，无法撤销。", "永久删除") {
                        runIo({ app.actions.delete(id, value.revision) }) {
                            toast("行动已删除")
                            actionId = null
                            current = null
                            showList()
                        }
                    }
                }
            }
        }
    }

    private fun showEdit() {
        val view = current ?: return showList()
        val draft = view.value.draft
        beginScreen("edit", "编辑计划")
        val form = card()
        Ui.sectionTitle(form, "计划")
        Ui.note(form, "旧计划会保留在历史中。")
        val title = labeledFieldIn(form, "想尝试什么", value = draft.title, maxLength = 200)
        val trigger = labeledFieldIn(form, "什么时候做", value = draft.trigger, multiline = true, maxLength = 2_000)
        val behavior = labeledFieldIn(form, "准备怎么做", value = draft.behavior, multiline = true, maxLength = 4_000)
        val signal = labeledFieldIn(form, "怎样看出效果", value = draft.expectedSignal, multiline = true, maxLength = 2_000)
        val reviewOn = labeledFieldIn(form, "回看日期", "YYYY-MM-DD", draft.reviewOn.toString())
        lateinit var zone: EditText
        Ui.disclosure(form, "时区设置", "actionTimeZone") { settings ->
            zone = labeledFieldIn(settings, "时区", value = draft.zoneId)
        }
        primaryIn(form, "保存修改", "saveEdit") {
            val updated = try { draftFrom(title, trigger, behavior, signal, reviewOn, zone, draft.origin) }
            catch (failure: IllegalArgumentException) { showError(failure); return@primaryIn }
            runIo({ app.actions.update(view.value.id, view.value.revision, updated) }) { saved ->
                toast("计划已保存")
                showDetail(saved.value.id)
            }
        }
    }

    private fun showReview() {
        val view = current ?: return showList()
        reviewOperationId = UUID.randomUUID().toString()
        val draft = view.value.draft
        beginScreen("review", "记录回看")
        val reviewCard = card()
        Ui.sectionTitle(reviewCard, "回看")
        Ui.note(reviewCard, "发生了什么，有哪些感受？暂时无法判断也可以如实写下。")
        Ui.fieldLabel(reviewCard, "执行情况")
        val executionSpinner = spinnerIn(reviewCard, listOf("还无法确认", "未执行", "已执行"), 0, "execution")
        val observed = labeledFieldIn(reviewCard, "这次观察到什么", multiline = true, maxLength = 4_000)
        val decisionCard = card()
        Ui.sectionTitle(decisionCard, "决定")
        val decisionSpinner = spinnerIn(decisionCard, listOf("继续原做法", "调整做法", "暂缓（延期回看）", "停止行动"), 0, "decision")
        val nextDate = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        decisionCard.addView(nextDate)
        val nextReviewOn = labeledFieldIn(nextDate, "下一次回看日期", "YYYY-MM-DD")
        Ui.note(nextDate, "选择今天之后的日期。")
        val nextCard = card()
        Ui.sectionTitle(nextCard, "下一轮计划")
        Ui.note(nextCard, "在原计划上修改想调整的部分。")
        val nextTitle = labeledFieldIn(nextCard, "标题", value = draft.title, maxLength = 200)
        val nextTrigger = labeledFieldIn(nextCard, "触发情境", value = draft.trigger, multiline = true, maxLength = 2_000)
        val nextBehavior = labeledFieldIn(nextCard, "具体做法", value = draft.behavior, multiline = true, maxLength = 4_000)
        val nextSignal = labeledFieldIn(nextCard, "期望信号", value = draft.expectedSignal, multiline = true, maxLength = 2_000)
        nextCard.visibility = View.GONE
        decisionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                nextCard.visibility = if (position == 1) View.VISIBLE else View.GONE
                nextDate.visibility = if (position == 3) View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        primaryIn(root, "保存回看", "saveReview") {
            val execution = ActionExecution.entries[executionSpinner.selectedItemPosition]
            val decision = when (decisionSpinner.selectedItemPosition) {
                1 -> ActionDecision.ADJUST
                2 -> ActionDecision.DEFER
                3 -> ActionDecision.STOP
                else -> ActionDecision.CONTINUE
            }
            val observedText = observed.text.toString()
            if (observedText.isBlank()) { showError(IllegalArgumentException("请填写观察结果")); return@primaryIn }
            val today = LocalDate.now(ZoneId.of(draft.zoneId))
            val work: () -> ActionView = when (decision) {
                ActionDecision.STOP -> {
                    { app.actions.stop(view.value.id, view.value.revision, requireNotNull(reviewOperationId), observedText, execution) }
                }
                ActionDecision.ADJUST -> {
                    val adjusted = try {
                        ActionDraft(
                            title = nextTitle.text.toString().trim(),
                            trigger = nextTrigger.text.toString().trim(),
                            behavior = nextBehavior.text.toString().trim(),
                            expectedSignal = nextSignal.text.toString().trim(),
                            reviewOn = parseDate(nextReviewOn.text.toString(), "下一次回看日期"),
                            zoneId = draft.zoneId,
                            origin = draft.origin,
                        )
                    } catch (failure: IllegalArgumentException) { showError(failure); return@primaryIn }
                    if (!adjusted.reviewOn.isAfter(today)) { showError(IllegalArgumentException("下一次回看日期须在当地今日（$today）之后")); return@primaryIn }
                    {
                        app.actions.review(view.value.id, view.value.revision, requireNotNull(reviewOperationId),
                            ActionReview(execution, observedText, decision, today), adjustedDraft = adjusted)
                    }
                }
                else -> {
                    val next = try { parseDate(nextReviewOn.text.toString(), "下一次回看日期") }
                    catch (failure: IllegalArgumentException) { showError(failure); return@primaryIn }
                    if (!next.isAfter(today)) { showError(IllegalArgumentException("下一次回看日期须在当地今日（$today）之后")); return@primaryIn }
                    {
                        app.actions.review(view.value.id, view.value.revision, requireNotNull(reviewOperationId),
                            ActionReview(execution, observedText, decision, today), nextReviewOn = next)
                    }
                }
            }
            runIo(work) { saved ->
                toast("回看已保存")
                reviewOperationId = null
                showDetail(saved.value.id)
            }
        }
    }

    private fun showStop() {
        val view = current ?: return showList()
        stopOperationId = UUID.randomUUID().toString()
        beginScreen("stop", "停止行动")
        val form = card()
        Ui.sectionTitle(form, "停止")
        Ui.note(form, "停止后不再列为到期行动，不能恢复为进行中；计划、历史与本次结果保留。")
        Ui.fieldLabel(form, "执行情况")
        val executionSpinner = spinnerIn(form, listOf("还无法确认", "未执行", "已执行"), 0, "execution")
        val observed = labeledFieldIn(form, "这次观察到什么", multiline = true, maxLength = 4_000)
        dangerIn(form, "确认停止", "confirmStop") {
            val observedText = observed.text.toString()
            if (observedText.isBlank()) { showError(IllegalArgumentException("请填写观察结果")); return@dangerIn }
            val execution = ActionExecution.entries[executionSpinner.selectedItemPosition]
            confirm("停止这个行动？", "停止后不再列为到期行动，且不能恢复为进行中；历史与回看保留。", "停止行动") {
                runIo({
                    app.actions.stop(view.value.id, view.value.revision, requireNotNull(stopOperationId), observedText, execution)
                }) { saved ->
                    toast("行动已停止")
                    stopOperationId = null
                    showDetail(saved.value.id)
                }
            }
        }
    }

    private fun showHistory() {
        val id = actionId ?: return showList()
        beginScreen("history", "行动历史")
        runIo({ app.actions.history(id, historyOffset, PAGE_SIZE) }) { items ->
            if (items.isEmpty()) {
                val empty = card()
                emptyState(empty, "没有更多版本了。")
            }
            items.forEach { view ->
                val value = view.value
                val entry = card()
                Ui.cardTitle(entry, "第 ${value.revision} 版")
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                entry.addView(chips)
                chip(chips, statusLabel(value.status), if (value.status == ActionStatus.OPEN) Ui.Tone.ACCENT else Ui.Tone.NEUTRAL)
                if (view.isCurrentRevision) chip(chips, "当前版本", Ui.Tone.OK)
                Ui.note(entry, "更新于 ${value.updatedAt}")
                Ui.body(entry, "计划：${value.draft.title} · 回看日 ${value.draft.reviewOn}")
                value.review?.let { review ->
                    Ui.body(entry, "回看：${review.reviewedOn} · ${decisionLabel(review.decision)} · ${executionLabel(review.execution)}")
                    Ui.body(entry, "观察结果：${review.observedText}")
                }
                value.reviewedDraft?.let { previous ->
                    Ui.body(entry, "本次回看针对的旧计划：")
                    Ui.body(entry, "标题：${previous.title}\n触发情境：${previous.trigger}\n具体做法：${previous.behavior}\n期望信号：${previous.expectedSignal}\n回看日期：${previous.reviewOn}")
                }
            }
            if (historyOffset > 0) button("上一页", "historyPrevious") {
                historyOffset = (historyOffset - PAGE_SIZE).coerceAtLeast(0)
                showHistory()
            }
            if (items.size == PAGE_SIZE) button("下一页", "historyNext") {
                historyOffset += PAGE_SIZE
                showHistory()
            }
        }
    }

    private fun draftFrom(
        title: EditText, trigger: EditText, behavior: EditText, signal: EditText,
        reviewOn: EditText, zone: EditText, origin: ActionOrigin?,
    ): ActionDraft {
        val zoneId = zone.text.toString().trim()
        try { ZoneId.of(zoneId) } catch (_: java.time.DateTimeException) { throw IllegalArgumentException("时区无效，请使用例如 Asia/Shanghai 的标识") }
        val draft = ActionDraft(
            title = title.text.toString().trim(),
            trigger = trigger.text.toString().trim(),
            behavior = behavior.text.toString().trim(),
            expectedSignal = signal.text.toString().trim(),
            reviewOn = parseDate(reviewOn.text.toString(), "回看日期"),
            zoneId = zoneId,
            origin = origin,
        )
        require(draft.title.isNotBlank()) { "请填写标题" }
        require(draft.trigger.isNotBlank()) { "请填写触发情境" }
        require(draft.behavior.isNotBlank()) { "请填写具体做法" }
        require(draft.expectedSignal.isNotBlank()) { "请填写期望信号" }
        return draft
    }

    private fun statusLabel(status: ActionStatus) = when (status) {
        ActionStatus.OPEN -> "进行中"
        ActionStatus.STOPPED -> "已停止"
    }
    private fun originLabel(state: ActionOriginState) = when (state) {
        ActionOriginState.NOT_LINKED -> "未关联知识"
        ActionOriginState.CURRENT -> "来源知识有效"
        ActionOriginState.STALE -> "来源已失效"
        ActionOriginState.UNKNOWN -> "来源状态未知"
    }
    private fun executionLabel(execution: ActionExecution) = when (execution) {
        ActionExecution.EXECUTED -> "已执行"
        ActionExecution.NOT_EXECUTED -> "未执行"
        ActionExecution.UNKNOWN -> "无法确认执行情况"
    }
    private fun decisionLabel(decision: ActionDecision) = when (decision) {
        ActionDecision.CONTINUE -> "继续原做法"
        ActionDecision.ADJUST -> "调整做法"
        ActionDecision.STOP -> "停止行动"
        ActionDecision.DEFER -> "暂缓（延期回看）"
    }

    companion object {
        const val EXTRA_ACTION_ID = "actionId"
        const val EXTRA_ORIGIN_ID = "originKnowledgeId"
        const val EXTRA_ORIGIN_REVISION = "originKnowledgeRevision"
        private const val PAGE_SIZE = 25
    }
}
