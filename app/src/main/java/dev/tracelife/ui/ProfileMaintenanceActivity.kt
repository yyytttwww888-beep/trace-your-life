package dev.tracelife.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import dev.tracelife.analysis.*
import dev.tracelife.profile.*
import dev.tracelife.storage.ClaimFeedbackDecision
import java.util.UUID

/** A human review surface over existing checked maintenance/publication operations. */
class ProfileMaintenanceActivity : ScreenActivity() {
    private lateinit var runId: String
    private lateinit var claim: ClaimRef
    private var offset = 0
    private var reason = ""
    private var reasonInput: EditText? = null
    private var feedbackText = ""
    private var feedbackDraftInitialized = false
    private var feedbackInput: EditText? = null
    private var appliedProfile: String? = null
    private var operationId = UUID.randomUUID().toString()

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        runId = requireNotNull(intent.getStringExtra("runId"))
        claim = ClaimRef(requireNotNull(intent.getStringExtra("batchKey")), intent.getIntExtra("claimIndex", -1), requireNotNull(intent.getStringExtra("claimSha256")))
        offset = savedInstanceState?.getInt("offset") ?: 0
        reason = savedInstanceState?.getString("reason").orEmpty()
        feedbackText = savedInstanceState?.getString("feedbackText").orEmpty()
        feedbackDraftInitialized = savedInstanceState?.getBoolean("feedbackDraftInitialized") ?: false
        appliedProfile = savedInstanceState?.getString("appliedProfile")
        operationId = savedInstanceState?.getString("operationId") ?: operationId
    }
    override fun onSaveInstanceState(outState: Bundle) {
        captureInputs()
        outState.putInt("offset", offset)
        outState.putString("reason", reason)
        outState.putString("feedbackText", feedbackText)
        outState.putBoolean("feedbackDraftInitialized", feedbackDraftInitialized)
        outState.putString("appliedProfile", appliedProfile)
        outState.putString("operationId", operationId)
        super.onSaveInstanceState(outState)
    }
    private fun captureInputs() {
        reasonInput?.let { reason = it.text.toString() }
        feedbackInput?.let { feedbackText = it.text.toString(); feedbackDraftInitialized = true }
    }
    override fun navigateBack() {
        if (busy) return
        captureInputs()
        if (screen in setOf("relation", "evidence")) showHome() else finish()
    }
    override fun showHome() {
        captureInputs()
        reasonInput = null; feedbackInput = null
        appliedProfile?.let { showApplied(it); return }
        beginScreen("maintenance", "把新描述放回经历里")
        runIo({
            val task = app.analysis.task(runId)
            require(app.analysis.claimRef(runId, claim.batchKey, claim.claimIndex) == claim) { "描述已经变化" }
            val generation = task.state.calls.last { it.batchKey == claim.batchKey && it.status == CallStatus.SUCCEEDED }
            val proposal = requireNotNull(generation.proposal).claims[claim.claimIndex]
            val progress = app.profileMaintenance.inspect(runId, claim, offset, 20)
            val feedback = app.analysisFeedback.forClaim(runId, claim)
            MaintenanceView(task, proposal, generation.evidence, progress, feedback)
        }) { view -> render(view) }
    }
    private data class MaintenanceView(val task: AnalysisTask, val proposal: ProposedClaim, val evidence: List<EvidenceReceipt>,
        val progress: ProfileMaintenanceProgress, val feedback: ClaimFeedbackView?)

    private fun render(view: MaintenanceView) {
        val progress = view.progress
        val task = view.task
        val scope = task.state.request.scope
        val description = card()
        Ui.body(description, view.proposal.text)
        Ui.note(description, "${scope.from} 至 ${scope.through} · ${profileDimensionTitle(requireNotNull(task.state.request.profileTarget).dimension)}")
        Ui.note(description, "这是一条待核对的理解。历史描述用于对照，不能当作新的原始依据。")
        Ui.disclosure(description, "查看本次生成的原文依据", "maintenanceEvidence") { rows ->
            view.evidence.forEachIndexed { index, receipt ->
                listRow(rows, "原文 ${index + 1} · 第 ${receipt.revision} 版 · ${receipt.end - receipt.start} 字", "maintenanceEvidence:${receipt.id}") {
                    captureInputs(); showEvidence(receipt, receipt.start)
                }
            }
        }
        val status = card()
        Ui.sectionTitle(status, maintenanceStatusTitle(progress.status))
        Ui.note(status, "历史对照 ${progress.comparedReferences}／${progress.totalReferences} 项 · 已扫描 ${progress.scannedFragments} 段 · 反例 ${progress.counterevidenceFragments} 段 · 缺上下文 ${progress.contextRequiredFragments} 段")
        progress.coverage?.let { coverage ->
            Ui.note(status, "本范围已读 ${coverage.completedTextCodePoints}／${coverage.textCodePoints} 字；未读附件 ${coverage.unreadAttachments} 个、日期未知 ${coverage.unknownTimeRecords} 条、跨期 ${coverage.overlappingRecords} 条。")
        }
        Ui.note(status, "已使用 ${task.state.calls.size}／${task.state.request.limits.maxCalls} 次请求。每轮最多 5 次，完成的步骤会保留；扫描完整不等于生活记录完整。")
        if (task.stored.archivedAt != null) {
            Ui.note(status, "这项整理已归档，恢复后才能继续核对。")
            addButton(status, "恢复整理", "restoreMaintenance") {
                runIo({ app.repository.unarchiveAnalysisRun(runId, task.stored.version) }) { showHome() }
            }
        } else if (progress.status == ProfileMaintenanceStatus.PLAN_REQUIRED) {
            primaryIn(status, "建立历史对照", "beginMaintenance") {
                runIo({ app.profileMaintenance.begin(runId, claim) }) { showHome() }
            }
        } else if (task.state.calls.any { it.status == CallStatus.IN_FLIGHT }) {
            addButton(status, "恢复中断进度", "recoverMaintenance") {
                confirm("确认上次请求已停止？", "仅保存中断状态，不会重新发送。", "恢复") {
                    runIo({ app.analysis.recoverInterrupted(runId) }) { showHome() }
                }
            }
        } else if (progress.status in setOf(ProfileMaintenanceStatus.RELATIONS_READY, ProfileMaintenanceStatus.SCAN_READY,
                ProfileMaintenanceStatus.FINAL_READY, ProfileMaintenanceStatus.PASS_LIMIT_REACHED, ProfileMaintenanceStatus.RETRY_NEEDED)) {
            primaryIn(status, "继续对照与原文核对", "processMaintenance") {
                captureInputs()
                confirm("继续检查这条描述？", "将分批检查 ${scope.from} 至 ${scope.through} 的原文和相关历史意见，本轮最多 5 次请求，可能产生费用。不会自动保存或采用描述。", "继续") {
                    runIo({
                        require(app.deepSeekCredentials.isConfigured()) { "请先在设置中配置 DeepSeek 密钥" }
                        try {
                            app.profileMaintenance.process(runId, claim, app.deepSeekProvider, app.deepSeekProvider, app.deepSeekProvider,
                                maxModelRequests = 5, fragmentsPerBatch = 4)
                            null
                        } catch (failure: AnalysisProviderException) { failure.message ?: "请求未成功" }
                    }) { failure -> failure?.let { toast(it) }; showHome() }
                }
            }
        }
        if (progress.references.isNotEmpty()) {
            val rows = card()
            Ui.sectionTitle(rows, "与过去的描述对照")
            progress.references.forEachIndexed { index, item ->
                listRow(rows, "${offset + index + 1}. ${if (item.reference.kind == ProfileMaintenanceReferenceKind.PROFILE) "已有描述" else "历史意见"} · ${relationTitle(item.relation)}", "maintenanceRelation:${item.reference.id}") {
                    captureInputs(); showRelation(progress, item.reference)
                }
            }
        }
        if (offset > 0) button("上一页对照", "maintenancePrevious") { captureInputs(); offset = (offset - 20).coerceAtLeast(0); showHome() }
        progress.nextOffset?.let { next -> button("下一页对照", "maintenanceNext") { captureInputs(); offset = next; showHome() } }
        if (task.stored.archivedAt == null && progress.status !in setOf(ProfileMaintenanceStatus.STALE, ProfileMaintenanceStatus.DEPENDENCY_BLOCKED,
                ProfileMaintenanceStatus.TASK_UNAVAILABLE, ProfileMaintenanceStatus.EXPIRED, ProfileMaintenanceStatus.CLOCK_ROLLBACK)) {
            val feedback = card()
            Ui.sectionTitle(feedback, "我的判断")
            val confirmed = view.feedback?.feedback?.decision == ClaimFeedbackDecision.CONFIRMED
            Ui.note(feedback, if (confirmed) "你已确认这段描述；最终核对必须匹配这次确认。" else "确认表示认可原句，不会改写内容或自动采用。确认后需再次完成最终核对。")
            feedbackInput = labeledFieldIn(feedback, "我的补充或纠正（可选）", value = if (feedbackDraftInitialized) feedbackText else view.feedback?.feedback?.userText.orEmpty(), multiline = true).apply { tag = "maintenanceFeedbackText" }
            if (!confirmed) addButton(feedback, "确认这段描述", "confirmMaintenanceClaim") { respond(view, ClaimFeedbackDecision.CONFIRMED) }
            addButton(feedback, "保存纠正", "correctMaintenanceClaim") { respond(view, ClaimFeedbackDecision.CORRECTED) }
            addButton(feedback, "不认可这段描述", "rejectMaintenanceClaim") { respond(view, ClaimFeedbackDecision.REJECTED) }
            if (confirmed && progress.status == ProfileMaintenanceStatus.READY_TO_APPLY) {
                val choices = card()
                Ui.sectionTitle(choices, "选择怎样保留")
                Ui.note(choices, "保存新描述或修改原描述后，仍是待采用。加入「关于我」需要另行选择。")
                reasonInput = labeledFieldIn(choices, "重新考虑历史意见的理由（有相关限制时必填）", value = reason, multiline = true).apply { tag = "maintenanceOverrideReason" }
                // Reading one registered page is deliberately separate from applying a suggestion.
                runIo({ app.profileMaintenance.suggestions(progress) }) { suggestions ->
                    suggestions.filter { it.suggestedAction != null }.forEach { suggestion ->
                        val action = requireNotNull(suggestion.suggestedAction)
                        suggestion.reason?.let { Ui.body(choices, it) }
                        if (suggestion.requiresOverrideReason) Ui.note(choices, "涉及以前的纠正或否定，请说明现在重新考虑的理由。")
                        addButton(choices, actionTitle(action), "applyMaintenance:${action.name}:${suggestion.reference?.id ?: "new"}") {
                            captureInputs()
                            if (suggestion.requiresOverrideReason && reason.isBlank()) {
                                showError(IllegalArgumentException("请填写重新考虑的理由")); return@addButton
                            }
                            confirm("${actionTitle(action)}？", if (action == ProfileMaintenanceAction.KEEP) "保留原描述和原采用状态。" else "保存依据与变化历史，保持为待采用描述。", "确定") {
                                runIo({ app.profileMaintenance.apply(runId, claim, requireNotNull(progress.taskVersion), requireNotNull(progress.planId),
                                    action, suggestion.reference?.id, suggestion.reference?.version, operationId, reason.ifBlank { null }) }) { result ->
                                    appliedProfile = result.profile?.id
                                    result.profile?.id?.let(::showApplied) ?: showHome()
                                }
                            }
                        }
                    }
                    if (suggestions.none { it.suggestedAction != null }) Ui.note(choices, "本页没有可以应用的建议。请查看其他页或先处理相关描述。")
                }
            }
        }
        button("刷新当前进度", "refreshMaintenance") { showHome() }
    }
    private fun respond(view: MaintenanceView, decision: ClaimFeedbackDecision) {
        captureInputs()
        if (decision == ClaimFeedbackDecision.CORRECTED && feedbackText.isBlank()) {
            showError(IllegalArgumentException("请写下需要纠正的内容")); return
        }
        runIo({ app.analysisFeedback.respond(runId, claim, view.task.stored.version, decision,
            feedbackText.ifBlank { null }, view.feedback?.feedback?.version) }) { showHome() }
    }
    private fun showRelation(progress: ProfileMaintenanceProgress, reference: ProfileMaintenanceReference) {
        beginScreen("relation", "历史描述与对照")
        runIo({ app.profileMaintenance.relation(progress, reference) to app.repository.profileMaintenanceReference(reference) }) { (result, content) ->
            content.claim?.let { text(it.text) } ?: note("原描述已不可用，不猜测已删除的内容。")
            content.userText?.let { text("我的补充：$it") }
            content.feedbackUserText?.let { text("历史意见：$it") }
            note(if (content.sourceAvailable) "原来源仍可定位；本页内容只作历史约束。" else "原来源已失效或不可用，历史意见仍保留。")
            result?.let {
                section(relationTitle(it.relation)); text(it.reason)
                it.unknowns.forEach(::note)
            } ?: note("尚未完成这一项比较。")
        }
    }
    private fun showEvidence(receipt: EvidenceReceipt, start: Int) {
        beginScreen("evidence", "本次依据原文")
        runIo({
            val task = app.analysis.task(runId)
            require(receipt in task.state.calls.flatMap { it.evidence }) { "这段引用未在任务中登记" }
            app.repository.analysisSourceFragment(task.stored.source, task.state.request.scope, receipt.recordId, receipt.field,
                start, minOf(1024, receipt.end - start))
        }) { fragment ->
            note("第 ${fragment.revision} 版 · 引用内已读 ${fragment.end - receipt.start}／${receipt.end - receipt.start} 字")
            text(fragment.text)
            if (fragment.end < receipt.end) button("继续读取", "maintenanceEvidenceNext") { showEvidence(receipt, fragment.end) }
        }
    }
    private fun showApplied(id: String) {
        beginScreen("applied", "已保留这次选择")
        note("原文依据和变化过程已保存。你可以查看描述，再决定是否用于「关于我」。")
        primaryButton("查看描述与采用状态", "openAppliedProfile") {
            startActivity(Intent(this, ProfileActivity::class.java).putExtra("entryId", id))
        }
    }
    private fun relationTitle(value: ProfileRelation?): String = when (value) {
        ProfileRelation.SAME -> "相同理解"
        ProfileRelation.SUPPLEMENT -> "补充条件"
        ProfileRelation.CONFLICT -> "存在冲突"
        ProfileRelation.UNRELATED -> "无直接关联"
        ProfileRelation.UNCERTAIN -> "尚不确定"
        null -> "等待对照"
    }
    private fun actionTitle(value: ProfileMaintenanceAction): String = when (value) {
        ProfileMaintenanceAction.NEW -> "保存为新的待采用描述"
        ProfileMaintenanceAction.KEEP -> "保留原来的描述"
        ProfileMaintenanceAction.NARROW -> "收窄原描述的适用条件"
        ProfileMaintenanceAction.REPLACE -> "用新描述替代原描述"
    }
    companion object {
        fun intent(context: Context, runId: String, claim: ClaimRef): Intent = Intent(context, ProfileMaintenanceActivity::class.java)
            .putExtra("runId", runId).putExtra("batchKey", claim.batchKey)
            .putExtra("claimIndex", claim.claimIndex).putExtra("claimSha256", claim.claimSha256)
    }
}
