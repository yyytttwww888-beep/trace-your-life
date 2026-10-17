package dev.tracelife.knowledge

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.AnalysisTaskUnavailableException
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.StoredKnowledgeRevision
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

enum class KnowledgeEvidenceState { CURRENT, STALE, EXPIRED, FEEDBACK_CHANGED, REVIEW_CHANGED }
data class KnowledgeView(
    val value: StoredKnowledgeRevision,
    val isCurrentRevision: Boolean,
    val evidenceState: KnowledgeEvidenceState,
    val userDecision: ClaimFeedbackDecision?,
)

/** Date filters locate the analysis scope, not an invented occurrence date for the statement. */
data class KnowledgeFilter(
    val from: LocalDate? = null,
    val through: LocalDate? = null,
    val topics: Set<String>? = null,
    val category: KnowledgeCategory? = null,
    val includeInvalid: Boolean = false,
) {
    init {
        require(listOfNotNull(from, through).all { it.year in 1..9999 })
        require(from == null || through == null || from <= through)
        require(topics == null || topics.size in 1..30 && topics.all { it.isNotBlank() && it.length <= 80 })
    }
}

/** Navigation deliberately contains no claim, review or source body. */
data class KnowledgeIndexItem(
    val id: String, val revision: Int, val category: KnowledgeCategory, val topics: Set<String>,
    val from: LocalDate, val through: LocalDate, val evidenceState: KnowledgeEvidenceState,
)
data class KnowledgeIndexPage(
    val source: SourceStamp, val analysisGeneration: Long, val evaluatedAt: Instant,
    val items: List<KnowledgeIndexItem>, val total: Int, val omittedInvalid: Int, val nextOffset: Int?,
    val filter: KnowledgeFilter, val pageSize: Int,
)

/** Trusted application API, never an unrestricted model tool. Publications are not new evidence. */
class KnowledgeLibrary(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun publish(
        runId: String, claim: ClaimRef, reviewKey: String, category: KnowledgeCategory,
        expectedRunVersion: Int, expectedRevision: Int? = null, topics: Set<String> = emptySet(),
    ): StoredKnowledgeRevision {
        require(expectedRevision == null || expectedRevision in 1 until Int.MAX_VALUE - 1)
        val run = repository.analysisRun(runId) ?: throw AnalysisTaskUnavailableException()
        val previous = repository.knowledgeRevision(KnowledgeValidation.idFor(runId, claim))
        val now = clock.instant()
        val value = KnowledgeValidation.fromRun(run, claim, reviewKey, category, topics,
            (expectedRevision ?: 0) + 1, previous?.createdAt ?: now, now)
        return repository.publishKnowledge(value, expectedRevision, expectedRunVersion)
    }

    fun get(id: String, revision: Int? = null): KnowledgeView? = consistentRead { source, _, now ->
        val value = repository.knowledgeRevision(id, revision) ?: return@consistentRead null
        view(value, repository.knowledgeRevision(id)?.revision == value.revision, source, now)
    }

    fun history(id: String, offset: Int = 0, limit: Int = 25): List<KnowledgeView> = consistentRead { source, _, now ->
        val head = repository.knowledgeRevision(id)?.revision
        val states = mutableMapOf<String, Pair<SourceStamp, AnalysisRunState>?>()
        repository.knowledgeHistory(id, offset, limit).map { view(it, it.revision == head, source, now, states) }
    }

    fun index(filter: KnowledgeFilter = KnowledgeFilter(), limit: Int = 25): KnowledgeIndexPage = indexPage(filter, 0, limit)

    fun next(page: KnowledgeIndexPage): KnowledgeIndexPage = indexPage(page.filter,
        requireNotNull(page.nextOffset) { "已到知识目录末页" }, page.pageSize, page)

    /** Trusted derived-view builder: one snapshot, one head scan and a shared task decode cache.
     * The visitor must retain only the bounded result it needs; this is not a model-reading API. */
    internal fun scanCurrent(filter: KnowledgeFilter = KnowledgeFilter(), visit: (KnowledgeView) -> Unit) = consistentRead { source, _, now ->
        val states = mutableMapOf<String, Pair<SourceStamp, AnalysisRunState>?>()
        var offset = 0
        do {
            val heads = repository.knowledgeHeads(offset, 100)
            heads.forEach { head ->
                val value = repository.knowledgeRevision(head.id, head.revision)
                    ?: throw StaleSnapshotException("知识目录在读取期间变化")
                if (!matches(value, filter)) return@forEach
                val result = view(value, true, source, now, states)
                if (filter.includeInvalid || result.evidenceState == KnowledgeEvidenceState.CURRENT) visit(result)
            }
            offset += heads.size
        } while (heads.size == 100)
    }

    private fun indexPage(filter: KnowledgeFilter, offset: Int, limit: Int, previous: KnowledgeIndexPage? = null): KnowledgeIndexPage {
        require(offset >= 0 && limit in 1..100)
        return consistentRead { source, generation, now ->
            if (previous != null && (previous.source != source || previous.analysisGeneration != generation))
                throw StaleSnapshotException("知识目录已变化，请从首页重新读取")
            // Keep expiry classification fixed across pages; it describes this evaluatedAt snapshot.
            val evaluatedAt = previous?.evaluatedAt ?: now
            val selected = mutableListOf<KnowledgeIndexItem>()
            val states = mutableMapOf<String, Pair<SourceStamp, AnalysisRunState>?>()
            var total = 0
            var omitted = 0
            var cursor = 0
            do {
                val heads = repository.knowledgeHeads(cursor, 100)
                for (head in heads) {
                    val value = repository.knowledgeRevision(head.id, head.revision)
                        ?: throw StaleSnapshotException("知识目录在读取期间变化")
                    if (!matches(value, filter)) continue
                    val state = view(value, true, source, evaluatedAt, states).evidenceState
                    if (!filter.includeInvalid && state != KnowledgeEvidenceState.CURRENT) { omitted++; continue }
                    if (total >= offset && selected.size < limit) selected += KnowledgeIndexItem(value.id, value.revision,
                        value.category, value.topics, value.scope.from, value.scope.through, state)
                    total++
                }
                cursor += heads.size
            } while (heads.size == 100)
            KnowledgeIndexPage(source, generation, evaluatedAt, selected, total, omitted,
                (offset.toLong() + selected.size).takeIf { it < total }?.toInt(), filter, limit)
        }
    }

    /** Permanent deletion; independent user feedback remains available through AnalysisFeedback. */
    fun delete(id: String, expectedRevision: Int) = repository.deleteKnowledge(id, expectedRevision)

    private fun matches(value: StoredKnowledgeRevision, filter: KnowledgeFilter): Boolean =
        (filter.from == null || value.scope.through >= filter.from) &&
            (filter.through == null || value.scope.from <= filter.through) &&
            (filter.category == null || value.category == filter.category) &&
            (filter.topics == null || value.topics.any { it in filter.topics })

    private fun view(value: StoredKnowledgeRevision, currentRevision: Boolean, source: SourceStamp, now: Instant,
        states: MutableMap<String, Pair<SourceStamp, AnalysisRunState>?> = mutableMapOf()): KnowledgeView {
        val id = AnalysisRunCodec.feedbackId(value.runId, value.claimRef)
        val feedback = repository.claimFeedback(id)
        val token = repository.claimFeedbackVersion(id)
        val state = when {
            value.source != source -> KnowledgeEvidenceState.STALE
            value.expiresAt != null && !now.isBefore(value.expiresAt) -> KnowledgeEvidenceState.EXPIRED
            token != value.feedbackVersion || feedback == null && token > 0 ||
                feedback != null && feedback.decision != ClaimFeedbackDecision.CONFIRMED ||
                value.category == KnowledgeCategory.INSIGHT && feedback?.decision != ClaimFeedbackDecision.CONFIRMED ->
                KnowledgeEvidenceState.FEEDBACK_CHANGED
            !reviewCurrent(value, states) -> KnowledgeEvidenceState.REVIEW_CHANGED
            else -> KnowledgeEvidenceState.CURRENT
        }
        return KnowledgeView(value, currentRevision, state, feedback?.decision)
    }

    private fun reviewCurrent(value: StoredKnowledgeRevision, states: MutableMap<String, Pair<SourceStamp, AnalysisRunState>?>): Boolean {
        if (!states.containsKey(value.runId)) states[value.runId] = repository.analysisRun(value.runId)?.let {
            it.source to AnalysisRunCodec.decode(it.payload)
        }
        val state = states[value.runId] ?: return true
        return state.first == value.source && KnowledgeValidation.reviewMatchesState(value, state.second)
    }

    private fun <T> consistentRead(block: (SourceStamp, Long, Instant) -> T): T {
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val result = block(source, generation, clock.instant())
        if (repository.analysisSourceStamp() != source || repository.currentAnalysisGeneration() != generation)
            throw StaleSnapshotException("知识或来源在读取期间变化，请重新读取")
        return result
    }
}
