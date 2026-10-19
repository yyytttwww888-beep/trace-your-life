package dev.tracelife.background

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.StaleSnapshotException
import java.io.Closeable
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Device-local, opt-in orchestration. Only ControlledAnalysis can read model evidence or dispatch.
 * All methods perform disk work and belong off the UI thread. Never publishes or confirms claims.
 */
class IncrementalOrganizer internal constructor(
    private val repository: LifeRepository,
    private val analysis: ControlledAnalysis,
    directory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val onPolicyChanged: (BackgroundPolicy) -> Unit = {},
) : Closeable {
    constructor(context: Context, repository: LifeRepository, analysis: ControlledAnalysis,
        onPolicyChanged: (BackgroundPolicy) -> Unit = {}) : this(repository, analysis,
        File(context.applicationContext.noBackupFilesDir, "incremental-analysis"), Clock.systemUTC(), onPolicyChanged)

    private val store = BackgroundStore(directory)
    private val processor = AnalysisBatchProcessor(analysis)

    fun settings(): BackgroundSettings = refresh()
    fun policy(): BackgroundPolicy = settings().policy

    /** A disabled → enabled transition authorizes only events committed after this checkpoint. */
    fun configure(policy: BackgroundPolicy, expectedVersion: Int = settings().version): BackgroundSettings {
        val current = refresh()
        checkClock(current)
        val storeId = repository.currentStoreId()
        val epoch = repository.localRestoreEpoch()
        val latest = repository.latestChangeEventId()
        val saved = store.transaction {
            val previous = requireNotNull(settings())
            checkClock(previous)
            if (previous.version != expectedVersion || previous.storeId != storeId || previous.restoreEpoch != epoch)
                throw BackgroundConflictException("后台策略或恢复状态已变化，请重新读取")
            val freshAuthorization = policy.enabled && !previous.policy.enabled
            if (freshAuthorization || !policy.enabled) clearJobs()
            previous.copy(version = next(previous.version), policy = policy,
                eventCursor = if (freshAuthorization || !policy.enabled) latest else previous.eventCursor,
                lastObservedAt = clock.instant(), requiresEnableAfterRestore = if (policy.enabled) false else previous.requiresEnableAfterRestore)
                .also(::save)
        }
        // If restore committed during configuration, this second fence revokes the new intent.
        val fenced = refresh()
        onPolicyChanged(fenced.policy)
        return if (fenced.version == saved.version) saved else fenced
    }

    fun pause(): BackgroundSettings = settings().let { configure(it.policy.copy(paused = it.policy.enabled), it.version) }
    fun resume(): BackgroundSettings = settings().let {
        require(it.policy.enabled) { "策略已停用，须明确重新启用" }
        configure(it.policy.copy(paused = false), it.version)
    }

    fun jobs(offset: Int = 0, limit: Int = 25): List<BackgroundJob> {
        refresh()
        return store.transaction { jobs(offset, limit) }
    }

    /** Replays one bounded outbox page. No provider is needed and no source body is sent anywhere. */
    fun synchronizeChanges(limit: Int = 100): Int {
        require(limit in 1..100)
        val state = refresh()
        checkClock(state)
        if (!state.policy.enabled) return 0
        val changes = repository.changeEventsAfter(state.eventCursor, limit)
        if (changes.isEmpty()) return 0
        val records = changes.filter { it.kind.startsWith("RECORD_") }.map { it.entityId }.distinct()
            .associateWith { repository.record(it) }
        if (repository.currentStoreId() != state.storeId || repository.localRestoreEpoch() != state.restoreEpoch) {
            refresh(); return 0
        }
        val now = clock.instant()
        return store.transaction {
            val latest = requireNotNull(settings())
            if (latest.storeId != state.storeId || latest.restoreEpoch != state.restoreEpoch ||
                latest.eventCursor != state.eventCursor || !latest.policy.enabled) return@transaction 0
            checkClock(latest)
            changes.forEach { event ->
                if (event.kind.startsWith("RECORD_")) {
                    val record = records[event.entityId]
                    if (record == null || record.archived) unavailable(event.entityId, now)
                    else {
                        // Reading the current revision coalesces several events from the same page.
                        val id = jobId(latest, event.entityId, record.revision)
                        supersede(event.entityId, record.revision, now)
                        if (job(id) == null) {
                            check(count() < MAX_JOBS) { "后台任务目录已满，请清理已结束项目" }
                            save(BackgroundJob(id, 1, record.id, record.revision, event.id, BackgroundJobStatus.QUEUED,
                                null, 0, 0, false, now, now))
                        }
                    }
                }
            }
            save(latest.copy(version = next(latest.version), eventCursor = changes.last().id, lastObservedAt = now))
            changes.size
        }
    }

    /** Explicit authorization to retry an uncertain/failed call; previously reserved quota remains spent. */
    fun retry(jobId: String, expectedVersion: Int): BackgroundJob {
        val lease = store.lease() ?: throw BackgroundConflictException("后台处理仍在执行")
        lease.use {
            val state = refresh()
            checkClock(state)
            require(state.policy.enabled) { "请先启用后台策略" }
            val job = store.transaction { requireNotNull(job(jobId)) }
            if (job.version != expectedVersion) throw BackgroundConflictException("后台任务已变化")
            require(job.status in setOf(BackgroundJobStatus.FAILED, BackgroundJobStatus.NEEDS_RECOVERY,
                BackgroundJobStatus.STORAGE_BLOCKED, BackgroundJobStatus.BUDGET_EXHAUSTED))
            require(job.reservedRequests < state.policy.maxRequestsPerJob) { "该任务累计请求额度已用尽" }
            var runId = job.runId
            runId?.let { id ->
                val task = taskOrNull(id)
                if (task == null || task.status == AnalysisStatus.STALE || task.status == AnalysisStatus.EXPIRED) runId = null
                else if (task.state.calls.any { it.status == CallStatus.IN_FLIGHT }) analysis.recoverInterrupted(id)
            }
            return updateJob(jobId) { current ->
                if (current.version != expectedVersion) throw BackgroundConflictException("后台任务已变化")
                current.copy(status = BackgroundJobStatus.QUEUED, runId = runId, retryAuthorized = true)
            }
        }
    }

    /** Removes only a terminal operational entry, never the authoritative analysis history. */
    fun forget(jobId: String, expectedVersion: Int) {
        val state = refresh()
        val highWater = repository.latestChangeEventId()
        store.transaction {
            val current = requireNotNull(settings())
            check(current.storeId == state.storeId && current.restoreEpoch == state.restoreEpoch && current.eventCursor >= highWater) {
                "须先处理已登记事件，避免遗漏的同修订事件重新入队"
            }
            val existing = requireNotNull(job(jobId))
            if (existing.version != expectedVersion) throw BackgroundConflictException("后台任务已变化")
            require(existing.status !in setOf(BackgroundJobStatus.QUEUED, BackgroundJobStatus.RUNNING))
            delete(jobId)
        }
    }

    fun process(
        extractor: AnalysisProvider,
        reviewer: SemanticReviewProvider,
        credentialsAvailable: Boolean = true,
        cancelled: () -> Boolean = { false },
        scheduledUnmeteredOnly: Boolean? = null,
    ): BackgroundPassReport {
        var dispatched = 0
        var finished = 0
        var operations = 0
        fun report(status: BackgroundPassStatus): BackgroundPassReport = store.transaction {
            BackgroundPassReport(status, dispatched, finished, count(BackgroundJobStatus.QUEUED),
                requireNotNull(settings()).reservedRequestsToday)
        }
        val state = refresh()
        val dispatchNetworkPolicy = scheduledUnmeteredOnly ?: state.policy.unmeteredOnly
        fun passBlock(settings: BackgroundSettings): BackgroundPassStatus? =
            blocked(settings, credentialsAvailable, cancelled) ?: if (settings.policy.unmeteredOnly != dispatchNetworkPolicy)
                BackgroundPassStatus.CONSTRAINTS_CHANGED else null
        passBlock(state)?.let { return report(it) }
        val lease = store.lease() ?: return report(BackgroundPassStatus.BUSY)
        lease.use {
            // Acquiring the OS lease proves no live dispatcher owns these leftover jobs.
            store.transaction { recoverOrphans(clock.instant()) }
            try { synchronizeChanges() } catch (_: IllegalStateException) { return report(BackgroundPassStatus.STORAGE_BLOCKED) }
            while (true) {
                val current = refresh()
                passBlock(current)?.let { return report(it) }
                if (dispatched >= current.policy.maxRequestsPerPass) return report(BackgroundPassStatus.PASS_LIMIT_REACHED)
                if (operations++ >= 100) return report(BackgroundPassStatus.PASS_LIMIT_REACHED)
                val pending = store.transaction { next() } ?: return report(when {
                    store.transaction { listOf(BackgroundJobStatus.FAILED, BackgroundJobStatus.NEEDS_RECOVERY,
                        BackgroundJobStatus.NEEDS_ATTENTION, BackgroundJobStatus.BUDGET_EXHAUSTED,
                        BackgroundJobStatus.STORAGE_BLOCKED).any { count(it) > 0 } } -> BackgroundPassStatus.NEEDS_ATTENTION
                    finished > 0 || dispatched > 0 -> BackgroundPassStatus.PROGRESSED
                    else -> BackgroundPassStatus.IDLE
                })
                updateJob(pending.id) { it.copy(status = BackgroundJobStatus.RUNNING) }
                var gateStop: BackgroundPassStatus? = null
                fun gate() {
                    val latest = refresh()
                    passBlock(latest)?.let { gateStop = it; throw DispatchStopped() }
                    if (dispatched >= latest.policy.maxRequestsPerPass) {
                        gateStop = BackgroundPassStatus.PASS_LIMIT_REACHED; throw DispatchStopped()
                    }
                    if (latest.reservedRequestsToday >= latest.policy.dailyRequestLimit) {
                        gateStop = BackgroundPassStatus.DAILY_BUDGET_EXHAUSTED; throw DispatchStopped()
                    }
                    val job = store.transaction { job(pending.id) }
                    if (job == null) { gateStop = BackgroundPassStatus.RESTORE_REQUIRES_ENABLE; throw DispatchStopped() }
                    if (job.reservedRequests >= latest.policy.maxRequestsPerJob) {
                        gateStop = BackgroundPassStatus.JOB_BUDGET_EXHAUSTED; throw DispatchStopped()
                    }
                }
                fun reserve() {
                    gate()
                    store.transaction {
                        val settings = requireNotNull(settings())
                        val job = job(pending.id) ?: run { gateStop = BackgroundPassStatus.RESTORE_REQUIRES_ENABLE; throw DispatchStopped() }
                        passBlock(settings)?.let { gateStop = it; throw DispatchStopped() }
                        if (settings.reservedRequestsToday >= settings.policy.dailyRequestLimit) {
                            gateStop = BackgroundPassStatus.DAILY_BUDGET_EXHAUSTED; throw DispatchStopped()
                        }
                        if (job.status != BackgroundJobStatus.RUNNING || job.reservedRequests >= settings.policy.maxRequestsPerJob) {
                            gateStop = BackgroundPassStatus.JOB_BUDGET_EXHAUSTED; throw DispatchStopped()
                        }
                        val now = clock.instant()
                        save(settings.copy(version = next(settings.version), reservedRequestsToday = settings.reservedRequestsToday + 1,
                            lastObservedAt = now))
                        save(job.copy(version = next(job.version), reservedRequests = job.reservedRequests + 1,
                            retryAuthorized = false, updatedAt = now))
                    }
                    // Check restore/consent again immediately before handing the bounded request to the adapter.
                    passBlock(refresh())?.let { gateStop = it; throw DispatchStopped() }
                    dispatched++
                }
                val countedExtractor = object : AnalysisProvider by extractor {
                    override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                        reserve(); return extractor.completePrepared(request)
                    }
                }
                val countedReviewer = object : SemanticReviewProvider by reviewer {
                    override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                        reserve(); return reviewer.reviewPrepared(request)
                    }
                }
                try {
                    val status = prepareOne(pending.id, countedExtractor, countedReviewer, ::gate)
                    if (gateStop != null) {
                        // No adapter dispatch happened at this gate. A failed ledger entry can be
                        // retried after explicit resume/renewed budget without duplicating a paid call.
                        updateIfPresent(pending.id) { it.copy(status = if (gateStop == BackgroundPassStatus.JOB_BUDGET_EXHAUSTED)
                            BackgroundJobStatus.BUDGET_EXHAUSTED else BackgroundJobStatus.QUEUED, retryAuthorized = true) }
                        return report(requireNotNull(gateStop))
                    }
                    updateIfPresent(pending.id) { it.copy(status = status) }
                    if (status in setOf(BackgroundJobStatus.PROPOSALS_READY, BackgroundJobStatus.NEEDS_ATTENTION,
                            BackgroundJobStatus.SOURCE_UNAVAILABLE, BackgroundJobStatus.SUPERSEDED)) finished++
                    if (status in setOf(BackgroundJobStatus.FAILED, BackgroundJobStatus.NEEDS_RECOVERY,
                            BackgroundJobStatus.STORAGE_BLOCKED, BackgroundJobStatus.BUDGET_EXHAUSTED))
                        return report(if (status == BackgroundJobStatus.STORAGE_BLOCKED) BackgroundPassStatus.STORAGE_BLOCKED
                            else BackgroundPassStatus.NEEDS_ATTENTION)
                } catch (_: Exception) {
                    // Exception messages can contain provider details; persist only a typed state.
                    val status = gateStop
                    updateIfPresent(pending.id) { it.copy(status = when (status) {
                        null -> BackgroundJobStatus.FAILED
                        BackgroundPassStatus.JOB_BUDGET_EXHAUSTED -> BackgroundJobStatus.BUDGET_EXHAUSTED
                        else -> BackgroundJobStatus.QUEUED
                    },
                        retryAuthorized = status != null) }
                    return report(status ?: BackgroundPassStatus.NEEDS_ATTENTION)
                }
            }
        }
    }

    /** Exactly one extraction or review, or a bounded local completion decision. */
    private fun prepareOne(jobId: String, extractor: AnalysisProvider, reviewer: SemanticReviewProvider,
        gate: () -> Unit): BackgroundJobStatus {
        var job = store.transaction { requireNotNull(job(jobId)) }
        val record = repository.record(job.recordId) ?: return BackgroundJobStatus.SOURCE_UNAVAILABLE
        if (record.archived) return BackgroundJobStatus.SOURCE_UNAVAILABLE
        if (record.revision != job.recordRevision) return BackgroundJobStatus.SUPERSEDED
        var task = job.runId?.let(::taskOrNull)
        // Never hide an uncertain paid failure by rebuilding a stale source task.
        if (task?.state?.calls?.any { it.status == CallStatus.IN_FLIGHT } == true) return BackgroundJobStatus.NEEDS_RECOVERY
        if (task?.state?.calls?.lastOrNull()?.status in setOf(CallStatus.FAILED, CallStatus.INTERRUPTED) && !job.retryAuthorized)
            return BackgroundJobStatus.FAILED
        if (task == null || task.status in setOf(AnalysisStatus.STALE, AnalysisStatus.EXPIRED)) {
            val settings = refresh()
            val date = clock.instant().atZone(ZoneId.of(settings.policy.zoneId)).toLocalDate()
            val occurrence = record.draft.occurrence
            val request = AnalysisRequest("逐字段整理这条新增或修订的记录，保留原始自述、未知和待核对解释。不要生成整个人画像或全周期结论。",
                AnalysisQuery(occurrence.startDate ?: date, occurrence.endDate ?: date, occurrence.zoneId,
                    recordIds = setOf(record.id)), AnalysisLimits(maxCalls = 100))
            val runId = "incremental-${job.id}-${job.runSequence + 1}"
            job = updateJob(jobId) { it.copy(runId = runId, runSequence = it.runSequence + 1) }
            try { task = analysis.start(request, runId) }
            catch (_: IllegalArgumentException) { return BackgroundJobStatus.STORAGE_BLOCKED }
        }
        val active = requireNotNull(task)
        if (active.stored.updatedAt > clock.instant()) return BackgroundJobStatus.NEEDS_ATTENTION
        // A source change between reading the record and starting a new run must not silently
        // bind a newer revision to an older job's identity and request allowance.
        val bound = analysis.evidencePage(active.stored.id).page.items.singleOrNull()
        if (bound == null || bound.id != job.recordId || bound.revision != job.recordRevision)
            return BackgroundJobStatus.SUPERSEDED
        val review = nextReview(active)
        if (review != null) {
            gate()
            try {
                analysis.reviewClaim(active.stored.id, reviewKey(review.first, review.second, reviewer.id), review.first, provider = reviewer)
            } catch (_: AnalysisValidationException) { return BackgroundJobStatus.FAILED }
            catch (_: AnalysisBudgetException) { return BackgroundJobStatus.BUDGET_EXHAUSTED }
            catch (_: StaleSnapshotException) { return afterSourceConflict(active.stored.id) }
            catch (_: AnalysisConflictException) { return BackgroundJobStatus.NEEDS_RECOVERY }
            catch (_: RevisionConflictException) { return BackgroundJobStatus.NEEDS_RECOVERY }
            return BackgroundJobStatus.QUEUED
        }
        val coverage = processor.coverage(active.stored.id)
        if (coverage.remainingTextFragments == 0L) return if (coverage.hasGaps || hasNegativeReview(active) || hasBlockedFeedback(active))
            BackgroundJobStatus.NEEDS_ATTENTION else BackgroundJobStatus.PROPOSALS_READY
        gate()
        val result = processor.process(active.stored.id, extractor, maxBatches = 1)
        return when (result.status) {
            AnalysisProcessingStatus.PASS_LIMIT_REACHED -> BackgroundJobStatus.QUEUED
            AnalysisProcessingStatus.ALL_TEXT_PROCESSED, AnalysisProcessingStatus.TEXT_COMPLETE_WITH_GAPS,
            AnalysisProcessingStatus.NO_DATA -> {
                val latest = analysis.task(active.stored.id)
                if (nextReview(latest) != null) BackgroundJobStatus.QUEUED
                else if (result.coverage?.hasGaps == true || hasNegativeReview(latest) || hasBlockedFeedback(latest)) BackgroundJobStatus.NEEDS_ATTENTION
                else BackgroundJobStatus.PROPOSALS_READY
            }
            AnalysisProcessingStatus.RETRY_NEEDED -> BackgroundJobStatus.FAILED
            AnalysisProcessingStatus.BUDGET_EXHAUSTED -> BackgroundJobStatus.BUDGET_EXHAUSTED
            AnalysisProcessingStatus.NEEDS_RECOVERY, AnalysisProcessingStatus.CONFLICT -> BackgroundJobStatus.NEEDS_RECOVERY
            AnalysisProcessingStatus.STALE, AnalysisProcessingStatus.EXPIRED -> afterSourceConflict(active.stored.id)
            AnalysisProcessingStatus.TASK_UNAVAILABLE -> BackgroundJobStatus.SOURCE_UNAVAILABLE
            AnalysisProcessingStatus.CANCELLED -> BackgroundJobStatus.QUEUED
        }
    }

    private fun nextReview(task: AnalysisTask): Pair<ClaimRef, Int>? {
        task.state.calls.filter { it.purpose == CallPurpose.EXTRACTION && it.status == CallStatus.SUCCEEDED }.forEach { call ->
            requireNotNull(call.proposal).claims.forEachIndexed { index, claim ->
                val ref = ClaimRef(call.batchKey, index, AnalysisRunCodec.claimSha256(claim))
                val id = AnalysisRunCodec.feedbackId(task.stored.id, ref)
                val feedback = repository.claimFeedback(id)
                val version = repository.claimFeedbackVersion(id)
                if ((feedback == null && version > 0) || (feedback != null && feedback.decision != ClaimFeedbackDecision.CONFIRMED))
                    return@forEachIndexed
                if (task.state.calls.none { it.purpose == CallPurpose.REVIEW && it.status == CallStatus.SUCCEEDED &&
                        it.reviewTarget == ref && it.feedbackVersion == version }) return ref to version
            }
        }
        return null
    }

    private fun hasNegativeReview(task: AnalysisTask) = task.state.calls.any {
        it.purpose == CallPurpose.REVIEW && it.status == CallStatus.SUCCEEDED && it.review?.verdict != ReviewVerdict.SUPPORTED
    }

    private fun hasBlockedFeedback(task: AnalysisTask): Boolean = task.state.calls
        .filter { it.purpose == CallPurpose.EXTRACTION && it.status == CallStatus.SUCCEEDED }.any { call ->
            requireNotNull(call.proposal).claims.withIndex().any { (index, claim) ->
                val id = AnalysisRunCodec.feedbackId(task.stored.id, ClaimRef(call.batchKey, index, AnalysisRunCodec.claimSha256(claim)))
                val feedback = repository.claimFeedback(id)
                (feedback == null && repository.claimFeedbackVersion(id) > 0) ||
                    (feedback != null && feedback.decision != ClaimFeedbackDecision.CONFIRMED)
            }
        }

    private fun afterSourceConflict(runId: String): BackgroundJobStatus {
        val task = taskOrNull(runId) ?: return BackgroundJobStatus.SOURCE_UNAVAILABLE
        return if (task.state.calls.any { it.status == CallStatus.IN_FLIGHT }) BackgroundJobStatus.NEEDS_RECOVERY
        else if (task.state.calls.lastOrNull()?.status in setOf(CallStatus.FAILED, CallStatus.INTERRUPTED)) BackgroundJobStatus.FAILED
        else BackgroundJobStatus.QUEUED
    }

    private fun refresh(): BackgroundSettings {
        val storeId = repository.currentStoreId()
        val epoch = repository.localRestoreEpoch()
        val highWater = repository.latestChangeEventId()
        val now = clock.instant()
        val day = now.atZone(ZoneOffset.UTC).toLocalDate()
        return store.transaction {
            val old = settings()
            when {
                old == null -> BackgroundSettings(1, BackgroundPolicy(), storeId, epoch, highWater, day, 0, now).also(::save)
                old.storeId != storeId || old.restoreEpoch != epoch -> {
                    clearJobs()
                    old.copy(version = next(old.version), policy = old.policy.copy(enabled = false, paused = false),
                        storeId = storeId, restoreEpoch = epoch, eventCursor = highWater,
                        lastObservedAt = maxOf(old.lastObservedAt, now), requiresEnableAfterRestore = true).also(::save)
                }
                now >= old.lastObservedAt && day > old.usageDay -> old.copy(version = next(old.version),
                    usageDay = day, reservedRequestsToday = 0, lastObservedAt = now).also(::save)
                else -> old
            }
        }
    }

    private fun blocked(settings: BackgroundSettings, credentials: Boolean, cancelled: () -> Boolean): BackgroundPassStatus? = when {
        clock.instant() < settings.lastObservedAt -> BackgroundPassStatus.CLOCK_ROLLBACK
        settings.requiresEnableAfterRestore -> BackgroundPassStatus.RESTORE_REQUIRES_ENABLE
        !settings.policy.enabled -> BackgroundPassStatus.DISABLED
        settings.policy.paused -> BackgroundPassStatus.PAUSED
        cancelled() -> BackgroundPassStatus.CANCELLED
        !credentials -> BackgroundPassStatus.CREDENTIALS_REQUIRED
        else -> null
    }
    private fun checkClock(settings: BackgroundSettings) { check(clock.instant() >= settings.lastObservedAt) { "时钟回拨，后台处理已停止" } }
    private fun updateJob(id: String, change: (BackgroundJob) -> BackgroundJob): BackgroundJob = store.transaction {
        val old = requireNotNull(job(id))
        change(old).copy(version = next(old.version), updatedAt = maxOf(old.updatedAt, clock.instant())).also(::save)
    }
    private fun updateIfPresent(id: String, change: (BackgroundJob) -> BackgroundJob) = store.transaction {
        job(id)?.let { old -> save(change(old).copy(version = next(old.version), updatedAt = maxOf(old.updatedAt, clock.instant()))) }
    }
    private fun taskOrNull(id: String): AnalysisTask? = try { analysis.task(id) } catch (_: AnalysisTaskUnavailableException) { null }
    private fun reviewKey(ref: ClaimRef, version: Int, providerId: String) = "background-review-v1-" +
        AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(ref.batchKey, ref.claimIndex, ref.claimSha256, version, providerId)))
    private fun jobId(settings: BackgroundSettings, recordId: String, revision: Int) =
        AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(settings.storeId, settings.restoreEpoch, recordId, revision)))
    private fun next(version: Int): Int { check(version < Int.MAX_VALUE); return version + 1 }
    override fun close() = store.close()
    private class DispatchStopped : IllegalStateException()
    private companion object { const val MAX_JOBS = 5_000 }
}
