package dev.tracelife.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tracelife.analysis.ReflectionRun
import dev.tracelife.analysis.ReflectionStatus
import dev.tracelife.conversation.ConversationPlan
import dev.tracelife.conversation.ConversationThread
import dev.tracelife.conversation.ConversationTurn
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Question drafts are local; opening, restoring and previewing never initiate a model request. */
class ConversationActivity : ScreenActivity() {
    private var threadId: String? = null
    private var parentTurnId: String? = null
    private var parentFindingIndex: Int? = null
    private var draft = ""
    private var restoredScreen = "home"
    private var historyOffset = 0
    private var turnOffset = -1
    private var focusTurnId: String? = null
    private val turnAnchors = linkedMapOf<String, View>()
    private var restoringTurnPosition = false
    private var preserveChosenFocus = false
    private var from = ""
    private var through = ""
    private var zoneId = ZoneId.systemDefault().id
    private var includeArchived = false
    private var recordIds: Set<String>? = null
    private var previewNeedsClarification = false
    private var questionField: EditText? = null
    private var fromField: EditText? = null
    private var throughField: EditText? = null
    private var resumedOnce = false

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            threadId = intent.getStringExtra(EXTRA_THREAD_ID)
            if (threadId != null) restoredScreen = "thread"
            return
        }
        threadId = savedInstanceState.getString("conversation.thread")
        parentTurnId = savedInstanceState.getString("conversation.parent")
        parentFindingIndex = savedInstanceState.getInt("conversation.finding", -1).takeIf { it >= 0 }
        draft = savedInstanceState.getString("conversation.draft", "")
        restoredScreen = savedInstanceState.getString("conversation.screen", "home")
        historyOffset = savedInstanceState.getInt("conversation.historyOffset")
        turnOffset = savedInstanceState.getInt("conversation.turnOffset", -1)
        focusTurnId = savedInstanceState.getString("conversation.focusTurn")
        from = savedInstanceState.getString("conversation.from", "")
        through = savedInstanceState.getString("conversation.through", "")
        zoneId = savedInstanceState.getString("conversation.zone", zoneId)
        includeArchived = savedInstanceState.getBoolean("conversation.archived")
        recordIds = savedInstanceState.getStringArrayList("conversation.records")?.toSet()
        previewNeedsClarification = savedInstanceState.getBoolean("conversation.needsClarification")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberFields()
        rememberVisibleTurn()
        outState.putString("conversation.thread", threadId)
        outState.putString("conversation.parent", parentTurnId)
        outState.putInt("conversation.finding", parentFindingIndex ?: -1)
        outState.putString("conversation.draft", draft)
        outState.putString("conversation.screen", screen)
        outState.putInt("conversation.historyOffset", historyOffset)
        outState.putInt("conversation.turnOffset", turnOffset)
        outState.putString("conversation.focusTurn", focusTurnId)
        outState.putString("conversation.from", from)
        outState.putString("conversation.through", through)
        outState.putString("conversation.zone", zoneId)
        outState.putBoolean("conversation.archived", includeArchived)
        recordIds?.let { outState.putStringArrayList("conversation.records", ArrayList(it)) }
        outState.putBoolean("conversation.needsClarification", previewNeedsClarification)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() { rememberFields(); rememberVisibleTurn(); super.onPause() }

    override fun onResume() {
        super.onResume()
        if (!resumedOnce) { resumedOnce = true; return }
        if (busy) return
        if (screen == "thread") { rememberFields(); openThread() }
    }

    override fun showHome() {
        when {
            restoredScreen == "preview" -> {
                mount("preview", "这次想了解")
                text(draft)
                fromField = labeledField("开始日期", "YYYY-MM-DD", from).apply { tag = "conversationFrom" }
                throughField = labeledField("结束日期", "YYYY-MM-DD", through).apply { tag = "conversationThrough" }
                button("更新日期范围", "conversationUpdateRange") {
                    rememberFields(); readScope()?.let { preparePreview(explicitScope = it) }
                }
                button("修改问题", "conversationEditQuestion") { if (threadId == null) showEntry() else openThread() }
                preparePreview(restored = true)
            }
            threadId != null -> openThread()
            else -> showEntry()
        }
    }

    override fun navigateBack() {
        if (busy) return
        rememberFields()
        when (screen) {
            "preview" -> if (threadId != null) openThread() else showEntry()
            "thread" -> { clearConversation(); showEntry() }
            else -> finish()
        }
    }

    private fun mount(name: String, title: String) {
        beginScreen(name, title)
        turnAnchors.clear()
        restoringTurnPosition = false
        questionField = null
        fromField = null
        throughField = null
    }

    private fun showEntry() {
        mount("home", "问一问")
        note("从一个具体问题开始，把这些日子的经历连起来。")
        composer(root, followup = false)
        Ui.disclosure(root, "可以这样问", "conversationExamples", expanded = true) { examples ->
            EXAMPLES.forEachIndexed { index, example ->
                listRow(examples, example, "conversationExample:$index") {
                    draft = example
                    questionField?.setText(example)
                }
            }
        }
        section("之前的问题")
        val history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        Ui.note(history, "正在读取…")
        runIo({
            val threads = app.conversations.history(offset = historyOffset, limit = PAGE_SIZE)
            threads.map { it to app.conversations.turns(it.id, limit = 1).firstOrNull() }
        }) { rows ->
            history.removeAllViews()
            if (rows.isEmpty()) emptyState(history, "还没有提问。先写下你想了解的事。")
            rows.forEach { (thread, firstTurn) -> historyRow(history, thread, firstTurn) }
            if (historyOffset > 0) addButton(history, "较新的问题", "conversationHistoryPrevious") {
                rememberFields(); historyOffset = maxOf(0, historyOffset - PAGE_SIZE); showEntry()
            }
            if (rows.size == PAGE_SIZE) addButton(history, "更早的问题", "conversationHistoryNext") {
                rememberFields(); historyOffset += PAGE_SIZE; showEntry()
            }
        }
    }

    private fun historyRow(parent: LinearLayout, thread: ConversationThread, turn: ConversationTurn?) {
        listRow(parent, "${turn?.question?.take(120) ?: "已保留的问题"}\n${formatTime(thread.updatedAt)}", "conversationThread:${thread.id}") {
            clearDraft()
            threadId = thread.id
            turnOffset = -1
            focusTurnId = null
            openThread()
        }
    }

    private fun composer(parent: LinearLayout, followup: Boolean) {
        val block = Ui.card(parent)
        parentFindingIndex?.let { Ui.note(block, "围绕第 ${it + 1} 条发现继续问") }
        if (parentTurnId != null) addButton(block, "取消指定发现", "conversationClearReference") {
            rememberFields(); parentTurnId = null; parentFindingIndex = null
            if (threadId == null) showEntry() else openThread()
        }
        questionField = labeledFieldIn(block, if (followup) "接着问" else "想了解什么", "例如：这三个月，我的学习方法有哪些变化？", draft,
            multiline = true, maxLength = 1_000).apply { tag = "conversationQuestion" }
        primaryIn(block, "看看分析范围", "previewConversation") { preparePreview() }
        if (followup) Ui.disclosure(block, "继续追问", "conversationFollowupIdeas") { ideas ->
            listOf("后来有没有改善？", "下周可以尝试什么？").forEachIndexed { index, question ->
                addButton(ideas, question, "conversationFollowupIdea:$index") { draft = question; questionField?.setText(question) }
            }
        }
        Ui.note(block, "先确认日期与关注点，再开始分析。回答会保留；值得留下的内容由你核对后保存。")
    }

    private fun preparePreview(restored: Boolean = false, explicitScope: AnalysisQuery? = null) {
        rememberFields()
        if (draft.isBlank()) { showError(IllegalArgumentException("先写下你想了解的问题")); return }
        val savedScope = if (restored && from.isNotBlank()) readScope() ?: return else null
        val keepClarification = restored && previewNeedsClarification
        // A displayed default range is not an explicit choice. Rotation must not resolve an ambiguity.
        val scope = explicitScope ?: savedScope.takeUnless { keepClarification }
        runIo({ app.conversations.prepare(draft, threadId, parentTurnId, parentFindingIndex, scope) }) { plan ->
            renderPreview(if (keepClarification && savedScope != null) plan.copy(scope = savedScope) else plan)
        }
    }

    private fun renderPreview(plan: ConversationPlan) {
        previewNeedsClarification = plan.clarification != null
        from = plan.scope.from.toString()
        through = plan.scope.through.toString()
        zoneId = plan.scope.zoneId
        includeArchived = plan.scope.includeArchived
        recordIds = plan.scope.recordIds
        mount("preview", "这次想了解")
        text(plan.question)
        val scope = card()
        Ui.sectionTitle(scope, plan.label)
        Ui.note(scope, if (plan.focusTopics.isEmpty()) "关注记录中的相关经历" else "关注：${plan.focusTopics.joinToString("、")}")
        fromField = labeledFieldIn(scope, "开始日期", "YYYY-MM-DD", from).apply { tag = "conversationFrom" }
        throughField = labeledFieldIn(scope, "结束日期", "YYYY-MM-DD", through).apply { tag = "conversationThrough" }
        Ui.note(scope, "时区 $zoneId${if (includeArchived) " · 包括归档记录" else ""}")
        recordIds?.let { Ui.note(scope, "仅查看已指定的 ${it.size} 条记录。") }
        plan.parentFindingIndex?.let { Ui.note(scope, "接着上一回答的第 ${it + 1} 条发现，回到记录中继续核对。") }
        plan.context?.let { previous ->
            Ui.disclosure(scope, "这次接着什么问", "conversationReference", expanded = true) { block ->
                Ui.note(block, "之前的问题：${previous.parentQuestion.lineSequence().first().take(120)}")
                previous.targets.forEach { target ->
                    Ui.body(block, target.text.take(240) + if (target.text.length > 240) "…" else "")
                }
                Ui.note(block, "这些是之前的理解，这次会重新查看记录依据。")
            }
        }
        plan.clarification?.let { message ->
            Ui.body(scope, message)
            Ui.note(scope, "可修改问题、调整日期；追问也可以点选具体发现。")
        }
        addButton(scope, "更新日期范围", "conversationUpdateRange") {
            rememberFields(); readScope()?.let { preparePreview(explicitScope = it) }
        }
        if (plan.clarification == null) primaryIn(scope, "开始分析", "sendConversation") { send() }
        addButton(root, "修改问题", "conversationEditQuestion") {
            rememberFields()
            if (threadId == null) showEntry() else openThread()
        }
        note("按范围分批查看记录，必要时汇总各批发现。没有记录的部分会说明；不会据此推断你没有成长。")
    }

    private fun send() {
        rememberFields()
        val scope = readScope() ?: return
        // Dates may have changed since preview. Prepare locally again before confirming this exact request.
        runIo({
            app.conversations.prepare(draft, threadId, parentTurnId, parentFindingIndex, scope) to app.deepSeekCredentials.isConfigured()
        }) { (plan, configured) ->
            renderPreview(plan)
            if (plan.clarification != null) return@runIo
            if (!configured) {
                confirm("先配置分析服务", "需要在设置中填写你的 DeepSeek 密钥。问题还保留在这里。", "前往设置") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                return@runIo
            }
            confirm("开始分析这个问题？", "将把 ${plan.scope.from} 至 ${plan.scope.through} 的相关记录片段与已登记的必要背景发送给 DeepSeek，可能产生费用。本轮最多请求 $REQUESTS_PER_PASS 次；未完成时可继续。", "开始分析") {
                threadId = plan.threadId
                intent.putExtra(EXTRA_THREAD_ID, plan.threadId)
                // Establish navigation before IO so a recreation can recover a submitted question.
                mount("thread", "问一问")
                note("正在保存问题…")
                runIo({ app.conversations.submit(plan).let { it to app.conversations.inspect(it) } }) { (turn, run) ->
                    clearDraft()
                    turnOffset = ((turn.position - 1) / PAGE_SIZE) * PAGE_SIZE
                    focusTurnId = turn.id
                    renderThread(listOf(turn to run), hasNext = false)
                    process(turn)
                }
            }
        }
    }

    private fun openThread() {
        val id = threadId ?: return showEntry()
        mount("thread", "问一问")
        note("正在读取问题与已保存的回答…")
        runIo({
            val offset = if (turnOffset < 0) app.conversations.latestTurn(id)?.position?.let { ((it - 1) / PAGE_SIZE) * PAGE_SIZE } ?: 0 else turnOffset
            val turns = app.conversations.turns(id, offset = offset, limit = PAGE_SIZE)
            Triple(offset, turns.map { it to app.conversations.inspect(it) },
                turns.size == PAGE_SIZE && app.conversations.turns(id, offset = offset + PAGE_SIZE, limit = 1).isNotEmpty())
        }) { (offset, turns, hasNext) -> turnOffset = offset; renderThread(turns, hasNext) }
    }

    private fun renderThread(turns: List<Pair<ConversationTurn, ReflectionRun?>>, hasNext: Boolean) {
        mount("thread", "问一问")
        addButton(root, "换一个话题", "conversationNewTopic") { clearConversation(); showEntry() }
        if (turns.isEmpty()) emptyState(root, "这里暂时没有回答。可以补充一个具体问题。")
        turns.forEach { (turn, run) -> renderTurn(turn, run) }
        if (turnOffset > 0) addButton(root, "前面的问题", "conversationTurnsPrevious") {
            rememberFields(); turnOffset = maxOf(0, turnOffset - PAGE_SIZE); openThread()
        }
        if (hasNext) addButton(root, "后面的问题", "conversationTurnsNext") {
            rememberFields(); turnOffset += PAGE_SIZE; openThread()
        }
        if (hasNext) note("这里还有后来的回答。直接追问默认接着最近一次回答；也可以点选上面某条发现。")
        composer(root, followup = true)
        Ui.disclosure(root, "管理这段对话", "conversationManagement") { block ->
            dangerIn(block, "删除这段对话", "conversationDelete") {
                val id = threadId ?: return@dangerIn
                confirm("删除这段对话？", "删除这里的提问、相关分析，以及依赖这些分析的机器结果和知识。原始记录与独立用户反馈会保留。此操作不能撤销。", "删除") {
                    runIo({ app.conversations.delete(id) }) { clearConversation(); showEntry() }
                }
            }
        }
        restoreTurnPosition()
    }

    private fun renderTurn(turn: ConversationTurn, run: ReflectionRun?) {
        turnAnchors[turn.id] = section(turn.question).apply { tag = "conversationTurn:${turn.id}" }
        note("${turn.scope.from} 至 ${turn.scope.through}${if (turn.focusTopics.isEmpty()) "" else " · ${turn.focusTopics.joinToString("、")}"}")
        if (run == null) {
            note("相关分析已不可用。你的问题仍然保留，可以重新提问。")
            button("根据最新记录重问", "conversationReask:${turn.id}") { reask(turn) }
            return
        }
        val status = card()
        Ui.sectionTitle(status, ConversationPresentation.status(run.status))
        Ui.note(status, ConversationPresentation.progress(run)).apply { tag = "conversationProgress:${run.id}" }
        if (run.findings.isNotEmpty()) {
            if (run.status in setOf(ReflectionStatus.STALE, ReflectionStatus.EXPIRED, ReflectionStatus.DEPENDENCY_BLOCKED))
                Ui.note(status, "这是历史回答，需要根据最新资料重新分析。")
            else if (!run.canReviewFindings && run.status in CONTINUABLE)
                Ui.note(status, "这是部分记录中的发现，尚未完成整体汇总。")
        }
        if (run.archivedAt != null) Ui.note(status, "这次分析已归档；可打开回答查看依据，或恢复进度。")
        if (run.archivedAt == null && run.status in CONTINUABLE && !run.canReviewFindings) primaryIn(status,
            if (run.totalRequests == 0) "开始这次分析" else "继续分析", "conversationContinue:${turn.id}") {
            runIo({ app.deepSeekCredentials.isConfigured() }) { configured ->
                if (configured) confirm("继续分析？", "继续发送本范围的片段或已有草稿给 DeepSeek。本轮最多请求 $REQUESTS_PER_PASS 次，可能产生费用，已有进度会保留。", "继续分析") { process(turn) }
                else confirm("先配置分析服务", "需要在设置中填写你的 DeepSeek 密钥。已有进度会保留。", "前往设置") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
        }
        addButton(status, "打开回答与依据", "conversationOpenAnswer:${turn.id}") {
            chooseTurn(turn.id)
            startActivity(Intent(this, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, turn.runId))
        }
        if (run.status in setOf(ReflectionStatus.STALE, ReflectionStatus.EXPIRED, ReflectionStatus.DEPENDENCY_BLOCKED))
            addButton(status, "根据最新记录重问", "conversationReask:${turn.id}") { reask(turn) }
        if (run.findings.isEmpty()) note(if (run.status == ReflectionStatus.NO_TEXT) "这段时间没有可读取的文字。补充记录后，可以再次提问。" else if (run.status == ReflectionStatus.READY) "现有资料没有形成有依据的回答。可以补充具体经历后再问。" else "进度已保留，回答尚未形成。")
        run.findings.forEachIndexed { index, finding ->
            val answer = card()
            Ui.fieldLabel(answer, "${index + 1}")
            Ui.body(answer, finding.text)
            if (finding.knowledgeId != null) Ui.note(answer, "已经保存过；当前有效性与后续尝试可在回答详情查看。")
            addButton(answer, "查看依据与保存", "conversationFinding:${turn.id}:$index") {
                chooseTurn(turn.id)
                startActivity(ReflectionActivity.intent(this, turn.runId, finding.target))
            }
            if (run.canReviewFindings && run.status == ReflectionStatus.READY && run.archivedAt == null &&
                (finding.feedback == null || finding.feedback.feedback.decision == ClaimFeedbackDecision.CONFIRMED)) addButton(answer, "接着这条问", "conversationFollowup:${turn.id}:$index") {
                rememberFields()
                chooseTurn(turn.id)
                parentTurnId = turn.id
                parentFindingIndex = index
                draft = "这条发现具体出现在哪几次记录中？"
                questionField?.setText(draft)
                preparePreview()
            }
        }
        if (run.unknowns.isNotEmpty()) Ui.disclosure(root, "还不能确定的事", "conversationUnknowns:${turn.id}") { block ->
            run.unknowns.forEach { Ui.body(block, it) }
        }
        Ui.disclosure(root, "这次看了哪些资料", "conversationCoverage:${turn.id}") { block ->
            Ui.note(block, ConversationPresentation.coverage(run))
        }
    }

    private fun process(turn: ConversationTurn) {
        chooseTurn(turn.id)
        runIo({
            app.reflectionWorkflow.process(turn.runId, app.deepSeekProvider, maxRequests = REQUESTS_PER_PASS,
                onProgress = { run -> runOnUiThread {
                    if (!isDestroyed && !isFinishing && busy && threadId == turn.threadId) {
                        root.findViewWithTag<TextView>("conversationProgress:${run.id}")?.text = "正在分析…\n${ConversationPresentation.progress(run)}"
                    }
                } })
        }) { pass ->
            toast("本轮请求 ${pass.modelRequestsThisPass} 次，进度已保存")
            focusTurnId = turn.id
            openThread()
            ConversationPresentation.completionNotice(pass.status, pass.run.status)?.let {
                showError(IllegalStateException(it))
            }
        }
    }

    private fun reask(turn: ConversationTurn) {
        runIo({ app.conversations.rephraseSource(turn) }) { original ->
            clearConversation()
            if (original == null) {
                showEntry()
                toast("原问题已经不可用，请写出这次想了解的完整问题。")
            } else {
                draft = original.question
                // A fresh thread cannot accidentally inherit a stale answer through an implicit pronoun.
                preparePreview(explicitScope = original.scope)
            }
        }
    }

    private fun readScope(): AnalysisQuery? = try {
        val first = LocalDate.parse(from)
        val last = LocalDate.parse(through)
        require(!last.isBefore(first)) { "结束日期不能早于开始日期" }
        AnalysisQuery(first, last, zoneId, includeArchived, recordIds)
    } catch (_: java.time.DateTimeException) {
        showError(IllegalArgumentException("请按 YYYY-MM-DD 填写日期")); null
    } catch (failure: IllegalArgumentException) {
        showError(failure); null
    }

    private fun rememberFields() {
        questionField?.let { draft = it.text.toString().trim() }
        fromField?.let { from = it.text.toString().trim() }
        throughField?.let { through = it.text.toString().trim() }
    }

    private fun clearDraft() {
        questionField = null
        draft = ""
        parentTurnId = null
        parentFindingIndex = null
        from = ""
        through = ""
        previewNeedsClarification = false
    }

    private fun clearConversation() {
        clearDraft()
        threadId = null
        turnOffset = -1
        focusTurnId = null
        preserveChosenFocus = false
        intent.removeExtra(EXTRA_THREAD_ID)
    }

    private fun chooseTurn(id: String) {
        focusTurnId = id
        preserveChosenFocus = true
    }

    private fun rememberVisibleTurn() {
        if (screen != "thread" || restoringTurnPosition || preserveChosenFocus) return
        val scroll = root.parent as? ScrollView ?: return
        val laidOut = turnAnchors.filterValues { it.isLaidOut && it.height > 0 }
        if (laidOut.isEmpty()) return
        focusTurnId = laidOut.entries.lastOrNull { it.value.top <= scroll.scrollY + dp(16) }?.key
            ?: laidOut.keys.first()
    }

    private fun restoreTurnPosition() {
        val content = root
        val scroll = content.parent as? ScrollView ?: return
        val selected = focusTurnId?.takeIf { it in turnAnchors }
            ?: (if (focusTurnId == null) turnAnchors.keys.lastOrNull() else turnAnchors.keys.firstOrNull())
            ?: return
        val anchor = turnAnchors.getValue(selected)
        focusTurnId = selected
        restoringTurnPosition = true
        fun positionIfReady(): Boolean {
            if (isDestroyed || isFinishing || root !== content || focusTurnId != selected) return true
            if (!anchor.isLaidOut || anchor.height == 0 || scroll.height == 0) return false
            scroll.scrollTo(0, maxOf(0, anchor.top - dp(8)))
            restoringTurnPosition = false
            preserveChosenFocus = false
            return true
        }
        val layout = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (positionIfReady() && scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        }
        scroll.viewTreeObserver.addOnGlobalLayoutListener(layout)
        scroll.post {
            if (positionIfReady() && scroll.viewTreeObserver.isAlive) scroll.viewTreeObserver.removeOnGlobalLayoutListener(layout)
        }
    }

    private fun formatTime(value: java.time.Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault()).format(value)

    companion object {
        const val EXTRA_THREAD_ID = "dev.tracelife.conversation.THREAD_ID"
        private const val PAGE_SIZE = 20
        private const val REQUESTS_PER_PASS = 5
        private val CONTINUABLE = setOf(ReflectionStatus.ANALYZING, ReflectionStatus.INTEGRATING, ReflectionStatus.RETRY_NEEDED)
        private val EXAMPLES = listOf(
            "这三个月，我在学习上的方法发生了哪些变化，哪些问题反复出现？",
            "这周的工作中，有哪些值得保留的做法？",
            "根据近一个月的记录，下周我可以尝试改进什么？",
        )
    }
}
