package dev.tracelife.ui

import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import dev.tracelife.analysis.AnalysisCall
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.AnalysisStatus
import dev.tracelife.analysis.CallStatus
import dev.tracelife.analysis.ClaimDisposition
import dev.tracelife.analysis.ClaimFeedbackView
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.FeedbackEvidenceState
import dev.tracelife.analysis.OrganizationClaim
import dev.tracelife.analysis.OrganizationProgress
import dev.tracelife.analysis.OrganizationStatus
import dev.tracelife.analysis.ReviewIssue
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import java.time.ZoneId
import java.util.UUID

class ReviewActivity : ScreenActivity() {

    private var runId = ""
    private var selected: OrganizationClaim? = null

    private val preferences get() = getSharedPreferences("ui", MODE_PRIVATE)

    override fun showHome() {
        beginScreen("home", "核对与整理", showBack = false)
        runId = preferences.getString("lastRunId", "").orEmpty()
        note("模型输出始终是候选：先经模型核对，再由你确认、纠正或否定，最后经独立条件发布才可能成为正式知识。核对只是有限证据下的质量检查，不是事实认证。")
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "最近整理任务")
            val runIdField = fieldIn(card, "任务 ID（粘贴已有任务可继续）", runId)
            Ui.button(card, "打开任务", "openTask") {
                if (busy) return@button
                val id = runIdField.text.toString().trim()
                if (id.isEmpty()) { toast("请填写任务 ID"); return@button }
                openTask(id)
            }
            Ui.primary(card, "继续整理一轮", "continuePass") { if (!busy) startPass(runIdField.text.toString().trim()) }
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "新建整理任务")
            val questionField = fieldIn(card, "整理问题", "这段时间记录了哪些经历和可能的解释？")
            val fromField = fieldIn(card, "起始日期（YYYY-MM-DD）")
            val throughField = fieldIn(card, "结束日期（YYYY-MM-DD）")
            val zoneField = fieldIn(card, "时区", ZoneId.systemDefault().id)
            Ui.primary(card, "创建并打开任务", "createTask") {
                if (busy) return@primary
                val question = questionField.text.toString().trim()
                val scope = try {
                    require(question.isNotEmpty()) { "请填写整理问题" }
                    val from = parseDate(fromField.text.toString(), "起始日期")
                    val through = parseDate(throughField.text.toString(), "结束日期")
                    require(!through.isBefore(from)) { "结束日期不能早于起始日期" }
                    val zone = try {
                        ZoneId.of(zoneField.text.toString().trim()).id
                    } catch (_: java.time.DateTimeException) {
                        throw IllegalArgumentException("时区无效，例如 Asia/Shanghai")
                    }
                    AnalysisQuery(from = from, through = through, zoneId = zone)
                } catch (failure: IllegalArgumentException) {
                    showError(failure)
                    return@primary
                }
                runIo({ app.analysis.start(AnalysisRequest(question = question, scope = scope)) }) { task ->
                    rememberTask(task.stored.id)
                    showTask()
                }
            }
        }
    }

    override fun navigateBack() {
        if (busy) return
        when (screen) {
            "task" -> showHome()
            "claim" -> showTask()
            else -> finish()
        }
    }

    private fun rememberTask(id: String) {
        runId = id
        preferences.edit().putString("lastRunId", id).apply()
    }

    private fun openTask(id: String) {
        rememberTask(id)
        showTask()
    }

    private fun startPass(id: String) {
        if (id.isEmpty()) { toast("请先创建或打开任务"); return }
        if (!app.deepSeekCredentials.isConfigured()) {
            toast("尚未配置 DeepSeek 密钥，请先到设置页配置")
            return
        }
        confirm("继续整理一轮？", "将向 DeepSeek 发送本任务选中的原文片段，属于付费模型请求。一轮最多 5 次模型请求、100 次发布；本轮完成不代表全部整理完成。", "继续") {
            rememberTask(id)
            runIo({ app.automaticOrganizer.process(id, app.deepSeekProvider, app.deepSeekProvider) }) { report ->
                toast("本轮结束：${statusLabel(report.status)}；模型请求 ${report.modelRequestsThisPass} 次，新发布 ${report.publishedThisPass} 条")
                showTask(report.progress)
            }
        }
    }

    private data class TaskSnapshot(val progress: OrganizationProgress, val texts: Map<String, String>)

    private fun showTask() {
        beginScreen("task", "整理任务")
        runIo({
            val progress = app.automaticOrganizer.inspect(runId)
            TaskSnapshot(progress, claimTexts())
        }) { renderProgress(it) }
    }

    private fun showTask(progress: OrganizationProgress) {
        beginScreen("task", "整理任务")
        runIo({ claimTexts() }) { texts -> renderProgress(TaskSnapshot(progress, texts)) }
    }

    private fun claimTexts(): Map<String, String> = runCatching { app.analysis.task(runId) }.getOrNull()
        ?.state?.calls.orEmpty()
        .filter { it.status == CallStatus.SUCCEEDED && it.purpose.producesClaims }
        .flatMap { call -> call.proposal?.claims?.mapIndexed { index, claim -> "${call.batchKey}#$index" to claim.text }.orEmpty() }
        .toMap()

    private fun renderProgress(snapshot: TaskSnapshot) {
        val progress = snapshot.progress
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "任务状态")
            Ui.note(card, "任务 ID：${progress.runId}")
            chipsRow(card).also { row -> chip(row, statusLabel(progress.status), statusTone(progress.status)) }
            statusHint(progress.status)?.let { Ui.note(card, it) }
            if (progress.status == OrganizationStatus.NEEDS_RECOVERY) {
                Ui.button(card, "恢复中断任务", "recoverTask") {
                    if (!busy) runIo({ app.analysis.recoverInterrupted(runId) }) {
                        toast("已把在途调用恢复为可重试")
                        showTask()
                    }
                }
            }
            if (progress.status != OrganizationStatus.TASK_UNAVAILABLE) {
                Ui.primary(card, "继续整理一轮", "continuePass") { if (!busy) startPass(runId) }
            }
            Ui.button(card, "刷新", "refreshTask") { if (!busy) showTask() }
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "处理覆盖")
            val coverage = progress.coverage
            if (coverage == null) {
                Ui.note(card, "当前状态下不提供处理覆盖进度。")
            } else {
                Ui.note(card, "范围内记录 ${coverage.records} 条；文字已处理 ${coverage.completedTextCodePoints} / ${coverage.textCodePoints} 码点；剩余待处理片段 ${coverage.remainingTextFragments} 个。")
                Ui.note(card, "未读附件 ${coverage.unreadAttachments} 个：附件不会提供给模型，始终是独立缺口。")
                Ui.note(card, "不可读记录 ${coverage.unreadableRecords} 条：无法安全分片，原始记录仍保留。")
                Ui.note(card, "发生日期未知 ${coverage.unknownTimeRecords} 条；与范围部分交叠 ${coverage.overlappingRecords} 条：这两类不计为确定发生在范围内。")
                if (coverage.missingExactIds.isNotEmpty()) Ui.note(card, "指定但范围内缺失的记录 ID：${coverage.missingExactIds.joinToString("、")}")
                Ui.note(card, "以上缺口如实保留，不能视为确定未发生；覆盖完整也不证明理解完整。")
            }
            Ui.note(card, "反例搜索未穷尽：核对只检查明确提供的原文，不能视为已排除全部反例。")
        }
        section("陈述 ${progress.claims.size} 条（模型提案，候选状态）")
        if (progress.claims.isEmpty()) note("当前没有提案陈述。")
        ClaimDisposition.entries.forEach { disposition ->
            val items = progress.claims.filter { it.disposition == disposition }
            if (items.isEmpty()) return@forEach
            chipsRow(root).also { row -> chip(row, "${dispositionLabel(disposition)}（${items.size}）", dispositionTone(disposition)) }
            items.forEach { item ->
                val preview = snapshot.texts["${item.claim.batchKey}#${item.claim.claimIndex}"]?.take(60)
                val meta = buildList {
                    add("反馈版本 ${item.feedbackVersion}")
                    item.reviewVerdict?.let { add("核对：${verdictLabel(it)}") }
                    item.knowledgeRevision?.let { add("已发布修订 $it") }
                }.joinToString(" · ")
                listRow(root, "[${kindLabel(item.kind)}] ${preview ?: "（正文在详情中查看）"}\n$meta",
                    "claim:${item.claim.batchKey}:${item.claim.claimIndex}") {
                    selected = item
                    showClaim()
                }
            }
        }
    }

    private data class ClaimDetail(
        val text: String?,
        val runStatus: AnalysisStatus,
        val review: AnalysisCall?,
        val reviewNote: String?,
        val feedback: ClaimFeedbackView?,
    )

    private fun showClaim() {
        val item = selected ?: return showTask()
        beginScreen("claim", "陈述详情")
        runIo({
            val task = app.analysis.task(runId)
            val claim = app.analysis.claimRef(runId, item.claim.batchKey, item.claim.claimIndex)
            val text = task.state.calls.lastOrNull { call ->
                call.batchKey == claim.batchKey && call.status == CallStatus.SUCCEEDED && call.purpose.producesClaims
            }?.proposal?.claims?.getOrNull(claim.claimIndex)?.text
            val reviewResult = runCatching { app.analysis.currentReview(runId, claim) }
            val review = reviewResult.getOrNull()
            val reviewNote = if (reviewResult.isFailure) "任务已失效或过期，当前核对意见不可读取；历史台账仍可查看。"
                else if (review == null) "当前反馈版本下没有核对意见。" else null
            val feedback = app.analysisFeedback.forClaim(runId, claim)
            val fresh = app.automaticOrganizer.inspect(runId).claims.firstOrNull { it.claim == claim } ?: item
            fresh to ClaimDetail(text, task.status, review, reviewNote, feedback)
        }) { (fresh, detail) ->
            selected = fresh
            renderClaim(fresh, detail)
        }
    }

    private fun renderClaim(item: OrganizationClaim, detail: ClaimDetail) {
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "陈述")
            chipsRow(card).also { row ->
                chip(row, kindLabel(item.kind), Ui.Tone.NEUTRAL)
                chip(row, dispositionLabel(item.disposition), dispositionTone(item.disposition))
            }
            Ui.note(card, "反馈版本 ${item.feedbackVersion} · 任务状态：${analysisStatusLabel(detail.runStatus)}")
            Ui.body(card, detail.text ?: "未能取得陈述正文（对应提取调用已变化），请返回任务页查看最新状态。")
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "核对意见")
            detail.review?.review?.let { review ->
                chipsRow(card).also { row -> chip(row, "判定：${verdictLabel(review.verdict)}", verdictTone(review.verdict)) }
                if (review.issues.isNotEmpty()) Ui.note(card, "问题：${review.issues.joinToString("、") { issueLabel(it) }}")
                if (review.unknowns.isNotEmpty()) Ui.note(card, "缺口与未知：${review.unknowns.joinToString("；")}")
                Ui.note(card, "支持引用 ${review.supportingEvidenceIds.size} 段；反例引用 ${review.contradictingEvidenceIds.size} 段。")
            }
            detail.reviewNote?.let { Ui.note(card, it) }
            Ui.note(card, "核对只是有限证据下的模型质量检查，不是事实认证，也不等于发布许可。")
        }
        val feedback = detail.feedback
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "你的反馈")
            if (feedback == null) {
                Ui.note(card, "尚无你的反馈。")
            } else {
                chipsRow(card).also { row ->
                    chip(row, "决定：${decisionLabel(feedback.feedback.decision)}", decisionTone(feedback.feedback.decision))
                    chip(row, "证据状态：${evidenceStateLabel(feedback.evidenceState)}", feedbackEvidenceTone(feedback.evidenceState))
                }
                Ui.note(card, "版本 ${feedback.feedback.version} · 更新于 ${feedback.feedback.updatedAt}")
                feedback.feedback.userText?.let { Ui.body(card, "你的文字：$it") }
                if (feedback.evidenceState != FeedbackEvidenceState.CURRENT) {
                    Ui.note(card, "证据已失效，你的独立文字仍保留；可以维护文字、否定或撤回，不能再确认。")
                }
            }
        }
        val taskActive = detail.runStatus != AnalysisStatus.STALE && detail.runStatus != AnalysisStatus.EXPIRED
        if (taskActive) {
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "操作")
                Ui.primary(card, "模型核对（付费请求）", "reviewClaim") {
                    if (busy) return@primary
                    if (!app.deepSeekCredentials.isConfigured()) {
                        toast("尚未配置 DeepSeek 密钥，请先到设置页配置")
                        return@primary
                    }
                    confirm("执行模型核对？", "将向 DeepSeek 发送该陈述及其全部原始引用片段，属于付费模型请求。核对是质量检查，不是事实认证。", "核对") {
                        runIo({
                            app.analysis.reviewClaim(runId, "ui-review-" + UUID.randomUUID().toString(), item.claim,
                                provider = app.deepSeekProvider)
                        }) {
                            toast("核对完成：${verdictLabel(it.verdict)}")
                            showClaim()
                        }
                    }
                }
                if (feedback == null || feedback.evidenceState == FeedbackEvidenceState.CURRENT) {
                    Ui.button(card, "确认符合我的记录", "confirmClaim") {
                        if (!busy) confirm("确认该陈述？", "确认只是你的独立决定，不改变来源性质，也不是事实认证。", "确认") {
                            submitFeedback(item, ClaimFeedbackDecision.CONFIRMED, null)
                        }
                    }
                }
                Ui.button(card, "纠正", "correctClaim") { if (!busy) correctDialog(item, feedback) }
                Ui.dangerButton(card, "否定", "rejectClaim") {
                    if (!busy) confirm("否定该陈述？", "否定会阻止该陈述发布为知识；你的决定独立于模型意见。", "否定") {
                        submitFeedback(item, ClaimFeedbackDecision.REJECTED, null)
                    }
                }
                Ui.button(card, "撤回反馈", "withdrawClaim") {
                    if (!busy) confirm("撤回你的反馈？", "撤回保留决定记录，ID 不可复用的永久删除需另行操作；撤回后已有模型核对需重新执行。", "撤回") {
                        submitFeedback(item, ClaimFeedbackDecision.WITHDRAWN, null)
                    }
                }
                if (item.disposition == ClaimDisposition.READY_TO_PUBLISH) {
                    Ui.primary(card, "发布为知识", "publishClaim") { if (!busy) publishDialog(item) }
                }
            }
        } else {
            note("任务已失效或过期，不能再核对、确认或发布；请新建任务。你的独立文字仍可维护。")
        }
    }

    private fun correctDialog(item: OrganizationClaim, feedback: ClaimFeedbackView?) {
        val input = EditText(this).apply {
            hint = "你的独立文字（必填，最多 2000 字）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setText(feedback?.feedback?.userText.orEmpty())
            filters = arrayOf(InputFilter.LengthFilter(2_000))
        }
        AlertDialog.Builder(this).setTitle("纠正该陈述")
            .setMessage("纠正文字是你独立输入的内容，不会复制模型正文；知识正文仍与原陈述一致。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存纠正") { _, _ ->
                val userText = input.text.toString().trim()
                if (userText.isEmpty()) {
                    toast("纠正必须填写你的独立文字")
                    return@setPositiveButton
                }
                submitFeedback(item, ClaimFeedbackDecision.CORRECTED, userText)
            }.show()
    }

    private fun submitFeedback(item: OrganizationClaim, decision: ClaimFeedbackDecision, userText: String?) {
        runIo({
            val runVersion = app.analysis.task(runId).stored.version
            val existingVersion = app.analysisFeedback.forClaim(runId, item.claim)?.feedback?.version
            app.analysisFeedback.respond(runId, item.claim, expectedRunVersion = runVersion,
                decision = decision, userText = userText, expectedVersion = existingVersion)
        }) {
            toast("反馈已保存")
            showTask()
        }
    }

    private fun publishDialog(item: OrganizationClaim) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ReviewActivity, android.R.layout.simple_spinner_item,
                listOf("观察", "知识条目", "洞察")).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        layout.addView(categorySpinner)
        val topicsInput = EditText(this).apply { hint = "主题（可选，多个用逗号分隔）" }
        layout.addView(topicsInput)
        AlertDialog.Builder(this).setTitle("发布为知识")
            .setMessage("模型核对不是事实认证；发布前请确认陈述与你的记录一致。洞察类别要求你已确认该陈述。")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("发布") { _, _ ->
                val category = when (categorySpinner.selectedItemPosition) {
                    0 -> KnowledgeCategory.OBSERVATION
                    1 -> KnowledgeCategory.KNOWLEDGE_NOTE
                    else -> KnowledgeCategory.INSIGHT
                }
                val topics = topicsInput.text.toString().split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                runIo({
                    val runVersion = app.analysis.task(runId).stored.version
                    val review = requireNotNull(app.analysis.currentReview(runId, item.claim)) {
                        "当前反馈版本下没有核对意见，请先执行模型核对"
                    }
                    app.knowledge.publish(runId, item.claim, reviewKey = review.batchKey, category = category,
                        expectedRunVersion = runVersion, topics = topics)
                }) { published ->
                    toast("已发布（修订 ${published.revision}）")
                    showTask()
                }
            }.show()
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

    private fun statusTone(status: OrganizationStatus) = when (status) {
        OrganizationStatus.PASS_FINISHED -> Ui.Tone.OK
        OrganizationStatus.READY -> Ui.Tone.ACCENT
        OrganizationStatus.NO_DATA, OrganizationStatus.PASS_LIMIT_REACHED, OrganizationStatus.CANCELLED -> Ui.Tone.NEUTRAL
        else -> Ui.Tone.WARN
    }

    private fun dispositionTone(disposition: ClaimDisposition) = when (disposition) {
        ClaimDisposition.PUBLISHED -> Ui.Tone.OK
        ClaimDisposition.READY_TO_PUBLISH -> Ui.Tone.ACCENT
        ClaimDisposition.PENDING_REVIEW, ClaimDisposition.AWAITING_CONFIRMATION,
        ClaimDisposition.REVIEW_UNSUPPORTED, ClaimDisposition.REVIEW_INSUFFICIENT_CONTEXT -> Ui.Tone.WARN
        ClaimDisposition.REVIEW_CONTRADICTED, ClaimDisposition.FEEDBACK_BLOCKED,
        ClaimDisposition.FEEDBACK_DELETED, ClaimDisposition.KNOWLEDGE_DELETED -> Ui.Tone.ERROR
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

    private fun feedbackEvidenceTone(state: FeedbackEvidenceState) = when (state) {
        FeedbackEvidenceState.CURRENT -> Ui.Tone.OK
        else -> Ui.Tone.WARN
    }

    private fun statusLabel(status: OrganizationStatus) = when (status) {
        OrganizationStatus.READY -> "可继续整理（仍有待处理内容）"
        OrganizationStatus.PASS_FINISHED -> "本轮整理完成"
        OrganizationStatus.NEEDS_ATTENTION -> "需要注意（存在缺口或未发布项）"
        OrganizationStatus.NO_DATA -> "范围内没有资料"
        OrganizationStatus.PASS_LIMIT_REACHED -> "达到本轮次数上限，可继续下一轮"
        OrganizationStatus.CANCELLED -> "已取消"
        OrganizationStatus.RETRY_NEEDED -> "需要重试"
        OrganizationStatus.BUDGET_EXHAUSTED -> "任务预算已用尽"
        OrganizationStatus.NEEDS_RECOVERY -> "存在中断的在途调用，需要恢复"
        OrganizationStatus.STALE -> "来源已变化，任务失效"
        OrganizationStatus.EXPIRED -> "任务已过期"
        OrganizationStatus.TASK_UNAVAILABLE -> "任务不存在或已清理"
        OrganizationStatus.CONFLICT -> "读取期间来源或任务发生变化"
        OrganizationStatus.PUBLICATION_BLOCKED -> "发布被阻止（条件不满足或容量已达上限）"
    }

    private fun statusHint(status: OrganizationStatus): String? = when (status) {
        OrganizationStatus.PASS_FINISHED -> "范围内文字已处理完毕；这不代表全部事项都被记录或理解。"
        OrganizationStatus.PASS_LIMIT_REACHED -> "每轮最多 5 次模型请求、100 次发布；可再次继续整理。"
        OrganizationStatus.BUDGET_EXHAUSTED -> "该任务的调用次数或输入预算已用尽；如需继续请新建任务。"
        OrganizationStatus.NEEDS_RECOVERY -> "进程中断留下了在途调用；恢复后可重试，不会自动重复付费请求。"
        OrganizationStatus.STALE -> "任何记录增删改都会使旧任务失效；请新建任务重新整理，旧结果不代表当前资料。"
        OrganizationStatus.EXPIRED -> "任务已超过有效期，不再接受新取证与核对；历史台账仍可查看。"
        OrganizationStatus.TASK_UNAVAILABLE -> "该任务可能已被删除或清理；请新建任务。"
        OrganizationStatus.CONFLICT -> "请刷新或重新打开任务。"
        else -> null
    }

    private fun analysisStatusLabel(status: AnalysisStatus) = when (status) {
        AnalysisStatus.READY -> "就绪"
        AnalysisStatus.NEEDS_REVIEW -> "有待核对"
        AnalysisStatus.NEEDS_RETRY -> "需要重试"
        AnalysisStatus.IN_FLIGHT -> "有在途调用"
        AnalysisStatus.STALE -> "已失效（来源已变化）"
        AnalysisStatus.EXPIRED -> "已过期"
    }

    private fun dispositionLabel(disposition: ClaimDisposition) = when (disposition) {
        ClaimDisposition.PENDING_REVIEW -> "待核对"
        ClaimDisposition.READY_TO_PUBLISH -> "待发布"
        ClaimDisposition.PUBLISHED -> "已发布"
        ClaimDisposition.REVIEW_UNSUPPORTED -> "核对：证据不支持"
        ClaimDisposition.REVIEW_CONTRADICTED -> "核对：存在反例"
        ClaimDisposition.REVIEW_INSUFFICIENT_CONTEXT -> "核对：上下文不足"
        ClaimDisposition.AWAITING_CONFIRMATION -> "等待你的确认（可能解释须先确认）"
        ClaimDisposition.FEEDBACK_BLOCKED -> "已被你的反馈阻止发布"
        ClaimDisposition.FEEDBACK_DELETED -> "关联反馈已永久删除"
        ClaimDisposition.KNOWLEDGE_DELETED -> "对应知识已删除"
    }

    private fun kindLabel(kind: ClaimKind) = when (kind) {
        ClaimKind.REPORTED_EXPERIENCE -> "自述经历"
        ClaimKind.POSSIBLE_INTERPRETATION -> "可能解释"
    }

    private fun verdictLabel(verdict: ReviewVerdict) = when (verdict) {
        ReviewVerdict.SUPPORTED -> "有限证据下支持（非事实认证）"
        ReviewVerdict.UNSUPPORTED -> "所给证据不能支撑"
        ReviewVerdict.CONTRADICTED -> "存在直接反例"
        ReviewVerdict.INSUFFICIENT_CONTEXT -> "上下文不足"
    }

    private fun issueLabel(issue: ReviewIssue) = when (issue) {
        ReviewIssue.USER_REPORT_AS_FACT -> "自述被当作事实"
        ReviewIssue.SCOPE_OVERGENERALIZATION -> "局部扩大为整体"
        ReviewIssue.CAUSAL_OVERREACH -> "相关扩大为因果"
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

    private fun evidenceStateLabel(state: FeedbackEvidenceState) = when (state) {
        FeedbackEvidenceState.CURRENT -> "当前有效"
        FeedbackEvidenceState.STALE -> "来源已变化（失效）"
        FeedbackEvidenceState.EXPIRED -> "任务已过期（失效）"
        FeedbackEvidenceState.TASK_UNAVAILABLE -> "任务不存在（失效）"
        FeedbackEvidenceState.DEPENDENCY_CHANGED -> "关联反馈已变化（失效）"
    }
}
