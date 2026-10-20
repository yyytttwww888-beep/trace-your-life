package dev.tracelife.profile

import dev.tracelife.analysis.*
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.util.Collections

enum class ProfileMaintenanceAction { KEEP, NEW, NARROW, REPLACE }
data class ProfileMaintenanceApplication(
    val action: ProfileMaintenanceAction,
    val knowledge: ProfileKnowledgeRef?,
    val profile: ProfileHead?,
    val adoption: ProfileAdoption?,
    val reused: Boolean = false,
)

enum class ProfileMaintenanceStatus {
    PLAN_REQUIRED, RELATIONS_READY, SCAN_READY, FINAL_READY, READY_TO_APPLY, NEEDS_ATTENTION,
    NOT_SUPPORTED, CONTEXT_REQUIRED, NO_TEXT, NO_RELEVANT_EVIDENCE, RELATED_EVIDENCE_LIMIT,
    PASS_LIMIT_REACHED, CANCELLED, RETRY_NEEDED, BUDGET_EXHAUSTED, NEEDS_RECOVERY,
    DEPENDENCY_BLOCKED, STALE, EXPIRED, CLOCK_ROLLBACK, TASK_UNAVAILABLE, CONFLICT,
}

/** A directory row has identities and classifications, never copied claims or user writing. */
data class ProfileMaintenanceIndexItem(
    val reference: ProfileMaintenanceReference,
    val relation: ProfileRelation?,
    val recommendation: ProfileMaintenanceRecommendation?,
)

data class ProfileMaintenanceProgress(
    val runId: String,
    val claim: ClaimRef,
    val status: ProfileMaintenanceStatus,
    val planId: String? = null,
    val source: SourceStamp? = null,
    val taskVersion: Int? = null,
    val analysisGeneration: Long? = null,
    val evaluatedAt: Instant? = null,
    val validUntil: Instant? = null,
    val references: List<ProfileMaintenanceIndexItem> = emptyList(),
    val totalReferences: Int = 0,
    val comparedReferences: Int = 0,
    val nextOffset: Int? = null,
    val pageSize: Int = 25,
    val coverage: AnalysisProcessingCoverage? = null,
    val scannedFragments: Int = 0,
    val relatedFragments: Int = 0,
    val counterevidenceFragments: Int = 0,
    val contextRequiredFragments: Int = 0,
    val finalRereadFragments: Int = 0,
    val verdict: ReviewVerdict? = null,
    val feedbackVersion: Int? = null,
    val newEntrySuggested: Boolean = false,
) {
    val allReferencesCompared: Boolean get() = planId != null && comparedReferences == totalReferences
    val allAvailableTextScanned: Boolean get() = coverage?.let { it.textCodePoints > 0 && it.remainingTextFragments == 0L } == true
    /** Coverage describes this plan and recorded range, never the unrecorded rest of life. */
    val counterevidenceSearchExhaustive: Boolean get() = false
}

data class ProfileMaintenanceReport(
    val status: ProfileMaintenanceStatus,
    val modelRequestsThisPass: Int,
    val progress: ProfileMaintenanceProgress,
)

/** Suggestions are reviewable choices. A model suggestion never changes an adoption decision. */
data class ProfileMaintenanceSuggestion(
    val reference: ProfileMaintenanceReference?,
    val relation: ProfileRelation?,
    val recommendation: ProfileMaintenanceRecommendation?,
    val suggestedAction: ProfileMaintenanceAction?,
    val reason: String?,
    val requiresOverrideReason: Boolean = false,
)

/** Caller-started finite passes over a fixed control directory and the complete raw-text scope.
 * The executor owns model reads, budgets and durable checkpoints; this class never fabricates
 * confirmation, publishes automatically, or sends a complete profile to a model. */
class ProfileMaintenance(
    private val repository: LifeRepository,
    private val analysis: ControlledAnalysis,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val inventory = TextAnalysisInventory(analysis)

    fun begin(runId: String, claim: ClaimRef): ProfileMaintenanceProgress {
        guardTime(runId)
        analysis.planProfileMaintenance(runId, claim)
        return inspect(runId, claim)
    }

    fun inspect(runId: String, claim: ClaimRef, offset: Int = 0, limit: Int = 25): ProfileMaintenanceProgress {
        require(offset >= 0 && limit in 1..100)
        return try { snapshot(runId, claim, offset, limit, 0).progress }
        catch (_: AnalysisTaskUnavailableException) { empty(runId, claim, ProfileMaintenanceStatus.TASK_UNAVAILABLE, limit) }
        catch (_: AnalysisDependencyBlockedException) { empty(runId, claim, ProfileMaintenanceStatus.DEPENDENCY_BLOCKED, limit) }
        catch (_: AnalysisConflictException) { interrupted(runId, claim, limit) }
        catch (_: RevisionConflictException) { interrupted(runId, claim, limit) }
        catch (_: StaleSnapshotException) { interrupted(runId, claim, limit) }
    }

    fun next(previous: ProfileMaintenanceProgress): ProfileMaintenanceProgress {
        assertCurrent(previous)
        return inspect(previous.runId, previous.claim, requireNotNull(previous.nextOffset) { "已到维护目录末页" }, previous.pageSize)
            .also { assertCurrent(previous) }
    }

    /** Only the selected registered comparison is expanded; this is not a bulk profile read. */
    fun relation(progress: ProfileMaintenanceProgress, reference: ProfileMaintenanceReference): ProfileRelationResult? {
        assertCurrent(progress)
        require(progress.references.any { it.reference == reference }) { "比较对象未在本页登记" }
        val context = analysis.profileMaintenanceContext(progress.runId, progress.claim)
        val result = context.relations.singleOrNull { it.profileReference == reference }?.profileRelation
        assertCurrent(progress)
        return result?.copy(unknowns = freeze(result.unknowns))
    }

    /** One page of suggestions; reason text is expanded only through this explicit leaf route. */
    fun suggestions(progress: ProfileMaintenanceProgress): List<ProfileMaintenanceSuggestion> {
        assertCurrent(progress)
        require(progress.allReferencesCompared) { "尚未完成目录比较" }
        val context = analysis.profileMaintenanceContext(progress.runId, progress.claim)
        val constrained = context.relations.filter { it.profileRelation?.relation != ProfileRelation.UNRELATED }.any { call ->
            val reference = requireNotNull(call.profileReference)
            val content = repository.profileMaintenanceReference(reference)
            reference.kind == ProfileMaintenanceReferenceKind.FEEDBACK ||
                content.adoption in setOf(ProfileAdoption.DECLINED, ProfileAdoption.SUPERSEDED) ||
                content.feedbackDecision in setOf(ClaimFeedbackDecision.CORRECTED, ClaimFeedbackDecision.REJECTED, ClaimFeedbackDecision.WITHDRAWN)
        }
        val suggestions = progress.references.map { item ->
            val result = relation(progress, item.reference)
            val action = if (item.reference.kind == ProfileMaintenanceReferenceKind.PROFILE && result?.relation != ProfileRelation.UNRELATED) when (result?.recommendation) {
                ProfileMaintenanceRecommendation.KEEP -> ProfileMaintenanceAction.KEEP
                ProfileMaintenanceRecommendation.NARROW -> ProfileMaintenanceAction.NARROW
                ProfileMaintenanceRecommendation.REPLACE -> ProfileMaintenanceAction.REPLACE
                else -> null
            } else null
            val override = constrained && action in setOf(ProfileMaintenanceAction.NARROW, ProfileMaintenanceAction.REPLACE)
            ProfileMaintenanceSuggestion(item.reference, result?.relation, result?.recommendation, action, result?.reason, override)
        }.toMutableList()
        if (progress.newEntrySuggested) {
            suggestions += ProfileMaintenanceSuggestion(null, null, null, ProfileMaintenanceAction.NEW, null, constrained)
        }
        assertCurrent(progress)
        return freeze(suggestions)
    }

    fun process(
        runId: String,
        claim: ClaimRef,
        relationProvider: SemanticProfileRelationProvider,
        scanProvider: SemanticReductionScanProvider,
        reviewProvider: SemanticReviewProvider,
        maxModelRequests: Int = 5,
        fragmentsPerBatch: Int = 1,
        cancelled: () -> Boolean = { false },
    ): ProfileMaintenanceReport {
        require(maxModelRequests in 1..100 && fragmentsPerBatch in 1..32)
        var requests = 0
        val related = object : SemanticProfileRelationProvider by relationProvider {
            override fun relatePrepared(request: PreparedModelRequest): ProfileRelationCompletion {
                guardTime(runId); requests++
                return relationProvider.relatePrepared(request)
            }
        }
        val scanned = object : SemanticReductionScanProvider by scanProvider {
            override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
                guardTime(runId); requests++
                return scanProvider.scanPrepared(request)
            }
        }
        val reviewed = object : SemanticReviewProvider by reviewProvider {
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                guardTime(runId); requests++
                return reviewProvider.reviewPrepared(request)
            }
        }
        fun result(status: ProfileMaintenanceStatus, progress: ProfileMaintenanceProgress = inspect(runId, claim)) =
            ProfileMaintenanceReport(if (progress.status in invalidStates) progress.status else status, requests, progress)
        try {
            while (true) {
                val current = snapshot(runId, claim, 0, 25, fragmentsPerBatch)
                val progress = current.progress
                if (progress.status !in activeStates) return result(progress.status, progress)
                if (cancelled()) return result(ProfileMaintenanceStatus.CANCELLED, progress)
                if (requests >= maxModelRequests) return result(ProfileMaintenanceStatus.PASS_LIMIT_REACHED, progress)
                val context = requireNotNull(current.context)
                when (progress.status) {
                    ProfileMaintenanceStatus.RELATIONS_READY -> {
                        val reference = context.plan.references.first { ref -> context.relations.none { it.profileReference == ref } }
                        analysis.relateProfile(runId, key("relation", progress,
                            listOf(reference.kind.name, reference.id, reference.version, reference.sha256)), claim, reference, related)
                    }
                    ProfileMaintenanceStatus.SCAN_READY -> {
                        var selections = current.selections
                        require(selections.isNotEmpty()) { "扫描覆盖与待处理片段不一致" }
                        while (true) {
                            if (cancelled()) return result(ProfileMaintenanceStatus.CANCELLED)
                            try {
                                analysis.scanProfile(runId, key("scan", progress, selections.map { listOf(it.recordId, it.field, it.start) }),
                                    claim, selections, scanned)
                                break
                            } catch (_: AnalysisBudgetException) {
                                if (selections.size == 1) return result(ProfileMaintenanceStatus.BUDGET_EXHAUSTED)
                                selections = selections.take((selections.size + 1) / 2)
                            }
                        }
                    }
                    ProfileMaintenanceStatus.FINAL_READY -> analysis.reviewProfileMaintenance(runId,
                        key("review", progress, emptyList()), claim, reviewed)
                    else -> error("不支持的画像维护阶段")
                }
            }
        } catch (_: AnalysisBudgetException) { return result(ProfileMaintenanceStatus.BUDGET_EXHAUSTED) }
        catch (_: AnalysisValidationException) { return result(ProfileMaintenanceStatus.RETRY_NEEDED) }
        catch (_: AnalysisDependencyBlockedException) { return result(ProfileMaintenanceStatus.DEPENDENCY_BLOCKED) }
        catch (_: AnalysisTaskUnavailableException) { return result(ProfileMaintenanceStatus.TASK_UNAVAILABLE) }
        catch (_: AnalysisConflictException) { return result(ProfileMaintenanceStatus.CONFLICT) }
        catch (_: RevisionConflictException) { return result(ProfileMaintenanceStatus.CONFLICT) }
        catch (_: StaleSnapshotException) { return result(ProfileMaintenanceStatus.CONFLICT) }
    }

    fun apply(
        runId: String,
        claim: ClaimRef,
        expectedRunVersion: Int,
        planId: String,
        action: ProfileMaintenanceAction,
        targetProfileId: String? = null,
        expectedProfileRevision: Int? = null,
        operationId: String,
        overrideReason: String? = null,
    ): ProfileMaintenanceApplication {
        guardTime(runId)
        return repository.applyProfileMaintenance(runId, claim, expectedRunVersion, planId, action,
            targetProfileId, expectedProfileRevision, operationId, overrideReason)
    }

    private data class Snapshot(
        val progress: ProfileMaintenanceProgress,
        val context: ProfileMaintenanceContext? = null,
        val selections: List<EvidenceSelection> = emptyList(),
    )

    private fun snapshot(runId: String, claim: ClaimRef, offset: Int, limit: Int, selectionLimit: Int): Snapshot {
        val task = analysis.task(runId)
        val at = clock.instant()
        blocked(task, at)?.let { return Snapshot(empty(runId, claim, it, limit)) }
        requireNotNull(task.state.request.profileTarget) { "任务不是画像候选分析" }
        val plan = task.state.profileMaintenancePlans.singleOrNull { it.target == claim }
            ?: return Snapshot(empty(runId, claim, ProfileMaintenanceStatus.PLAN_REQUIRED, limit))
        val generation = repository.currentAnalysisGeneration()
        val context = analysis.profileMaintenanceContext(runId, claim)
        val scan = inventory.build(task, CallPurpose.PROFILE_SCAN, selectionLimit, context.scans.flatMap { it.evidence })
        val findings = context.scans.flatMap { requireNotNull(it.profileScan).findings }
        val related = findings.filter { it.classification != ReductionRelevance.IRRELEVANT }.map { it.receiptId }.toSet()
        // Review always includes the entire generation batch even if relevance says otherwise.
        val generationCall = requireNotNull(task.state.calls.lastOrNull { it.batchKey == claim.batchKey && it.status == CallStatus.SUCCEEDED })
        val finalIds = related + generationCall.evidence.map { it.id }
        val needsContext = findings.count { it.classification == ReductionRelevance.NEEDS_CONTEXT }
        val status = when {
            context.relations.any { it.profileRelation?.relation == ProfileRelation.UNCERTAIN } -> ProfileMaintenanceStatus.NEEDS_ATTENTION
            context.relations.size < plan.references.size -> ProfileMaintenanceStatus.RELATIONS_READY
            scan.coverage.remainingTextFragments > 0 -> ProfileMaintenanceStatus.SCAN_READY
            needsContext > 0 || scan.coverage.unreadableRecords > 0 -> ProfileMaintenanceStatus.CONTEXT_REQUIRED
            scan.coverage.textCodePoints == 0L -> ProfileMaintenanceStatus.NO_TEXT
            finalIds.isEmpty() -> ProfileMaintenanceStatus.NO_RELEVANT_EVIDENCE
            finalIds.size > 32 -> ProfileMaintenanceStatus.RELATED_EVIDENCE_LIMIT
            context.latestReview?.review?.verdict == ReviewVerdict.SUPPORTED -> ProfileMaintenanceStatus.READY_TO_APPLY
            context.latestReview != null -> ProfileMaintenanceStatus.NOT_SUPPORTED
            else -> ProfileMaintenanceStatus.FINAL_READY
        }
        val rows = plan.references.drop(offset).take(limit).map { ref ->
            val result = context.relations.singleOrNull { it.profileReference == ref }?.profileRelation
            ProfileMaintenanceIndexItem(ref.copy(feedbackVersions = Collections.unmodifiableMap(LinkedHashMap(ref.feedbackVersions))),
                result?.relation, result?.recommendation)
        }
        val coverage = scan.coverage.copy(missingExactIds = Collections.unmodifiableSet(LinkedHashSet(scan.coverage.missingExactIds)))
        val progress = ProfileMaintenanceProgress(runId, claim, status, ProfileMaintenanceProtocol.planSha256(plan), task.stored.source,
            task.stored.version, generation, at, task.stored.expiresAt, freeze(rows), plan.references.size, context.relations.size,
            (offset.toLong() + rows.size).takeIf { it < plan.references.size }?.toInt(), limit, coverage, findings.size,
            related.size, findings.count { it.classification == ReductionRelevance.COUNTEREVIDENCE }, needsContext,
            context.latestReview?.evidence?.size ?: 0, context.latestReview?.review?.verdict,
            repository.claimFeedbackVersion(AnalysisRunCodec.feedbackId(runId, claim)),
            status == ProfileMaintenanceStatus.READY_TO_APPLY && context.relations.all {
                it.profileReference?.kind != ProfileMaintenanceReferenceKind.PROFILE || it.profileRelation?.relation == ProfileRelation.UNRELATED
            })
        assertCurrent(progress)
        return Snapshot(progress, context, scan.selections)
    }

    private fun assertCurrent(progress: ProfileMaintenanceProgress) {
        val task = analysis.task(progress.runId)
        val now = clock.instant()
        if (progress.taskVersion == null || task.stored.version != progress.taskVersion ||
            progress.source == null || !repository.analysisSourceCurrent(progress.source, task.state.request.scope) || repository.currentAnalysisGeneration() != progress.analysisGeneration ||
            progress.evaluatedAt == null || now < progress.evaluatedAt || progress.validUntil?.let { now >= it } == true || blocked(task, now) != null)
            throw StaleSnapshotException("画像维护资料、反馈或期限已变化，请重新读取")
        val context = analysis.profileMaintenanceContext(progress.runId, progress.claim)
        if (ProfileMaintenanceProtocol.planSha256(context.plan) != progress.planId)
            throw StaleSnapshotException("画像维护目录已变化，请重新读取")
    }

    private fun key(purpose: String, progress: ProfileMaintenanceProgress, input: List<Any>): String =
        "profile-maintenance-$purpose-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(progress.planId,
            if (purpose == "review") progress.feedbackVersion else null, input)))

    private fun guardTime(runId: String) {
        val task = analysis.task(runId)
        if (clock.instant() < maxOf(task.stored.createdAt, task.stored.updatedAt))
            throw StaleSnapshotException("画像维护时钟回拨")
    }

    private fun blocked(task: AnalysisTask, at: Instant): ProfileMaintenanceStatus? = when {
        at < task.stored.createdAt || at < task.stored.updatedAt -> ProfileMaintenanceStatus.CLOCK_ROLLBACK
        task.status == AnalysisStatus.STALE -> ProfileMaintenanceStatus.STALE
        task.status == AnalysisStatus.EXPIRED -> ProfileMaintenanceStatus.EXPIRED
        task.status == AnalysisStatus.IN_FLIGHT -> ProfileMaintenanceStatus.NEEDS_RECOVERY
        else -> null
    }

    private fun interrupted(runId: String, claim: ClaimRef, limit: Int) = try {
        val task = analysis.task(runId)
        empty(runId, claim, blocked(task, clock.instant()) ?: ProfileMaintenanceStatus.CONFLICT, limit)
    } catch (_: AnalysisTaskUnavailableException) { empty(runId, claim, ProfileMaintenanceStatus.TASK_UNAVAILABLE, limit) }

    private fun empty(runId: String, claim: ClaimRef, status: ProfileMaintenanceStatus, limit: Int) =
        ProfileMaintenanceProgress(runId, claim, status, pageSize = limit)
    private fun <T> freeze(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

    private companion object {
        val activeStates = setOf(ProfileMaintenanceStatus.RELATIONS_READY, ProfileMaintenanceStatus.SCAN_READY, ProfileMaintenanceStatus.FINAL_READY)
        val invalidStates = setOf(ProfileMaintenanceStatus.DEPENDENCY_BLOCKED, ProfileMaintenanceStatus.STALE,
            ProfileMaintenanceStatus.EXPIRED, ProfileMaintenanceStatus.CLOCK_ROLLBACK, ProfileMaintenanceStatus.TASK_UNAVAILABLE,
            ProfileMaintenanceStatus.CONFLICT, ProfileMaintenanceStatus.NEEDS_RECOVERY)
    }
}
