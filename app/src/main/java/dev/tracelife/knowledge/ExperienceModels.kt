package dev.tracelife.knowledge

import dev.tracelife.transfer.Deletion
import java.time.Instant

/** A user-owned connection between publications, not a new claim or an evidence source. */
enum class ExperienceRelation { INITIAL, SUPPLEMENT, EXCEPTION, NARROW, REPLACE }

data class StoredExperienceThread(
    val id: String, val version: Int, val title: String, val topics: Set<String>,
    val createdAt: Instant, val updatedAt: Instant,
)

/** Never copies machine wording: deletion of a publication removes it from every reading view. */
data class StoredExperienceChange(
    val threadId: String, val sequence: Int, val knowledgeId: String, val knowledgeRevision: Int,
    val relation: ExperienceRelation, val userNote: String?, val createdAt: Instant,
)

data class ExperienceCollection(
    val threads: List<StoredExperienceThread>, val changes: List<StoredExperienceChange>,
    val deletions: List<Deletion>,
)

object ExperienceValidation {
    const val MAX_THREADS = 10_000
    const val MAX_CHANGES = 100_000
    const val MAX_DELETIONS = 100_000
    const val MAX_ITEM_BYTES = 16 * 1024
    const val MAX_BYTES = 32L * 1024 * 1024

    fun id(value: String) {
        require(value.isNotBlank() && value.length <= 200 && value.none(Char::isISOControl)) { "经验身份无效" }
    }

    fun validate(value: StoredExperienceThread) {
        id(value.id)
        require(value.version in 1 until Int.MAX_VALUE && value.title.isNotBlank() && value.title.length <= 160) { "经验名称或版本无效" }
        require(value.topics.size <= 30 && value.topics.all { it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl) }) { "经验主题无效" }
        require(value.updatedAt >= value.createdAt) { "经验更新时间无效" }
    }

    fun validate(value: StoredExperienceChange) {
        id(value.threadId); id(value.knowledgeId)
        require(value.sequence in 1 until Int.MAX_VALUE && value.knowledgeRevision in 1 until Int.MAX_VALUE) { "经验变化版本无效" }
        require((value.sequence == 1) == (value.relation == ExperienceRelation.INITIAL)) { "第一步必须为起点，后续须明确选择变化关系" }
        require(value.userNote == null || value.userNote.isNotBlank() && value.userNote.length <= 2_000) { "独立说明需为 1 至 2000 字" }
        require(value.relation !in setOf(ExperienceRelation.EXCEPTION, ExperienceRelation.NARROW, ExperienceRelation.REPLACE) || value.userNote != null) {
            "请写下这次例外、收窄或替代的原因"
        }
    }

    fun validateCollection(threads: List<StoredExperienceThread>, changes: List<StoredExperienceChange>, deletions: List<Deletion>) {
        require(threads.size <= MAX_THREADS && changes.size <= MAX_CHANGES && deletions.size <= MAX_DELETIONS) { "经验线索超过保存数量上限" }
        require(threads.map { it.id }.distinct().size == threads.size && deletions.map { it.id }.distinct().size == deletions.size) { "经验线索身份重复" }
        threads.forEach(::validate); changes.forEach(::validate)
        deletions.forEach { id(it.id); require(it.lastRevision in 1 until Int.MAX_VALUE) }
        val byId = threads.associateBy { it.id }
        require(byId.keys.intersect(deletions.map { it.id }.toSet()).isEmpty()) { "经验线索及其删除标记同时存在" }
        val grouped = changes.groupBy { it.threadId }
        require(grouped.keys == byId.keys) { "经验线索缺少形成过程或指向不存在的线索" }
        grouped.forEach { (id, events) ->
            val thread = byId.getValue(id)
            val ordered = events.sortedBy { it.sequence }
            require(ordered.size == thread.version && ordered.map { it.sequence } == (1..thread.version).toList()) { "经验形成过程不完整或重复" }
            require(ordered.map { it.knowledgeId to it.knowledgeRevision }.distinct().size == ordered.size) { "同一知识修订重复加入经验线索" }
            require(ordered.first().createdAt == thread.createdAt && ordered.last().createdAt == thread.updatedAt &&
                ordered.zipWithNext().all { (a, b) -> a.createdAt <= b.createdAt }) { "经验形成过程时间不一致" }
        }
        val bytes = threads.sumOf { ExperienceCodec.encodeThread(it).toByteArray().size.toLong() } +
            changes.sumOf { ExperienceCodec.encodeChange(it).toByteArray().size.toLong() }
        require(bytes <= MAX_BYTES) { "经验线索超过保存大小上限" }
    }

    /** Existing user decisions win over a restored chain; either side's deletion fence wins. */
    fun merge(currentThreads: List<StoredExperienceThread>, currentChanges: List<StoredExperienceChange>, currentDeleted: List<Deletion>,
        incomingThreads: List<StoredExperienceThread>, incomingChanges: List<StoredExperienceChange>, incomingDeleted: List<Deletion>,
    ): ExperienceCollection {
        validateCollection(currentThreads, currentChanges, currentDeleted)
        validateCollection(incomingThreads, incomingChanges, incomingDeleted)
        val deletions = (incomingDeleted + currentDeleted).groupBy { it.id }.values.map { values -> values.maxBy { it.deletedAt } }
        val erased = deletions.map { it.id }.toSet()
        val retained = currentThreads.map { it.id }.toSet()
        val threads = (currentThreads + incomingThreads.filter { it.id !in retained }).filter { it.id !in erased }
        val changes = (currentChanges + incomingChanges.filter { it.threadId !in retained }).filter { it.threadId !in erased }
        validateCollection(threads, changes, deletions)
        return ExperienceCollection(threads, changes, deletions)
    }
}
