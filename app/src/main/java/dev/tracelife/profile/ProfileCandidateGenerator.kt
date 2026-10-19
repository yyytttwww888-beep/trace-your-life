package dev.tracelife.profile

import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.util.Collections
import java.util.UUID

/** A bounded caller-started pass. Durable synthesis/review calls are the recovery checkpoints.
 * This API never confirms, publishes or adopts during process; materialization is user-selected. */
class ProfileCandidateGenerator(
    private val repository: LifeRepository,
    private val analysis: ControlledAnalysis,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val synthesis = PeriodSynthesisProcessor(analysis)

    fun start(request: AnalysisRequest, id: String = UUID.randomUUID().toString()): AnalysisTask {
        requireNotNull(request.profileTarget) { "画像候选任务必须指定维度和层次" }
        return analysis.start(request, id)
    }

    fun inspect(runId: String, offset: Int = 0, limit: Int = 25): ProfileCandidateProgress {
        require(offset >= 0 && limit in 1..100)
        return try { snapshot(runId, offset, limit).progress }
        catch (_: AnalysisTaskUnavailableException) { unavailable(runId, limit) }
        catch (_: AnalysisConflictException) { interrupted(runId, limit) }
        catch (_: StaleSnapshotException) { interrupted(runId, limit) }
        catch (_: RevisionConflictException) { interrupted(runId, limit) }
    }

    /** Pages are bound to both task and derived-store versions, not just an unbounded offset. */
    fun next(previous: ProfileCandidateProgress): ProfileCandidateProgress {
        assertCurrent(previous)
        val next = inspect(previous.runId, requireNotNull(previous.nextOffset) { "已到画像候选末页" }, previous.pageSize)
        assertCurrent(previous)
        return next
    }

    fun candidate(progress: ProfileCandidateProgress, claim: ClaimRef): ProfileCandidateDetail {
        assertCurrent(progress)
        require(progress.candidates.any { it.claim == claim }) { "候选未在本页登记" }
        return candidate(progress.runId, claim, requireNotNull(progress.taskVersion)).also { assertCurrent(progress) }
    }

    /** Exact identity leaf. No raw body is returned; evidence receipts retain controlled routing. */
    fun candidate(runId: String, claim: ClaimRef, expectedRunVersion: Int): ProfileCandidateDetail {
        require(expectedRunVersion > 0)
        val snapshot = snapshot(runId, 0, 100)
        if (snapshot.progress.taskVersion != expectedRunVersion) throw RevisionConflictException("画像候选任务已有新版本")
        require(snapshot.progress.coverage != null) { "画像候选任务当前不可读取" }
        val item = requireNotNull(snapshot.items.singleOrNull { it.claim == claim }) { "候选不属于此画像任务" }
        val call = requireNotNull(snapshot.task.state.calls.lastOrNull {
            it.batchKey == claim.batchKey && it.purpose == CallPurpose.SYNTHESIS && it.status == CallStatus.SUCCEEDED
        })
        val proposed = requireNotNull(call.proposal).claims[claim.claimIndex]
        val review = latestReview(snapshot.task, claim, item.feedbackVersion)?.review
        return ProfileCandidateDetail(item, proposed.copy(evidenceIds = freeze(proposed.evidenceIds)), freeze(call.evidence),
            review?.copy(supportingEvidenceIds = freeze(review.supportingEvidenceIds), contradictingEvidenceIds = freeze(review.contradictingEvidenceIds),
                issues = freeze(review.issues), unknowns = freeze(review.unknowns))).also { assertCurrent(snapshot.progress) }
    }

    fun materialize(runId: String, claim: ClaimRef, expectedRunVersion: Int): ProfileCandidateMaterialization {
        val task = analysis.task(runId)
        requireNotNull(task.state.request.profileTarget)
        require(clock.instant() >= task.stored.updatedAt && clock.instant() >= task.stored.createdAt) { "时钟回拨，不能物化画像候选" }
        return repository.materializeProfileCandidate(runId, claim, expectedRunVersion)
    }

    fun process(
        runId: String,
        generator: AnalysisProvider,
        reviewer: SemanticReviewProvider,
        maxModelRequests: Int = 5,
        fragmentsPerBatch: Int = 1,
        cancelled: () -> Boolean = { false },
    ): ProfileCandidateReport {
        require(maxModelRequests in 1..100 && fragmentsPerBatch in 1..32)
        var requests = 0
        var operations = 0
        var generated = 0
        var reviewed = 0
        fun guardTime() {
            val task = analysis.task(runId)
            if (clock.instant() < maxOf(task.stored.createdAt, task.stored.updatedAt)) throw StaleSnapshotException("画像候选任务时钟回拨")
        }
        val countedGenerator = object : AnalysisProvider by generator {
            override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                guardTime(); requests++
                return generator.completePrepared(request)
            }
        }
        val countedReviewer = object : SemanticReviewProvider by reviewer {
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                guardTime(); requests++
                return reviewer.reviewPrepared(request)
            }
        }
        fun result(status: ProfileCandidateStatus, progress: ProfileCandidateProgress = inspect(runId)) =
            ProfileCandidateReport(runId, if (progress.coverage == null) progress.status else status, requests, generated, reviewed, progress)
        try {
            while (true) {
                val current = snapshot(runId, 0, 25)
                val progress = current.progress
                if (progress.coverage == null) return result(progress.status, progress)
                if (cancelled()) return result(ProfileCandidateStatus.CANCELLED, progress)
                val next = current.items.firstOrNull { it.disposition == ProfileCandidateDisposition.PENDING_REVIEW }
                if (next == null && progress.coverage.remainingTextFragments == 0L) return result(progress.status, progress)
                if (operations >= maxModelRequests) return result(ProfileCandidateStatus.PASS_LIMIT_REACHED, progress)
                operations++
                if (next != null) {
                    analysis.reviewClaim(runId, reviewKey(runId, next, reviewer.id), next.claim, provider = countedReviewer)
                    reviewed++
                } else {
                    val report = synthesis.process(runId, countedGenerator, maxBatches = 1,
                        fragmentsPerBatch = fragmentsPerBatch, cancelled = cancelled)
                    generated += report.completedBatchesThisPass
                    when (report.status) {
                        SynthesisProcessingStatus.READY, SynthesisProcessingStatus.INPUTS_PROCESSED,
                        SynthesisProcessingStatus.INPUTS_PROCESSED_WITH_GAPS, SynthesisProcessingStatus.NO_TEXT,
                        SynthesisProcessingStatus.PASS_LIMIT_REACHED -> Unit
                        else -> return result(ProfileCandidateStatus.valueOf(report.status.name))
                    }
                }
            }
        } catch (_: AnalysisBudgetException) { return result(ProfileCandidateStatus.BUDGET_EXHAUSTED) }
        catch (_: AnalysisValidationException) { return result(ProfileCandidateStatus.RETRY_NEEDED) }
        catch (_: AnalysisTaskUnavailableException) { return result(ProfileCandidateStatus.TASK_UNAVAILABLE, unavailable(runId, 25)) }
        catch (_: AnalysisConflictException) { return result(ProfileCandidateStatus.CONFLICT) }
        catch (_: StaleSnapshotException) { return result(ProfileCandidateStatus.CONFLICT) }
        catch (_: RevisionConflictException) { return result(ProfileCandidateStatus.CONFLICT) }
        catch (error: IllegalArgumentException) {
            try { analysis.task(runId) } catch (_: AnalysisTaskUnavailableException) {
                return result(ProfileCandidateStatus.TASK_UNAVAILABLE, unavailable(runId, 25))
            }
            throw error
        }
    }

    private data class Snapshot(val task: AnalysisTask, val progress: ProfileCandidateProgress, val items: List<ProfileCandidateItem>)

    private fun snapshot(runId: String, offset: Int, limit: Int): Snapshot {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val at = clock.instant()
        val task = analysis.task(runId)
        val target = requireNotNull(task.state.request.profileTarget) { "任务不是画像候选分析" }
        blocked(task, at)?.let { return Snapshot(task, empty(task, it, generation, at, limit), emptyList()) }
        val processing = synthesis.inspect(runId)
        if (processing.coverage == null) return Snapshot(task, empty(task,
            ProfileCandidateStatus.valueOf(processing.status.name), generation, at, limit), emptyList())
        val knowledge = KnowledgeLibrary(repository, Clock.fixed(at, clock.zone))
        var validUntil = task.stored.expiresAt
        val items = task.state.calls.filter { it.purpose == CallPurpose.SYNTHESIS && it.status == CallStatus.SUCCEEDED }.flatMap { call ->
            requireNotNull(call.proposal).claims.mapIndexed { index, proposed ->
                val claim = ClaimRef(call.batchKey, index, AnalysisRunCodec.claimSha256(proposed))
                val feedbackId = AnalysisRunCodec.feedbackId(runId, claim)
                val feedback = repository.claimFeedback(feedbackId)
                val version = repository.claimFeedbackVersion(feedbackId)
                val review = latestReview(task, claim, version)?.review
                val knowledgeId = KnowledgeValidation.idFor(runId, claim)
                val linked = knowledge.get(knowledgeId)
                linked?.value?.expiresAt?.let { expiry -> if (expiry > at && validUntil?.let { expiry < it } != false) validUntil = expiry }
                linked?.value?.publishedAt?.let { published -> if (published > at && validUntil?.let { published < it } != false) validUntil = published }
                val profileId = profileCandidateId(knowledgeId, target)
                val profile = repository.profileRevision(profileId) ?: repository.profileForSlot(knowledgeId, target)
                profile?.updatedAt?.let { updated -> if (updated > at && validUntil?.let { updated < it } != false) validUntil = updated }
                val matches = profile != null && linked != null && profile.draft.dimension == target.dimension && profile.draft.layer == target.layer &&
                    profile.draft.knowledge == ProfileKnowledgeRef(linked.value.id, linked.value.revision) &&
                    linked.evidenceState == KnowledgeEvidenceState.CURRENT && linked.value.category == KnowledgeCategory.INSIGHT &&
                    linked.value.publishedAt <= at && profile.updatedAt <= at
                val disposition = when {
                    version > 0 && feedback == null -> ProfileCandidateDisposition.FEEDBACK_DELETED
                    feedback != null && feedback.decision != ClaimFeedbackDecision.CONFIRMED -> ProfileCandidateDisposition.FEEDBACK_BLOCKED
                    repository.knowledgeDeleted(knowledgeId) -> ProfileCandidateDisposition.KNOWLEDGE_DELETED
                    repository.profileDeleted(profileId) -> ProfileCandidateDisposition.PROFILE_DELETED
                    review == null -> ProfileCandidateDisposition.PENDING_REVIEW
                    review.verdict == ReviewVerdict.UNSUPPORTED -> ProfileCandidateDisposition.REVIEW_UNSUPPORTED
                    review.verdict == ReviewVerdict.CONTRADICTED -> ProfileCandidateDisposition.REVIEW_CONTRADICTED
                    review.verdict == ReviewVerdict.INSUFFICIENT_CONTEXT -> ProfileCandidateDisposition.REVIEW_INSUFFICIENT_CONTEXT
                    feedback == null -> ProfileCandidateDisposition.AWAITING_CONFIRMATION
                    profile != null && !matches -> ProfileCandidateDisposition.PROFILE_CONFLICT
                    profile != null -> ProfileCandidateDisposition.EXISTING_PROFILE
                    repository.profileMaintenanceCatalog(target, runId, claim).isNotEmpty() -> ProfileCandidateDisposition.MAINTENANCE_REQUIRED
                    else -> ProfileCandidateDisposition.READY_TO_MATERIALIZE
                }
                ProfileCandidateItem(claim, disposition, version, review?.verdict, knowledgeId, linked?.value?.revision,
                    profile?.let { ProfileHead(it.id, it.revision) }, profile?.adoption)
            }
        }
        val coverage = processing.coverage.copy(missingExactIds = Collections.unmodifiableSet(LinkedHashSet(processing.coverage.missingExactIds)))
        val status = when {
            items.any { it.disposition == ProfileCandidateDisposition.PENDING_REVIEW } || coverage.remainingTextFragments > 0 -> ProfileCandidateStatus.READY
            items.any { it.disposition != ProfileCandidateDisposition.EXISTING_PROFILE } || coverage.hasGaps -> ProfileCandidateStatus.NEEDS_ATTENTION
            coverage.textCodePoints == 0L -> ProfileCandidateStatus.NO_TEXT
            else -> ProfileCandidateStatus.PASS_FINISHED
        }
        val selected = items.drop(offset).take(limit)
        val progress = ProfileCandidateProgress(runId, status, target, source, task.stored.version, generation, at, validUntil,
            coverage, processing.integration, freeze(selected), items.size,
            (offset.toLong() + selected.size).takeIf { it < items.size }?.toInt(), limit)
        assertCurrent(progress)
        return Snapshot(task, progress, items)
    }

    private fun latestReview(task: AnalysisTask, claim: ClaimRef, version: Int) = task.state.calls.lastOrNull {
        it.purpose == CallPurpose.REVIEW && it.status == CallStatus.SUCCEEDED && it.reviewTarget == claim && it.feedbackVersion == version
    }

    private fun assertCurrent(progress: ProfileCandidateProgress) {
        val now = clock.instant()
        val task = analysis.task(progress.runId)
        if (progress.source != repository.analysisSourceStamp() || progress.analysisGeneration != repository.currentAnalysisGeneration() ||
            progress.taskVersion != task.stored.version || now < progress.evaluatedAt ||
            progress.validUntil?.let { now >= it } == true || blocked(task, now) != null)
            throw StaleSnapshotException("画像候选资料、反馈或期限已变化，请重新读取")
    }

    private fun blocked(task: AnalysisTask, at: Instant): ProfileCandidateStatus? = when {
        at < task.stored.createdAt || at < task.stored.updatedAt -> ProfileCandidateStatus.CLOCK_ROLLBACK
        task.status == AnalysisStatus.STALE -> ProfileCandidateStatus.STALE
        task.status == AnalysisStatus.EXPIRED -> ProfileCandidateStatus.EXPIRED
        task.status == AnalysisStatus.IN_FLIGHT -> ProfileCandidateStatus.NEEDS_RECOVERY
        else -> null
    }

    private fun empty(task: AnalysisTask, status: ProfileCandidateStatus, generation: Long, at: Instant, limit: Int) =
        ProfileCandidateProgress(task.stored.id, status, task.state.request.profileTarget, task.stored.source, task.stored.version,
            generation, at, task.stored.expiresAt, null, SynthesisIntegration.INCOMPLETE, emptyList(), 0, null, limit)

    private fun unavailable(runId: String, limit: Int) = ProfileCandidateProgress(runId, ProfileCandidateStatus.TASK_UNAVAILABLE,
        null, null, null, null, clock.instant(), null, null, SynthesisIntegration.INCOMPLETE, emptyList(), 0, null, limit)

    private fun interrupted(runId: String, limit: Int): ProfileCandidateProgress = try {
        val task = analysis.task(runId)
        val at = clock.instant()
        empty(task, blocked(task, at) ?: ProfileCandidateStatus.CONFLICT, repository.currentAnalysisGeneration(), at, limit)
    } catch (_: AnalysisTaskUnavailableException) { unavailable(runId, limit) }

    private fun reviewKey(runId: String, item: ProfileCandidateItem, providerId: String) = "profile-review-v1-" +
        AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(runId, item.claim.batchKey, item.claim.claimIndex,
            item.claim.claimSha256, item.feedbackVersion, providerId)))

    private fun <T> freeze(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
