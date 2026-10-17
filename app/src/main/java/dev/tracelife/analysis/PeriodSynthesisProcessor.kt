package dev.tracelife.analysis

import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException

enum class SynthesisProcessingStatus {
    READY, INPUTS_PROCESSED, INPUTS_PROCESSED_WITH_GAPS, NO_TEXT, PASS_LIMIT_REACHED,
    CANCELLED, RETRY_NEEDED, BUDGET_EXHAUSTED, NEEDS_RECOVERY, STALE, EXPIRED,
    TASK_UNAVAILABLE, CONFLICT,
}

/** Reading all inputs in several calls is not a cross-batch synthesis. */
enum class SynthesisIntegration { INCOMPLETE, SINGLE_CALL_ALL_AVAILABLE_TEXT, MULTIPLE_BATCHES_NOT_INTEGRATED, NO_TEXT }

data class SynthesisBatchSummary(
    val batchKey: String,
    val evidenceFragments: Int,
    val claims: List<ClaimRef>,
    val containsAllAvailableText: Boolean,
)

data class SynthesisProgress(
    val runId: String,
    val status: SynthesisProcessingStatus,
    val source: SourceStamp?,
    val taskVersion: Int?,
    val coverage: AnalysisProcessingCoverage?,
    val integration: SynthesisIntegration,
    val batches: List<SynthesisBatchSummary>,
) {
    val counterevidenceSearchExhaustive: Boolean get() = false
}

data class SynthesisProcessingReport(
    val runId: String,
    val status: SynthesisProcessingStatus,
    val modelRequestsThisPass: Int,
    val completedBatchesThisPass: Int,
    val progress: SynthesisProgress,
)

/**
 * Explicitly started multi-fragment synthesis. Uses no unregistered summaries, metrics or history.
 * Requests read original text through ControlledAnalysis; all returned claims still require review
 * and user confirmation. Successful synthesis receipts are distinct from extraction coverage.
 */
class PeriodSynthesisProcessor(private val analysis: ControlledAnalysis) {
    private val inventory = TextAnalysisInventory(analysis)

    fun inspect(runId: String): SynthesisProgress = try {
        snapshot(runId, 0).first
    } catch (_: AnalysisTaskUnavailableException) {
        unavailable(runId)
    } catch (_: StaleSnapshotException) {
        interrupted(runId)
    } catch (_: AnalysisConflictException) {
        interrupted(runId)
    } catch (_: RevisionConflictException) {
        interrupted(runId)
    }

    fun process(
        runId: String,
        provider: AnalysisProvider,
        maxBatches: Int = 3,
        fragmentsPerBatch: Int = 4,
        cancelled: () -> Boolean = { false },
    ): SynthesisProcessingReport {
        require(maxBatches in 1..100 && fragmentsPerBatch in 1..32) { "每轮支持 1–100 批，每批 1–32 个片段" }
        var requests = 0
        var completed = 0
        val counted = object : AnalysisProvider by provider {
            override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                requests++
                return provider.completePrepared(request)
            }
        }
        fun result(status: SynthesisProcessingStatus, progress: SynthesisProgress = inspect(runId)) =
            SynthesisProcessingReport(runId, if (progress.coverage == null) progress.status else status,
                requests, completed, progress)

        try {
            while (true) {
                val (progress, candidates) = snapshot(runId, fragmentsPerBatch)
                if (progress.coverage == null || candidates.isEmpty()) return result(progress.status, progress)
                if (cancelled()) return result(SynthesisProcessingStatus.CANCELLED, progress)
                if (completed >= maxBatches) return result(SynthesisProcessingStatus.PASS_LIMIT_REACHED, progress)
                var selected = candidates
                while (true) {
                    if (cancelled()) return result(SynthesisProcessingStatus.CANCELLED)
                    try {
                        analysis.synthesize(runId, key(selected), selected, counted)
                        completed++
                        break
                    } catch (error: AnalysisBudgetException) {
                        // Budget checks happen before dispatch. Reduce the actual input, never trim
                        // text after receipt registration or silently mark discarded inputs read.
                        if (selected.size == 1) return result(SynthesisProcessingStatus.BUDGET_EXHAUSTED)
                        selected = selected.take(maxOf(1, selected.size / 2))
                    }
                }
            }
        } catch (_: AnalysisValidationException) {
            return result(SynthesisProcessingStatus.RETRY_NEEDED)
        } catch (_: AnalysisTaskUnavailableException) {
            return result(SynthesisProcessingStatus.TASK_UNAVAILABLE, unavailable(runId))
        } catch (_: AnalysisConflictException) {
            return result(SynthesisProcessingStatus.CONFLICT, interrupted(runId))
        } catch (_: StaleSnapshotException) {
            return result(SynthesisProcessingStatus.STALE, interrupted(runId))
        } catch (_: RevisionConflictException) {
            return result(SynthesisProcessingStatus.CONFLICT, interrupted(runId))
        } catch (error: IllegalArgumentException) {
            // Explicit task deletion need not change the source stamp; its tombstone can reject
            // a returning model result before a normal source-conflict exception is available.
            try { analysis.task(runId) } catch (_: AnalysisTaskUnavailableException) {
                return result(SynthesisProcessingStatus.TASK_UNAVAILABLE, unavailable(runId))
            }
            throw error
        }
    }

    private fun snapshot(runId: String, limit: Int): Pair<SynthesisProgress, List<EvidenceSelection>> {
        val task = analysis.task(runId)
        blocked(task)?.let { return blockedProgress(task, it) to emptyList() }
        val scan = inventory.build(task, CallPurpose.SYNTHESIS, limit)
        val latest = analysis.task(runId)
        if (latest.stored.version != task.stored.version || latest.status != task.status)
            throw AnalysisConflictException("综合进度在读取期间发生变化")
        val coverage = scan.coverage
        val batches = task.state.calls.filter { it.purpose == CallPurpose.SYNTHESIS && it.status == CallStatus.SUCCEEDED }
            .map { call -> SynthesisBatchSummary(call.batchKey, call.evidence.size,
                requireNotNull(call.proposal).claims.mapIndexed { index, claim ->
                    ClaimRef(call.batchKey, index, AnalysisRunCodec.claimSha256(claim))
                }, coverage.textCodePoints > 0 && TextAnalysisInventory.coveredCodePoints(call.evidence) == coverage.textCodePoints)
            }
        val status = when {
            coverage.remainingTextFragments > 0 -> SynthesisProcessingStatus.READY
            coverage.hasGaps -> SynthesisProcessingStatus.INPUTS_PROCESSED_WITH_GAPS
            coverage.textCodePoints == 0L -> SynthesisProcessingStatus.NO_TEXT
            else -> SynthesisProcessingStatus.INPUTS_PROCESSED
        }
        val integration = when {
            coverage.remainingTextFragments > 0 -> SynthesisIntegration.INCOMPLETE
            coverage.textCodePoints == 0L -> SynthesisIntegration.NO_TEXT
            batches.any { it.containsAllAvailableText } -> SynthesisIntegration.SINGLE_CALL_ALL_AVAILABLE_TEXT
            else -> SynthesisIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED
        }
        return SynthesisProgress(runId, status, task.stored.source, task.stored.version, coverage, integration, batches) to scan.selections
    }

    private fun blocked(task: AnalysisTask): SynthesisProcessingStatus? = when (task.status) {
        AnalysisStatus.STALE -> SynthesisProcessingStatus.STALE
        AnalysisStatus.EXPIRED -> SynthesisProcessingStatus.EXPIRED
        AnalysisStatus.IN_FLIGHT -> SynthesisProcessingStatus.NEEDS_RECOVERY
        else -> null
    }

    private fun blockedProgress(task: AnalysisTask, status: SynthesisProcessingStatus) =
        SynthesisProgress(task.stored.id, status, task.stored.source, task.stored.version,
            null, SynthesisIntegration.INCOMPLETE, emptyList())

    private fun unavailable(runId: String) = SynthesisProgress(runId, SynthesisProcessingStatus.TASK_UNAVAILABLE,
        null, null, null, SynthesisIntegration.INCOMPLETE, emptyList())

    private fun interrupted(runId: String): SynthesisProgress = try {
        val task = analysis.task(runId)
        blockedProgress(task, blocked(task) ?: SynthesisProcessingStatus.CONFLICT)
    } catch (_: AnalysisTaskUnavailableException) { unavailable(runId) }

    private fun key(selections: List<EvidenceSelection>) = "period-synthesis-v1-" + AnalysisRunCodec.sha256(
        AnalysisRunCodec.json(selections.map { listOf(it.recordId, it.field, it.start) }))
}
