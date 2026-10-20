package dev.tracelife.knowledge

import dev.tracelife.analysis.AnalysisFeedback
import dev.tracelife.analysis.ClaimFeedbackView
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ExperienceMemberView(
    val change: StoredExperienceChange, val knowledge: KnowledgeView?, val feedback: ClaimFeedbackView?,
)
data class ExperienceSummary(val thread: StoredExperienceThread, val current: ExperienceMemberView)
data class ExperiencePage(
    val source: SourceStamp, val generation: Long, val evaluatedAt: Instant,
    val topic: String?, val knowledgeId: String?, val offset: Int, val limit: Int,
    val items: List<ExperienceSummary>, val nextOffset: Int?,
)
data class ExperienceView(
    val source: SourceStamp, val generation: Long, val evaluatedAt: Instant,
    val thread: StoredExperienceThread, val current: ExperienceMemberView,
    val changes: List<ExperienceMemberView>, val offset: Int, val limit: Int, val nextOffset: Int?,
)

/** User-managed continuity. No inference, model call, or implicit semantic merge occurs here. */
class KnowledgeContinuity(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun create(knowledgeId: String, revision: Int, title: String, topics: Set<String>, userNote: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): StoredExperienceThread {
        val now = clock.instant()
        val thread = StoredExperienceThread(id, 1, title.trim(), topics, now, now)
        val change = StoredExperienceChange(id, 1, knowledgeId, revision, ExperienceRelation.INITIAL, userNote?.trim()?.ifBlank { null }, now)
        return repository.saveExperienceThread(thread, change, null)
    }

    fun append(id: String, expectedVersion: Int, knowledgeId: String, revision: Int,
        relation: ExperienceRelation, userNote: String? = null,
    ): StoredExperienceThread {
        require(relation != ExperienceRelation.INITIAL) { "已有经验须选择补充、例外、收窄或替代" }
        val thread = requireNotNull(repository.experienceThread(id)) { "经验线索不存在或已删除" }
        val now = maxOf(clock.instant(), thread.updatedAt)
        val value = thread.copy(version = expectedVersion + 1, updatedAt = now)
        val change = StoredExperienceChange(id, value.version, knowledgeId, revision, relation, userNote?.trim()?.ifBlank { null }, now)
        return repository.saveExperienceThread(value, change, expectedVersion)
    }

    fun delete(id: String, expectedVersion: Int) = repository.deleteExperienceThread(id, expectedVersion)

    fun page(topic: String? = null, knowledgeId: String? = null, offset: Int = 0, limit: Int = 25,
        previous: ExperiencePage? = null,
    ): ExperiencePage = consistent(previous?.source, previous?.generation) { source, generation ->
        require(previous == null || previous.topic == topic && previous.knowledgeId == knowledgeId && previous.limit == limit)
        val at = previous?.evaluatedAt ?: clock.instant()
        val threads = repository.experienceThreads(topic, knowledgeId, offset, limit)
        val items = threads.map { thread -> ExperienceSummary(thread,
            member(requireNotNull(repository.experienceCurrentChange(thread.id)), at)) }
        val next = (offset + threads.size).takeIf { threads.size == limit && repository.experienceThreads(topic, knowledgeId, it, 1).isNotEmpty() }
        ExperiencePage(source, generation, at, topic, knowledgeId, offset, limit, items, next)
    }

    fun get(id: String, offset: Int = 0, limit: Int = 25, previous: ExperienceView? = null): ExperienceView? =
        consistent(previous?.source, previous?.generation) { source, generation ->
            require(previous == null || previous.thread.id == id && previous.limit == limit)
            val thread = repository.experienceThread(id) ?: return@consistent null
            val at = previous?.evaluatedAt ?: clock.instant()
            val changes = repository.experienceHistory(id, offset, limit).map { member(it, at) }
            ExperienceView(source, generation, at, thread, member(requireNotNull(repository.experienceCurrentChange(id)), at),
                changes, offset, limit, (offset + changes.size).takeIf { it < thread.version })
        }

    private fun member(change: StoredExperienceChange, at: Instant): ExperienceMemberView {
        val fixed = Clock.fixed(at, clock.zone)
        val knowledge = KnowledgeLibrary(repository, fixed).get(change.knowledgeId, change.knowledgeRevision)
        val feedback = knowledge?.let { AnalysisFeedback(repository, fixed).forClaim(it.value.runId, it.value.claimRef) }
        return ExperienceMemberView(change, knowledge, feedback)
    }

    private fun <T> consistent(expectedSource: SourceStamp?, expectedGeneration: Long?, block: (SourceStamp, Long) -> T): T {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        if (expectedSource != null && expectedSource != source || expectedGeneration != null && expectedGeneration != generation)
            throw StaleSnapshotException("经验或关联资料已有变化，请重新打开")
        val result = block(source, generation)
        if (source != repository.analysisSourceStamp() || generation != repository.currentAnalysisGeneration())
            throw StaleSnapshotException("经验或关联资料在读取时有变化，请重新打开")
        return result
    }
}
