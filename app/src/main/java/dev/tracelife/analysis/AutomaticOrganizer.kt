package dev.tracelife.analysis

import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.StoredKnowledgeRevision
import dev.tracelife.transfer.StaleSnapshotException

enum class OrganizationStatus {
    READY, PASS_FINISHED, NEEDS_ATTENTION, NO_DATA, PASS_LIMIT_REACHED, CANCELLED,
    RETRY_NEEDED, BUDGET_EXHAUSTED, NEEDS_RECOVERY, STALE, EXPIRED, TASK_UNAVAILABLE,
    CONFLICT, PUBLICATION_BLOCKED,
}

/** These are workflow states, not assertions that the model found every useful fact. */
enum class ClaimDisposition {
    PENDING_REVIEW, READY_TO_PUBLISH, PUBLISHED, REVIEW_UNSUPPORTED, REVIEW_CONTRADICTED,
    REVIEW_INSUFFICIENT_CONTEXT, AWAITING_CONFIRMATION, FEEDBACK_BLOCKED, FEEDBACK_DELETED,
    KNOWLEDGE_DELETED,
}

data class OrganizationClaim(
    val claim: ClaimRef,
    val kind: ClaimKind,
    val disposition: ClaimDisposition,
    val feedbackVersion: Int,
    val reviewVerdict: ReviewVerdict?,
    val knowledgeId: String,
    val knowledgeRevision: Int?,
)

/** Bounded by the task's 100 calls and 20 claims per proposal; contains no source or claim text. */
data class OrganizationProgress(
    val runId: String,
    val status: OrganizationStatus,
    val source: SourceStamp?,
    val taskVersion: Int?,
    val analysisGeneration: Long?,
    val coverage: AnalysisProcessingCoverage?,
    val claims: List<OrganizationClaim>,
) {
    val counts: Map<ClaimDisposition, Int> get() = ClaimDisposition.entries.associateWith { state ->
        claims.count { it.disposition == state }
    }
    val counterevidenceSearchExhaustive: Boolean get() = false
}

data class OrganizationReport(
    val runId: String,
    val status: OrganizationStatus,
    /** Actual provider dispatches in this pass, including failed calls. */
    val modelRequestsThisPass: Int,
    /** Successful publication operations, including concurrent idempotent reuse. */
    val publishedThisPass: Int,
    val progress: OrganizationProgress,
)

/**
 * One explicitly started, bounded pass. Existing durable calls and publications are the checkpoint;
 * no scheduler, implicit recovery, automatic negative-verdict retry, or new source access is added.
 * Call off the main thread. Every model dispatch still goes through [ControlledAnalysis].
 */
class AutomaticOrganizer(
    private val repository: LifeRepository,
    private val analysis: ControlledAnalysis,
    private val processor: AnalysisBatchProcessor,
    private val knowledge: KnowledgeLibrary,
) {
    /** No provider is required or invoked. Inconsistent reads return no partial candidate list. */
    fun inspect(runId: String): OrganizationProgress = try {
        snapshot(runId).progress
    } catch (_: AnalysisTaskUnavailableException) {
        unavailable(runId)
    } catch (_: StaleSnapshotException) {
        interruptedProgress(runId, OrganizationStatus.CONFLICT)
    } catch (_: AnalysisConflictException) {
        interruptedProgress(runId, OrganizationStatus.CONFLICT)
    } catch (_: RevisionConflictException) {
        interruptedProgress(runId, OrganizationStatus.CONFLICT)
    }

    fun process(
        runId: String,
        extractor: AnalysisProvider,
        reviewer: SemanticReviewProvider,
        maxModelRequests: Int = 5,
        cancelled: () -> Boolean = { false },
    ): OrganizationReport {
        require(maxModelRequests in 1..100) { "每轮模型请求上限须为 1 至 100" }
        var requests = 0
        var selectedOperations = 0
        var publications = 0
        // Guard before calling the executor; do not reject a paid request after it was registered.
        // A concurrent idempotent cache hit may consume a conservative operation slot, never more money.
        val countedExtractor = object : AnalysisProvider by extractor {
            override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                requests++
                return extractor.completePrepared(request)
            }
        }
        val countedReviewer = object : SemanticReviewProvider by reviewer {
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                requests++
                return reviewer.reviewPrepared(request)
            }
        }
        fun result(status: OrganizationStatus, progress: OrganizationProgress = inspect(runId)): OrganizationReport {
            val outcome = if (progress.coverage == null) progress.status else status
            return OrganizationReport(runId, outcome, requests, publications, progress)
        }

        try {
            while (true) {
                val current = snapshot(runId)
                val progress = current.progress
                if (progress.coverage == null) return result(progress.status, progress)
                if (cancelled()) return result(OrganizationStatus.CANCELLED, progress)
                val next = current.candidates.firstOrNull {
                    it.item.disposition == ClaimDisposition.READY_TO_PUBLISH ||
                        it.item.disposition == ClaimDisposition.PENDING_REVIEW
                }
                when (next?.item?.disposition) {
                    ClaimDisposition.READY_TO_PUBLISH -> {
                        if (publications >= MAX_PUBLICATIONS_PER_PASS)
                            return result(OrganizationStatus.PASS_LIMIT_REACHED, progress)
                        val previous = next.existing
                        val category = previous?.category ?: if (next.item.kind == ClaimKind.POSSIBLE_INTERPRETATION)
                            KnowledgeCategory.INSIGHT else KnowledgeCategory.OBSERVATION
                        try {
                            knowledge.publish(runId, next.item.claim, requireNotNull(next.review).batchKey, category,
                                requireNotNull(progress.taskVersion), previous?.revision, previous?.topics ?: emptySet())
                        } catch (_: IllegalArgumentException) {
                            // Capacity, deletion and publication eligibility are enforced transactionally.
                            return result(OrganizationStatus.PUBLICATION_BLOCKED)
                        }
                        publications++
                    }
                    ClaimDisposition.PENDING_REVIEW -> {
                        if (selectedOperations >= maxModelRequests)
                            return result(OrganizationStatus.PASS_LIMIT_REACHED, progress)
                        selectedOperations++
                        analysis.reviewClaim(runId, reviewKey(runId, next.item, reviewer.id), next.item.claim,
                            provider = countedReviewer)
                    }
                    else -> {
                        if (progress.coverage.remainingTextFragments == 0L) return result(progress.status, progress)
                        if (selectedOperations >= maxModelRequests)
                            return result(OrganizationStatus.PASS_LIMIT_REACHED, progress)
                        selectedOperations++
                        val extraction = processor.process(runId, countedExtractor, maxBatches = 1, cancelled = cancelled)
                        when (extraction.status) {
                            AnalysisProcessingStatus.ALL_TEXT_PROCESSED, AnalysisProcessingStatus.TEXT_COMPLETE_WITH_GAPS,
                            AnalysisProcessingStatus.NO_DATA, AnalysisProcessingStatus.PASS_LIMIT_REACHED -> Unit
                            else -> return result(OrganizationStatus.valueOf(extraction.status.name))
                        }
                    }
                }
            }
        } catch (_: AnalysisBudgetException) {
            return result(OrganizationStatus.BUDGET_EXHAUSTED)
        } catch (_: AnalysisValidationException) {
            return result(OrganizationStatus.RETRY_NEEDED)
        } catch (_: AnalysisTaskUnavailableException) {
            return result(OrganizationStatus.TASK_UNAVAILABLE, unavailable(runId))
        } catch (_: StaleSnapshotException) {
            val progress = interruptedProgress(runId, OrganizationStatus.CONFLICT)
            return result(progress.status, progress)
        } catch (_: AnalysisConflictException) {
            val progress = interruptedProgress(runId, OrganizationStatus.CONFLICT)
            return result(progress.status, progress)
        } catch (_: RevisionConflictException) {
            val progress = interruptedProgress(runId, OrganizationStatus.CONFLICT)
            return result(progress.status, progress)
        }
    }

    private data class Candidate(
        val item: OrganizationClaim,
        val review: AnalysisCall?,
        val existing: StoredKnowledgeRevision?,
    )
    private data class Snapshot(val progress: OrganizationProgress, val candidates: List<Candidate>)

    private fun snapshot(runId: String): Snapshot {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val task = analysis.task(runId)
        val blocked = blocked(task)
        val coverage = if (blocked == null) processor.coverage(runId) else null
        val candidates = if (blocked == null) candidates(task) else emptyList()
        val latest = analysis.task(runId)
        if (repository.analysisSourceStamp() != source || repository.currentAnalysisGeneration() != generation ||
            latest.stored.version != task.stored.version || latest.status != task.status)
            throw AnalysisConflictException("整理进度读取期间来源或分析发生变化")
        val items = candidates.map { it.item }
        val status = blocked ?: when {
            coverage!!.remainingTextFragments > 0 || items.any {
                it.disposition == ClaimDisposition.PENDING_REVIEW || it.disposition == ClaimDisposition.READY_TO_PUBLISH
            } -> OrganizationStatus.READY
            coverage.hasGaps || items.any { it.disposition != ClaimDisposition.PUBLISHED } -> OrganizationStatus.NEEDS_ATTENTION
            coverage.records == 0 -> OrganizationStatus.NO_DATA
            else -> OrganizationStatus.PASS_FINISHED
        }
        return Snapshot(OrganizationProgress(runId, status, task.stored.source, task.stored.version,
            generation, coverage, items), candidates)
    }

    private fun candidates(task: AnalysisTask): List<Candidate> {
        val runId = task.stored.id
        val reviews = task.state.calls.filter { it.purpose == CallPurpose.REVIEW && it.status == CallStatus.SUCCEEDED }
            .groupBy { it.reviewTarget }
        return task.state.calls.filter { it.purpose.producesClaims && it.status == CallStatus.SUCCEEDED }
            .flatMap { extraction ->
                requireNotNull(extraction.proposal).claims.mapIndexed { index, proposed ->
                    val ref = ClaimRef(extraction.batchKey, index, AnalysisRunCodec.claimSha256(proposed))
                    val feedbackId = AnalysisRunCodec.feedbackId(runId, ref)
                    val feedback = repository.claimFeedback(feedbackId)
                    val token = repository.claimFeedbackVersion(feedbackId)
                    val id = KnowledgeValidation.idFor(runId, ref)
                    val existing = repository.knowledgeRevision(id)
                    val review = reviews[ref]?.lastOrNull { it.feedbackVersion == token }
                    val verdict = review?.review?.verdict
                    val state = when {
                        feedback == null && token > 0 -> ClaimDisposition.FEEDBACK_DELETED
                        feedback != null && feedback.decision != ClaimFeedbackDecision.CONFIRMED -> ClaimDisposition.FEEDBACK_BLOCKED
                        repository.knowledgeDeleted(id) -> ClaimDisposition.KNOWLEDGE_DELETED
                        proposed.kind == ClaimKind.POSSIBLE_INTERPRETATION && feedback?.decision != ClaimFeedbackDecision.CONFIRMED ->
                            ClaimDisposition.AWAITING_CONFIRMATION
                        review == null -> ClaimDisposition.PENDING_REVIEW
                        verdict == ReviewVerdict.UNSUPPORTED -> ClaimDisposition.REVIEW_UNSUPPORTED
                        verdict == ReviewVerdict.CONTRADICTED -> ClaimDisposition.REVIEW_CONTRADICTED
                        verdict == ReviewVerdict.INSUFFICIENT_CONTEXT -> ClaimDisposition.REVIEW_INSUFFICIENT_CONTEXT
                        existing != null && existing.source == task.stored.source && existing.feedbackVersion == token &&
                            KnowledgeValidation.reviewMatchesState(existing, task.state) -> ClaimDisposition.PUBLISHED
                        else -> ClaimDisposition.READY_TO_PUBLISH
                    }
                    Candidate(OrganizationClaim(ref, proposed.kind, state, token, verdict, id, existing?.revision), review, existing)
                }
            }
    }

    private fun interruptedProgress(runId: String, fallback: OrganizationStatus): OrganizationProgress = try {
        val task = analysis.task(runId)
        OrganizationProgress(runId, blocked(task) ?: fallback, task.stored.source, task.stored.version, null, null, emptyList())
    } catch (_: AnalysisTaskUnavailableException) {
        unavailable(runId)
    }

    private fun unavailable(runId: String) = OrganizationProgress(runId, OrganizationStatus.TASK_UNAVAILABLE,
        null, null, null, null, emptyList())

    private fun blocked(task: AnalysisTask): OrganizationStatus? = when (task.status) {
        AnalysisStatus.STALE -> OrganizationStatus.STALE
        AnalysisStatus.EXPIRED -> OrganizationStatus.EXPIRED
        AnalysisStatus.IN_FLIGHT -> OrganizationStatus.NEEDS_RECOVERY
        else -> null
    }

    private fun reviewKey(runId: String, item: OrganizationClaim, providerId: String) = "organizer-review-v1-" +
        AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(runId, item.claim.batchKey, item.claim.claimIndex,
            item.claim.claimSha256, item.feedbackVersion, providerId)))

    companion object {
        private const val MAX_PUBLICATIONS_PER_PASS = 100
    }
}
