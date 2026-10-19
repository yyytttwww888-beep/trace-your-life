package dev.tracelife.analysis

import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException

enum class ReductionProcessingStatus {
    READY, NEEDS_INPUT_PROCESSING, TREE_INTEGRATED, SINGLE_BATCH, NO_TEXT,
    PASS_LIMIT_REACHED, CANCELLED, RETRY_NEEDED, BUDGET_EXHAUSTED, NEEDS_RECOVERY,
    DEPENDENCY_BLOCKED, STALE, EXPIRED, TASK_UNAVAILABLE, CONFLICT,
}

enum class ReductionNodeState { CURRENT, STALE_DEPENDENCIES }

/** References and counts only; draft text is available separately through reductionNode. */
data class ReductionNodeSummary(
    val ref: ReductionNodeRef,
    val children: List<ReductionNodeRef>,
    val leafCount: Int,
    val state: ReductionNodeState,
)

data class ReductionAttemptSummary(
    val batchKey: String,
    val attempt: Int,
    val status: CallStatus,
    val children: List<ReductionNodeRef>,
)

data class ReductionProgress(
    val runId: String,
    val status: ReductionProcessingStatus,
    val source: SourceStamp?,
    val taskVersion: Int?,
    val coverage: AnalysisProcessingCoverage?,
    val leafRefs: List<ReductionNodeRef>,
    val leafManifestSha256: String?,
    val root: ReductionNodeRef?,
    val rootLeafCount: Int,
    val originalEvidenceFragments: Int,
    val frontier: List<ReductionNodeRef>,
    val nextInputs: List<ReductionNodeRef>,
    val nodes: List<ReductionNodeSummary>,
    val attempts: List<ReductionAttemptSummary>,
    val blockedLeaves: List<ReductionNodeRef>,
) {
    val leafCount: Int get() = leafRefs.size
    val counterevidenceSearchExhaustive: Boolean get() = false
    /** A complete reduction tree is navigation, never an original-evidence verification. */
    val originalEvidenceVerified: Boolean get() = false
}

data class ReductionProcessingReport(
    val runId: String,
    val status: ReductionProcessingStatus,
    val modelRequestsThisPass: Int,
    val completedNodesThisPass: Int,
    val progress: ReductionProgress,
)

/**
 * Explicit, bounded reduction of successful synthesis drafts. All original text must have synthesis
 * coverage first. A complete tree includes every successful leaf, including empty proposals; it
 * neither upgrades a draft to evidence nor publishes knowledge. No automatic retries or recovery.
 */
class PeriodReductionProcessor(private val analysis: ControlledAnalysis) {
    private val inventory = TextAnalysisInventory(analysis)

    fun inspect(runId: String, fanIn: Int = 4): ReductionProgress {
        require(fanIn in 2..4) { "归并每组须为 2–4 个节点" }
        return try {
            snapshot(runId, fanIn).progress
        } catch (_: AnalysisTaskUnavailableException) {
            unavailable(runId)
        } catch (_: StaleSnapshotException) {
            interrupted(runId)
        } catch (_: AnalysisConflictException) {
            interrupted(runId)
        } catch (_: RevisionConflictException) {
            interrupted(runId)
        }
    }

    fun process(
        runId: String,
        provider: AnalysisProvider,
        maxRequests: Int = 3,
        fanIn: Int = 4,
        cancelled: () -> Boolean = { false },
    ): ReductionProcessingReport {
        require(maxRequests in 1..100 && fanIn in 2..4) { "每轮支持 1–100 次请求，每组 2–4 个节点" }
        var requests = 0
        var completed = 0
        val counted = object : AnalysisProvider by provider {
            override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                requests++
                return provider.completePrepared(request)
            }
        }
        fun result(status: ReductionProcessingStatus, progress: ReductionProgress = inspect(runId, fanIn)) =
            ReductionProcessingReport(runId,
                if (progress.coverage == null || progress.status == ReductionProcessingStatus.DEPENDENCY_BLOCKED) progress.status else status,
                requests, completed, progress)
        try {
            while (true) {
                val current = snapshot(runId, fanIn)
                val work = current.work ?: return result(current.progress.status, current.progress)
                if (cancelled()) return result(ReductionProcessingStatus.CANCELLED, current.progress)
                if (requests >= maxRequests) return result(ReductionProcessingStatus.PASS_LIMIT_REACHED, current.progress)
                var children = work.children
                while (true) {
                    if (cancelled()) return result(ReductionProcessingStatus.CANCELLED)
                    try {
                        analysis.reduce(runId, key(current.progress.leafManifestSha256!!, children, current.dependencies), children, counted)
                        completed++
                        break
                    } catch (_: AnalysisBudgetException) {
                        // A pre-dispatch budget rejection consumes no request. Shrink the group,
                        // never the child text or leaf manifest; two unfit children stop the pass.
                        if (children.size == 2) return result(ReductionProcessingStatus.BUDGET_EXHAUSTED)
                        children = children.take(2)
                    }
                }
            }
        } catch (_: AnalysisDependencyBlockedException) {
            return result(ReductionProcessingStatus.DEPENDENCY_BLOCKED)
        } catch (_: AnalysisValidationException) {
            return result(ReductionProcessingStatus.RETRY_NEEDED)
        } catch (_: AnalysisTaskUnavailableException) {
            return result(ReductionProcessingStatus.TASK_UNAVAILABLE, unavailable(runId))
        } catch (_: AnalysisConflictException) {
            return result(ReductionProcessingStatus.CONFLICT, interrupted(runId))
        } catch (_: StaleSnapshotException) {
            return result(ReductionProcessingStatus.STALE, interrupted(runId))
        } catch (_: RevisionConflictException) {
            return result(ReductionProcessingStatus.CONFLICT, interrupted(runId))
        } catch (error: IllegalArgumentException) {
            try { analysis.task(runId) } catch (_: AnalysisTaskUnavailableException) {
                return result(ReductionProcessingStatus.TASK_UNAVAILABLE, unavailable(runId))
            }
            throw error
        }
    }

    private data class Work(val children: List<ReductionNodeRef>)
    private data class Snapshot(val progress: ReductionProgress, val work: Work? = null,
        val dependencies: Map<ReductionNodeRef, ReductionDependencies> = emptyMap())
    private data class Plan(val root: ReductionNodeRef?, val frontier: List<ReductionNodeRef>, val next: List<ReductionNodeRef>)

    private fun snapshot(runId: String, fanIn: Int): Snapshot {
        val task = analysis.task(runId)
        blocked(task)?.let { return Snapshot(empty(task, it)) }
        val coverage = inventory.build(task, CallPurpose.SYNTHESIS, 0).coverage
        val leaves = task.state.calls.filter { it.purpose == CallPurpose.SYNTHESIS && it.status == CallStatus.SUCCEEDED }
            .map(ReductionGraph::nodeRef)
        val manifest = AnalysisRunCodec.sha256(AnalysisRunCodec.json(leaves.map(::refValue)))
        val reductions = task.state.calls.filter { it.purpose == CallPurpose.REDUCTION }
        val attempts = reductions.map { ReductionAttemptSummary(it.batchKey, it.attempt, it.status, it.reductionInputs.map { input -> input.node }) }
        val dependencies = linkedMapOf<ReductionNodeRef, ReductionDependencies>()
        val blockedLeaves = mutableListOf<ReductionNodeRef>()
        for (leaf in leaves) {
            try { dependencies[leaf] = analysis.reductionDependencies(runId, leaf) }
            catch (_: AnalysisDependencyBlockedException) { blockedLeaves += leaf }
        }
        val nodes = reductions.filter { it.status == CallStatus.SUCCEEDED }.map { call ->
            val ref = ReductionGraph.nodeRef(call)
            val validity = try {
                dependencies[ref] = analysis.reductionDependencies(runId, ref)
                ReductionNodeState.CURRENT
            } catch (_: AnalysisDependencyBlockedException) {
                ReductionNodeState.STALE_DEPENDENCIES
            } catch (_: AnalysisConflictException) {
                ReductionNodeState.STALE_DEPENDENCIES
            }
            ReductionNodeSummary(ref, call.reductionInputs.map { it.node }, ReductionGraph.leafRefs(task.state, ref).size, validity)
        }
        // Feedback mutations also bump the task version. Rechecking it prevents a progress view
        // from combining pre-feedback and post-feedback dependency states.
        val latest = analysis.task(runId)
        if (latest.stored.version != task.stored.version || latest.status != task.status)
            throw AnalysisConflictException("归并进度在读取期间发生变化")
        val ready = coverage.remainingTextFragments == 0L && blockedLeaves.isEmpty()
        var plan = if (ready) plan(leaves, nodes, dependencies, fanIn) else Plan(null, leaves, emptyList())
        // A failed budget-shrunk group remains the next explicit retry. Its identity includes the
        // current leaf manifest and feedback, so an obsolete failure never forces old inputs back.
        if (plan.next.isNotEmpty()) {
            reductions.lastOrNull { call ->
                val children = call.reductionInputs.map { it.node }
                call.status in setOf(CallStatus.FAILED, CallStatus.INTERRUPTED) &&
                    children.size in 2..plan.next.size && children == plan.next.take(children.size) &&
                    call.batchKey == key(manifest, children, dependencies)
            }?.let { call -> plan = plan.copy(next = call.reductionInputs.map { it.node }) }
        }
        val status = when {
            blockedLeaves.isNotEmpty() -> ReductionProcessingStatus.DEPENDENCY_BLOCKED
            coverage.remainingTextFragments > 0 -> ReductionProcessingStatus.NEEDS_INPUT_PROCESSING
            leaves.isEmpty() -> ReductionProcessingStatus.NO_TEXT
            leaves.size == 1 -> ReductionProcessingStatus.SINGLE_BATCH
            plan.root != null -> ReductionProcessingStatus.TREE_INTEGRATED
            else -> {
                val nextKey = key(manifest, plan.next, dependencies)
                if (reductions.lastOrNull { it.batchKey == nextKey }?.status in setOf(CallStatus.FAILED, CallStatus.INTERRUPTED))
                    ReductionProcessingStatus.RETRY_NEEDED else ReductionProcessingStatus.READY
            }
        }
        val evidence = task.state.calls.filter { it.purpose == CallPurpose.SYNTHESIS && it.status == CallStatus.SUCCEEDED }
            .flatMap { it.evidence }.distinctBy { it.id }
        val progress = ReductionProgress(runId, status, task.stored.source, task.stored.version, coverage, leaves, manifest,
            plan.root, if (plan.root != null) leaves.size else 0, evidence.size, plan.frontier, plan.next, nodes, attempts, blockedLeaves)
        return Snapshot(progress, plan.next.takeIf { it.isNotEmpty() }?.let(::Work), dependencies)
    }

    /**
     * Rebuild a balanced frontier from contiguous, non-overlapping groups at each level. Reuse
     * groups of any supported size, even when fanIn changed or a prior budget shrank four to two.
     * A singleton tail advances one level without a paid call.
     */
    private fun plan(leaves: List<ReductionNodeRef>, nodes: List<ReductionNodeSummary>,
        dependencies: Map<ReductionNodeRef, ReductionDependencies>, fanIn: Int): Plan {
        if (leaves.isEmpty()) return Plan(null, emptyList(), emptyList())
        val current = nodes.filter { it.state == ReductionNodeState.CURRENT }
        val leafSet = leaves.toSet()
        current.lastOrNull { dependencies.getValue(it.ref).leafRefs.toSet() == leafSet }?.let {
            return Plan(it.ref, listOf(it.ref), emptyList())
        }
        var layer = leaves
        while (layer.size > 1) {
            val nextLayer = mutableListOf<ReductionNodeRef>()
            var position = 0
            while (position < layer.size) {
                val cached = current.filter { it.children.size <= layer.size - position &&
                    it.children == layer.subList(position, position + it.children.size) }
                    .maxByOrNull { it.children.size }
                if (cached != null) {
                    nextLayer += cached.ref
                    position += cached.children.size
                } else if (position == layer.lastIndex) {
                    nextLayer += layer[position++]
                } else {
                    return Plan(null, nextLayer + layer.drop(position), layer.drop(position).take(fanIn))
                }
            }
            layer = nextLayer
        }
        return Plan(layer.single(), layer, emptyList())
    }

    private fun key(manifest: String, children: List<ReductionNodeRef>, dependencies: Map<ReductionNodeRef, ReductionDependencies>) =
        "period-reduction-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(manifest, children.map { ref ->
            listOf(refValue(ref), dependencies.getValue(ref).feedbackTokens.map { token ->
                listOf(token.claim.batchKey, token.claim.claimIndex, token.claim.claimSha256, token.version)
            })
        })))

    private fun refValue(ref: ReductionNodeRef) = listOf(ref.batchKey, ref.attempt, ref.outputSha256)

    private fun blocked(task: AnalysisTask): ReductionProcessingStatus? = when (task.status) {
        AnalysisStatus.STALE -> ReductionProcessingStatus.STALE
        AnalysisStatus.EXPIRED -> ReductionProcessingStatus.EXPIRED
        AnalysisStatus.IN_FLIGHT -> ReductionProcessingStatus.NEEDS_RECOVERY
        else -> null
    }

    private fun empty(task: AnalysisTask, status: ReductionProcessingStatus) = empty(task.stored.id, status, task.stored.source, task.stored.version)
    private fun empty(runId: String, status: ReductionProcessingStatus, source: SourceStamp? = null, version: Int? = null) =
        ReductionProgress(runId, status, source, version, null, emptyList(), null, null, 0, 0,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun unavailable(runId: String) = empty(runId, ReductionProcessingStatus.TASK_UNAVAILABLE)
    private fun interrupted(runId: String) = try {
        val task = analysis.task(runId)
        empty(task, blocked(task) ?: ReductionProcessingStatus.CONFLICT)
    } catch (_: AnalysisTaskUnavailableException) { unavailable(runId) }
}
