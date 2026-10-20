package dev.tracelife.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.Spinner
import dev.tracelife.analysis.AnalysisProviderException
import dev.tracelife.analysis.AnalysisTask
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.AnalysisLimits
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.analysis.CallStatus
import dev.tracelife.profile.*
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Entry, recreation and history reads never resume online requests. */
class LongitudinalProfileActivity : ScreenActivity() {
    private var runId: String? = null
    private var restoredScreen = "form"
    private var offset = 0
    private var dimensionValue = 4
    private var monthValue = 1
    private var questionValue = "在不同阶段，哪些做法反复有帮助？有哪些条件和例外？"
    private var dimensionInput: Spinner? = null
    private var monthInput: Spinner? = null
    private var questionInput: EditText? = null
    private var passNotice: String? = null

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        restoredScreen = savedInstanceState?.getString("screen") ?: if (intent.getBooleanExtra("history", false)) "history" else "form"
        runId = savedInstanceState?.getString("runId") ?: intent.getStringExtra("runId")
        offset = savedInstanceState?.getInt("offset") ?: 0
        dimensionValue = savedInstanceState?.getInt("dimension", 4) ?: 4
        monthValue = savedInstanceState?.getInt("months", 1) ?: 1
        questionValue = savedInstanceState?.getString("question") ?: questionValue
        passNotice = savedInstanceState?.getString("passNotice")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        captureForm()
        outState.putString("screen", screen)
        outState.putString("runId", runId)
        outState.putInt("offset", offset)
        outState.putInt("dimension", dimensionValue)
        outState.putInt("months", monthValue)
        outState.putString("question", questionValue)
        outState.putString("passNotice", passNotice)
        super.onSaveInstanceState(outState)
    }
    override fun showHome() {
        val initial = restoredScreen
        restoredScreen = "form"
        when {
            runId != null && initial != "history" -> showTask(requireNotNull(runId), offset)
            initial == "history" -> showHistory(offset)
            else -> showForm()
        }
    }
    override fun navigateBack() {
        if (busy) return
        when (screen) {
            "task" -> { runId = null; showHistory(0) }
            "history" -> showForm()
            else -> finish()
        }
    }
    private fun captureForm() {
        if (screen != "form") return
        dimensionInput?.let { dimensionValue = it.selectedItemPosition }
        monthInput?.let { monthValue = it.selectedItemPosition }
        questionInput?.let { questionValue = it.text.toString() }
    }
    private fun showForm() {
        beginScreen("form", "从不同阶段认识自己")
        val form = card()
        Ui.note(form, "比较完整月份中的经历，保留情境和例外。资料不足时可以没有新描述；月份多也不代表固定人格。")
        Ui.fieldLabel(form, "想了解的侧面")
        dimensionInput = spinnerIn(form, ProfileDimension.entries.map(::profileDimensionTitle), dimensionValue, "longitudinalDimension")
        Ui.fieldLabel(form, "比较范围")
        monthInput = spinnerIn(form, (2..12).map { "最近 $it 个完整月" }, monthValue, "longitudinalMonths")
        questionInput = labeledFieldIn(form, "这次想了解什么", value = questionValue, multiline = true).apply { tag = "longitudinalQuestion" }
        Ui.note(form, "以当前月份之前的完整月份为范围；每轮最多 5 次请求，任务累计最多 100 次。范围、已读资料与进度保存在手机，关闭页面不会自动继续。")
        primaryIn(form, "查看范围并开始", "startLongitudinal") {
            captureForm()
            if (questionValue.isBlank()) { showError(IllegalArgumentException("请写下这次想了解什么")); return@primaryIn }
            val end = LocalDate.now().withDayOfMonth(1)
            val from = end.minusMonths((monthValue + 2).toLong())
            val periods = (0 until monthValue + 2).map { index ->
                val start = from.plusMonths(index.toLong())
                ProfileComparisonPeriod(start, start.plusMonths(1).minusDays(1))
            }
            val request = AnalysisRequest(questionValue.trim(), AnalysisQuery(from, end.minusDays(1), ZoneId.systemDefault().id),
                limits = AnalysisLimits(inputTokenLimit = 32_000),
                profileTarget = ProfileAnalysisTarget(ProfileDimension.entries[dimensionValue], ProfileLayer.LONG_TERM, periods))
            confirm("开始比较这些月份？", "$from 至 ${end.minusDays(1)}，${profileDimensionTitle(ProfileDimension.entries[dimensionValue])}。所选原文将分批发送给 DeepSeek；本轮最多 5 次请求，可能产生费用。之后仍需核对与明确保存。", "开始") {
                runIo({
                    require(app.deepSeekCredentials.isConfigured()) { "请先在设置中配置 DeepSeek 密钥" }
                    app.longitudinalProfiles.start(request, id = "longitudinal-v1-${UUID.randomUUID()}").stored.id
                }) { id -> runId = id; process(id) }
            }
        }
        button("整理历史与进度", "profileTaskHistory") { captureForm(); showHistory(0) }
    }
    private fun showHistory(start: Int) {
        offset = start
        beginScreen("history", "描述整理历史")
        note("包括近期、情境和长期整理。打开只查看进度；已归档任务需先恢复才能继续。")
        runIo({
            val rows = app.repository.analysisRunIndex(offset = start, limit = 20)
            rows to rows.mapNotNull { row -> app.analysis.task(row.id).takeIf { it.state.request.profileTarget != null } }
        }) { (rows, tasks) ->
            if (tasks.isEmpty()) note("这页没有画像整理任务。其他类型的任务已略过。")
            tasks.forEach { task ->
                val target = requireNotNull(task.state.request.profileTarget)
                listRow(root, "${profileDimensionTitle(target.dimension)} · ${if (target.layer == ProfileLayer.LONG_TERM) "跨阶段" else "近期与情境"}\n${task.state.request.scope.from} 至 ${task.state.request.scope.through}${if (task.stored.archivedAt != null) " · 已归档" else ""}", "profileTask:${task.stored.id}") {
                    showTask(task.stored.id, 0)
                }
            }
            if (start > 0) button("上一页", "profileHistoryPrevious") { showHistory((start - 20).coerceAtLeast(0)) }
            if (rows.size == 20) button("下一页", "profileHistoryNext") { showHistory(start + 20) }
        }
    }
    private fun showTask(id: String, start: Int) {
        if (runId != id) passNotice = null
        runId = id; offset = start
        beginScreen("task", "描述与整理进度")
        runIo({
            val task = app.analysis.task(id)
            val long = if (task.state.request.profileTarget?.layer == ProfileLayer.LONG_TERM) app.longitudinalProfiles.inspect(id, start, 20) else null
            Triple(task, long, long?.candidates ?: app.profileCandidates.inspect(id, start, 20))
        }) { (task, long, page) -> renderProgress(task, page, long) }
    }
    private fun renderProgress(task: AnalysisTask, page: ProfileCandidateProgress, long: LongitudinalProfileProgress?) {
        val scope = task.state.request.scope
        val details = card()
        Ui.body(details, "${scope.from} 至 ${scope.through}")
        Ui.body(details, task.state.request.question)
        passNotice?.let { Ui.note(details, it) }
        Ui.note(details, "已经使用 ${task.state.calls.size}／${task.state.request.limits.maxCalls} 次请求。每轮最多 5 次，进度自动保存。")
        page.coverage?.let { Ui.note(details, "已整理 ${it.completedTextCodePoints}／${it.textCodePoints} 字；日期未定 ${it.unknownTimeRecords} 条，跨期 ${it.overlappingRecords} 条，未读附件 ${it.unreadAttachments} 个。") }
        long?.periods?.forEach { Ui.note(details, "${it.period.from} 至 ${it.period.through}：${it.datedRecordsWithText} 条有日期的文字记录") }
        if (long?.status == LongitudinalProfileStatus.INSUFFICIENT_PERIODS) Ui.body(details, "资料不足：至少两个比较月份需要有明确日期的文字记录。本次不会发送请求。")
        if (long?.status == LongitudinalProfileStatus.INTEGRATED)
            Ui.note(details, "已将 ${long.integration?.leafCount} 批资料整体整理。下面的描述保留全部来源，仍需逐项核对、保存和采用。")
        else if (page.integration == dev.tracelife.analysis.SynthesisIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED)
            Ui.note(details, "局部描述已保留。继续整理会对照所有批次，形成整个时期的候选；在此之前不能称为整体结论。")
        Ui.note(details, candidateStatusTitle(page.status))
        if (task.stored.archivedAt != null) {
            button("恢复这项整理", "restoreProfileTask") {
                runIo({ app.repository.unarchiveAnalysisRun(task.stored.id, task.stored.version) }) { showTask(task.stored.id, offset) }
            }
        } else if (task.state.calls.any { it.status == CallStatus.IN_FLIGHT }) {
            button("恢复中断进度", "recoverProfileTask") {
                confirm("确认上次请求已停止？", "恢复只标记中断，不会重新发送请求。", "恢复") {
                    runIo({ app.analysis.recoverInterrupted(task.stored.id) }) { showTask(task.stored.id, offset) }
                }
            }
        } else if (page.status in setOf(ProfileCandidateStatus.READY, ProfileCandidateStatus.NEEDS_ATTENTION, ProfileCandidateStatus.PASS_LIMIT_REACHED, ProfileCandidateStatus.RETRY_NEEDED, ProfileCandidateStatus.CANCELLED) && long?.status != LongitudinalProfileStatus.INSUFFICIENT_PERIODS) {
            primaryButton("继续整理与核对", "continueProfileTask") {
                confirm("继续在线整理？", "保持 ${scope.from} 至 ${scope.through} 的范围，本轮最多 5 次请求。成功进度会复用，可能产生费用。", "继续") { process(task.stored.id) }
            }
        }
        val candidates = card()
        if (long?.status == LongitudinalProfileStatus.INTEGRATED) {
            Ui.sectionTitle(candidates, "整体候选 · ${long.integratedCandidates.size}")
            if (long.integratedCandidates.isEmpty()) Ui.note(candidates, "全部批次已整理，但资料尚不足以提出整体描述。原有局部线索和未知项仍保留。")
            long.integratedCandidates.forEachIndexed { index, claim ->
                listRow(candidates, "整体描述 ${index + 1} · 查看内容与完整核对", "integratedProfile:${claim.claimSha256.take(12)}") {
                    startActivity(ProfileMaintenanceActivity.intent(this, task.stored.id, claim))
                }
            }
        }
        Ui.sectionTitle(candidates, "分批形成的线索 · ${page.total}")
        if (page.candidates.isEmpty()) Ui.note(candidates, "尚未形成描述。资料不足或未发现跨期线索时可以没有结果。")
        page.candidates.forEachIndexed { index, item ->
            listRow(candidates, "描述 ${offset + index + 1} · 查看内容、历史对照与原文", "longitudinalCandidate:${item.claim.claimSha256.take(12)}") {
                startActivity(ProfileMaintenanceActivity.intent(this, task.stored.id, item.claim))
            }
        }
        if (offset > 0) button("上一页", "profileCandidatesPrevious") { showTask(task.stored.id, (offset - 20).coerceAtLeast(0)) }
        page.nextOffset?.let { next -> button("下一页", "profileCandidatesNext") { showTask(task.stored.id, next) } }
        button("刷新进度", "refreshProfileTask") { showTask(task.stored.id, offset) }
    }
    private fun process(id: String) {
        runId = id
        beginScreen("task", "正在整理，进度会保留")
        note("本轮最多 5 次请求。离开后重新打开只读取已保存进度。")
        runIo({
            require(app.deepSeekCredentials.isConfigured()) { "请先在设置中配置 DeepSeek 密钥" }
            try {
                val status = if (app.analysis.task(id).state.request.profileTarget?.layer == ProfileLayer.LONG_TERM)
                    app.longitudinalProfiles.process(id, app.deepSeekProvider, app.deepSeekProvider, maxModelRequests = 5).status.name
                else app.profileCandidates.process(id, app.deepSeekProvider, app.deepSeekProvider, maxModelRequests = 5).status.name
                when (status) {
                    "BUDGET_EXHAUSTED" -> "本轮已停止：完整资料超出本次输入或累计额度。已有线索保留，请缩小范围重新整理。"
                    "RETRY_NEEDED" -> "本轮请求未成功，已有进度保留；继续会明确重新请求。"
                    "DEPENDENCY_BLOCKED" -> "已有线索的意见发生变化，请重新整理这些月份。"
                    else -> null
                }
            } catch (failure: AnalysisProviderException) { failure.message ?: "请求未成功" }
        }) { notice -> passNotice = notice; showTask(id, 0) }
    }
    private fun candidateStatusTitle(value: ProfileCandidateStatus): String = when (value) {
        ProfileCandidateStatus.READY, ProfileCandidateStatus.PASS_LIMIT_REACHED -> "还有资料可以继续整理。"
        ProfileCandidateStatus.PASS_FINISHED -> "本次文字已处理完成，请逐条查看描述。"
        ProfileCandidateStatus.NEEDS_ATTENTION -> "已有描述等待确认、维护或处理核对问题。"
        ProfileCandidateStatus.NO_TEXT -> "没有可读取的文字资料。"
        ProfileCandidateStatus.NEEDS_RECOVERY -> "上次请求中断，需要明确恢复。"
        ProfileCandidateStatus.RETRY_NEEDED -> "请求未成功，已有进度保留。"
        ProfileCandidateStatus.BUDGET_EXHAUSTED -> "任务额度已用完，已有内容仍可查看。"
        ProfileCandidateStatus.CANCELLED -> "已停止，已有进度保留。"
        else -> "资料、时间或任务状态发生变化；旧进度不能继续，请重新建立任务。"
    }
}
