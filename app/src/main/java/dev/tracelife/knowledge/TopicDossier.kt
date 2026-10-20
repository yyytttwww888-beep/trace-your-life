package dev.tracelife.knowledge

import dev.tracelife.actions.ActionExperiments
import dev.tracelife.actions.ActionStatus
import dev.tracelife.actions.ActionView
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

data class DossierSection<T>(val items: List<T>, val total: Int)
data class TopicDossierView(val topic: String, val source: SourceStamp, val generation: Long, val evaluatedAt: Instant,
    val understandings: DossierSection<KnowledgeView>, val concerns: DossierSection<KnowledgeView>,
    val recent: DossierSection<KnowledgeView>, val pending: DossierSection<KnowledgeView>,
    val actions: DossierSection<ActionView>, val experiences: DossierSection<ExperienceSummary>,
    val records: Int, val unknownDates: Int)

/** A reading view of maintained publications, never a newly inferred summary or recurrence score. */
class TopicDossier(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun open(topic: String, limit: Int = 4): TopicDossierView {
        require(topic.isNotBlank() && topic.length <= 80 && limit in 1..20)
        val source = repository.analysisSourceStamp(); val generation = repository.currentAnalysisGeneration()
        val now = clock.instant(); val fixed = Clock.fixed(now, clock.zone)
        val library = KnowledgeLibrary(repository, fixed)
        val understandings = ArrayList<KnowledgeView>(); val concerns = ArrayList<KnowledgeView>()
        val recent = ArrayList<KnowledgeView>(); val pending = ArrayList<KnowledgeView>()
        var understandingCount = 0; var concernCount = 0; var recentCount = 0; var pendingCount = 0
        fun retain(items: MutableList<KnowledgeView>, view: KnowledgeView) {
            items += view
            items.sortWith(compareByDescending<KnowledgeView> { it.value.publishedAt }.thenBy { it.value.id })
            if (items.size > limit) items.removeAt(items.lastIndex)
        }
        library.scanCurrent(KnowledgeFilter(topics = setOf(topic), includeInvalid = true)) { view ->
            if (view.evidenceState != KnowledgeEvidenceState.CURRENT) { pendingCount++; retain(pending, view) }
            else {
                val text = view.value.claim.text
                if (text.startsWith("值得关注：") || text.startsWith("需要关注：")) { concernCount++; retain(concerns, view) }
                else if (!text.startsWith("可以尝试：")) { understandingCount++; retain(understandings, view) }
                if (view.value.publishedAt >= now.minus(30, ChronoUnit.DAYS)) { recentCount++; retain(recent, view) }
            }
        }
        val continuity = KnowledgeContinuity(repository, fixed)
        val experienceItems = mutableListOf<ExperienceSummary>()
        var experienceCount = 0; var cursor = 0
        do {
            val page = continuity.page(topic, offset = cursor, limit = 50)
            experienceCount += page.items.size
            page.items.forEach { if (experienceItems.size < limit) experienceItems += it }
            cursor = page.nextOffset ?: -1
        } while (cursor >= 0)
        val actions = ActionExperiments(repository, fixed)
        val actionItems = mutableListOf<ActionView>(); var actionCount = 0
        cursor = 0
        do {
            val heads = repository.actionHeads(cursor, 100)
            for (head in heads) {
                val value = requireNotNull(repository.actionRevision(head.id, head.revision))
                if (value.status != ActionStatus.OPEN) continue
                val origin = value.draft.origin ?: continue
                val knowledge = library.get(origin.knowledgeId, origin.revision) ?: continue
                if (topic !in knowledge.value.topics) continue
                actionCount++
                actionItems += requireNotNull(actions.get(head.id))
                actionItems.sortWith(compareBy<ActionView> { it.value.draft.reviewOn }.thenBy { it.value.id })
                if (actionItems.size > limit) actionItems.removeAt(actionItems.lastIndex)
            }
            cursor += heads.size
        } while (heads.size == 100)
        val records = repository.listRecords(dev.tracelife.model.RecordFilter(topic = topic, limit = 1))
        if (repository.analysisSourceStamp() != source || repository.currentAnalysisGeneration() != generation)
            throw StaleSnapshotException("主题资料有变化，请重新打开")
        return TopicDossierView(topic, source, generation, now, DossierSection(understandings, understandingCount),
            DossierSection(concerns, concernCount), DossierSection(recent, recentCount), DossierSection(pending, pendingCount),
            DossierSection(actionItems, actionCount), DossierSection(experienceItems, experienceCount), records.total, records.unknownTimeCount)
    }
}
