package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.tracelife.analysis.ReflectionFinding
import dev.tracelife.analysis.ReflectionRun
import dev.tracelife.analysis.ReflectionStatus
import dev.tracelife.analysis.ReflectionTarget
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.analysis.KnowledgeContextKind
import dev.tracelife.analysis.KnowledgeContextPlan
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.storage.ClaimFeedbackDecision

/** One capture journey for the answer and its detail page. All writes still cross the workflow. */
abstract class FindingCaptureActivity : ScreenActivity() {
    protected fun showKnowledgeContext(parent: LinearLayout, plan: KnowledgeContextPlan?, tag: String) {
        if (plan == null) return
        Ui.disclosure(parent, "参考的已有理解 · ${plan.entries.size}", tag) { details ->
            Ui.note(details, "从已查看的 ${plan.scanned} 项内容中找到 ${plan.matched} 项相关理解，本次选用 ${plan.entries.size} 项。")
            if (plan.entries.isEmpty()) Ui.note(details, "本次没有选入已有理解，会从所选范围的原始资料分析。")
            if (plan.matched > plan.entries.size) Ui.note(details, "另有 ${plan.matched - plan.entries.size} 项相关理解未纳入本次背景。")
            if (plan.moreAvailable) Ui.note(details, "还有未查看的已有内容，本次不代表全部知识库。")
            Ui.note(details, "这些是已沉淀的理解，不是新的原始证据；回答仍须回到记录和实际回看核对。")
            plan.entries.forEachIndexed { index, entry ->
                val item = Ui.card(details)
                Ui.sectionTitle(item, when (entry.kind) {
                    KnowledgeContextKind.KNOWLEDGE -> "已保存的理解"
                    KnowledgeContextKind.EXPERIENCE -> "经验：${entry.title}"
                    KnowledgeContextKind.PROFILE -> "已采用的个人描述"
                })
                Ui.body(item, entry.text)
                entry.userNote?.takeIf { it.isNotBlank() }?.let { Ui.note(item, "你的补充：$it") }
                Ui.note(item, "涉及 ${entry.scope.from} 至 ${entry.scope.through}")
                addButton(item, "查看这份知识与依据", "$tag:knowledge:$index") {
                    startActivity(Intent(this, KnowledgeActivity::class.java)
                        .putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID, entry.knowledgeId)
                        .putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_REVISION, entry.knowledgeRevision))
                }
                when (entry.kind) {
                    KnowledgeContextKind.EXPERIENCE -> addButton(item, "查看这条经验的变化", "$tag:experience:$index") {
                        startActivity(Intent(this, ExperienceActivity::class.java).putExtra(ExperienceActivity.EXTRA_THREAD_ID, entry.id))
                    }
                    KnowledgeContextKind.PROFILE -> addButton(item, "查看这条个人描述", "$tag:profile:$index") {
                        startActivity(Intent(this, ProfileActivity::class.java).putExtra("entryId", entry.id))
                    }
                    else -> Unit
                }
            }
        }
    }

    protected fun captureFinding(runId: String, target: ReflectionTarget, maxRequests: Int,
        onProgress: (ReflectionRun) -> Unit = {}, onComplete: (ReflectionRun) -> Unit,
    ) {
        runIo({
            val run = app.reflectionWorkflow.inspect(runId)
            val finding = requireNotNull(run.findings.singleOrNull { it.target == target }) { "这条发现已变化，请刷新回答" }
            if (finding.knowledgeEvidenceState == KnowledgeEvidenceState.CURRENT) CapturePreparation(run, finding, emptySet(), true)
            else {
                require(FindingCapture.canSave(run, finding)) { FindingCapture.blockReason(run, finding) }
                CapturePreparation(run, finding, app.reflectionWorkflow.suggestedTopics(runId, target),
                    FindingCapture.canReuseReview(finding) || app.deepSeekCredentials.isConfigured())
            }
        }) { prepared ->
            if (prepared.finding.knowledgeEvidenceState == KnowledgeEvidenceState.CURRENT) {
                onComplete(prepared.run)
            } else if (!prepared.configured) {
                confirm("先配置分析服务", "原文核对需要在设置中填写你的 DeepSeek 密钥。回答已经保留。", "前往设置") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            } else showCaptureDialog(prepared, maxRequests, onProgress, onComplete)
        }
    }

    private fun showCaptureDialog(prepared: CapturePreparation, maxRequests: Int,
        onProgress: (ReflectionRun) -> Unit, onComplete: (ReflectionRun) -> Unit,
    ) {
        val (run, finding, suggestedTopics) = prepared
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        Ui.body(layout, finding.text)
        val topics = labeledFieldIn(layout, "主题（可选）", "例如：学习、工作", suggestedTopics.joinToString("、"))
            .apply { tag = "captureFindingTopics" }
        if (suggestedTopics.isNotEmpty()) Ui.note(layout, "已带入来源记录的主题，可直接调整。")
        val content = ScrollView(this).apply { addView(layout) }
        val reviewed = FindingCapture.canReuseReview(finding)
        AlertDialog.Builder(this).setTitle("确认并保留这条发现？")
            .setMessage(if (reviewed) "原文核对仍然有效，将复用核对结果保存，不再发起模型请求。确认与原文依据分别保留。"
                else "确认后会重新核对原文，可能产生费用，本轮最多请求 $maxRequests 次。只有依据支持才保存；未完成可接着核对。")
            .setView(content).setNegativeButton("取消", null)
            .setPositiveButton(if (reviewed) "确认并保存" else "确认并核对") { _, _ ->
                val selectedTopics = topics.text.toString().split(",", "，", "、").map(String::trim).filter(String::isNotEmpty).toSet()
                if (selectedTopics.size > 30 || selectedTopics.any { it.length > 80 }) {
                    showError(IllegalArgumentException("最多填写 30 个主题，每个主题不超过 80 字")); return@setPositiveButton
                }
                runIo({
                    onProgress(run)
                    if (finding.feedback?.feedback?.decision != ClaimFeedbackDecision.CONFIRMED)
                        app.reflectionWorkflow.respond(run.id, finding.target, run.taskVersion, ClaimFeedbackDecision.CONFIRMED,
                            finding.feedback?.feedback?.version)
                    app.reflectionWorkflow.reviewAndSave(run.id, finding.target, app.deepSeekProvider, app.deepSeekProvider,
                        maxRequests = maxRequests, topics = selectedTopics, onProgress = onProgress)
                }) { pass ->
                    onComplete(pass.run.copy(status = pass.status))
                    if (pass.run.findings.singleOrNull { it.target == finding.target }?.knowledgeEvidenceState == KnowledgeEvidenceState.CURRENT)
                        toast("已保存到知识库，可继续加入经验或建立行动")
                    else ConversationPresentation.completionNotice(pass.status, pass.run.status)?.let { showError(IllegalStateException(it)) }
                }
            }.show()
    }

    /** Re-resolve the exact current knowledge revision at the user's click, never from a cached card. */
    protected fun savedFindingActions(parent: LinearLayout, knowledgeId: String, tagPrefix: String, beforeOpen: () -> Unit = {}) {
        addButton(parent, "加入经验线索", "$tagPrefix:experience") {
            beforeOpen()
            openSavedContinuation(knowledgeId, experience = true)
        }
        addButton(parent, "据此尝试一个行动", "$tagPrefix:action") {
            beforeOpen()
            openSavedContinuation(knowledgeId, experience = false)
        }
    }

    private fun openSavedContinuation(knowledgeId: String, experience: Boolean) {
        runIo({
            val knowledge = requireNotNull(app.knowledge.get(knowledgeId)) { "这条知识已不可用，请刷新回答" }
            require(knowledge.isCurrentRevision && knowledge.evidenceState == KnowledgeEvidenceState.CURRENT) {
                "这条知识需要更新，重新核对后再关联经验或建立行动"
            }
            knowledge.value
        }) { value ->
            startActivity(if (experience) Intent(this, ExperienceActivity::class.java)
                .putExtra(ExperienceActivity.EXTRA_KNOWLEDGE_ID, value.id).putExtra(ExperienceActivity.EXTRA_KNOWLEDGE_REVISION, value.revision)
            else Intent(this, ActionsActivity::class.java)
                .putExtra(ActionsActivity.EXTRA_ORIGIN_ID, value.id).putExtra(ActionsActivity.EXTRA_ORIGIN_REVISION, value.revision))
        }
    }

    private data class CapturePreparation(val run: ReflectionRun, val finding: ReflectionFinding, val topics: Set<String>, val configured: Boolean)
}

internal object FindingCapture {
    private val blocked = setOf(ReflectionStatus.STALE, ReflectionStatus.EXPIRED, ReflectionStatus.NEEDS_RECOVERY,
        ReflectionStatus.DEPENDENCY_BLOCKED, ReflectionStatus.CONFLICT, ReflectionStatus.BUDGET_EXHAUSTED)
    private val rejected = setOf(ClaimFeedbackDecision.REJECTED, ClaimFeedbackDecision.CORRECTED, ClaimFeedbackDecision.WITHDRAWN)
    private val unsupported = setOf(ReviewVerdict.UNSUPPORTED, ReviewVerdict.CONTRADICTED, ReviewVerdict.INSUFFICIENT_CONTEXT)

    fun canReuseReview(finding: ReflectionFinding) = finding.reviewVerdict == ReviewVerdict.SUPPORTED &&
        finding.feedback?.feedback?.decision == ClaimFeedbackDecision.CONFIRMED

    fun canSave(run: ReflectionRun, finding: ReflectionFinding): Boolean = run.archivedAt == null && run.canReviewFindings &&
        run.status !in blocked && finding.knowledgeEvidenceState != KnowledgeEvidenceState.CURRENT &&
        finding.feedback?.feedback?.decision !in rejected && finding.reviewVerdict !in unsupported &&
        finding.historicalFeedback.resolved == finding.historicalFeedback.total && finding.historicalFeedback.conflicts == 0

    fun blockReason(run: ReflectionRun, finding: ReflectionFinding): String = when {
        finding.historicalFeedback.resolved != finding.historicalFeedback.total || finding.historicalFeedback.conflicts > 0 -> "请先对照以前的意见，处理未完成项和冲突"
        run.archivedAt != null -> "请先恢复这次分析，再继续核对保存"
        run.status in blocked -> "这次分析需要更新，请先查看任务状态"
        !run.canReviewFindings -> "请先完成分析与汇总，再核对保存"
        finding.feedback?.feedback?.decision in rejected -> "这条发现已被纠正、否定或撤回，不能继续保存"
        else -> "现有依据尚不支持这条发现，不能保存"
    }

    fun status(finding: ReflectionFinding): String = when {
        finding.knowledgeId != null && finding.feedback?.feedback?.decision == ClaimFeedbackDecision.REJECTED -> "已保存 · 已否定"
        finding.knowledgeId != null && finding.feedback?.feedback?.decision == ClaimFeedbackDecision.CORRECTED -> "已保存 · 已纠正"
        finding.knowledgeId != null && finding.feedback?.feedback?.decision == ClaimFeedbackDecision.WITHDRAWN -> "已保存 · 已撤回"
        finding.knowledgeId != null && finding.knowledgeEvidenceState != KnowledgeEvidenceState.CURRENT -> "已保存 · 待更新"
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

    fun tone(finding: ReflectionFinding): Ui.Tone = when {
        finding.knowledgeId == null -> Ui.Tone.NEUTRAL
        finding.knowledgeEvidenceState == KnowledgeEvidenceState.CURRENT -> Ui.Tone.OK
        else -> Ui.Tone.WARN
    }
}
