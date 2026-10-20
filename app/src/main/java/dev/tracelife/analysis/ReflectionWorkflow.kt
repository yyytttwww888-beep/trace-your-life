package dev.tracelife.analysis

import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceFragment
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Instant
import java.util.UUID

enum class ReflectionStatus {
    ANALYZING, INTEGRATING, READY, NO_TEXT, RETRY_NEEDED, NEEDS_RECOVERY,
    BUDGET_EXHAUSTED, DEPENDENCY_BLOCKED, STALE, EXPIRED, CONFLICT,
}

sealed class ReflectionTarget {
    data class Claim(val ref: ClaimRef) : ReflectionTarget()
    data class Reduction(val ref: ReductionStatementRef) : ReflectionTarget()
}

data class ReflectionFinding(
    val target: ReflectionTarget,
    val text: String,
    val feedback: ClaimFeedbackView?,
    val reviewVerdict: ReviewVerdict?,
    val reviewProgress: PeriodReductionReviewProgress?,
    /** Durable saved identity; source or feedback changes do not undo the user's save. */
    val knowledgeId: String?,
    val knowledgeEvidenceState: KnowledgeEvidenceState? = null,
    val historicalFeedback: HistoricalFeedbackCoverage = HistoricalFeedbackCoverage(0, 0, 0, 0),
)

data class ReflectionRun(
    val id: String,
    val createdAt: Instant,
    val scope: AnalysisQuery,
    val status: ReflectionStatus,
    val taskVersion: Int,
    val coverage: AnalysisProcessingCoverage?,
    val synthesizedBatches: Int,
    val reducedBatches: Int,
    val totalRequests: Int,
    val findings: List<ReflectionFinding>,
    val unknowns: List<String>,
    /** Complete, current findings only. A failed review can still be resumed explicitly. */
    val canReviewFindings: Boolean,
    val archivedAt: Instant? = null,
)

data class ReflectionHistoryPage(val items: List<ReflectionRun>, val nextOffset: Int?)

data class ReflectionPass(val run: ReflectionRun, val modelRequestsThisPass: Int, val status: ReflectionStatus)
data class ReflectionEvidencePage(val items: List<EvidenceReceipt>, val total: Int, val nextOffset: Int?)

/** User-triggered application workflow. All durable progress lives in the existing analysis ledger.
 * Local reading APIs are for trusted UI only; model reads stay inside ControlledAnalysis.
 * Nothing in generation confirms a finding, publishes knowledge or adopts an action. */
class ReflectionWorkflow(
    private val repository: LifeRepository,
    private val analysis: ControlledAnalysis,
    private val feedback: AnalysisFeedback = AnalysisFeedback(repository),
    private val knowledge: KnowledgeLibrary = KnowledgeLibrary(repository),
) {
    private val synthesis = PeriodSynthesisProcessor(analysis)
    private val reduction = PeriodReductionProcessor(analysis)
    private val review = PeriodReductionReviewProcessor(analysis)

    fun start(scope: AnalysisQuery, limits: AnalysisLimits = AnalysisLimits()): ReflectionRun {
        val source = repository.analysisSourceStamp()
        val focus = FeedbackContextCatalog(repository).routeTopics(source, scope)
        return inspect(analysis.start(AnalysisRequest(QUESTION, scope, limits, focusTopics = focus,
            inheritHistoricalFeedback = true), PREFIX + UUID.randomUUID()).stored.id)
    }

    /** Filtering happens before limiting: unrelated extraction/profile tasks cannot hide history. */
    fun history(limit: Int = 20, offset: Int = 0): List<ReflectionRun> {
        require(limit in 1..100 && offset >= 0)
        return historyPage(false, offset, limit).items
    }

    fun historyPage(archived: Boolean = false, offset: Int = 0, limit: Int = 20): ReflectionHistoryPage {
        require(limit in 1..100 && offset >= 0)
        val page = repository.analysisRunIndex(PREFIX, archived, offset, limit)
        val next = (offset + page.size).takeIf { page.size == limit && repository.analysisRunIndex(PREFIX, archived, it, 1).isNotEmpty() }
        return ReflectionHistoryPage(page.map { inspect(it.id) }, next)
    }

    fun archive(runId: String, expectedRunVersion: Int): ReflectionRun {
        val task = task(runId)
        require(task.stored.version == expectedRunVersion && task.state.calls.none { it.status == CallStatus.IN_FLIGHT })
        repository.archiveAnalysisRun(runId, expectedRunVersion)
        return inspect(runId)
    }

    fun unarchive(runId: String, expectedRunVersion: Int): ReflectionRun {
        task(runId)
        repository.unarchiveAnalysisRun(runId, expectedRunVersion)
        return inspect(runId)
    }

    fun inspect(runId: String): ReflectionRun {
        val task = task(runId)
        val progress = synthesis.inspect(runId)
        val integrated = reduction.inspect(runId)
        val leaves = task.state.calls.filter { it.purpose == CallPurpose.SYNTHESIS && it.status == CallStatus.SUCCEEDED }
        val nodes = task.state.calls.filter { it.purpose == CallPurpose.REDUCTION && it.status == CallStatus.SUCCEEDED }
        val status = when (task.status) {
            AnalysisStatus.STALE -> ReflectionStatus.STALE
            AnalysisStatus.EXPIRED -> ReflectionStatus.EXPIRED
            AnalysisStatus.IN_FLIGHT -> ReflectionStatus.NEEDS_RECOVERY
            AnalysisStatus.NEEDS_RETRY -> ReflectionStatus.RETRY_NEEDED
            // A single complete original batch has independent claims and needs no reduction.
            // Rejecting one of them must not block another claim's own review/publication.
            else -> if (leaves.size == 1 && progress.coverage?.remainingTextFragments == 0L &&
                integrated.status == ReductionProcessingStatus.DEPENDENCY_BLOCKED) ReflectionStatus.READY
                else status(integrated.status)
        }
        // Historical bodies remain visible with their explicit stale/blocked state. Resolve only a
        // complete root, never the last arbitrary subtree, and never treat it as current evidence.
        val leafRefs = leaves.map(ReductionGraph::nodeRef).toSet()
        val root = nodes.lastOrNull { ReductionGraph.leafRefs(task.state, ReductionGraph.nodeRef(it)).toSet() == leafRefs }
        val readableCurrent = status !in setOf(ReflectionStatus.STALE, ReflectionStatus.EXPIRED,
            ReflectionStatus.NEEDS_RECOVERY, ReflectionStatus.DEPENDENCY_BLOCKED, ReflectionStatus.CONFLICT)
        val rows = if (root != null) requireNotNull(root.reductionOutput).statements.mapIndexed { index, statement ->
            finding(task, ReflectionTarget.Reduction(ReductionGraph.statementRef(root, index)), statement.text, readableCurrent)
        } else leaves.flatMap { leaf -> requireNotNull(leaf.proposal).claims.mapIndexed { index, claim ->
            finding(task, ReflectionTarget.Claim(ClaimRef(leaf.batchKey, index, AnalysisRunCodec.claimSha256(claim))), claim.text, readableCurrent)
        } }
        val unknowns = (leaves.flatMap { it.proposal?.unknowns.orEmpty() } + nodes.flatMap { it.reductionOutput?.unknowns.orEmpty() }).distinct()
        val complete = progress.coverage?.remainingTextFragments == 0L &&
            (leaves.size == 1 || integrated.status == ReductionProcessingStatus.TREE_INTEGRATED)
        val reviewable = complete && status !in setOf(ReflectionStatus.STALE, ReflectionStatus.EXPIRED,
            ReflectionStatus.NEEDS_RECOVERY, ReflectionStatus.CONFLICT) &&
            (leaves.size == 1 || integrated.status != ReductionProcessingStatus.DEPENDENCY_BLOCKED)
        val moreCallsNeeded = !complete || rows.any {
            it.feedback?.feedback?.decision == ClaimFeedbackDecision.CONFIRMED && it.knowledgeEvidenceState != KnowledgeEvidenceState.CURRENT &&
                it.reviewVerdict == null && it.reviewProgress?.status !in setOf(ReductionReviewStatus.CONTEXT_REQUIRED,
                    ReductionReviewStatus.NO_RELEVANT_EVIDENCE, ReductionReviewStatus.RELATED_EVIDENCE_LIMIT)
        }
        val budgetUsed = task.state.calls.size >= task.state.request.limits.maxCalls ||
            task.state.calls.sumOf { it.inputBytes.toLong() } >= task.state.request.limits.totalInputBytes
        val finalStatus = if (budgetUsed && moreCallsNeeded && status in setOf(ReflectionStatus.ANALYZING,
                ReflectionStatus.INTEGRATING, ReflectionStatus.READY, ReflectionStatus.RETRY_NEEDED))
            ReflectionStatus.BUDGET_EXHAUSTED else status
        ensureUnchanged(task)
        return ReflectionRun(runId, task.stored.createdAt, task.state.request.scope, finalStatus, task.stored.version,
            progress.coverage, leaves.size, nodes.size, task.state.calls.size, rows, unknowns,
            reviewable && task.stored.archivedAt == null, task.stored.archivedAt)
    }

    /** A single shared pass limit spans original synthesis and all reduction levels. */
    fun process(runId: String, provider: AnalysisProvider, maxRequests: Int = 5,
        onProgress: (ReflectionRun) -> Unit = {},
    ): ReflectionPass {
        require(maxRequests in 1..100)
        task(runId)
        var requests = 0
        while (requests < maxRequests) {
            val current = inspect(runId)
            if (current.status !in setOf(ReflectionStatus.ANALYZING, ReflectionStatus.INTEGRATING, ReflectionStatus.RETRY_NEEDED))
                return ReflectionPass(current, requests, current.status)
            val pending = synthesis.inspect(runId)
            val before = current.taskVersion
            val outcome: ReflectionStatus
            if (pending.coverage?.remainingTextFragments?.let { it > 0 } == true) {
                val result = synthesis.process(runId, provider, maxBatches = 1)
                requests += result.modelRequestsThisPass
                outcome = when (result.status) {
                    SynthesisProcessingStatus.RETRY_NEEDED -> ReflectionStatus.RETRY_NEEDED
                    SynthesisProcessingStatus.BUDGET_EXHAUSTED -> ReflectionStatus.BUDGET_EXHAUSTED
                    else -> inspect(runId).status
                }
            } else {
                val result = reduction.process(runId, provider, maxRequests = 1)
                requests += result.modelRequestsThisPass
                outcome = status(result.status)
            }
            val updated = inspect(runId)
            onProgress(updated)
            if (outcome !in setOf(ReflectionStatus.ANALYZING, ReflectionStatus.INTEGRATING) || updated.taskVersion == before)
                return ReflectionPass(updated, requests, outcome)
        }
        return inspect(runId).let { ReflectionPass(it, requests, it.status) }
    }

    /** Recovery records an interrupted attempt; it does not issue another paid request. */
    fun recover(runId: String): ReflectionRun {
        task(runId)
        analysis.recoverInterrupted(runId)
        return inspect(runId)
    }

    fun respond(runId: String, target: ReflectionTarget, expectedRunVersion: Int,
        decision: ClaimFeedbackDecision, expectedFeedbackVersion: Int? = null,
        userText: String? = null,
    ): ReflectionRun {
        val current = task(runId)
        if (current.stored.archivedAt != null && decision != ClaimFeedbackDecision.CONFIRMED) {
            currentTask(runId)
            require(current.state.calls.none { it.status == CallStatus.IN_FLIGHT } &&
                ReductionGraph.matchesFeedbackTarget(current.state, claimRef(target))) { "发现身份已变化" }
        } else requireCurrentFinding(runId, target)
        when (target) {
            is ReflectionTarget.Claim -> feedback.respond(runId, target.ref, expectedRunVersion, decision,
                expectedVersion = expectedFeedbackVersion, userText = userText)
            is ReflectionTarget.Reduction -> feedback.respondReduction(runId, target.ref, expectedRunVersion, decision,
                expectedVersion = expectedFeedbackVersion, userText = userText)
        }
        return inspect(runId)
    }

    /** Trusted UI read, separate from model evidence. Every related constraint is reachable. */
    fun historicalFeedbackPage(runId: String, target: ReflectionTarget, offset: Int = 0, limit: Int = 16): HistoricalFeedbackPage {
        require(offset >= 0 && limit in 1..100)
        val task = currentTask(runId)
        val refs = task.state.feedbackContext?.references.orEmpty()
        require(ReductionGraph.matchesFeedbackTarget(task.state, claimRef(target)))
        val catalog = FeedbackContextCatalog(repository)
        val items = refs.drop(offset).take(limit).map { ref -> HistoricalFeedbackCheck(catalog.resolve(ref),
            FeedbackContextProtocol.latest(task.state, claimRef(target), ref.id)) }
        ensureUnchanged(task)
        return HistoricalFeedbackPage(items, refs.size, (offset + items.size).takeIf { it < refs.size })
    }

    fun resolveHistoricalFeedback(runId: String, target: ReflectionTarget, feedbackId: String, expectedRunVersion: Int,
        choice: HistoricalFeedbackChoice, reason: String, expectedResolutionVersion: Int? = null): ReflectionRun {
        requireCurrentFinding(runId, target)
        analysis.resolveHistoricalFeedback(runId, claimRef(target), feedbackId, expectedRunVersion, choice, reason, expectedResolutionVersion)
        return inspect(runId)
    }

    /** Explicit confirmation is required beforehand; continuing never silently reconfirms a target.
     * Every successful review rereads originals through existing protocols before publication. */
    fun reviewAndSave(runId: String, target: ReflectionTarget,
        scanProvider: SemanticReductionScanProvider, reviewProvider: SemanticReviewProvider,
        maxRequests: Int = 5, topics: Set<String> = emptySet(), onProgress: (ReflectionRun) -> Unit = {},
    ): ReflectionPass {
        require(maxRequests in 1..100)
        require(topics.size <= 30 && topics.all { it.isNotBlank() && it.length <= 80 })
        requireCurrentFinding(runId, target)
        val history = FeedbackContextProtocol.coverage(task(runId).state, claimRef(target))
        if (history.resolved != history.total) throw AnalysisDependencyBlockedException("请先逐项对照历史意见；存在未处理或冲突")
        val decision = decision(runId, target)
        require(decision?.feedback?.decision == ClaimFeedbackDecision.CONFIRMED && decision.evidenceState == FeedbackEvidenceState.CURRENT) {
            "请先确认这条发现；未确认或已否定的内容不能保存"
        }
        val existingId = KnowledgeValidation.idFor(runId, claimRef(target))
        if (knowledge.get(existingId)?.evidenceState == KnowledgeEvidenceState.CURRENT)
            return inspect(runId).let { ReflectionPass(it, 0, it.status) }
        var requests = 0
        var passStatus: ReflectionStatus? = null
        try {
            when (target) {
                is ReflectionTarget.Claim -> {
                    if (analysis.currentReview(runId, target.ref) == null) {
                        val counted = object : SemanticReviewProvider by reviewProvider {
                            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                                requests++
                                return reviewProvider.reviewPrepared(request)
                            }
                        }
                        val key = "reflection-review-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(
                            target.ref.batchKey, target.ref.claimIndex, target.ref.claimSha256, decision.feedback.version) +
                            listOfNotNull(FeedbackContextProtocol.reviewHash(task(runId).state, target.ref))))
                        analysis.reviewClaim(runId, key, target.ref, provider = counted)
                        onProgress(inspect(runId))
                    }
                    val checked = analysis.currentReview(runId, target.ref)
                    if (checked?.review?.verdict == ReviewVerdict.SUPPORTED) knowledge.publish(runId, target.ref,
                        checked.batchKey, KnowledgeCategory.INSIGHT, task(runId).stored.version,
                        expectedRevision = knowledge.get(existingId)?.value?.revision, topics = topics)
                }
                is ReflectionTarget.Reduction -> {
                    while (requests < maxRequests) {
                        val pass = review.process(runId, target.ref, scanProvider, reviewProvider, maxRequests = 1)
                        requests += pass.modelRequestsThisPass
                        onProgress(inspect(runId))
                        if (pass.status == ReductionReviewStatus.SUPPORTED) {
                            val checked = requireNotNull(analysis.currentReductionReview(runId, target.ref))
                            knowledge.publishReduction(runId, target.ref, checked.batchKey, task(runId).stored.version,
                                expectedRevision = knowledge.get(existingId)?.value?.revision, topics = topics)
                            break
                        }
                        passStatus = when (pass.status) {
                            ReductionReviewStatus.RETRY_NEEDED -> ReflectionStatus.RETRY_NEEDED
                            ReductionReviewStatus.BUDGET_EXHAUSTED -> ReflectionStatus.BUDGET_EXHAUSTED
                            ReductionReviewStatus.DEPENDENCY_BLOCKED -> ReflectionStatus.DEPENDENCY_BLOCKED
                            else -> null
                        }
                        if (pass.status != ReductionReviewStatus.PASS_LIMIT_REACHED || pass.modelRequestsThisPass == 0) break
                    }
                }
            }
        } catch (_: AnalysisBudgetException) { passStatus = ReflectionStatus.BUDGET_EXHAUSTED
        } catch (_: AnalysisValidationException) { passStatus = ReflectionStatus.RETRY_NEEDED
        } catch (_: AnalysisDependencyBlockedException) { passStatus = ReflectionStatus.DEPENDENCY_BLOCKED
        } catch (_: AnalysisConflictException) { passStatus = ReflectionStatus.CONFLICT
        } catch (_: RevisionConflictException) { passStatus = ReflectionStatus.CONFLICT
        } catch (_: StaleSnapshotException) { passStatus = ReflectionStatus.STALE }
        return inspect(runId).let { ReflectionPass(it, requests, passStatus ?: it.status) }
    }

    /** Includes full generating-batch context, not just citations selected by the model. */
    fun evidence(runId: String, target: ReflectionTarget, offset: Int = 0, limit: Int = 25): ReflectionEvidencePage {
        require(offset >= 0 && limit in 1..100)
        val task = currentTask(runId)
        val sources = sources(task, target)
        val end = minOf(sources.size.toLong(), offset.toLong() + limit).toInt()
        val rows = if (offset >= sources.size) emptyList() else sources.subList(offset, end).toList()
        ensureUnchanged(task)
        return ReflectionEvidencePage(rows, sources.size, end.takeIf { offset < it && it < sources.size })
    }

    /** Existing tags from this finding's actual source records, offered for explicit confirmation. */
    fun suggestedTopics(runId: String, target: ReflectionTarget): Set<String> {
        val task = currentTask(runId)
        val ids = sources(task, target).map { it.recordId }.toSet()
        val topics = sortedSetOf<String>()
        var offset = 0
        do {
            val page = repository.analysisSourcePage(task.stored.source, task.state.request.scope, offset, 100)
            page.items.filter { it.id in ids }.forEach { topics += it.topics }
            offset += page.items.size
        } while (page.nextOffset != null)
        ensureUnchanged(task)
        return topics.take(30).toSet()
    }

    /** start is relative to the registered receipt; it cannot expand beyond that receipt. */
    fun original(runId: String, target: ReflectionTarget, evidenceId: String,
        start: Int = 0, maxCodePoints: Int = 1_024,
    ): SourceFragment {
        require(start >= 0 && maxCodePoints in 1..4_096)
        val task = currentTask(runId)
        val receipt = requireNotNull(sources(task, target).singleOrNull { it.id == evidenceId }) { "引用不属于这条发现" }
        require(start < receipt.end - receipt.start)
        val absolute = receipt.start + start
        val count = minOf(maxCodePoints, receipt.end - absolute)
        val result = repository.analysisSourceFragment(task.stored.source, task.state.request.scope,
            receipt.recordId, receipt.field, absolute, count)
        if (result.revision != receipt.revision || result.totalCodePoints != receipt.totalCodePoints ||
            result.start != absolute || result.end != absolute + count ||
            start == 0 && count == receipt.end - receipt.start && AnalysisRunCodec.sha256(result.text) != receipt.sha256)
            throw StaleSnapshotException("原文与分析时的记录不一致")
        ensureUnchanged(task)
        return result
    }

    private fun finding(task: AnalysisTask, target: ReflectionTarget, text: String, current: Boolean): ReflectionFinding {
        val runId = task.stored.id
        val progress = if (current && target is ReflectionTarget.Reduction) review.inspect(runId, target.ref) else null
        val verdict = if (!current) null else when (target) {
            is ReflectionTarget.Claim -> analysis.currentReview(runId, target.ref)?.review?.verdict
            is ReflectionTarget.Reduction -> progress?.verdict
        }
        val saved = knowledge.get(KnowledgeValidation.idFor(runId, claimRef(target)))
        return ReflectionFinding(target, text, decision(runId, target), verdict, progress,
            saved?.value?.id, saved?.evidenceState, FeedbackContextProtocol.coverage(task.state, claimRef(target)))
    }

    private fun decision(runId: String, target: ReflectionTarget) = when (target) {
        is ReflectionTarget.Claim -> feedback.forClaim(runId, target.ref)
        is ReflectionTarget.Reduction -> feedback.forReduction(runId, target.ref)
    }

    private fun claimRef(target: ReflectionTarget) = when (target) {
        is ReflectionTarget.Claim -> target.ref
        is ReflectionTarget.Reduction -> ReductionGraph.feedbackRef(target.ref)
    }

    private fun sources(task: AnalysisTask, target: ReflectionTarget): List<EvidenceReceipt> {
        requireTarget(task, target)
        return when (target) {
            is ReflectionTarget.Claim -> task.state.calls.single { it.batchKey == target.ref.batchKey && it.status == CallStatus.SUCCEEDED }.evidence
            is ReflectionTarget.Reduction -> analysis.reductionDependencies(task.stored.id, target.ref.root).originalEvidence
        }
    }

    private fun requireTarget(task: AnalysisTask, target: ReflectionTarget) {
        when (target) {
            is ReflectionTarget.Claim -> require(task.state.calls.any { call ->
                call.purpose == CallPurpose.SYNTHESIS && call.status == CallStatus.SUCCEEDED && call.batchKey == target.ref.batchKey &&
                    call.proposal?.claims?.getOrNull(target.ref.claimIndex)?.let { AnalysisRunCodec.claimSha256(it) == target.ref.claimSha256 } == true
            }) { "发现已变化或不属于这次分析" }
            is ReflectionTarget.Reduction -> require(task.state.calls.any { call ->
                call.purpose == CallPurpose.REDUCTION && call.status == CallStatus.SUCCEEDED && ReductionGraph.nodeRef(call) == target.ref.root &&
                    call.reductionOutput?.statements?.getOrNull(target.ref.index) != null &&
                    ReductionGraph.statementRef(call, target.ref.index) == target.ref
            }) { "发现已变化或不属于这次分析" }
        }
    }

    private fun requireCurrentFinding(runId: String, target: ReflectionTarget) {
        val run = inspect(runId)
        require(run.canReviewFindings && run.findings.any { it.target == target }) {
            "请先完成这次分析；局部或历史结果不能作为当前周期发现保存"
        }
    }

    private fun task(runId: String): AnalysisTask = analysis.task(runId).also { require(isReflection(it)) { "这不是主动分析任务" } }
    private fun isReflection(task: AnalysisTask) = task.stored.id.startsWith(PREFIX) &&
        task.state.request.profileTarget == null && !task.state.request.actionPeriod
    private fun currentTask(runId: String) = task(runId).also {
        if (it.status in setOf(AnalysisStatus.STALE, AnalysisStatus.EXPIRED)) throw StaleSnapshotException("来源已变化，请重新分析；旧结果仅供回顾")
    }
    private fun ensureUnchanged(previous: AnalysisTask) {
        val latest = task(previous.stored.id)
        if (latest.stored.version != previous.stored.version || latest.status != previous.status)
            throw AnalysisConflictException("分析进度已变化，请刷新")
    }
    private fun status(value: ReductionProcessingStatus): ReflectionStatus = when (value) {
        ReductionProcessingStatus.NEEDS_INPUT_PROCESSING -> ReflectionStatus.ANALYZING
        ReductionProcessingStatus.READY, ReductionProcessingStatus.PASS_LIMIT_REACHED -> ReflectionStatus.INTEGRATING
        ReductionProcessingStatus.TREE_INTEGRATED, ReductionProcessingStatus.SINGLE_BATCH -> ReflectionStatus.READY
        ReductionProcessingStatus.NO_TEXT -> ReflectionStatus.NO_TEXT
        ReductionProcessingStatus.RETRY_NEEDED -> ReflectionStatus.RETRY_NEEDED
        ReductionProcessingStatus.BUDGET_EXHAUSTED -> ReflectionStatus.BUDGET_EXHAUSTED
        ReductionProcessingStatus.NEEDS_RECOVERY -> ReflectionStatus.NEEDS_RECOVERY
        ReductionProcessingStatus.DEPENDENCY_BLOCKED -> ReflectionStatus.DEPENDENCY_BLOCKED
        ReductionProcessingStatus.STALE -> ReflectionStatus.STALE
        ReductionProcessingStatus.EXPIRED -> ReflectionStatus.EXPIRED
        ReductionProcessingStatus.CANCELLED, ReductionProcessingStatus.CONFLICT, ReductionProcessingStatus.TASK_UNAVAILABLE -> ReflectionStatus.CONFLICT
    }

    companion object {
        private const val PREFIX = "reflection-v1-"
        private const val QUESTION = "回顾选定范围的记录，对照期望、实际做法和结果，提炼有依据的可复用经验、值得关注的问题或不足，以及下一步可尝试的小行动。" +
            "正文面向记录者本人阅读，直接写经验、问题和做法，必要时称呼为「你」，避免使用「用户记录了」「用户表示」这类第三人称开场；仍须保留自述性质、推断与事实的区别。" +
            "每条发现用「值得留下：」「值得关注：」「可以尝试：」之一开头；每类最多2条、总计最多6条，跨批归并也保留这些前缀与数量限制。" +
            "说明适用情境、原文依据与不确定性，不能只复述用户记录了什么。下一步须说明尝试条件与可观察信号，不能假称已采纳。" +
            "没有相应证据就不生成该类发现，不强凑三类；没有充分依据允许空结果并说明缺少什么。" +
            "缺少记录不等于做得不好或没有成长，不作人格标签、健康诊断、成长评分或未经程序计算的统计。"
    }
}
