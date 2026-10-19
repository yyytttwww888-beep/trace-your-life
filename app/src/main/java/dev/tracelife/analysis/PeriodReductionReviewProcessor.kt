package dev.tracelife.analysis

import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException

enum class ReductionReviewStatus {
    SCAN_READY, FINAL_READY, SUPPORTED, NOT_SUPPORTED, CONTEXT_REQUIRED, NO_RELEVANT_EVIDENCE,
    RELATED_EVIDENCE_LIMIT, PASS_LIMIT_REACHED, CANCELLED, RETRY_NEEDED, BUDGET_EXHAUSTED,
    NEEDS_RECOVERY, DEPENDENCY_BLOCKED, STALE, EXPIRED, TASK_UNAVAILABLE, CONFLICT,
}

/** Coverage is measured independently of semantic conclusions. No raw text escapes this view. */
data class PeriodReductionReviewProgress(
    val runId: String,
    val target: ReductionStatementRef,
    val status: ReductionReviewStatus,
    val source: SourceStamp? = null,
    val taskVersion: Int? = null,
    val feedbackVersion: Int? = null,
    val originalFragments: Int = 0,
    val scannedFragments: Int = 0,
    val relatedFragments: Int = 0,
    val irrelevantFragments: Int = 0,
    val contextRequiredFragments: Int = 0,
    val counterevidenceFragments: Int = 0,
    val finalRereadFragments: Int = 0,
    val verdict: ReviewVerdict? = null,
) {
    val allOriginalsScanned: Boolean get() = originalFragments > 0 && scannedFragments == originalFragments
    val counterevidenceSearchExhaustive: Boolean get() = false
}

data class PeriodReductionReviewReport(val status: ReductionReviewStatus, val modelRequestsThisPass: Int,
    val progress: PeriodReductionReviewProgress)

/** Explicit finite passes. Successful scans are reused; neither failure nor recovery retries itself. */
class PeriodReductionReviewProcessor(private val analysis: ControlledAnalysis) {
    fun inspect(runId: String, target: ReductionStatementRef): PeriodReductionReviewProgress = try {
        snapshot(runId, target).progress
    } catch (_: AnalysisTaskUnavailableException) {
        empty(runId, target, ReductionReviewStatus.TASK_UNAVAILABLE)
    } catch (_: AnalysisDependencyBlockedException) {
        empty(runId, target, ReductionReviewStatus.DEPENDENCY_BLOCKED)
    } catch (_: StaleSnapshotException) {
        interrupted(runId, target)
    } catch (_: AnalysisConflictException) {
        interrupted(runId, target)
    } catch (_: RevisionConflictException) {
        interrupted(runId, target)
    }

    fun process(runId: String, target: ReductionStatementRef,
        scanProvider: SemanticReductionScanProvider, reviewProvider: SemanticReviewProvider,
        maxRequests: Int = 3, batchSize: Int = 8, cancelled: () -> Boolean = { false },
    ): PeriodReductionReviewReport {
        require(maxRequests in 1..100 && batchSize in 1..32)
        var requests = 0
        val scan = object : SemanticReductionScanProvider by scanProvider {
            override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
                requests++
                return scanProvider.scanPrepared(request)
            }
        }
        val review = object : SemanticReviewProvider by reviewProvider {
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                requests++
                return reviewProvider.reviewPrepared(request)
            }
        }
        fun result(status: ReductionReviewStatus, progress: PeriodReductionReviewProgress = inspect(runId, target)) =
            PeriodReductionReviewReport(if (progress.status in invalidStates) progress.status else status, requests, progress)
        try {
            while (true) {
                val current = snapshot(runId, target)
                if (current.progress.status !in setOf(ReductionReviewStatus.SCAN_READY, ReductionReviewStatus.FINAL_READY))
                    return result(current.progress.status, current.progress)
                if (cancelled()) return result(ReductionReviewStatus.CANCELLED, current.progress)
                if (requests >= maxRequests) return result(ReductionReviewStatus.PASS_LIMIT_REACHED, current.progress)
                val context = requireNotNull(current.context)
                if (current.progress.status == ReductionReviewStatus.FINAL_READY) {
                    analysis.reviewReduction(runId, key("final", context, emptyList()), target, review)
                } else {
                    var ids = current.pending.take(batchSize)
                    while (true) {
                        if (cancelled()) return result(ReductionReviewStatus.CANCELLED)
                        try {
                            analysis.scanReduction(runId, key("scan", context, ids), target, ids, scan)
                            break
                        } catch (_: AnalysisBudgetException) {
                            // Only pre-dispatch budget failures may shrink. Keep original receipt
                            // boundaries and every pending fragment; never truncate the source.
                            if (ids.size == 1) return result(ReductionReviewStatus.BUDGET_EXHAUSTED)
                            ids = ids.take((ids.size + 1) / 2)
                        }
                    }
                }
            }
        } catch (_: AnalysisBudgetException) {
            return result(ReductionReviewStatus.BUDGET_EXHAUSTED)
        } catch (_: AnalysisDependencyBlockedException) {
            return result(ReductionReviewStatus.DEPENDENCY_BLOCKED)
        } catch (_: AnalysisValidationException) {
            return result(ReductionReviewStatus.RETRY_NEEDED)
        } catch (_: AnalysisTaskUnavailableException) {
            return result(ReductionReviewStatus.TASK_UNAVAILABLE)
        } catch (_: AnalysisConflictException) {
            return result(ReductionReviewStatus.CONFLICT)
        } catch (_: RevisionConflictException) {
            return result(ReductionReviewStatus.CONFLICT)
        } catch (_: StaleSnapshotException) {
            return result(ReductionReviewStatus.STALE)
        }
    }

    private data class Snapshot(val progress: PeriodReductionReviewProgress,
        val context: ReductionReviewContext? = null, val pending: List<String> = emptyList())

    private fun snapshot(runId: String, target: ReductionStatementRef): Snapshot {
        val task = analysis.task(runId)
        blocked(task)?.let { return Snapshot(empty(runId, target, it, task)) }
        val context = analysis.reductionReviewContext(runId, target)
        val findings = context.scans.flatMap { it.result.findings }
        val scanned = findings.map { it.receiptId }.toSet()
        val pending = context.originalEvidence.map { it.id }.filter { it !in scanned }
        val related = findings.count { it.classification != ReductionRelevance.IRRELEVANT }
        val needsContext = findings.count { it.classification == ReductionRelevance.NEEDS_CONTEXT }
        val latestReview = context.latestReview
        val status = when {
            pending.isNotEmpty() -> ReductionReviewStatus.SCAN_READY
            needsContext > 0 -> ReductionReviewStatus.CONTEXT_REQUIRED
            related == 0 -> ReductionReviewStatus.NO_RELEVANT_EVIDENCE
            related > 32 -> ReductionReviewStatus.RELATED_EVIDENCE_LIMIT
            latestReview?.review?.verdict == ReviewVerdict.SUPPORTED -> ReductionReviewStatus.SUPPORTED
            latestReview != null -> ReductionReviewStatus.NOT_SUPPORTED
            else -> ReductionReviewStatus.FINAL_READY
        }
        val latest = analysis.task(runId)
        if (latest.stored.version != task.stored.version || latest.status != task.status)
            throw AnalysisConflictException("原文核对进度在读取期间发生变化")
        return Snapshot(PeriodReductionReviewProgress(runId, target, status, task.stored.source, task.stored.version,
            context.feedbackVersion, context.originalEvidence.size, scanned.size, related,
            findings.count { it.classification == ReductionRelevance.IRRELEVANT }, needsContext,
            findings.count { it.classification == ReductionRelevance.COUNTEREVIDENCE },
            latestReview?.evidence?.size ?: 0, latestReview?.review?.verdict), context, pending)
    }

    private fun key(purpose: String, context: ReductionReviewContext, ids: List<String>): String =
        "period-review-$purpose-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(
            context.target.root.batchKey, context.target.root.attempt, context.target.root.outputSha256,
            context.target.index, context.target.statementSha256, context.feedbackVersion,
            context.dependencyTokens.map { listOf(it.claim.batchKey, it.claim.claimIndex, it.claim.claimSha256, it.version) }, ids,
        )))

    private fun blocked(task: AnalysisTask): ReductionReviewStatus? = when (task.status) {
        AnalysisStatus.STALE -> ReductionReviewStatus.STALE
        AnalysisStatus.EXPIRED -> ReductionReviewStatus.EXPIRED
        AnalysisStatus.IN_FLIGHT -> ReductionReviewStatus.NEEDS_RECOVERY
        else -> null
    }
    private fun empty(runId: String, target: ReductionStatementRef, status: ReductionReviewStatus, task: AnalysisTask? = null) =
        PeriodReductionReviewProgress(runId, target, status, task?.stored?.source, task?.stored?.version)
    private fun interrupted(runId: String, target: ReductionStatementRef) = try {
        val task = analysis.task(runId)
        empty(runId, target, blocked(task) ?: ReductionReviewStatus.CONFLICT, task)
    } catch (_: AnalysisTaskUnavailableException) { empty(runId, target, ReductionReviewStatus.TASK_UNAVAILABLE) }

    private companion object {
        val invalidStates = setOf(ReductionReviewStatus.STALE, ReductionReviewStatus.EXPIRED,
            ReductionReviewStatus.TASK_UNAVAILABLE, ReductionReviewStatus.CONFLICT, ReductionReviewStatus.DEPENDENCY_BLOCKED)
    }
}
