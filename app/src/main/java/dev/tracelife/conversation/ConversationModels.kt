package dev.tracelife.conversation

import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.transfer.Deletion
import java.time.Instant
import java.time.ZoneId

/** User-owned questions. Answers remain in their controlled analysis ledger, never copied here. */
data class ConversationThread(val id: String, val createdAt: Instant, val updatedAt: Instant)

data class ConversationTurn(
    val id: String,
    val threadId: String,
    val position: Int,
    val question: String,
    val scope: AnalysisQuery,
    val focusTopics: Set<String> = emptySet(),
    val parentTurnId: String? = null,
    val parentFindingIndex: Int? = null,
    val runId: String,
    val createdAt: Instant,
)

data class ConversationCollection(
    val threads: List<ConversationThread>, val turns: List<ConversationTurn>, val deletions: List<Deletion>,
)

object ConversationValidation {
    const val MAX_THREADS = 10_000
    const val MAX_TURNS = 50_000
    const val MAX_TURNS_PER_THREAD = 200
    const val MAX_DELETIONS = 100_000
    const val MAX_ITEM_BYTES = 32 * 1024
    const val MAX_BYTES = 32L * 1024 * 1024

    fun id(value: String) {
        require(value.isNotBlank() && value.length <= 200 && value.none(Char::isISOControl)) { "对话身份无效" }
    }
    fun validate(value: ConversationThread) {
        id(value.id)
        require(value.updatedAt >= value.createdAt) { "对话时间无效" }
    }
    fun validate(value: ConversationTurn) {
        id(value.id); id(value.threadId); id(value.runId); value.parentTurnId?.let(::id)
        require(value.runId == "reflection-v1-conversation-${value.id}") { "问题与分析任务身份不一致" }
        require(value.position in 1..MAX_TURNS_PER_THREAD) { "对话达到问题数量上限，请新建对话" }
        require(value.question.isNotBlank() && value.question.length <= 2_000) { "问题需为 1 至 2000 字" }
        require(value.parentFindingIndex == null || value.parentTurnId != null && value.parentFindingIndex in 0..999) { "追问目标无效" }
        require(value.parentTurnId != value.id) { "问题不能追问自身" }
        require(value.focusTopics.size <= 30 && value.focusTopics.all { it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl) }) { "问题主题无效" }
        require(value.scope.from <= value.scope.through && value.scope.from.year in 1..9999 && value.scope.through.year in 1..9999) { "问题时间范围无效" }
        ZoneId.of(value.scope.zoneId)
        value.scope.recordIds?.let { ids -> require(ids.size in 1..100); ids.forEach(::id) }
    }
    fun validateCollection(threads: List<ConversationThread>, turns: List<ConversationTurn>, deletions: List<Deletion>) {
        require(threads.size <= MAX_THREADS && turns.size <= MAX_TURNS && deletions.size <= MAX_DELETIONS) { "对话超过保存数量上限" }
        require(threads.map { it.id }.distinct().size == threads.size && turns.map { it.id }.distinct().size == turns.size &&
            turns.map { it.runId }.distinct().size == turns.size && deletions.map { it.id }.distinct().size == deletions.size) { "对话身份重复" }
        threads.forEach(::validate); turns.forEach(::validate)
        deletions.forEach { id(it.id); require(it.lastRevision in 1..MAX_TURNS_PER_THREAD + 1) }
        val byId = threads.associateBy { it.id }
        require(byId.keys.intersect(deletions.map { it.id }.toSet()).isEmpty()) { "对话及其删除标记同时存在" }
        val grouped = turns.groupBy { it.threadId }
        require(grouped.keys.all { it in byId }) { "问题缺少所属对话" }
        threads.forEach { thread ->
            val ordered = grouped[thread.id].orEmpty().sortedBy { it.position }
            require(ordered.size <= MAX_TURNS_PER_THREAD && ordered.map { it.position } == (1..ordered.size).toList()) { "问题顺序缺失或重复" }
            require(ordered.all { it.createdAt >= thread.createdAt && it.createdAt <= thread.updatedAt } &&
                ordered.zipWithNext().all { (a, b) -> a.createdAt <= b.createdAt }) { "问题时间顺序无效" }
            require(thread.updatedAt == (ordered.lastOrNull()?.createdAt ?: thread.createdAt)) { "对话更新时间不一致" }
            val predecessors = mutableSetOf<String>()
            ordered.forEach { turn ->
                require(turn.parentTurnId == null || turn.parentTurnId in predecessors) { "追问须指向同一对话中的已有问题" }
                predecessors += turn.id
            }
        }
        val bytes = threads.sumOf { ConversationCodec.encodeThread(it).toByteArray().size.toLong() } +
            turns.sumOf { ConversationCodec.encodeTurn(it).toByteArray().size.toLong() }
        require(bytes <= MAX_BYTES) { "对话超过保存大小上限" }
    }
    /** Preserve the whole local question chain; either side's deletion fence wins. */
    fun merge(current: ConversationCollection, incoming: ConversationCollection): ConversationCollection {
        validateCollection(current.threads, current.turns, current.deletions)
        validateCollection(incoming.threads, incoming.turns, incoming.deletions)
        val deleted = (current.deletions + incoming.deletions).groupBy { it.id }.values.map { it.maxBy { row -> row.deletedAt } }
        val erased = deleted.map { it.id }.toSet()
        val local = current.threads.map { it.id }.toSet()
        val threads = (current.threads + incoming.threads.filter { it.id !in local }).filter { it.id !in erased }
        val turns = (current.turns + incoming.turns.filter { it.threadId !in local }).filter { it.threadId !in erased }
        validateCollection(threads, turns, deleted)
        return ConversationCollection(threads, turns, deleted)
    }
}
