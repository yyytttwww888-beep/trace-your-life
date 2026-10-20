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
import java.time.LocalDate
import java.util.UUID

class ReviewActivity : ScreenActivity() {

    private var runId = ""
    private var selected: OrganizationClaim? = null

    private val preferences get() = getSharedPreferences("ui", MODE_PRIVATE)

    override fun showHome() {
        beginScreen("home", "整理", showBack = false)
        primaryButton("分析经验与问题", "navReflection") {
            startActivity(android.content.Intent(this, ReflectionActivity::class.java))
        }
        runId = preferences.getString("lastRunId", "").orEmpty()
        note("从记录里，留下值得回看的经历和想法。整理出的内容可以逐条核对、补充，再收进知识库。")
        if (runId.isNotEmpty()) Ui.card(root).also { card ->
            Ui.sectionTitle(card, "上次的整理")
            Ui.note(card, "继续查看结果，或接着整理尚未完成的内容。")
            addButton(card, "查看整理结果", "recentTask") { openTask(runId) }
            primaryIn(card, "继续整理", "continuePass") { startPass(runId) }
        }
        Ui.disclosure(root, "通过编号打开", "openTaskDetails") { card ->
            val runIdField = labeledFieldIn(card, "整理编号", value = runId)
            addButton(card, "打开整理", "openTask") {
                val id = runIdField.text.toString().trim()
                if (id.isEmpty()) { toast("请填写整理编号"); return@addButton }
                openTask(id)
            }
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "开始新的整理")
            val questionField = labeledFieldIn(card, "这次想梳理什么？", value = "这段时间有哪些值得留下的经历和想法？")
            val fromField = labeledFieldIn(card, "开始日期", "YYYY-MM-DD", LocalDate.now().minusDays(6).toString())
            val throughField = labeledFieldIn(card, "结束日期", "YYYY-MM-DD", LocalDate.now().toString())
            lateinit var zoneField: EditText
            Ui.disclosure(card, "日期设置", "reviewDateSettings") { details ->
                zoneField = labeledFieldIn(details, "时区", "例如 Asia/Shanghai", ZoneId.systemDefault().id)
            }
            primaryIn(card, "准备整理", "createTask") {
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
                    return@primaryIn
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
        if (id.isEmpty()) { toast("请先选择要整理的内容"); return }
        if (!app.deepSeekCredentials.isConfigured()) {
            toast("尚未配置 DeepSeek 密钥，请先到设置页配置")
            return
        }
        confirm("开始在线整理？", "将把本次所选记录片段发送给 DeepSeek，可能产生费用。这次最多请求 5 次，符合条件的内容会收进知识库；可能的解释仍需你确认。未完成的内容会保留进度。", "开始整理") {
            rememberTask(id)
            runIo({ app.automaticOrganizer.process(id, app.deepSeekProvider, app.deepSeekProvider) }) { report ->
                toast("${statusLabel(report.status)} · 收录 ${report.publishedThisPass} 条 · 请求 ${report.modelRequestsThisPass} 次")
                showTask(report.progress)
            }
        }
    }

    private data class TaskSnapshot(val progress: OrganizationProgress, val texts: Map<String, String>)

    private fun showTask() {
        beginScreen("task", "整理进度")
        runIo({
            val progress = app.automaticOrganizer.inspect(runId)
            TaskSnapshot(progress, claimTexts())
        }) { renderProgress(it) }
    }

    private fun showTask(progress: OrganizationProgress) {
        beginScreen("task", "整理进度")
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
            Ui.sectionTitle(card, "进度")
            chipsRow(card).also { row -> chip(row, statusLabel(progress.status), statusTone(progress.status)) }
            statusHint(progress.status)?.let { Ui.note(card, it) }
            if (progress.status == OrganizationStatus.NEEDS_RECOVERY) {
                addButton(card, "恢复中断任务", "recoverTask") {
                    runIo({ app.analysis.recoverInterrupted(runId) }) {
                        toast("已恢复，可以继续整理")
                        showTask()
                    }
                }
            }
            if (progress.status != OrganizationStatus.TASK_UNAVAILABLE) {
                primaryIn(card, "继续整理", "continuePass") { startPass(runId) }
            }
            addButton(card, "刷新", "refreshTask") { showTask() }
        }
        Ui.disclosure(root, "本次整理范围与进度", "coverageDetails") { card ->
            Ui.note(card, "整理编号：${progress.runId}")
            val coverage = progress.coverage
            if (coverage == null) {
                Ui.note(card, "当前状态下不提供处理覆盖进度。")
            } else {
                Ui.note(card, "范围内记录 ${coverage.records} 条；已整理 ${coverage.completedTextCodePoints} / ${coverage.textCodePoints} 字；还有 ${coverage.remainingTextFragments} 段文字待整理。")
                Ui.note(card, "未读附件 ${coverage.unreadAttachments} 个：附件不会提供给模型，始终是独立缺口。")
                Ui.note(card, "不可读记录 ${coverage.unreadableRecords} 条：无法安全分片，原始记录仍保留。")
                Ui.note(card, "发生日期未知 ${coverage.unknownTimeRecords} 条；与范围部分交叠 ${coverage.overlappingRecords} 条：这两类不计为确定发生在范围内。")
                if (coverage.missingExactIds.isNotEmpty()) Ui.note(card, "指定但范围内缺失的记录 ID：${coverage.missingExactIds.joinToString("、")}")
                Ui.note(card, "以上缺口如实保留，不能视为确定未发生；覆盖完整也不证明理解完整。")
            }
            Ui.note(card, "反例搜索未穷尽：核对只检查明确提供的原文，不能视为已排除全部反例。")
        }
        section("整理结果 · ${progress.claims.size} 条")
        if (progress.claims.isEmpty()) Ui.card(root).also { emptyState(it, "暂时没有整理结果。开始整理后，内容会出现在这里。") }
        ClaimDisposition.entries.forEach { disposition ->
            val items = progress.claims.filter { it.disposition == disposition }
            if (items.isEmpty()) return@forEach
            chipsRow(root).also { row -> chip(row, "${dispositionLabel(disposition)}（${items.size}）", dispositionTone(disposition)) }
            items.forEach { item ->
                val preview = snapshot.texts["${item.claim.batchKey}#${item.claim.claimIndex}"]?.take(60)
                val meta = buildList {
                    item.reviewVerdict?.let { add("核对：${verdictLabel(it)}") }
                    item.knowledgeRevision?.let { add("已收进知识库") }
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
        beginScreen("claim", "整理结果")
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
            Ui.sectionTitle(card, "内容")
            chipsRow(card).also { row ->
                chip(row, kindLabel(item.kind), Ui.Tone.NEUTRAL)
                chip(row, dispositionLabel(item.disposition), dispositionTone(item.disposition))
            }
            Ui.note(card, analysisStatusLabel(detail.runStatus))
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
            Ui.note(card, "核对只依据已提供的记录，结果仍需你判断。")
        }
        val feedback = detail.feedback
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "你的反馈")
            if (feedback == null) {
                emptyState(card, "尚无你的反馈。")
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
                addButton(card, "核对原文", "reviewClaim") {
                    if (!app.deepSeekCredentials.isConfigured()) {
                        toast("尚未配置 DeepSeek 密钥，请先到设置页配置")
                        return@addButton
                    }
                    confirm("在线核对原文？", "将把这段内容和引用的原文发送给 DeepSeek，可能产生费用。核对意见仅供参考。", "核对") {
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
                    addButton(card, "确认符合我的记录", "confirmClaim") {
                        confirm("确认这段内容？", "确认表示内容与你的记录一致；仍保留原来的来源性质和核对意见。", "确认") {
                            submitFeedback(item, ClaimFeedbackDecision.CONFIRMED, null)
                        }
                    }
                }
                addButton(card, "纠正", "correctClaim") { correctDialog(item, feedback) }
                dangerIn(card, "否定", "rejectClaim") {
                    confirm("否定这段内容？", "这段内容将不能收进知识库；原文和你的决定会保留。", "否定") {
                        submitFeedback(item, ClaimFeedbackDecision.REJECTED, null)
                    }
                }
                addButton(card, "撤回反馈", "withdrawClaim") {
                    confirm("撤回你的反馈？", "撤回会保留已有记录。之后需要重新核对，相关已收录内容也会失效。", "撤回") {
                        submitFeedback(item, ClaimFeedbackDecision.WITHDRAWN, null)
                    }
                }
                if (item.disposition == ClaimDisposition.READY_TO_PUBLISH) {
                    primaryIn(card, "收进知识库", "publishClaim") { publishDialog(item) }
                }
            }
        } else {
            note("来源已变化或本次整理已过期，请新建整理后再核对。你的补充文字会保留。")
        }
    }

    private fun correctDialog(item: OrganizationClaim, feedback: ClaimFeedbackView?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        Ui.fieldLabel(layout, "你想怎样纠正？")
        val input = EditText(this).apply {
            hint = "最多 2000 字"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setText(feedback?.feedback?.userText.orEmpty())
            filters = arrayOf(InputFilter.LengthFilter(2_000))
        }
        layout.addView(input)
        AlertDialog.Builder(this).setTitle("纠正该陈述")
            .setMessage("你的文字会单独保存，不会直接替换原来的内容。相关结果需要重新核对。")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存纠正") { _, _ ->
                val userText = input.text.toString().trim()
                if (userText.isEmpty()) {
                    toast("请写下你的纠正")
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
        Ui.fieldLabel(layout, "类别")
        val categorySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@ReviewActivity, android.R.layout.simple_spinner_item,
                listOf("观察", "知识条目", "洞察")).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        layout.addView(categorySpinner)
        Ui.fieldLabel(layout, "主题（可选）")
        val topicsInput = EditText(this).apply { hint = "多个用逗号分隔" }
        layout.addView(topicsInput)
        AlertDialog.Builder(this).setTitle("收进知识库")
            .setMessage("请确认内容与你的记录一致。保存为洞察前，需要先明确确认；核对意见仅供参考。")
            .setView(layout)
            .setNegativeButton("取消", null)
            .setPositiveButton("收录") { _, _ ->
                val category = when (categorySpinner.selectedItemPosition) {
                    0 -> KnowledgeCategory.OBSERVATION
                    1 -> KnowledgeCategory.KNOWLEDGE_NOTE
                    else -> KnowledgeCategory.INSIGHT
                }
                val topics = topicsInput.text.toString().split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                runIo({
                    val runVersion = app.analysis.task(runId).stored.version
                    val review = requireNotNull(app.analysis.currentReview(runId, item.claim)) {
                        "反馈已更新，请先重新核对原文"
                    }
                    app.knowledge.publish(runId, item.claim, reviewKey = review.batchKey, category = category,
                        expectedRunVersion = runVersion, topics = topics)
                }) { _ ->
                    toast("已收进知识库")
                    showTask()
                }
            }.show()
    }

    private fun chipsRow(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        parent.addView(this)
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
        OrganizationStatus.READY -> "还有内容待整理"
        OrganizationStatus.PASS_FINISHED -> "本轮整理完成"
        OrganizationStatus.NEEDS_ATTENTION -> "有待查看的内容"
        OrganizationStatus.NO_DATA -> "范围内没有资料"
        OrganizationStatus.PASS_LIMIT_REACHED -> "本轮次数已用完"
        OrganizationStatus.CANCELLED -> "已取消"
        OrganizationStatus.RETRY_NEEDED -> "需要重试"
        OrganizationStatus.BUDGET_EXHAUSTED -> "本次整理额度已用完"
        OrganizationStatus.NEEDS_RECOVERY -> "上次整理中断"
        OrganizationStatus.STALE -> "来源已变化，任务失效"
        OrganizationStatus.EXPIRED -> "任务已过期"
        OrganizationStatus.TASK_UNAVAILABLE -> "任务不存在或已清理"
        OrganizationStatus.CONFLICT -> "内容已变化，请刷新"
        OrganizationStatus.PUBLICATION_BLOCKED -> "暂时无法收录"
    }

    private fun statusHint(status: OrganizationStatus): String? = when (status) {
        OrganizationStatus.PASS_FINISHED -> "范围内文字已处理完毕；这不代表全部事项都被记录或理解。"
        OrganizationStatus.PASS_LIMIT_REACHED -> "每轮最多请求 5 次、收录 100 条。已有进度保留，可以继续整理。"
        OrganizationStatus.BUDGET_EXHAUSTED -> "本次整理已达到次数或内容上限。已有结果保留，如需继续请新建整理。"
        OrganizationStatus.NEEDS_RECOVERY -> "上次请求未完成，恢复后可再次尝试；再次请求可能产生费用。"
        OrganizationStatus.STALE -> "任何记录增删改都会使旧任务失效；请新建任务重新整理，旧结果不代表当前资料。"
        OrganizationStatus.EXPIRED -> "这次整理已过期，可以查看历史结果；如需核对，请新建整理。"
        OrganizationStatus.TASK_UNAVAILABLE -> "该任务可能已被删除或清理；请新建任务。"
        OrganizationStatus.CONFLICT -> "请刷新或重新打开任务。"
        else -> null
    }

    private fun analysisStatusLabel(status: AnalysisStatus) = when (status) {
        AnalysisStatus.READY -> "就绪"
        AnalysisStatus.NEEDS_REVIEW -> "有待核对"
        AnalysisStatus.NEEDS_RETRY -> "需要重试"
        AnalysisStatus.IN_FLIGHT -> "整理中"
        AnalysisStatus.STALE -> "已失效（来源已变化）"
        AnalysisStatus.EXPIRED -> "已过期"
    }

    private fun dispositionLabel(disposition: ClaimDisposition) = when (disposition) {
        ClaimDisposition.PENDING_REVIEW -> "待核对"
        ClaimDisposition.READY_TO_PUBLISH -> "可收录"
        ClaimDisposition.PUBLISHED -> "已收录"
        ClaimDisposition.REVIEW_UNSUPPORTED -> "核对：证据不支持"
        ClaimDisposition.REVIEW_CONTRADICTED -> "核对：存在反例"
        ClaimDisposition.REVIEW_INSUFFICIENT_CONTEXT -> "核对：上下文不足"
        ClaimDisposition.AWAITING_CONFIRMATION -> "等你确认"
        ClaimDisposition.FEEDBACK_BLOCKED -> "你的反馈已阻止收录"
        ClaimDisposition.FEEDBACK_DELETED -> "关联反馈已永久删除"
        ClaimDisposition.KNOWLEDGE_DELETED -> "对应知识已删除"
    }

    private fun kindLabel(kind: ClaimKind) = when (kind) {
        ClaimKind.REPORTED_EXPERIENCE -> "自述经历"
        ClaimKind.POSSIBLE_INTERPRETATION -> "可能解释"
    }

    private fun verdictLabel(verdict: ReviewVerdict) = when (verdict) {
        ReviewVerdict.SUPPORTED -> "现有记录支持"
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
