package dev.tracelife.analysis

import dev.tracelife.model.RevisionConflictException
import dev.tracelife.transfer.StaleSnapshotException

/** The outcome of one bounded pass, not a claim about the quality or truth of the proposals. */
enum class AnalysisProcessingStatus {
    ALL_TEXT_PROCESSED, TEXT_COMPLETE_WITH_GAPS, NO_DATA, PASS_LIMIT_REACHED, CANCELLED,
    RETRY_NEEDED, BUDGET_EXHAUSTED, NEEDS_RECOVERY, STALE, EXPIRED, TASK_UNAVAILABLE, CONFLICT,
}

/** Counts use source metadata and successful receipt ranges, never model-reported progress. */
data class AnalysisProcessingCoverage(
    val records: Int,
    val textCodePoints: Long,
    val completedTextCodePoints: Long,
    val remainingTextFragments: Long,
    val unreadAttachments: Int,
    val unreadableRecords: Int,
    val unknownTimeRecords: Int,
    val overlappingRecords: Int,
    val missingExactIds: Set<String>,
) {
    val hasGaps: Boolean get() = unreadAttachments > 0 || unreadableRecords > 0 ||
        unknownTimeRecords > 0 || overlappingRecords > 0 || missingExactIds.isNotEmpty()
}

data class AnalysisProcessingReport(
    val runId: String,
    val status: AnalysisProcessingStatus,
    /** Successful selected batches in this pass, including concurrent idempotent reuse. */
    val completedBatchesThisPass: Int,
    /** Null when the task is unavailable, expired, stale, awaiting recovery or concurrently changed. */
    val coverage: AnalysisProcessingCoverage?,
)

/**
 * Caller-triggered, resumable text extraction. It neither schedules periodic Android work nor
 * publishes reviewed knowledge. Every dispatch goes through [ControlledAnalysis.runBatch].
 * Call off the main thread; cancellation is checked before dispatching the next fragment.
 */
class AnalysisBatchProcessor(private val analysis: ControlledAnalysis) {
    /** Read-only processing coverage. This neither dispatches a model nor recovers interrupted work. */
    fun coverage(runId: String): AnalysisProcessingCoverage = inventory(analysis.task(runId), 0).coverage

    fun process(
        runId: String,
        provider: AnalysisProvider,
        maxBatches: Int = 5,
        cancelled: () -> Boolean = { false },
    ): AnalysisProcessingReport {
        require(maxBatches in 1..100) { "每次处理批数须为 1 至 100" }
        var completed = 0
        try {
            val initial = taskOrNull(runId)
                ?: return report(runId, AnalysisProcessingStatus.TASK_UNAVAILABLE, completed)
            blocked(initial)?.let { return report(runId, it, completed) }
            val planned = inventory(initial, maxBatches)
            if (planned.selections.isEmpty()) return report(runId, finished(planned.coverage), completed, planned.coverage)
            var stop: AnalysisProcessingStatus? = null
            for (selection in planned.selections) {
                if (cancelled()) { stop = AnalysisProcessingStatus.CANCELLED; break }
                try {
                    analysis.runBatch(runId, key(selection), listOf(selection), provider, EXTRACTION_INSTRUCTIONS)
                    completed++
                } catch (_: AnalysisBudgetException) {
                    stop = AnalysisProcessingStatus.BUDGET_EXHAUSTED; break
                } catch (_: AnalysisValidationException) {
                    stop = AnalysisProcessingStatus.RETRY_NEEDED; break
                } catch (_: AnalysisConflictException) {
                    stop = AnalysisProcessingStatus.CONFLICT; break
                }
            }
            val latest = taskOrNull(runId)
                ?: return report(runId, AnalysisProcessingStatus.TASK_UNAVAILABLE, completed)
            blocked(latest)?.let { return report(runId, it, completed) }
            val coverage = inventory(latest, 0).coverage
            return report(runId, stop ?: finished(coverage), completed, coverage)
        } catch (_: StaleSnapshotException) {
            val latest = taskOrNull(runId)
            val status = if (latest == null) AnalysisProcessingStatus.TASK_UNAVAILABLE else
                blocked(latest) ?: AnalysisProcessingStatus.STALE
            return report(runId, status, completed)
        } catch (_: RevisionConflictException) {
            val latest = taskOrNull(runId)
            val status = if (latest == null) AnalysisProcessingStatus.TASK_UNAVAILABLE else
                blocked(latest) ?: AnalysisProcessingStatus.CONFLICT
            return report(runId, status, completed)
        } catch (error: IllegalArgumentException) {
            // A deletion between metadata navigation and the next dispatch removes the run itself.
            if (taskOrNull(runId) == null) return report(runId, AnalysisProcessingStatus.TASK_UNAVAILABLE, completed)
            throw error
        }
    }

    private fun inventory(task: AnalysisTask, candidateLimit: Int) =
        TextAnalysisInventory(analysis).build(task, CallPurpose.EXTRACTION, candidateLimit)

    private fun finished(coverage: AnalysisProcessingCoverage): AnalysisProcessingStatus = when {
        coverage.remainingTextFragments > 0 -> AnalysisProcessingStatus.PASS_LIMIT_REACHED
        coverage.hasGaps -> AnalysisProcessingStatus.TEXT_COMPLETE_WITH_GAPS
        coverage.records == 0 -> AnalysisProcessingStatus.NO_DATA
        else -> AnalysisProcessingStatus.ALL_TEXT_PROCESSED
    }

    private fun blocked(task: AnalysisTask): AnalysisProcessingStatus? = when (task.status) {
        AnalysisStatus.STALE -> AnalysisProcessingStatus.STALE
        AnalysisStatus.EXPIRED -> AnalysisProcessingStatus.EXPIRED
        AnalysisStatus.IN_FLIGHT -> AnalysisProcessingStatus.NEEDS_RECOVERY
        else -> null
    }

    private fun taskOrNull(runId: String): AnalysisTask? = try {
        analysis.task(runId)
    } catch (_: AnalysisTaskUnavailableException) {
        null
    }

    private fun key(selection: EvidenceSelection) = "auto-v1-" + AnalysisRunCodec.sha256(
        AnalysisRunCodec.json(listOf(selection.recordId, selection.field, selection.start)),
    )

    private fun report(runId: String, status: AnalysisProcessingStatus, completed: Int, coverage: AnalysisProcessingCoverage? = null) =
        AnalysisProcessingReport(runId, status, completed, coverage)

    companion object {
        // Stable with auto-v1 keys: changing instructions requires a new batch-key version.
        private const val EXTRACTION_INSTRUCTIONS = "逐片段整理：提取有用的自述观察、可复用的方法或经验，以及与进展相反的证据。" +
            "观察和知识只作为待核对提案保留，不凭记录数量判断成长，也不把学到或收藏当作已经掌握。" +
            "明确区分 REPORTED_EXPERIENCE 与 POSSIBLE_INTERPRETATION；每项只引用本次提供的原文证据。" +
            "不完整句子、跨片段上下文、未知发生时间、未提供的答案和附件都应作为未知项说明。" +
            "没有有用的候选时允许空 claims；不要编造补全，不形成全周期报告、因果判断或固定人格标签。"
    }
}
