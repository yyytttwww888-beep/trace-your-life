package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ReductionReviewStatus
import dev.tracelife.analysis.ReflectionFinding
import dev.tracelife.analysis.ReflectionRun
import dev.tracelife.analysis.ReflectionStatus
import dev.tracelife.analysis.ReflectionTarget
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** An explicit, resumable analysis journey. Opening or recreating this page never calls a model. */
class ReflectionActivity : ScreenActivity() {
    private var runId: String? = null
    private var restoredScreen = "home"
    private var findingKey: String? = null
    private var selectedRange = 1
    private var historyOffset = 0
    private var from = LocalDate.now().minusDays(6).toString()
    private var through = LocalDate.now().toString()
    private var fromField: EditText? = null
    private var throughField: EditText? = null
    private var progressLabel: TextView? = null
    private var resumedOnce = false
    private val preferences get() = getSharedPreferences("ui", MODE_PRIVATE)

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            runId = savedInstanceState.getString("reflection.run")
            restoredScreen = savedInstanceState.getString("reflection.screen", "home")
            findingKey = savedInstanceState.getString("reflection.finding")
            selectedRange = savedInstanceState.getInt("reflection.range", 1)
            historyOffset = savedInstanceState.getInt("reflection.historyOffset", 0)
            from = savedInstanceState.getString("reflection.from", from)
            through = savedInstanceState.getString("reflection.through", through)
        } else {
            runId = intent.getStringExtra(EXTRA_RUN_ID)
            if (runId != null) restoredScreen = "run"
            intent.getStringExtra(EXTRA_FROM)?.let { from = it; selectedRange = 3 }
            intent.getStringExtra(EXTRA_THROUGH)?.let { through = it; selectedRange = 3 }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberDates()
        outState.putString("reflection.run", runId)
        outState.putString("reflection.screen", screen)
        outState.putString("reflection.finding", findingKey)
        outState.putInt("reflection.range", selectedRange)
        outState.putInt("reflection.historyOffset", historyOffset)
        outState.putString("reflection.from", from)
        outState.putString("reflection.through", through)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!resumedOnce) { resumedOnce = true; return }
        if (busy) return
        if (screen == "home") showEntry() else runId?.let { openRun(it) }
    }

    override fun onPause() {
        rememberDates()
        super.onPause()
    }

    override fun showHome() {
        if (restoredScreen != "home" && runId != null) openRun(requireNotNull(runId)) else showEntry()
    }

    override fun navigateBack() {
        if (busy) return
        when (screen) {
            "original", "evidence" -> runId?.let { openRun(it) } ?: showEntry()
            "finding" -> { findingKey = null; runId?.let { openRun(it) } ?: showEntry() }
            "run" -> { findingKey = null; showEntry() }
            else -> finish()
        }
    }

    private fun showEntry() {
        beginScreen("home", "分析", showBack = false)
        progressLabel = null
        note("回看这段时间的记录，找出值得留下的经验、值得关注的问题和可以尝试的改进。")
        val scopeCard = card()
        Ui.fieldLabel(scopeCard, "分析哪段时间")
        val range = spinnerIn(scopeCard, listOf("今天", "近 7 天", "近 30 天", "自定义日期"), selectedRange, "reflectionRange")
        val dates = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            scopeCard.addView(this)
        }
        fromField = labeledFieldIn(dates, "开始日期", "YYYY-MM-DD", from).apply { tag = "reflectionFrom" }
        throughField = labeledFieldIn(dates, "结束日期", "YYYY-MM-DD", through).apply { tag = "reflectionThrough" }
        dates.visibility = if (selectedRange == 3) View.VISIBLE else View.GONE
        val rangeLabel = Ui.note(scopeCard, "$from 至 $through")
        range.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                rememberDates()
                selectedRange = position
                if (position != 3) {
                    val today = LocalDate.now()
                    through = today.toString()
                    from = today.minusDays(when (position) { 0 -> 0L; 1 -> 6L; else -> 29L }).toString()
                    fromField?.setText(from)
                    throughField?.setText(through)
                }
                dates.visibility = if (position == 3) View.VISIBLE else View.GONE
                rangeLabel.visibility = if (position == 3) View.GONE else View.VISIBLE
                rangeLabel.text = "$from 至 $through"
            }
        }
        primaryIn(scopeCard, "开始分析", "startReflection") { startAnalysis() }
        Ui.note(scopeCard, "分析所选范围内可读取的文字，日期不确定的记录也会单列纳入。分批处理，结果会保留，确认后再存入知识库。")
        section("分析记录")
        val history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        Ui.note(history, "正在读取分析记录…")
        runIo({ app.reflectionWorkflow.history(limit = HISTORY_PAGE_SIZE + 1, offset = historyOffset) }) { runs ->
            history.removeAllViews()
            if (runs.isEmpty()) {
                emptyState(history, if (historyOffset == 0) "还没有分析过。保存记录后，在这里开始第一次回看。" else "这一页已没有分析记录。")
            } else runs.take(HISTORY_PAGE_SIZE).forEach { run ->
                listRow(history,
                    "${run.scope.from} 至 ${run.scope.through}\n${statusLabel(run.status)} · ${run.findings.size} 条发现\n${formatTime(run)}",
                    "reflectionRun:${run.id}") { findingKey = null; openRun(run.id) }
            }
            if (historyOffset > 0) addButton(history, "较新的分析", "reflectionHistoryPrevious") {
                rememberDates()
                historyOffset = maxOf(0, historyOffset - HISTORY_PAGE_SIZE)
                showEntry()
            }
            if (runs.size > HISTORY_PAGE_SIZE) addButton(history, "更早的分析", "reflectionHistoryNext") {
                rememberDates()
                historyOffset += HISTORY_PAGE_SIZE
                showEntry()
            }
        }
    }

    private fun startAnalysis() {
        rememberDates()
        val scope = try {
            val start = LocalDate.parse(from)
            val end = LocalDate.parse(through)
            require(!end.isBefore(start)) { "结束日期不能早于开始日期" }
            AnalysisQuery(start, end, ZoneId.systemDefault().id)
        } catch (_: java.time.DateTimeException) {
            showError(IllegalArgumentException("请按 YYYY-MM-DD 填写日期")); return
        } catch (failure: IllegalArgumentException) {
            showError(failure); return
        }
        withConfigured {
            confirm("开始分析这段时间？", "将把 $from 至 $through 范围内以及日期不确定的记录片段发送给 DeepSeek，可能产生费用。本轮最多请求 $REQUESTS_PER_PASS 次；未完成时保留进度，由你决定是否继续。", "开始分析") {
                runIo({ app.reflectionWorkflow.start(scope) }) { run ->
                    findingKey = null
                    historyOffset = 0
                    rememberRun(run.id)
                    renderRun(run)
                    processRun(run)
                }
            }
        }
    }

    private fun withConfigured(action: () -> Unit) {
        runIo({ app.deepSeekCredentials.isConfigured() }) { configured ->
            if (configured) action() else confirm("先配置分析服务", "需要在设置中填写你的 DeepSeek 密钥。记录已经保存在手机上。", "前往设置") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    private fun rememberDates() {
        fromField?.let { from = it.text.toString().trim() }
        throughField?.let { through = it.text.toString().trim() }
    }

    private fun rememberRun(id: String) {
        runId = id
        preferences.edit().putString("lastReflectionRunId", id).apply()
        // This identity is established before any request, including when a rotation follows.
        intent.putExtra(EXTRA_RUN_ID, id)
    }

    private fun openRun(id: String) {
        rememberRun(id)
        beginScreen("run", "分析结果")
        fromField = null
        throughField = null
        progressLabel = Ui.note(root, "正在读取已保存的进度…").apply { tag = "reflectionProgress" }
        runIo({ app.reflectionWorkflow.inspect(id) }) { run ->
            val selected = findingKey?.let { key -> run.findings.firstOrNull { targetKey(it.target) == key } }
            if (selected == null) { findingKey = null; renderRun(run) } else renderFinding(run, selected)
        }
    }

    private fun renderRun(run: ReflectionRun) {
        beginScreen("run", "分析结果")
        fromField = null
        throughField = null
        note("${run.scope.from} 至 ${run.scope.through}")
        val summary = card()
        Ui.sectionTitle(summary, statusLabel(run.status))
        progressLabel = Ui.note(summary, progressText(run)).apply { tag = "reflectionProgress" }
        Ui.note(summary, statusHint(run))
        if (run.status in continuable && !run.canReviewFindings) primaryIn(summary, if (run.totalRequests == 0) "开始分析" else "继续分析", "continueReflection") {
            withConfigured {
                confirm("继续分析？", "继续发送本范围尚未处理的片段或已有草稿给 DeepSeek，可能产生费用。本轮最多请求 $REQUESTS_PER_PASS 次，已完成的步骤会保留。", "继续分析") { processRun(run) }
            }
        }
        if (run.status == ReflectionStatus.NEEDS_RECOVERY) addButton(summary, "恢复中断进度", "recoverReflection") {
            confirm("恢复中断进度？", "上次请求未正常结束。恢复本身不会调用模型，之后由你决定是否继续；重新请求可能重复产生费用。", "恢复进度") {
                runIo({ app.reflectionWorkflow.recover(run.id) }) { renderRun(it) }
            }
        }
        if (run.status in setOf(ReflectionStatus.STALE, ReflectionStatus.EXPIRED, ReflectionStatus.BUDGET_EXHAUSTED, ReflectionStatus.DEPENDENCY_BLOCKED)) {
            addButton(summary, "重新选择范围", "newReflection") { findingKey = null; showEntry() }
        }
        addButton(summary, "刷新结果", "refreshReflection") { openRun(run.id) }
        renderCoverage(run)
        if (run.findings.isEmpty()) {
            section("本次发现")
            emptyState(root, when (run.status) {
                ReflectionStatus.READY -> "这次没有形成有依据的经验或判断。没有发现不等于没有进步，可以补充具体经历后再分析。"
                ReflectionStatus.NO_TEXT -> "这段时间没有可读取的文字。先记下经历、做法或困惑，再来回看。"
                else -> "结果还没有形成。已保存的处理进度会显示在上方。"
            })
        } else {
            note("发现仍需核对；已保存与待核对的内容会分别标明。")
            groups.forEach { group ->
                val items = run.findings.filter { groupFor(it.text) == group.first }
                if (items.isEmpty()) return@forEach
                section(group.second)
                items.forEach { finding ->
                    val container = card()
                    chip(container, findingStatus(finding), if (finding.knowledgeId != null) Ui.Tone.OK else Ui.Tone.NEUTRAL)
                    Ui.body(container, finding.text)
                    addButton(container, "查看依据与处理", "reflectionFinding:${targetKey(finding.target)}") {
                        findingKey = targetKey(finding.target)
                        renderFinding(run, finding)
                    }
                }
            }
        }
        if (run.unknowns.isNotEmpty()) Ui.disclosure(root, "还不能确定的事", "reflectionUnknowns") { card ->
            run.unknowns.forEach { Ui.body(card, it) }
        }
    }

    private fun renderCoverage(run: ReflectionRun) {
        Ui.disclosure(root, "分析了哪些资料", "reflectionCoverage") { card ->
            val coverage = run.coverage
            if (coverage == null) {
                Ui.note(card, "当前任务不能提供有效覆盖数据，请查看任务状态。")
            } else {
                Ui.note(card, "范围内 ${coverage.records} 条记录；已处理 ${coverage.completedTextCodePoints} / ${coverage.textCodePoints} 字，还有 ${coverage.remainingTextFragments} 段待分析。")
                Ui.note(card, "未读附件 ${coverage.unreadAttachments} 个；不可读取记录 ${coverage.unreadableRecords} 条。")
                Ui.note(card, "日期未知 ${coverage.unknownTimeRecords} 条；跨越所选日期 ${coverage.overlappingRecords} 条。它们不能直接归为这段时间内发生。")
                if (coverage.missingExactIds.isNotEmpty()) Ui.note(card, "有 ${coverage.missingExactIds.size} 条指定记录不在当前可用范围。")
            }
            Ui.note(card, "成功分析 ${run.synthesizedBatches} 批，汇总 ${run.reducedBatches} 批；累计请求 ${run.totalRequests} 次。")
            Ui.note(card, "没有记录不等于没有发生。分析不会读取整个知识库，处理完成也不保证理解正确。")
        }
    }

    private fun processRun(run: ReflectionRun) {
        progressLabel?.text = "正在分析所选资料…本轮最多请求 $REQUESTS_PER_PASS 次。"
        runIo({
            app.reflectionWorkflow.process(run.id, app.deepSeekProvider, maxRequests = REQUESTS_PER_PASS,
                onProgress = { updated -> reportProgress(updated, run.totalRequests, false) })
        }) { pass ->
            renderRun(pass.run.copy(status = pass.status))
            toast("本轮请求 ${pass.modelRequestsThisPass} 次，进度已保存")
        }
    }

    private fun reportProgress(run: ReflectionRun, initialRequests: Int, reviewing: Boolean) {
        runOnUiThread {
            if (isDestroyed || isFinishing || runId != run.id || !busy) return@runOnUiThread
            val phase = if (reviewing) "正在核对依据" else if (run.status == ReflectionStatus.INTEGRATING) "正在汇总各批结果" else "正在分析记录"
            progressLabel?.text = "$phase…本轮已请求 ${run.totalRequests - initialRequests} / $REQUESTS_PER_PASS 次。\n${progressText(run)}"
        }
    }

    private fun renderFinding(run: ReflectionRun, finding: ReflectionFinding) {
        findingKey = targetKey(finding.target)
        beginScreen("finding", "这条发现")
        note("${run.scope.from} 至 ${run.scope.through}")
        chip(root, findingStatus(finding), if (finding.knowledgeId != null) Ui.Tone.OK else Ui.Tone.NEUTRAL)
        text(finding.text)
        progressLabel = Ui.note(root, finding.reviewVerdict?.let(::verdictLabel) ?: "尚未完成原文核对。你的确认与原文核对会分别保留。").apply { tag = "reflectionProgress" }
        finding.reviewProgress?.let { progress ->
            note("已查阅 ${progress.scannedFragments} / ${progress.originalFragments} 段来源；最终核对重读 ${progress.finalRereadFragments} 段。")
            reviewHint(progress.status)?.let { note(it) }
        }
        addButton(root, "查看记录依据", "reflectionEvidence") { showEvidence(run, finding) }
        if (run.status !in blocked && run.canReviewFindings) {
            val decision = finding.feedback?.feedback?.decision
            if (finding.knowledgeId == null && decision !in rejected && finding.reviewVerdict !in unsupported) {
                primaryButton(if (decision == ClaimFeedbackDecision.CONFIRMED) "继续核对并保存" else "保留到知识库", "keepReflectionFinding") {
                    keepFinding(run, finding)
                }
            }
            if (decision != ClaimFeedbackDecision.REJECTED) dangerButton("这条不符合我的情况", "rejectReflectionFinding") {
                confirm("否定这条发现？", "将保留你的决定，阻止这条发现继续收录；已经保存的关联内容也会退出有效知识。原始记录会保留。", "否定") {
                    runIo({ app.reflectionWorkflow.respond(run.id, finding.target, run.taskVersion,
                        ClaimFeedbackDecision.REJECTED, finding.feedback?.feedback?.version) }) { updated ->
                        renderUpdatedFinding(updated)
                    }
                }
            }
        } else if (!run.canReviewFindings && run.status !in blocked) {
            note("这是部分记录中的发现。请先完成整段时间的分析与汇总，再核对和保存。")
        } else note("${statusLabel(run.status)}。这条发现不能继续保存为当前知识，请返回查看任务状态。")
        note("这是依据所选记录提出的解释，不是对你的固定评价。觉得不准确时可以否定，再补充经历。")
    }

    private fun keepFinding(run: ReflectionRun, finding: ReflectionFinding) {
        withConfigured { showKeepDialog(run, finding) }
    }

    private fun showKeepDialog(run: ReflectionRun, finding: ReflectionFinding) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        val topics = labeledFieldIn(layout, "主题（可选）", "例如：学习、工作")
        AlertDialog.Builder(this).setTitle("确认并保留这条发现？")
            .setMessage("确认表示内容符合你的记录。接着会将相关原文发送给 DeepSeek 再次核对，可能产生费用，本轮最多请求 $REQUESTS_PER_PASS 次；只有记录支持时才保存到知识库。未完成时可继续。")
            .setView(layout).setNegativeButton("取消", null)
            .setPositiveButton("确认并核对") { _, _ ->
                val selectedTopics = topics.text.toString().split(",", "，", "、").map(String::trim).filter(String::isNotEmpty).toSet()
                if (selectedTopics.size > 30 || selectedTopics.any { it.length > 80 }) {
                    showError(IllegalArgumentException("最多填写 30 个主题，每个主题不超过 80 字")); return@setPositiveButton
                }
                progressLabel?.text = "正在核对记录依据…本轮最多请求 $REQUESTS_PER_PASS 次。"
                runIo({
                    if (finding.feedback?.feedback?.decision != ClaimFeedbackDecision.CONFIRMED) {
                        app.reflectionWorkflow.respond(run.id, finding.target, run.taskVersion,
                            ClaimFeedbackDecision.CONFIRMED, finding.feedback?.feedback?.version)
                    }
                    app.reflectionWorkflow.reviewAndSave(run.id, finding.target, app.deepSeekProvider, app.deepSeekProvider,
                        maxRequests = REQUESTS_PER_PASS, topics = selectedTopics,
                        onProgress = { updated -> reportProgress(updated, run.totalRequests, true) })
                }) { pass -> renderUpdatedFinding(pass.run.copy(status = pass.status)) }
            }.show()
    }

    private fun renderUpdatedFinding(run: ReflectionRun) {
        val finding = run.findings.firstOrNull { targetKey(it.target) == findingKey }
        if (finding == null) { findingKey = null; renderRun(run) } else renderFinding(run, finding)
    }

    private fun showEvidence(run: ReflectionRun, finding: ReflectionFinding, offset: Int = 0) {
        beginScreen("evidence", "记录依据")
        note("这里只展示这条发现关联的原文。批内上下文也会用于核对，列出来源不表示每段都支持这条判断。")
        runIo({ app.reflectionWorkflow.evidence(run.id, finding.target, offset) }) { page ->
            if (page.items.isEmpty()) emptyState(root, "当前没有可读取的依据。来源可能已变化，请返回刷新结果。")
            page.items.forEachIndexed { index, receipt ->
                listRow(root, "记录原文 · 第 ${offset + index + 1} 段\n${if (receipt.field == "body") "随记正文" else "复盘回答"} · 修订 ${receipt.revision}",
                    "reflectionOriginal:${receipt.id}") { showOriginal(run, finding, receipt, offset) }
            }
            page.nextOffset?.let { next -> button("下一页依据", "nextReflectionEvidence") { showEvidence(run, finding, next) } }
            if (offset > 0) button("返回第一页", "firstReflectionEvidence") { showEvidence(run, finding) }
        }
    }

    private fun showOriginal(run: ReflectionRun, finding: ReflectionFinding, receipt: EvidenceReceipt, evidenceOffset: Int, start: Int = 0) {
        beginScreen("original", "记录原文")
        runIo({ app.reflectionWorkflow.original(run.id, finding.target, receipt.id, start) }) { fragment ->
            note("记录修订 ${fragment.revision} · 引用范围 ${receipt.start + 1}–${receipt.end} 字")
            fragment.question?.let { section(it) }
            text(fragment.text)
            note("当前显示第 ${fragment.start + 1}–${fragment.end} 字。")
            if (fragment.end < receipt.end) button("继续读这段原文", "nextReflectionOriginal") {
                showOriginal(run, finding, receipt, evidenceOffset, fragment.end - receipt.start)
            }
            button("返回依据列表", "backReflectionEvidence") { showEvidence(run, finding, evidenceOffset) }
        }
    }

    override fun showError(failure: Throwable) {
        progressLabel?.text = "操作未完成。已保存的进度仍可通过「刷新结果」查看，不会自动重试。"
        super.showError(failure)
    }

    private fun progressText(run: ReflectionRun): String {
        val coverage = run.coverage
        return if (coverage == null) "累计请求 ${run.totalRequests} 次。"
        else "${coverage.records} 条记录 · 已处理 ${coverage.completedTextCodePoints} / ${coverage.textCodePoints} 字 · 累计请求 ${run.totalRequests} 次"
    }

    private fun statusHint(run: ReflectionRun): String = when (run.status) {
        ReflectionStatus.ANALYZING -> "还有文字待分析。每轮有限次调用，达到本轮次数后会停下并保留进度。"
        ReflectionStatus.INTEGRATING -> "所选文字已分批分析，仍需汇总。当前批次的发现不能代表整个时间段。"
        ReflectionStatus.READY -> "本次分析已完成。先看发现与依据，再决定哪些值得保存。"
        ReflectionStatus.NO_TEXT -> "范围内没有可读取的文字；附件暂不参与分析。"
        ReflectionStatus.RETRY_NEEDED -> if (run.canReviewFindings) "上次核对没有成功。请打开对应发现，继续未完成的核对；可能产生费用。"
            else "上次请求没有成功，已有结果已保留。继续需要你再次确认，可能产生费用。"
        ReflectionStatus.NEEDS_RECOVERY -> "上次请求中断或仍未结束。确认没有其他正在运行的分析后，再恢复进度。"
        ReflectionStatus.BUDGET_EXHAUSTED -> "当前任务已达到内容或累计请求上限。已有发现保留，可缩小日期范围后重新分析。"
        ReflectionStatus.DEPENDENCY_BLOCKED -> "来源判断或反馈已经变化，当前汇总不能继续使用。请重新分析。"
        ReflectionStatus.STALE -> "记录已发生变化。这次分析作为历史保留，不再代表最新资料。"
        ReflectionStatus.EXPIRED -> "这次分析已过期。结果仍可回看，保存前需要重新分析。"
        ReflectionStatus.CONFLICT -> "资料正在其他地方变化。请刷新结果后再操作。"
    }

    private fun findingStatus(finding: ReflectionFinding): String = when {
        finding.knowledgeId != null -> "已保存到知识库"
        finding.feedback?.feedback?.decision == ClaimFeedbackDecision.REJECTED -> "已否定"
        finding.feedback?.feedback?.decision == ClaimFeedbackDecision.CORRECTED -> "已纠正，原判断不再收录"
        finding.feedback?.feedback?.decision == ClaimFeedbackDecision.WITHDRAWN -> "已撤回"
        finding.reviewVerdict == ReviewVerdict.CONTRADICTED -> "有反例，未保存"
        finding.reviewVerdict == ReviewVerdict.UNSUPPORTED -> "依据不支持，未保存"
        finding.reviewVerdict == ReviewVerdict.INSUFFICIENT_CONTEXT -> "资料不足，未保存"
        finding.feedback?.feedback?.decision == ClaimFeedbackDecision.CONFIRMED -> "你已确认，待完成核对"
        else -> "待核对"
    }

    private fun formatTime(run: ReflectionRun) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault()).format(run.createdAt)

    companion object {
        const val EXTRA_RUN_ID = "dev.tracelife.reflection.RUN_ID"
        const val EXTRA_FROM = "dev.tracelife.reflection.FROM"
        const val EXTRA_THROUGH = "dev.tracelife.reflection.THROUGH"
        private const val REQUESTS_PER_PASS = 5
        private const val HISTORY_PAGE_SIZE = 20
        private val continuable = setOf(ReflectionStatus.ANALYZING, ReflectionStatus.INTEGRATING, ReflectionStatus.RETRY_NEEDED)
        private val blocked = setOf(ReflectionStatus.STALE, ReflectionStatus.EXPIRED, ReflectionStatus.NEEDS_RECOVERY,
            ReflectionStatus.DEPENDENCY_BLOCKED, ReflectionStatus.CONFLICT, ReflectionStatus.BUDGET_EXHAUSTED)
        private val rejected = setOf(ClaimFeedbackDecision.REJECTED, ClaimFeedbackDecision.CORRECTED, ClaimFeedbackDecision.WITHDRAWN)
        private val unsupported = setOf(ReviewVerdict.UNSUPPORTED, ReviewVerdict.CONTRADICTED, ReviewVerdict.INSUFFICIENT_CONTEXT)
        private val groups = listOf("值得留下：" to "值得留下的经验", "值得关注：" to "值得关注", "可以尝试：" to "可以尝试的改进", "" to "其他发现")
        private fun groupFor(text: String) = groups.firstOrNull { it.first.isNotEmpty() && text.startsWith(it.first) }?.first.orEmpty()
        private fun targetKey(target: ReflectionTarget): String = when (target) {
            is ReflectionTarget.Claim -> "claim:${target.ref.batchKey}:${target.ref.claimIndex}:${target.ref.claimSha256}"
            is ReflectionTarget.Reduction -> "reduction:${target.ref.root.batchKey}:${target.ref.root.attempt}:${target.ref.index}:${target.ref.statementSha256}"
        }
        private fun statusLabel(status: ReflectionStatus) = when (status) {
            ReflectionStatus.ANALYZING -> "待继续分析"
            ReflectionStatus.INTEGRATING -> "待汇总各批结果"
            ReflectionStatus.READY -> "分析完成"
            ReflectionStatus.NO_TEXT -> "没有可分析的文字"
            ReflectionStatus.RETRY_NEEDED -> "请求未成功"
            ReflectionStatus.NEEDS_RECOVERY -> "上次分析中断"
            ReflectionStatus.BUDGET_EXHAUSTED -> "本次分析额度已用完"
            ReflectionStatus.DEPENDENCY_BLOCKED -> "相关判断已变化"
            ReflectionStatus.STALE -> "来源已变化"
            ReflectionStatus.EXPIRED -> "分析已过期"
            ReflectionStatus.CONFLICT -> "需要刷新结果"
        }
        private fun verdictLabel(verdict: ReviewVerdict) = when (verdict) {
            ReviewVerdict.SUPPORTED -> "原文核对：现有记录支持。"
            ReviewVerdict.UNSUPPORTED -> "原文核对：目前的依据不支持这条发现，未保存。"
            ReviewVerdict.CONTRADICTED -> "原文核对：记录中存在反例，未保存。"
            ReviewVerdict.INSUFFICIENT_CONTEXT -> "原文核对：必要背景不足，未保存。"
        }
        private fun reviewHint(status: ReductionReviewStatus): String? = when (status) {
            ReductionReviewStatus.CONTEXT_REQUIRED -> "部分原文缺少必要背景，请补充经历后重新分析。"
            ReductionReviewStatus.NO_RELEVANT_EVIDENCE -> "没有找到与这条发现相关的原文，不能保存。"
            ReductionReviewStatus.RELATED_EVIDENCE_LIMIT -> "所需原文超过单次核对上限，不能删减依据后保存。请缩小日期范围重新分析。"
            ReductionReviewStatus.SCAN_READY, ReductionReviewStatus.FINAL_READY, ReductionReviewStatus.PASS_LIMIT_REACHED -> "核对尚未完成，可以继续上次进度。"
            ReductionReviewStatus.BUDGET_EXHAUSTED -> "核对达到内容或累计请求上限，不能保存。请缩小范围重新分析。"
            ReductionReviewStatus.RETRY_NEEDED -> "核对请求未成功，已保存进度；可以明确重试。"
            ReductionReviewStatus.NEEDS_RECOVERY -> "核对中断，请返回分析结果恢复任务。"
            else -> null
        }
    }
}
