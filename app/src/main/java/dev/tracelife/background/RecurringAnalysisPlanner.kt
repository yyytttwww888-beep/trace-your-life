package dev.tracelife.background

import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.profile.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Calendar scheduling is local metadata only. Reading and generation use existing controlled workflows. */
internal class RecurringAnalysisPlanner(
    private val repository: LifeRepository,
    private val analysis: ControlledAnalysis,
    private val clock: Clock,
) {
    private val reflection = ReflectionWorkflow(repository, analysis, AnalysisFeedback(repository, clock), KnowledgeLibrary(repository, clock))
    private val profiles = LongitudinalProfileGenerator(repository, analysis, clock)

    fun configure(store: BackgroundStore, previous: BackgroundPolicy, next: BackgroundPolicy, now: Instant) {
        val today = now.atZone(ZoneId.of(next.zoneId)).toLocalDate()
        PERIODIC_KINDS.forEach { kind ->
            val changed = previous.enabled != next.enabled || previous.zoneId != next.zoneId ||
                enabled(previous, kind) != enabled(next, kind) ||
                kind == BackgroundJobKind.MONTHLY_PROFILE && previous.monthlyProfileDimension != next.monthlyProfileDimension
            if (changed) {
                store.cancelScheduled(kind, now)
                store.schedule(kind, if (next.enabled && enabled(next, kind)) periodStart(kind, today) else null)
            }
        }
    }

    /** At most eight closed periods per wake, with cursor and identity saved in the same transaction. */
    fun enqueue(store: BackgroundStore, settings: BackgroundSettings, maxJobs: Int): Int {
        if (!settings.policy.enabled || settings.policy.paused) return 0
        val now = clock.instant()
        val today = now.atZone(ZoneId.of(settings.policy.zoneId)).toLocalDate()
        var created = 0
        PERIODIC_KINDS.forEach { kind ->
            if (!enabled(settings.policy, kind)) return@forEach
            var start = store.scheduledFrom(kind) ?: periodStart(kind, today).also { store.schedule(kind, it) }
            while (created < 8) {
                val next = nextStart(kind, start)
                if (next > today) break
                val through = next.minusDays(1)
                val from = if (kind == BackgroundJobKind.MONTHLY_PROFILE) start.minusMonths(2) else start
                val dimension = settings.policy.monthlyProfileDimension.takeIf { kind == BackgroundJobKind.MONTHLY_PROFILE }
                val id = AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf("periodic-v1", settings.storeId,
                    settings.restoreEpoch, kind.name, from.toString(), through.toString(), settings.policy.zoneId, dimension?.name)))
                if (store.job(id) == null) {
                    check(store.count() < maxJobs) { "后台任务目录已满，请清理已结束项目" }
                    store.save(BackgroundJob(id, 1, "", 0, 0, BackgroundJobStatus.QUEUED, null, 0, 0,
                        false, now, now, kind, from, through, settings.policy.zoneId, dimension))
                    created++
                }
                start = next
                store.schedule(kind, start)
            }
        }
        return created
    }

    /** One request at most. Stale or deleted tasks never silently rebind to a different snapshot. */
    fun prepareOne(initial: BackgroundJob, generator: AnalysisProvider, reviewer: SemanticReviewProvider,
        gate: () -> Unit, bind: (String) -> BackgroundJob): BackgroundJobStatus {
        var job = initial
        var task = job.runId?.let { id -> try { analysis.task(id) } catch (_: AnalysisTaskUnavailableException) { null } }
        if (job.runId != null && task == null) return BackgroundJobStatus.SOURCE_UNAVAILABLE
        if (task?.state?.calls?.any { it.status == CallStatus.IN_FLIGHT } == true) return BackgroundJobStatus.NEEDS_RECOVERY
        if (task?.state?.calls?.lastOrNull()?.status in setOf(CallStatus.FAILED, CallStatus.INTERRUPTED) && !job.retryAuthorized)
            return BackgroundJobStatus.FAILED
        if (task?.status in setOf(AnalysisStatus.STALE, AnalysisStatus.EXPIRED)) return BackgroundJobStatus.REBUILD_REQUIRED
        if (task?.stored?.archivedAt != null) return BackgroundJobStatus.NEEDS_ATTENTION
        if (task == null) {
            gate()
            val id = if (job.kind == BackgroundJobKind.MONTHLY_PROFILE) "longitudinal-background-${job.id}-${job.runSequence + 1}"
                else "reflection-v1-background-${job.id}-${job.runSequence + 1}"
            job = bind(id)
            val scope = AnalysisQuery(requireNotNull(job.from), requireNotNull(job.through), requireNotNull(job.zoneId))
            val limits = AnalysisLimits(inputTokenLimit = 32_000)
            try {
                if (job.kind == BackgroundJobKind.MONTHLY_PROFILE) {
                    val periods = (0..2).map { index ->
                        val from = scope.from.plusMonths(index.toLong())
                        ProfileComparisonPeriod(from, from.plusMonths(1).minusDays(1))
                    }
                    task = profiles.start(AnalysisRequest(PROFILE_QUESTION, scope, limits,
                        profileTarget = ProfileAnalysisTarget(requireNotNull(job.profileDimension), ProfileLayer.LONG_TERM, periods)), id)
                } else {
                    val context = KnowledgeContextCatalog(repository, clock).plan(REFLECTION_QUESTION, scope, emptySet())
                    reflection.startQuestion(REFLECTION_QUESTION, scope, limits, id = id, knowledgeContext = context, includeActions = true)
                    task = analysis.task(id)
                }
            } catch (_: IllegalArgumentException) { return BackgroundJobStatus.STORAGE_BLOCKED }
        }
        val active = requireNotNull(task)
        gate()
        return if (job.kind == BackgroundJobKind.MONTHLY_PROFILE) {
            when (profiles.process(active.stored.id, generator, reviewer, maxModelRequests = 1, fragmentsPerBatch = 8).status) {
                LongitudinalProfileStatus.PASS_LIMIT_REACHED, LongitudinalProfileStatus.READY,
                LongitudinalProfileStatus.INTEGRATING -> BackgroundJobStatus.QUEUED
                LongitudinalProfileStatus.PASS_FINISHED, LongitudinalProfileStatus.INTEGRATED -> BackgroundJobStatus.PROPOSALS_READY
                LongitudinalProfileStatus.RETRY_NEEDED -> BackgroundJobStatus.FAILED
                LongitudinalProfileStatus.NEEDS_RECOVERY -> BackgroundJobStatus.NEEDS_RECOVERY
                LongitudinalProfileStatus.BUDGET_EXHAUSTED -> BackgroundJobStatus.BUDGET_EXHAUSTED
                LongitudinalProfileStatus.STALE, LongitudinalProfileStatus.EXPIRED -> BackgroundJobStatus.REBUILD_REQUIRED
                LongitudinalProfileStatus.TASK_UNAVAILABLE -> BackgroundJobStatus.SOURCE_UNAVAILABLE
                else -> BackgroundJobStatus.NEEDS_ATTENTION
            }
        } else {
            when (reflection.process(active.stored.id, generator, maxRequests = 1).status) {
                ReflectionStatus.ANALYZING, ReflectionStatus.INTEGRATING -> BackgroundJobStatus.QUEUED
                ReflectionStatus.READY -> BackgroundJobStatus.PROPOSALS_READY
                ReflectionStatus.RETRY_NEEDED -> BackgroundJobStatus.FAILED
                ReflectionStatus.NEEDS_RECOVERY -> BackgroundJobStatus.NEEDS_RECOVERY
                ReflectionStatus.BUDGET_EXHAUSTED -> BackgroundJobStatus.BUDGET_EXHAUSTED
                ReflectionStatus.STALE, ReflectionStatus.EXPIRED -> BackgroundJobStatus.REBUILD_REQUIRED
                else -> BackgroundJobStatus.NEEDS_ATTENTION
            }
        }
    }

    companion object {
        private val PERIODIC_KINDS = BackgroundJobKind.entries.filter { it != BackgroundJobKind.RECORD }
        private const val REFLECTION_QUESTION = "回看这个完整周期的记录、行动计划与实际回看，结合已有理解，找出值得保留的经验、反复遇到的问题和下一次可检验的做法。区分期望、实际结果、条件和未知，不把计划当执行，也不把记录数量当成长。每条以“值得留下：”“值得关注：”或“可以尝试：”开头；改进须包含触发条件、具体动作和回看信号。"
        private const val PROFILE_QUESTION = "比较这三个完整月份中指定侧面的经历，提出可能持续出现或发生变化的做法，保留实际日期、条件、例外和不同解释。少量重复不等于稳定人格；资料不足可不提出候选。"
        fun enabled(policy: BackgroundPolicy, kind: BackgroundJobKind): Boolean = when (kind) {
            BackgroundJobKind.RECORD -> true
            BackgroundJobKind.WEEKLY_REFLECTION -> policy.weeklyReflection
            BackgroundJobKind.MONTHLY_REFLECTION -> policy.monthlyReflection
            BackgroundJobKind.MONTHLY_PROFILE -> policy.monthlyProfileDimension != null
        }
        private fun periodStart(kind: BackgroundJobKind, date: LocalDate): LocalDate =
            if (kind == BackgroundJobKind.WEEKLY_REFLECTION) date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else date.withDayOfMonth(1)
        private fun nextStart(kind: BackgroundJobKind, date: LocalDate): LocalDate =
            if (kind == BackgroundJobKind.WEEKLY_REFLECTION) date.plusWeeks(1) else date.plusMonths(1)
    }
}
