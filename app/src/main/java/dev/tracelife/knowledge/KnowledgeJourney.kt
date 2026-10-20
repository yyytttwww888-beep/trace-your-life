package dev.tracelife.knowledge

import dev.tracelife.actions.ActionExperiments
import dev.tracelife.actions.ActionView
import dev.tracelife.model.RecordFilter
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.LocalDate
import java.time.ZoneId
import java.time.Clock
import java.time.Instant

/** Reading shelves are navigation, never a change to the evidence category of a publication. */
enum class KnowledgeShelf { FINDINGS, EXPERIENCE, OBSERVATIONS, ALL }
data class KnowledgeJourneyPage(
    val filter: KnowledgeFilter, val shelf: KnowledgeShelf, val offset: Int, val limit: Int,
    val source: SourceStamp, val analysisGeneration: Long,
    val evaluatedAt: Instant,
    val entries: List<KnowledgeView>, val total: Int, val currentCount: Int, val pendingCount: Int,
    val nextOffset: Int?,
)
data class TopicOverview(val name: String, val records: Int, val saved: Int, val pending: Int, val experienceThreads: Int = 0)
data class RelatedActions(val items: List<ActionView>, val total: Int, val nextOffset: Int?)

/** Trusted local navigation only. It makes no model call and never provides a bulk model context.
 * Scans retain counters plus one requested page; original text is read only after choosing a topic.
 */
class KnowledgeJourney(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    private val knowledge = KnowledgeLibrary(repository, clock)

    fun page(filter: KnowledgeFilter, shelf: KnowledgeShelf, offset: Int = 0, limit: Int = 25,
        previous: KnowledgeJourneyPage? = null,
    ): KnowledgeJourneyPage = consistent { source, generation ->
        require(offset >= 0 && limit in 1..100)
        if (previous != null && (source != previous.source || generation != previous.analysisGeneration))
            throw StaleSnapshotException("知识有更新，请重新打开列表")
        require(previous == null || previous.filter == filter && previous.shelf == shelf && previous.limit == limit)
        val at = previous?.evaluatedAt ?: clock.instant()
        var current = 0
        var pending = 0
        var total = 0
        val entries = mutableListOf<KnowledgeView>()
        KnowledgeLibrary(repository, Clock.fixed(at, clock.zone)).scanCurrent(filter.copy(category = null, includeInvalid = true)) { view ->
            if (view.evidenceState == KnowledgeEvidenceState.CURRENT) current++ else pending++
            if (!filter.includeInvalid && view.evidenceState != KnowledgeEvidenceState.CURRENT) return@scanCurrent
            val value = view.value
            val selected = when (shelf) {
                KnowledgeShelf.FINDINGS -> value.category == KnowledgeCategory.INSIGHT
                KnowledgeShelf.EXPERIENCE -> value.category == KnowledgeCategory.KNOWLEDGE_NOTE ||
                    value.category == KnowledgeCategory.INSIGHT && value.claim.text.startsWith("值得留下：")
                KnowledgeShelf.OBSERVATIONS -> value.category == KnowledgeCategory.OBSERVATION
                KnowledgeShelf.ALL -> true
            }
            if (selected) {
                if (total >= offset && entries.size < limit) entries += view
                total++
            }
        }
        KnowledgeJourneyPage(filter, shelf, offset, limit, source, generation, at, entries, total, current, pending,
            (offset.toLong() + entries.size).takeIf { it < total }?.toInt())
    }

    /** Topic names come exclusively from saved user tags; counts are computed, not model summaries. */
    fun topics(): List<TopicOverview> = consistent { source, _ ->
        val counts = sortedMapOf<String, IntArray>()
        val scope = AnalysisQuery(LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31), ZoneId.systemDefault().id)
        var offset = 0
        do {
            val page = repository.analysisSourcePage(source, scope, offset, 100)
            page.items.forEach { row -> row.topics.forEach { counts.getOrPut(it) { IntArray(4) }[0]++ } }
            offset += page.items.size
        } while (page.nextOffset != null)
        knowledge.scanCurrent(KnowledgeFilter(includeInvalid = true)) { view ->
            view.value.topics.forEach { topic ->
                val count = counts.getOrPut(topic) { IntArray(4) }
                count[1]++
                if (view.evidenceState != KnowledgeEvidenceState.CURRENT) count[2]++
            }
        }
        var threadOffset = 0
        do {
            val threads = repository.experienceThreads(offset = threadOffset, limit = 100)
            threads.forEach { thread -> thread.topics.forEach { counts.getOrPut(it) { IntArray(4) }[3]++ } }
            threadOffset += threads.size
        } while (threads.size == 100)
        counts.map { (topic, count) -> TopicOverview(topic, count[0], count[1], count[2], count[3]) }
    }

    fun records(topic: String, offset: Int = 0, limit: Int = 10) = consistent { _, _ ->
        require(topic.isNotBlank())
        repository.listRecords(RecordFilter(topic = topic, offset = offset, limit = limit))
    }

    fun relatedActions(knowledgeId: String, offset: Int = 0, limit: Int = 10): RelatedActions = consistent { _, _ ->
        require(offset >= 0 && limit in 1..100)
        val actions = ActionExperiments(repository)
        val items = mutableListOf<ActionView>()
        var total = 0
        var cursor = 0
        do {
            val heads = repository.actionHeads(cursor, 100)
            heads.forEach { head ->
                val value = requireNotNull(repository.actionRevision(head.id, head.revision))
                if (value.draft.origin?.knowledgeId == knowledgeId) {
                    if (total >= offset && items.size < limit) items += requireNotNull(actions.get(head.id))
                    total++
                }
            }
            cursor += heads.size
        } while (heads.size == 100)
        RelatedActions(items, total, (offset.toLong() + items.size).takeIf { it < total }?.toInt())
    }

    private fun <T> consistent(block: (SourceStamp, Long) -> T): T {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val result = block(source, generation)
        if (source != repository.analysisSourceStamp() || generation != repository.currentAnalysisGeneration())
            throw StaleSnapshotException("资料在读取时有更新，请重新打开")
        return result
    }
}
