package dev.tracelife.ui

import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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

    override fun showHome() = showList()

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
        beginScreen("list", "行动实验", showBack = false)
        val filterCard = card()
        Ui.sectionTitle(filterCard, "筛选")
        Ui.note(filterCard, "行动是你明确选择的尝试，不代表原建议正确或已经执行。到期判断绑定列表生成时的评估时间，跨日后请重新打开本页查看最新到期情况。")
        spinnerIn(filterCard, listOf("全部", "进行中", "仅到期"), filterIndex, "filter").also { filterSpinner ->
            filterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != filterIndex) { filterIndex = position; loadFirstPage() }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        primaryButton("新建行动", "newAction") { showCreate() }
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
                toast("行动列表已变化，已从首页重新读取")
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
        Ui.note(results, "共 ${page.total} 项 · 评估时间 ${page.evaluatedAt}")
        if (page.items.isEmpty()) Ui.note(results, "当前筛选下没有行动。")
        page.items.forEach { item ->
            listRow(results, "${item.title}\n回看日 ${item.reviewOn} · ${originLabel(item.originState)}", "action:${item.id}") {
                showDetail(item.id)
            }
            val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            results.addView(chips)
            chip(chips, statusLabel(item.status), if (item.status == ActionStatus.OPEN) Ui.Tone.ACCENT else Ui.Tone.NEUTRAL)
            if (item.due) chip(chips, "已到期", Ui.Tone.WARN)
        }
        if (pageStack.size > 1) addButton(results, "上一页", "previousPage") { goPrevious() }
        if (page.nextOffset != null) addButton(results, "下一页", "nextPage") { goNext(page) }
    }

    private fun showCreate() {
        createId = UUID.randomUUID().toString()
        beginScreen("create", "新建行动")
        val form = card()
        Ui.sectionTitle(form, "计划")
        Ui.note(form, "标题、触发情境、具体做法和期望信号都由你明确填写。")
        val title = fieldIn(form, "标题（必填，200 字内）", maxLength = 200)
        val trigger = fieldIn(form, "触发情境：什么时候做（必填）", multiline = true, maxLength = 2_000)
        val behavior = fieldIn(form, "具体做法（必填）", multiline = true, maxLength = 4_000)
        val signal = fieldIn(form, "期望信号：观察什么来判断（必填）", multiline = true, maxLength = 2_000)
        val reviewOn = fieldIn(form, "回看日期（YYYY-MM-DD）")
        val zone = fieldIn(form, "时区", ZoneId.systemDefault().id)
        primaryIn(form, "保存行动", "saveAction") {
            val draft = try { draftFrom(title, trigger, behavior, signal, reviewOn, zone, null) }
            catch (failure: IllegalArgumentException) { showError(failure); return@primaryIn }
            runIo({ app.actions.create(draft, requireNotNull(createId)) }) { view ->
                toast("行动已保存")
                createId = null
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
            Ui.sectionTitle(plan, "计划")
            val statusChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            plan.addView(statusChips)
            chip(statusChips, statusLabel(value.status), if (value.status == ActionStatus.OPEN) Ui.Tone.ACCENT else Ui.Tone.NEUTRAL)
            chip(statusChips, if (view.due) "已到期" else "未到回看日期", if (view.due) Ui.Tone.WARN else Ui.Tone.NEUTRAL)
            Ui.note(plan, "评估时间 ${view.evaluatedAt} · 修订 ${value.revision}")
            Ui.note(plan, "创建：${value.createdAt}\n更新：${value.updatedAt}\n时区：${draft.zoneId}")
            Ui.sectionTitle(plan, "标题")
            Ui.body(plan, draft.title)
            Ui.sectionTitle(plan, "触发情境")
            Ui.body(plan, draft.trigger)
            Ui.sectionTitle(plan, "具体做法")
            Ui.body(plan, draft.behavior)
            Ui.sectionTitle(plan, "期望信号")
            Ui.body(plan, draft.expectedSignal)
            Ui.body(plan, "回看日期：${draft.reviewOn}")
            val reviewCard = card()
            Ui.sectionTitle(reviewCard, "最近回看")
            val review = value.review
            if (review == null) {
                Ui.note(reviewCard, "尚未提交回看。")
            } else {
                Ui.body(reviewCard, "回看日期：${review.reviewedOn} · 决定：${decisionLabel(review.decision)}")
                Ui.body(reviewCard, "执行情况：${executionLabel(review.execution)}${if (review.execution == ActionExecution.UNKNOWN) "（本次回看说明无法判断执行情况）" else ""}")
                Ui.body(reviewCard, "观察结果：${review.observedText}")
            }
            val originCard = card()
            Ui.sectionTitle(originCard, "来源")
            val originChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            originCard.addView(originChips)
            chip(originChips, originLabel(view.originState), when (view.originState) {
                ActionOriginState.CURRENT -> Ui.Tone.OK
                ActionOriginState.STALE -> Ui.Tone.WARN
                else -> Ui.Tone.NEUTRAL
            })
            if (view.originState == ActionOriginState.STALE || view.originState == ActionOriginState.UNKNOWN)
                Ui.note(originCard, "来源状态只影响标注；行动文字与回看结果保留。")
            draft.origin?.let { Ui.body(originCard, "来源知识：${it.knowledgeId} · 修订 ${it.revision}") }
            val actions = card()
            Ui.sectionTitle(actions, "操作")
            if (value.status == ActionStatus.OPEN) {
                primaryIn(actions, "提交回看", "reviewAction") { showReview() }
                addButton(actions, "编辑计划", "editAction") { showEdit() }
            }
            addButton(actions, "查看历史", "actionHistory") { historyOffset = 0; showHistory() }
            Ui.divider(actions)
            if (value.status == ActionStatus.OPEN) {
                dangerIn(actions, "停止行动", "stopAction") { showStop() }
            }
            dangerIn(actions, "删除行动", "deleteAction") {
                confirm("永久删除这个行动？", "整个历史（计划、全部修订与回看）都会被永久删除并登记删除标记，此操作不能撤销。", "永久删除") {
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

    private fun showEdit() {
        val view = current ?: return showList()
        val draft = view.value.draft
        beginScreen("edit", "编辑计划")
        val form = card()
        Ui.sectionTitle(form, "计划")
        Ui.note(form, "修改计划会保存为新修订，旧计划保留在历史中。")
        val title = fieldIn(form, "标题（必填，200 字内）", draft.title, maxLength = 200)
        val trigger = fieldIn(form, "触发情境：什么时候做（必填）", draft.trigger, multiline = true, maxLength = 2_000)
        val behavior = fieldIn(form, "具体做法（必填）", draft.behavior, multiline = true, maxLength = 4_000)
        val signal = fieldIn(form, "期望信号：观察什么来判断（必填）", draft.expectedSignal, multiline = true, maxLength = 2_000)
        val reviewOn = fieldIn(form, "回看日期（YYYY-MM-DD）", draft.reviewOn.toString())
        val zone = fieldIn(form, "时区", draft.zoneId)
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
        beginScreen("review", "提交回看")
        val reviewCard = card()
        Ui.sectionTitle(reviewCard, "回看")
        Ui.note(reviewCard, "回看针对当前这份计划保存；观察结果必须明确填写，可以表达无法判断。执行或停止与否不等于计划对错。")
        Ui.sectionTitle(reviewCard, "执行情况")
        val executionSpinner = spinnerIn(reviewCard, listOf("执行情况未知（无法判断）", "未执行", "已执行"), 0, "execution")
        val observed = fieldIn(reviewCard, "观察结果（必填）", multiline = true, maxLength = 4_000)
        val decisionCard = card()
        Ui.sectionTitle(decisionCard, "决定")
        val decisionSpinner = spinnerIn(decisionCard, listOf("继续原做法", "调整做法", "暂缓（延期回看）", "停止行动"), 0, "decision")
        val nextReviewOn = fieldIn(decisionCard, "下一次回看日期（继续／调整／暂缓必填，YYYY-MM-DD，须在当地今日之后）")
        val nextCard = card()
        Ui.sectionTitle(nextCard, "下一轮计划")
        Ui.note(nextCard, "仅选择「调整做法」时生效，已预填当前计划")
        val nextTitle = fieldIn(nextCard, "标题", draft.title, maxLength = 200)
        val nextTrigger = fieldIn(nextCard, "触发情境", draft.trigger, multiline = true, maxLength = 2_000)
        val nextBehavior = fieldIn(nextCard, "具体做法", draft.behavior, multiline = true, maxLength = 4_000)
        val nextSignal = fieldIn(nextCard, "期望信号", draft.expectedSignal, multiline = true, maxLength = 2_000)
        primaryButton("保存回看", "saveReview") {
            val execution = ActionExecution.entries[executionSpinner.selectedItemPosition]
            val decision = when (decisionSpinner.selectedItemPosition) {
                1 -> ActionDecision.ADJUST
                2 -> ActionDecision.DEFER
                3 -> ActionDecision.STOP
                else -> ActionDecision.CONTINUE
            }
            val observedText = observed.text.toString()
            if (observedText.isBlank()) { showError(IllegalArgumentException("请填写观察结果")); return@primaryButton }
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
                    } catch (failure: IllegalArgumentException) { showError(failure); return@primaryButton }
                    if (!adjusted.reviewOn.isAfter(today)) { showError(IllegalArgumentException("下一次回看日期须在当地今日（$today）之后")); return@primaryButton }
                    {
                        app.actions.review(view.value.id, view.value.revision, requireNotNull(reviewOperationId),
                            ActionReview(execution, observedText, decision, today), adjustedDraft = adjusted)
                    }
                }
                else -> {
                    val next = try { parseDate(nextReviewOn.text.toString(), "下一次回看日期") }
                    catch (failure: IllegalArgumentException) { showError(failure); return@primaryButton }
                    if (!next.isAfter(today)) { showError(IllegalArgumentException("下一次回看日期须在当地今日（$today）之后")); return@primaryButton }
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
        Ui.sectionTitle(form, "执行情况")
        val executionSpinner = spinnerIn(form, listOf("执行情况未知（无法判断）", "未执行", "已执行"), 0, "execution")
        val observed = fieldIn(form, "观察结果（必填）", multiline = true, maxLength = 4_000)
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
            if (items.isEmpty()) note("没有更多修订。")
            items.forEach { view ->
                val value = view.value
                val entry = card()
                Ui.sectionTitle(entry, "修订 ${value.revision}")
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
    private fun spinnerIn(parent: LinearLayout, items: List<String>, selected: Int, viewTag: String) = Spinner(this).apply {
        tag = viewTag
        adapter = ArrayAdapter(this@ActionsActivity, android.R.layout.simple_spinner_item, items).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(selected)
        Ui.styleSpinner(this@ActionsActivity, this)
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
    }
    private fun primaryIn(parent: LinearLayout, label: String, tag: String, action: () -> Unit) {
        Ui.primary(parent, label, tag) { if (!busy) action() }
    }
    private fun dangerIn(parent: LinearLayout, label: String, tag: String, action: () -> Unit) {
        Ui.dangerButton(parent, label, tag) { if (!busy) action() }
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
        ActionExecution.UNKNOWN -> "执行情况未知"
    }
    private fun decisionLabel(decision: ActionDecision) = when (decision) {
        ActionDecision.CONTINUE -> "继续原做法"
        ActionDecision.ADJUST -> "调整做法"
        ActionDecision.STOP -> "停止行动"
        ActionDecision.DEFER -> "暂缓（延期回看）"
    }

    companion object {
        private const val PAGE_SIZE = 25
    }
}
