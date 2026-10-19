package dev.tracelife.actions

import dev.tracelife.analysis.*
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException

enum class ActionPeriodIntegration { INCOMPLETE, SINGLE_CALL_ALL_AVAILABLE_INPUTS, MULTIPLE_BATCHES_NOT_INTEGRATED, TREE_INTEGRATED, NO_INPUTS }
data class ActionPeriodBatch(val batchKey: String, val originalFragments: Int, val actionEvents: Int,
    val claims: List<ClaimRef>, val containsAllAvailableInputs: Boolean)
data class ActionPeriodProgress(val runId: String, val status: SynthesisProcessingStatus, val source: SourceStamp?,
    val taskVersion: Int?, val recordCoverage: AnalysisProcessingCoverage?, val actionEvents: Int,
    val processedActionEvents: Int, val integration: ActionPeriodIntegration, val batches: List<ActionPeriodBatch>,
    val root: ReductionNodeRef? = null) {
    val counterevidenceSearchExhaustive: Boolean get() = false
}
data class ActionPeriodProcessingReport(val status: SynthesisProcessingStatus, val modelRequestsThisPass: Int,
    val completedBatchesThisPass: Int, val progress: ActionPeriodProgress)

/** Explicit, bounded original-source synthesis. No action adoption or automatic confirmation. */
class ActionPeriodReflection(private val analysis: ControlledAnalysis) {
    private data class Input(val record: EvidenceSelection? = null, val action: ActionEvidenceReceipt? = null)
    private val inventory = TextAnalysisInventory(analysis)

    fun start(request: AnalysisRequest, id: String = java.util.UUID.randomUUID().toString()): AnalysisTask {
        require(request.profileTarget == null)
        return analysis.start(request.copy(actionPeriod = true), id)
    }
    fun inspect(runId: String): ActionPeriodProgress = try { snapshot(runId, 0).first }
        catch (_: AnalysisTaskUnavailableException) { unavailable(runId) }
        catch (_: StaleSnapshotException) { interrupted(runId) }
        catch (_: AnalysisConflictException) { interrupted(runId) }
        catch (_: RevisionConflictException) { interrupted(runId) }

    fun process(runId: String, provider: AnalysisProvider, maxBatches: Int = 3, inputsPerBatch: Int = 16,
        cancelled: () -> Boolean = { false }): ActionPeriodProcessingReport {
        require(maxBatches in 1..100 && inputsPerBatch in 1..32)
        var requests = 0
        var completed = 0
        val counted = object : AnalysisProvider by provider {
            override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                requests++
                return provider.completePrepared(request)
            }
        }
        fun result(status: SynthesisProcessingStatus, progress: ActionPeriodProgress = inspect(runId)) =
            ActionPeriodProcessingReport(if (progress.recordCoverage == null) progress.status else status, requests, completed, progress)
        try {
            while (true) {
                val (progress, candidates) = snapshot(runId, inputsPerBatch)
                if (progress.recordCoverage == null || candidates.isEmpty()) return result(progress.status, progress)
                if (cancelled()) return result(SynthesisProcessingStatus.CANCELLED, progress)
                if (completed >= maxBatches) return result(SynthesisProcessingStatus.PASS_LIMIT_REACHED, progress)
                var selected = candidates
                while (true) {
                    if (cancelled()) return result(SynthesisProcessingStatus.CANCELLED)
                    try {
                        analysis.synthesizeActions(runId, key(selected), selected.mapNotNull { it.record }, selected.mapNotNull { it.action }, counted)
                        completed++
                        break
                    } catch (_: AnalysisBudgetException) {
                        if (selected.size == 1) return result(SynthesisProcessingStatus.BUDGET_EXHAUSTED)
                        selected = selected.take(maxOf(1, selected.size / 2))
                    }
                }
            }
        } catch (_: AnalysisValidationException) { return result(SynthesisProcessingStatus.RETRY_NEEDED) }
        catch (_: AnalysisTaskUnavailableException) { return result(SynthesisProcessingStatus.TASK_UNAVAILABLE, unavailable(runId)) }
        catch (_: AnalysisConflictException) { return result(SynthesisProcessingStatus.CONFLICT, interrupted(runId)) }
        catch (_: AnalysisDependencyBlockedException) { return result(SynthesisProcessingStatus.STALE, interrupted(runId)) }
        catch (_: StaleSnapshotException) { return result(SynthesisProcessingStatus.STALE, interrupted(runId)) }
        catch (_: RevisionConflictException) { return result(SynthesisProcessingStatus.CONFLICT, interrupted(runId)) }
        catch (error: IllegalArgumentException) {
            try { analysis.task(runId) } catch (_: AnalysisTaskUnavailableException) { return result(SynthesisProcessingStatus.TASK_UNAVAILABLE, unavailable(runId)) }
            throw error
        }
    }
    /** Continue bounded cross-batch integration; confirmation/publication remain explicit. */
    fun reduce(runId: String, provider: AnalysisProvider, maxRequests: Int = 3, fanIn: Int = 4,
        cancelled: () -> Boolean = { false }): ReductionProcessingReport {
        require(analysis.task(runId).state.request.actionPeriod)
        return PeriodReductionProcessor(analysis).process(runId, provider, maxRequests, fanIn, cancelled)
    }

    private fun snapshot(runId: String, limit: Int): Pair<ActionPeriodProgress, List<Input>> {
        val task = analysis.task(runId)
        val plan = requireNotNull(task.state.actionPlan) { "任务不是行动周期复盘" }
        blocked(task)?.let { return emptyProgress(task, it) to emptyList() }
        val scan = inventory.build(task, CallPurpose.ACTION_SYNTHESIS, limit)
        val successful = task.state.calls.filter { it.purpose == CallPurpose.ACTION_SYNTHESIS && it.status == CallStatus.SUCCEEDED }
        val processed = successful.flatMap { it.actionEvidence }.map { it.id }.toSet()
        val remaining = plan.entries.filter { it.id !in processed }
        val selected = buildList {
            var r = 0; var a = 0
            while (size < limit && (r < scan.selections.size || a < remaining.size)) {
                if (a < remaining.size && size < limit) add(Input(action = remaining[a++]))
                if (r < scan.selections.size && size < limit) add(Input(record = scan.selections[r++]))
            }
        }
        val coverage = scan.coverage
        val batches = successful.map { call ->
            val full = TextAnalysisInventory.coveredCodePoints(call.evidence) == coverage.textCodePoints &&
                call.actionEvidence.map { it.id }.toSet() == plan.entries.map { it.id }.toSet()
            ActionPeriodBatch(call.batchKey, call.evidence.size, call.actionEvidence.size,
                requireNotNull(call.proposal).claims.mapIndexed { i, claim -> ClaimRef(call.batchKey, i, AnalysisRunCodec.claimSha256(claim)) }, full)
        }
        val status = when {
            remaining.isNotEmpty() || coverage.remainingTextFragments > 0 -> SynthesisProcessingStatus.READY
            coverage.hasGaps -> SynthesisProcessingStatus.INPUTS_PROCESSED_WITH_GAPS
            coverage.textCodePoints == 0L && plan.entries.isEmpty() -> SynthesisProcessingStatus.NO_TEXT
            else -> SynthesisProcessingStatus.INPUTS_PROCESSED
        }
        val reduction = if (remaining.isEmpty() && coverage.remainingTextFragments == 0L && successful.size > 1)
            PeriodReductionProcessor(analysis).inspect(runId) else null
        val root = reduction?.takeIf { it.status == ReductionProcessingStatus.TREE_INTEGRATED }?.root
        val integration = when {
            remaining.isNotEmpty() || coverage.remainingTextFragments > 0 -> ActionPeriodIntegration.INCOMPLETE
            coverage.textCodePoints == 0L && plan.entries.isEmpty() -> ActionPeriodIntegration.NO_INPUTS
            root != null -> ActionPeriodIntegration.TREE_INTEGRATED
            batches.any { it.containsAllAvailableInputs } -> ActionPeriodIntegration.SINGLE_CALL_ALL_AVAILABLE_INPUTS
            else -> ActionPeriodIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED
        }
        val latest = analysis.task(runId)
        if (latest.stored.version != task.stored.version || latest.status != task.status) throw AnalysisConflictException("行动周期进度已变化")
        return ActionPeriodProgress(runId, status, task.stored.source, task.stored.version, coverage,
            plan.entries.size, processed.size, integration, batches, root) to selected
    }
    private fun key(values: List<Input>) = "action-period-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(values.map {
        it.action?.let(ActionEvidenceProtocol::receiptMap) ?: it.record!!.let { r -> mapOf("recordId" to r.recordId, "field" to r.field, "start" to r.start) }
    }))
    private fun blocked(task: AnalysisTask): SynthesisProcessingStatus? = when (task.status) {
        AnalysisStatus.STALE -> SynthesisProcessingStatus.STALE
        AnalysisStatus.EXPIRED -> SynthesisProcessingStatus.EXPIRED
        AnalysisStatus.IN_FLIGHT -> SynthesisProcessingStatus.NEEDS_RECOVERY
        else -> null
    }
    private fun emptyProgress(task: AnalysisTask, status: SynthesisProcessingStatus) = ActionPeriodProgress(task.stored.id,
        status, task.stored.source, task.stored.version, null, task.state.actionPlan?.entries?.size ?: 0, 0,
        ActionPeriodIntegration.INCOMPLETE, emptyList())
    private fun unavailable(id: String) = ActionPeriodProgress(id, SynthesisProcessingStatus.TASK_UNAVAILABLE,
        null, null, null, 0, 0, ActionPeriodIntegration.INCOMPLETE, emptyList())
    private fun interrupted(id: String): ActionPeriodProgress = try {
        val task = analysis.task(id)
        emptyProgress(task, blocked(task) ?: SynthesisProcessingStatus.CONFLICT)
    } catch (_: AnalysisTaskUnavailableException) { unavailable(id) }
}
