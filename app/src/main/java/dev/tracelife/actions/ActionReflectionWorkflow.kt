package dev.tracelife.actions

import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.knowledge.KnowledgeView
import dev.tracelife.storage.*
import dev.tracelife.transfer.StaleSnapshotException
import java.util.UUID

sealed interface ActionReflectionTarget {
    data class Claim(val ref: ClaimRef) : ActionReflectionTarget
    data class Reduction(val ref: ReductionStatementRef) : ActionReflectionTarget
}

data class ActionReflectionFinding(val target: ActionReflectionTarget, val text: String,
    val feedback: ClaimFeedbackView?, val verdict: ReviewVerdict?, val saved: KnowledgeView?,
    val reviewProgress: PeriodReductionReviewProgress?)
data class ActionReflectionRun(val task: AnalysisTask, val progress: ActionPeriodProgress,
    val findings: List<ActionReflectionFinding>, val unknowns: List<String>) {
    val canReview: Boolean get() = task.stored.archivedAt == null && task.status !in setOf(
        AnalysisStatus.STALE, AnalysisStatus.EXPIRED, AnalysisStatus.IN_FLIGHT) && progress.integration in setOf(
        ActionPeriodIntegration.SINGLE_CALL_ALL_AVAILABLE_INPUTS, ActionPeriodIntegration.TREE_INTEGRATED)
}
data class ActionReflectionPass(val run: ActionReflectionRun, val requests: Int, val status: String)
data class ActionReflectionSources(val records: List<EvidenceReceipt>, val actions: List<ActionEvidenceReceipt>)
data class ActionReflectionSourcePage(val records: List<EvidenceReceipt>, val actions: List<ActionEvidenceReceipt>,
    val total: Int, val nextOffset: Int?)

/** Phone workflow only: bounded calls share one allowance; decisions and publication stay explicit. */
class ActionReflectionWorkflow(private val repository: LifeRepository, private val analysis: ControlledAnalysis,
    private val feedback: AnalysisFeedback = AnalysisFeedback(repository),
    private val knowledge: KnowledgeLibrary = KnowledgeLibrary(repository)) {
    private val period = ActionPeriodReflection(analysis)
    private val review = PeriodReductionReviewProcessor(analysis)

    fun start(scope: AnalysisQuery, id: String = PREFIX + UUID.randomUUID(),
        limits: AnalysisLimits = AnalysisLimits()): ActionReflectionRun {
        require(id.startsWith(PREFIX))
        period.start(AnalysisRequest(QUESTION, scope, limits), id)
        return inspect(id)
    }

    /** SQL filters the task directory before paging; no task body is loaded for other features. */
    fun history(offset: Int = 0, limit: Int = 20): List<AnalysisTask> =
        repository.analysisRunIndex(prefix = PREFIX, offset = offset, limit = limit).map { task(it.id) }

    fun inspect(id: String): ActionReflectionRun {
        val task = task(id)
        val progress = normalize(period.inspect(id))
        val leaves = task.state.calls.filter { it.purpose == CallPurpose.ACTION_SYNTHESIS && it.status == CallStatus.SUCCEEDED }
        val leafRefs = leaves.map(ReductionGraph::nodeRef).toSet()
        // Keep the complete historical root readable after source/feedback changes. It remains a
        // historical draft, never a current reading or publication token.
        val root = task.state.calls.lastOrNull { it.purpose == CallPurpose.REDUCTION && it.status == CallStatus.SUCCEEDED &&
            ReductionGraph.leafRefs(task.state, ReductionGraph.nodeRef(it)).toSet() == leafRefs }
        val current = task.status !in setOf(AnalysisStatus.STALE, AnalysisStatus.EXPIRED, AnalysisStatus.IN_FLIGHT) &&
            (root == null || progress.root == ReductionGraph.nodeRef(root))
        val findings = if (root != null) {
            requireNotNull(root.reductionOutput).statements.mapIndexed { index, value ->
                finding(task, ActionReflectionTarget.Reduction(ReductionGraph.statementRef(root, index)), value.text, current)
            }
        } else leaves.flatMap { call -> requireNotNull(call.proposal).claims.mapIndexed { index, value ->
                finding(task, ActionReflectionTarget.Claim(ClaimRef(call.batchKey, index, AnalysisRunCodec.claimSha256(value))), value.text, current)
            } }
        val unknowns = if (root != null) requireNotNull(root.reductionOutput).unknowns else
            task.state.calls.filter { it.purpose == CallPurpose.ACTION_SYNTHESIS && it.status == CallStatus.SUCCEEDED }
                .flatMap { it.proposal?.unknowns.orEmpty() }.distinct()
        ensureUnchanged(task)
        return ActionReflectionRun(task, progress, findings, unknowns)
    }

    fun process(id: String, provider: AnalysisProvider, maxRequests: Int = 3,
        inputsPerBatch: Int = 16, cancelled: () -> Boolean = { false }): ActionReflectionPass {
        require(maxRequests in 1..100)
        require(task(id).stored.archivedAt == null) { "请先恢复归档任务" }
        val generated = period.process(id, provider, maxBatches = maxRequests, inputsPerBatch = inputsPerBatch, cancelled = cancelled)
        var requests = generated.modelRequestsThisPass
        var status = generated.status.name
        if (normalize(generated.progress).integration == ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED &&
            requests < maxRequests && generated.status in setOf(SynthesisProcessingStatus.INPUTS_PROCESSED,
                SynthesisProcessingStatus.INPUTS_PROCESSED_WITH_GAPS)) {
            val reduced = period.reduce(id, provider, maxRequests - requests, cancelled = cancelled)
            requests += reduced.modelRequestsThisPass
            status = reduced.status.name
        }
        return ActionReflectionPass(inspect(id), requests, status)
    }

    fun respond(id: String, target: ActionReflectionTarget, expectedRunVersion: Int,
        decision: ClaimFeedbackDecision, userText: String? = null): ActionReflectionRun {
        val run = requireFinding(id, target)
        require(run.task.stored.version == expectedRunVersion) { "分析已有更新，请重新打开" }
        val previous = decision(id, target)
        when (target) {
            is ActionReflectionTarget.Claim -> feedback.respond(id, target.ref, expectedRunVersion, decision, userText,
                previous?.feedback?.version)
            is ActionReflectionTarget.Reduction -> feedback.respondReduction(id, target.ref, expectedRunVersion, decision,
                userText, previous?.feedback?.version)
        }
        return inspect(id)
    }

    fun reviewAndSave(id: String, target: ActionReflectionTarget, scanProvider: SemanticReductionScanProvider,
        reviewProvider: SemanticReviewProvider, maxRequests: Int = 3, topics: Set<String> = emptySet(),
        cancelled: () -> Boolean = { false }): ActionReflectionPass {
        require(maxRequests in 1..100)
        requireFinding(id, target)
        val response = requireNotNull(decision(id, target)) { "请先明确确认这条发现" }
        require(response.feedback.decision == ClaimFeedbackDecision.CONFIRMED && response.evidenceState == FeedbackEvidenceState.CURRENT) {
            "请先明确确认这条发现；未确认或已否定的内容不能保存"
        }
        val knowledgeId = KnowledgeValidation.idFor(id, claimRef(target))
        if (knowledge.get(knowledgeId)?.evidenceState == KnowledgeEvidenceState.CURRENT)
            return ActionReflectionPass(inspect(id), 0, "SAVED")
        if (cancelled()) return ActionReflectionPass(inspect(id), 0, "CANCELLED")
        var requests = 0
        var status = "REVIEW_PENDING"
        try {
            val checked = when (target) {
                is ActionReflectionTarget.Claim -> {
                    if (analysis.currentReview(id, target.ref) == null) {
                        val counted = object : SemanticReviewProvider by reviewProvider {
                            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                                requests++
                                return reviewProvider.reviewPrepared(request)
                            }
                        }
                        val key = "action-phone-review-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(
                            target.ref.batchKey, target.ref.claimIndex, target.ref.claimSha256, response.feedback.version)))
                        analysis.reviewClaim(id, key, target.ref, provider = counted)
                    }
                    analysis.currentReview(id, target.ref)
                }
                is ActionReflectionTarget.Reduction -> {
                    val pass = review.process(id, target.ref, scanProvider, reviewProvider, maxRequests, cancelled = cancelled)
                    requests += pass.modelRequestsThisPass
                    status = pass.status.name
                    if (pass.status == ReductionReviewStatus.SUPPORTED) analysis.currentReductionReview(id, target.ref) else null
                }
            }
            if (checked?.review?.verdict == ReviewVerdict.SUPPORTED) {
                val revision = knowledge.get(knowledgeId)?.value?.revision
                when (target) {
                    is ActionReflectionTarget.Claim -> knowledge.publish(id, target.ref, checked.batchKey,
                        KnowledgeCategory.INSIGHT, task(id).stored.version, revision, topics)
                    is ActionReflectionTarget.Reduction -> knowledge.publishReduction(id, target.ref, checked.batchKey,
                        task(id).stored.version, revision, topics)
                }
                status = "SAVED"
            } else if (checked != null) status = checked.review?.verdict?.name ?: status
        } catch (_: AnalysisBudgetException) { status = "BUDGET_EXHAUSTED"
        } catch (_: AnalysisValidationException) { status = "RETRY_NEEDED"
        } catch (_: AnalysisDependencyBlockedException) { status = "DEPENDENCY_BLOCKED"
        } catch (_: AnalysisConflictException) { status = "CONFLICT"
        } catch (_: StaleSnapshotException) { status = "STALE" }
        return ActionReflectionPass(inspect(id), requests, status)
    }

    fun recover(id: String): ActionReflectionRun {
        task(id)
        analysis.recoverInterrupted(id)
        return inspect(id)
    }

    /** Complete generating context, including sources a draft did not cite as support. */
    fun sources(id: String, target: ActionReflectionTarget, offset: Int = 0, limit: Int = 20): ActionReflectionSourcePage {
        require(offset >= 0 && limit in 1..100)
        val task = currentTask(id)
        val all = sources(task, target)
        val entries = all.actions.map { null to it } + all.records.map { it to null }
        val selected = entries.drop(offset).take(limit)
        ensureUnchanged(task)
        return ActionReflectionSourcePage(selected.mapNotNull { it.first }, selected.mapNotNull { it.second },
            entries.size, (offset + selected.size).takeIf { it < entries.size })
    }

    fun original(id: String, target: ActionReflectionTarget, evidenceId: String, start: Int = 0,
        maxCodePoints: Int = 1_024): SourceFragment {
        require(start >= 0 && maxCodePoints in 1..4_096)
        val task = currentTask(id)
        val ref = requireNotNull(sources(task, target).records.singleOrNull { it.id == evidenceId }) { "原文不在这条发现的来源中" }
        require(start < ref.end - ref.start)
        val count = minOf(maxCodePoints, ref.end - ref.start - start)
        val result = repository.analysisSourceFragment(task.stored.source, task.state.request.scope, ref.recordId, ref.field, ref.start + start, count)
        if (result.revision != ref.revision || result.totalCodePoints != ref.totalCodePoints ||
            result.start != ref.start + start || result.end != ref.start + start + count ||
            start == 0 && count == ref.end - ref.start && AnalysisRunCodec.sha256(result.text) != ref.sha256)
            throw StaleSnapshotException("原文版本已变化")
        ensureUnchanged(task)
        return result
    }

    fun actionOriginal(id: String, evidenceId: String, target: ActionReflectionTarget? = null): Map<String, Any?> {
        val task = currentTask(id)
        val allowed = target?.let { sources(task, it).actions } ?: requireNotNull(task.state.actionPlan).entries
        val ref = requireNotNull(allowed.singleOrNull { it.id == evidenceId }) { "行动不在本次来源中" }
        return repository.actionEvidenceBody(ref).also { ensureUnchanged(task) }
    }

    private fun requireFinding(id: String, target: ActionReflectionTarget): ActionReflectionRun = inspect(id).also { run ->
        require(run.canReview && run.findings.any { it.target == target }) { "先完成本期材料的整理与归并；来源变化或归档时不能保存" }
    }
    private fun finding(task: AnalysisTask, target: ActionReflectionTarget, text: String, current: Boolean): ActionReflectionFinding {
        val id = task.stored.id
        val progress = if (current && target is ActionReflectionTarget.Reduction) review.inspect(id, target.ref) else null
        val verdict = if (!current) null else when (target) {
            is ActionReflectionTarget.Claim -> analysis.currentReview(id, target.ref)?.review?.verdict
            is ActionReflectionTarget.Reduction -> progress?.verdict
        }
        return ActionReflectionFinding(target, text, decision(id, target), verdict,
            knowledge.get(KnowledgeValidation.idFor(id, claimRef(target))), progress)
    }
    private fun sources(task: AnalysisTask, target: ActionReflectionTarget): ActionReflectionSources = when (target) {
        is ActionReflectionTarget.Claim -> {
            val call = requireNotNull(task.state.calls.singleOrNull { it.batchKey == target.ref.batchKey &&
                it.status == CallStatus.SUCCEEDED && it.purpose == CallPurpose.ACTION_SYNTHESIS })
            require(AnalysisRunCodec.claimSha256(requireNotNull(call.proposal).claims[target.ref.claimIndex]) == target.ref.claimSha256)
            ActionReflectionSources(call.evidence, call.actionEvidence)
        }
        is ActionReflectionTarget.Reduction -> {
            require(analysis.reductionStatementRef(task.stored.id, target.ref.root, target.ref.index) == target.ref)
            val node = analysis.reductionNode(task.stored.id, target.ref.root.batchKey)
            ActionReflectionSources(node.originalEvidence, node.originalActionEvidence)
        }
    }
    private fun decision(id: String, target: ActionReflectionTarget) = when (target) {
        is ActionReflectionTarget.Claim -> feedback.forClaim(id, target.ref)
        is ActionReflectionTarget.Reduction -> feedback.forReduction(id, target.ref)
    }
    private fun task(id: String) = analysis.task(id).also { require(it.state.request.actionPeriod) { "这不是行动复盘任务" } }
    private fun normalize(progress: ActionPeriodProgress) = if (progress.batches.size > 1 &&
        progress.integration == ActionPeriodIntegration.SINGLE_CALL_ALL_AVAILABLE_INPUTS)
        progress.copy(integration = ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED) else progress
    private fun currentTask(id: String) = task(id).also {
        if (it.status in setOf(AnalysisStatus.STALE, AnalysisStatus.EXPIRED)) throw StaleSnapshotException("本期来源已变化，请开始新的复盘")
    }
    private fun ensureUnchanged(before: AnalysisTask) {
        val after = task(before.stored.id)
        if (before.stored.version != after.stored.version || before.status != after.status)
            throw AnalysisConflictException("分析在读取期间变化，请重新打开")
    }
    companion object {
        const val PREFIX = "action-reflection-v1-"
        const val QUESTION = "对照这段时间的原计划、执行回看和日常记录：哪些行动有执行自述，哪些尚未回看或仍未知；哪些做法值得继续、调整或暂缓，下一阶段具体关注什么？面向记录者本人表达，保留反例和条件。计划不等于执行，未回看不等于失败，记录多少不代表成长。"
        fun claimRef(target: ActionReflectionTarget): ClaimRef = when (target) {
            is ActionReflectionTarget.Claim -> target.ref
            is ActionReflectionTarget.Reduction -> ReductionGraph.feedbackRef(target.ref)
        }
    }
}
