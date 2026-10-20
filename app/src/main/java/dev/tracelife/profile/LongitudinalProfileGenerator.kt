package dev.tracelife.profile

import dev.tracelife.analysis.*
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceFieldKind
import dev.tracelife.storage.UnreadableSourceException
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

enum class LongitudinalProfileStatus {
    READY, NEEDS_ATTENTION, PASS_FINISHED, INSUFFICIENT_PERIODS, NO_TEXT, PASS_LIMIT_REACHED,
    INTEGRATING, INTEGRATED, DEPENDENCY_BLOCKED,
    CANCELLED, RETRY_NEEDED, BUDGET_EXHAUSTED, NEEDS_RECOVERY, STALE, EXPIRED, CLOCK_ROLLBACK, TASK_UNAVAILABLE, CONFLICT,
}
data class ProfilePeriodCoverage(val period: ProfileComparisonPeriod, val datedRecordsWithText: Int)
data class LongitudinalProfileProgress(val runId: String, val status: LongitudinalProfileStatus,
    val periods: List<ProfilePeriodCoverage>, val candidates: ProfileCandidateProgress,
    val integration: ReductionProgress? = null, val integratedCandidates: List<ClaimRef> = emptyList()) {
    val independentEventsEstablished: Boolean get() = false
    val counterevidenceSearchExhaustive: Boolean get() = false
}
data class LongitudinalProfileReport(val runId: String, val status: LongitudinalProfileStatus,
    val modelRequestsThisPass: Int, val generatedBatchesThisPass: Int, val reviewedThisPass: Int,
    val progress: LongitudinalProfileProgress)

data class IntegratedProfileCandidateDetail(val claim: ClaimRef, val text: String,
    val originalEvidence: List<EvidenceReceipt>, val unknowns: List<String>)

/** Cross-period original-text batches. Repeated anchors establish context, never extra coverage. */
class LongitudinalProfileGenerator(private val repository: LifeRepository, private val analysis: ControlledAnalysis,
    private val clock: Clock = Clock.systemUTC()) {
    private val candidates = ProfileCandidateGenerator(repository, analysis, clock)
    private val reduction = PeriodReductionProcessor(analysis)

    fun start(request: AnalysisRequest, id: String = UUID.randomUUID().toString()): AnalysisTask {
        val target = requireNotNull(request.profileTarget)
        require(target.layer == ProfileLayer.LONG_TERM)
        LongitudinalProfileProtocol.validateScope(target, request.scope)
        return analysis.start(request, id)
    }

    /** Complete calendar months are a useful default, not evidence that a trait is stable. */
    fun startMonthly(dimension: ProfileDimension, question: String, zoneId: String,
        completedMonths: Int = 3, limits: AnalysisLimits = AnalysisLimits(inputTokenLimit = 32_000),
        id: String = UUID.randomUUID().toString()): AnalysisTask {
        require(completedMonths in 2..12)
        val end = clock.instant().atZone(ZoneId.of(zoneId)).toLocalDate().withDayOfMonth(1)
        val periods = (completedMonths downTo 1).map { months ->
            val from = end.minusMonths(months.toLong())
            ProfileComparisonPeriod(from, from.plusMonths(1).minusDays(1))
        }
        return start(AnalysisRequest(question, AnalysisQuery(periods.first().from, periods.last().through, zoneId), limits,
            profileTarget = ProfileAnalysisTarget(dimension, ProfileLayer.LONG_TERM, periods)), id)
    }

    fun inspect(runId: String, offset: Int = 0, limit: Int = 25): LongitudinalProfileProgress {
        val progress = candidates.inspect(runId, offset, limit)
        if (progress.coverage == null) return LongitudinalProfileProgress(runId,
            LongitudinalProfileStatus.valueOf(progress.status.name), emptyList(), progress)
        require(progress.target?.layer == ProfileLayer.LONG_TERM) { "任务不是纵向画像" }
        val inventory = inventory(analysis.task(runId), 3)
        if (analysis.task(runId).stored.version != progress.taskVersion || repository.currentAnalysisGeneration() != progress.analysisGeneration)
            throw StaleSnapshotException("纵向画像在读取期间变化")
        val integrated = reduction.inspect(runId)
        val status = when {
            progress.coverage.textCodePoints == 0L -> LongitudinalProfileStatus.NO_TEXT
            inventory.periods.count { it.datedRecordsWithText > 0 } < 2 -> LongitudinalProfileStatus.INSUFFICIENT_PERIODS
            integrated.status == ReductionProcessingStatus.DEPENDENCY_BLOCKED -> LongitudinalProfileStatus.DEPENDENCY_BLOCKED
            integrated.status == ReductionProcessingStatus.TREE_INTEGRATED -> LongitudinalProfileStatus.INTEGRATED
            progress.coverage.remainingTextFragments == 0L && integrated.leafCount > 1 -> LongitudinalProfileStatus.INTEGRATING
            else -> LongitudinalProfileStatus.valueOf(progress.status.name)
        }
        val refs = if (integrated.status == ReductionProcessingStatus.TREE_INTEGRATED) {
            val node = analysis.reductionNode(runId, requireNotNull(integrated.root).batchKey)
            node.digest.statements.indices.map { ReductionGraph.feedbackRef(analysis.reductionStatementRef(runId, node.ref, it)) }
        } else emptyList()
        return LongitudinalProfileProgress(runId, status, freeze(inventory.periods), progress, integrated, freeze(refs))
    }

    fun next(previous: LongitudinalProfileProgress): LongitudinalProfileProgress {
        val next = candidates.next(previous.candidates)
        return inspect(previous.runId, requireNotNull(previous.candidates.nextOffset), next.pageSize).also {
            require(it.candidates.taskVersion == next.taskVersion && it.candidates.analysisGeneration == next.analysisGeneration)
        }
    }

    fun candidate(progress: LongitudinalProfileProgress, claim: ClaimRef): ProfileCandidateDetail =
        candidates.candidate(progress.candidates, claim)

    fun integratedCandidate(progress: LongitudinalProfileProgress, claim: ClaimRef): IntegratedProfileCandidateDetail {
        require(claim in progress.integratedCandidates) { "整体候选未在当前目录登记" }
        val task = analysis.task(progress.runId)
        require(task.stored.version == progress.candidates.taskVersion && repository.currentAnalysisGeneration() == progress.candidates.analysisGeneration)
        val source = ProfileIntegrationProtocol.resolve(task.state, claim)
        analysis.reductionDependencies(progress.runId, requireNotNull(source.integration).target.root)
        require(analysis.task(progress.runId).stored.version == task.stored.version)
        return IntegratedProfileCandidateDetail(claim, source.claim.text, freeze(source.originalEvidence),
            freeze(requireNotNull(source.call.reductionOutput).unknowns))
    }

    fun process(runId: String, generator: AnalysisProvider, reviewer: SemanticReviewProvider,
        maxModelRequests: Int = 5, fragmentsPerBatch: Int = 4,
        cancelled: () -> Boolean = { false }): LongitudinalProfileReport {
        require(maxModelRequests in 1..100 && fragmentsPerBatch in 3..32)
        var requests = 0; var generated = 0; var reviewed = 0
        fun guard() {
            val task = analysis.task(runId)
            if (clock.instant() < maxOf(task.stored.createdAt, task.stored.updatedAt)) throw StaleSnapshotException("纵向任务时钟回拨")
        }
        val countedGenerator = object : AnalysisProvider by generator {
            override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                guard(); requests++; return generator.completePrepared(request)
            }
        }
        val countedReviewer = object : SemanticReviewProvider by reviewer {
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                guard(); requests++; return reviewer.reviewPrepared(request)
            }
        }
        fun result(status: LongitudinalProfileStatus): LongitudinalProfileReport {
            val progress = inspect(runId)
            return LongitudinalProfileReport(runId, if (progress.candidates.coverage == null) progress.status else status,
                requests, generated, reviewed, progress)
        }
        try {
            while (true) {
                val progress = inspect(runId)
                if (progress.candidates.coverage == null || progress.status in setOf(LongitudinalProfileStatus.NO_TEXT,
                        LongitudinalProfileStatus.INSUFFICIENT_PERIODS)) return result(progress.status)
                if (cancelled()) return result(LongitudinalProfileStatus.CANCELLED)
                var page = progress.candidates
                var pending: ProfileCandidateItem? = null
                while (true) {
                    pending = page.candidates.firstOrNull { it.disposition == ProfileCandidateDisposition.PENDING_REVIEW }
                    if (pending != null || page.nextOffset == null) break
                    page = candidates.next(page)
                }
                val inventory = inventory(analysis.task(runId), fragmentsPerBatch)
                if (pending == null && inventory.selections.isEmpty()) {
                    if (progress.integration?.leafCount?.let { it > 1 } != true) return result(progress.status)
                    if (progress.status == LongitudinalProfileStatus.INTEGRATED) return result(progress.status)
                    if (requests >= maxModelRequests) return result(LongitudinalProfileStatus.PASS_LIMIT_REACHED)
                    val integrated = reduction.process(runId, countedGenerator, maxRequests = maxModelRequests - requests, cancelled = cancelled)
                    return result(when (integrated.status) {
                        ReductionProcessingStatus.TREE_INTEGRATED -> LongitudinalProfileStatus.INTEGRATED
                        ReductionProcessingStatus.NEEDS_INPUT_PROCESSING -> LongitudinalProfileStatus.READY
                        ReductionProcessingStatus.SINGLE_BATCH -> progress.status
                        else -> LongitudinalProfileStatus.valueOf(integrated.status.name)
                    })
                }
                if (requests >= maxModelRequests) return result(LongitudinalProfileStatus.PASS_LIMIT_REACHED)
                if (pending != null) {
                    val key = "longitudinal-review-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(runId,
                        pending.claim.batchKey, pending.claim.claimIndex, pending.claim.claimSha256, pending.feedbackVersion, reviewer.id)))
                    analysis.reviewClaim(runId, key, pending.claim, provider = countedReviewer)
                    reviewed++
                } else {
                    var batchSize = fragmentsPerBatch
                    while (true) {
                        val selections = inventory(analysis.task(runId), batchSize).selections
                        val key = "longitudinal-v1-" + AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(generator.id,
                            selections.map { listOf(it.recordId, it.field, it.start) })))
                        try {
                            analysis.synthesize(runId, key, selections, countedGenerator)
                            generated++; break
                        } catch (_: AnalysisBudgetException) {
                            if (batchSize == 3) return result(LongitudinalProfileStatus.BUDGET_EXHAUSTED)
                            batchSize = maxOf(3, batchSize / 2)
                        }
                        if (cancelled()) return result(LongitudinalProfileStatus.CANCELLED)
                    }
                }
            }
        } catch (_: AnalysisBudgetException) { return result(LongitudinalProfileStatus.BUDGET_EXHAUSTED) }
        catch (_: AnalysisValidationException) { return result(LongitudinalProfileStatus.RETRY_NEEDED) }
        catch (_: AnalysisTaskUnavailableException) { return result(LongitudinalProfileStatus.TASK_UNAVAILABLE) }
        catch (_: AnalysisDependencyBlockedException) { return result(LongitudinalProfileStatus.DEPENDENCY_BLOCKED) }
        catch (_: StaleSnapshotException) { return result(LongitudinalProfileStatus.CONFLICT) }
        catch (_: AnalysisConflictException) { return result(LongitudinalProfileStatus.CONFLICT) }
        catch (_: RevisionConflictException) { return result(LongitudinalProfileStatus.CONFLICT) }
    }

    private data class Inventory(val periods: List<ProfilePeriodCoverage>, val selections: List<EvidenceSelection>)

    /** One source metadata page at a time; pending candidates are bounded by periods × batch size. */
    private fun inventory(task: AnalysisTask, limit: Int): Inventory {
        val target = requireNotNull(task.state.request.profileTarget)
        require(target.layer == ProfileLayer.LONG_TERM)
        val pending = List(target.periods.size + 1) { mutableListOf<EvidenceSelection>() }
        val anchors = mutableMapOf<Int, EvidenceSelection>()
        val dated = IntArray(target.periods.size)
        val completed = task.state.calls.filter { it.purpose == CallPurpose.SYNTHESIS && it.status == CallStatus.SUCCEEDED }
            .flatMap { it.evidence }.groupBy { Triple(it.recordId, it.revision, it.field) }
        val size = task.state.request.limits.fragmentCodePoints
        var offset: Int? = 0
        while (offset != null) {
            val page = analysis.evidencePage(task.stored.id, offset).page
            for (source in page.items) {
                val fields = try { analysis.fields(task.stored.id, source.id) } catch (_: UnreadableSourceException) { continue }
                val texts = fields.filter { it.kind == SourceFieldKind.TEXT && it.codePoints > 0 }
                val period = LongitudinalProfileProtocol.periodFor(target, source.occurrence)
                if (texts.isNotEmpty() && period != null) dated[period]++
                val group = pending[period ?: target.periods.size]
                for (field in texts) {
                    if (period != null) anchors.putIfAbsent(period, EvidenceSelection(source.id, field.key, 0))
                    if (group.size >= limit) continue
                    val ranges = completed[Triple(source.id, source.revision, field.key)].orEmpty().sortedBy { it.start }
                    var start = 0
                    while (start < field.codePoints && group.size < limit) {
                        val end = minOf(start.toLong() + size, field.codePoints.toLong()).toInt()
                        var coveredThrough = start
                        for (ref in ranges) {
                            if (ref.start > coveredThrough) break
                            if (ref.end > coveredThrough) coveredThrough = ref.end
                        }
                        if (coveredThrough < end) { group += EvidenceSelection(source.id, field.key, start); start = end }
                        else start = if (coveredThrough >= field.codePoints) field.codePoints else coveredThrough / size * size
                    }
                }
            }
            offset = page.nextOffset
        }
        val selections = mutableListOf<EvidenceSelection>()
        if (anchors.size >= 2 && pending.any { it.isNotEmpty() }) {
            // Reserve two slots so even an unknown-date tail receives two dated comparison anchors.
            var round = 0
            while (selections.size < limit - 2 && pending.any { it.size > round }) {
                for (group in pending) {
                    group.getOrNull(round)?.let { if (selections.size < limit - 2) selections += it }
                }
                round++
            }
            val selectedPeriods = pending.take(target.periods.size).mapIndexedNotNull { index, values ->
                index.takeIf { values.any { it in selections } }
            }.toMutableSet()
            for ((period, anchor) in anchors.toSortedMap()) {
                if (selectedPeriods.size >= 2) break
                if (period !in selectedPeriods) { selections += anchor; selectedPeriods += period }
            }
            // Fill remaining room fairly; anchor repeats do not consume or manufacture coverage.
            round = 0
            while (selections.size < limit && pending.any { it.size > round }) {
                for (group in pending) group.getOrNull(round)?.let {
                    if (selections.size < limit && it !in selections) selections += it
                }
                round++
            }
        }
        if (analysis.task(task.stored.id).stored.version != task.stored.version) throw AnalysisConflictException("纵向目录在读取期间变化")
        return Inventory(target.periods.mapIndexed { index, period -> ProfilePeriodCoverage(period, dated[index]) }, selections)
    }

    private fun <T> freeze(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
}
