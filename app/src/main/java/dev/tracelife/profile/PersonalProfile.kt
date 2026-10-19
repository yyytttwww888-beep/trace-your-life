package dev.tracelife.profile

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.knowledge.KnowledgeView
import dev.tracelife.metrics.RecordMetricEvidenceService
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.SourceFragment
import dev.tracelife.storage.SourcePage
import dev.tracelife.storage.SourceStamp
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

data class ProfileQuery(
    val zoneId: String = "UTC",
    val recentDays: Int = 30,
    val dimension: ProfileDimension? = null,
    val layer: ProfileLayer? = null,
    val adoption: ProfileAdoption? = null,
    val currentOnly: Boolean = true,
) {
    init { ZoneId.of(zoneId); require(recentDays in 1..366) }
}
enum class ProfileKnowledgeState { CURRENT, MISSING, SUPERSEDED, NOT_YET_PUBLISHED, STALE, EXPIRED, FEEDBACK_CHANGED, REVIEW_CHANGED }
data class ProfileIndexItem(
    val id: String,
    val revision: Int,
    val draft: ProfileDraft,
    val adoption: ProfileAdoption,
    val knowledgeState: ProfileKnowledgeState,
    val recentState: ProfileRecentState,
    val isCurrent: Boolean,
    val updatedAt: Instant,
)
data class ProfileEntryView(
    val value: StoredProfileRevision,
    val isCurrentRevision: Boolean,
    val knowledge: KnowledgeView?,
    val knowledgeState: ProfileKnowledgeState,
    val recentState: ProfileRecentState,
    val isCurrent: Boolean,
    val evaluatedAt: Instant,
)
data class ProfileDimensionSummary(
    val dimension: ProfileDimension,
    val totalEntries: Int,
    val currentEntries: Int,
    val updatedAt: Instant?,
) { val unknown: Boolean get() = currentEntries == 0 }
data class ProfilePage<T>(val items: List<T>, val total: Int, val nextOffset: Int?)
class ProfileSnapshot internal constructor(
    val id: String,
    val query: ProfileQuery,
    val source: SourceStamp,
    val analysisGeneration: Long,
    val evaluatedAt: Instant,
    val validUntil: Instant,
    val dimensions: List<ProfileDimensionSummary>,
    val omittedNotCurrent: Int,
    internal val index: List<ProfileIndexItem>,
) { val total: Int get() = index.size }

/** User-controlled organization of existing knowledge. It does not generate or verify traits. */
class PersonalProfile(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    fun create(draft: ProfileDraft, id: String = UUID.randomUUID().toString(), userText: String? = null): ProfileEntryView {
        val now = clock.instant()
        val stored = repository.saveProfile(StoredProfileRevision(id, 1, draft, ProfileAdoption.CANDIDATE, now, now,
            userText = userText), null)
        return commandView(stored)
    }

    fun update(id: String, expectedRevision: Int, operationId: String, draft: ProfileDraft): ProfileEntryView =
        command(id, expectedRevision, operationId, ProfileChange.ORGANIZED) { previous -> previous.copy(draft = draft,
            adoption = if (draft == previous.draft) previous.adoption else ProfileAdoption.CANDIDATE) }

    fun adopt(id: String, expectedRevision: Int, operationId: String): ProfileEntryView =
        command(id, expectedRevision, operationId, ProfileChange.ADOPTED) { it.copy(adoption = ProfileAdoption.ADOPTED) }

    fun decline(id: String, expectedRevision: Int, operationId: String): ProfileEntryView =
        command(id, expectedRevision, operationId, ProfileChange.DECLINED) { it.copy(adoption = ProfileAdoption.DECLINED) }

    fun supersede(id: String, expectedRevision: Int, operationId: String): ProfileEntryView =
        command(id, expectedRevision, operationId, ProfileChange.SUPERSEDED) { it.copy(adoption = ProfileAdoption.SUPERSEDED) }

    /** Null clears only the new revision's note; earlier independent text remains in history. */
    fun feedback(id: String, expectedRevision: Int, operationId: String, userText: String?): ProfileEntryView =
        command(id, expectedRevision, operationId, ProfileChange.FEEDBACK) { it.copy(userText = userText) }

    fun delete(id: String, expectedRevision: Int) = repository.deleteProfile(id, expectedRevision)

    fun open(query: ProfileQuery = ProfileQuery()): ProfileSnapshot {
        val at = clock.instant()
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val zone = ZoneId.of(query.zoneId)
        val today = at.atZone(zone).toLocalDate()
        require(today.year in 1..9999) { "画像查看日期超出支持范围" }
        var validUntil = today.plusDays(1).atStartOfDay(zone).toInstant()
        val knowledge = KnowledgeLibrary(repository, Clock.fixed(at, clock.zone))
        val knowledgeCache = mutableMapOf<ProfileKnowledgeRef, KnowledgeView?>()
        val all = mutableListOf<ProfileIndexItem>()
        val selected = mutableListOf<ProfileIndexItem>()
        var cursor = 0
        var count = 0
        do {
            val heads = repository.profileHeads(cursor, 100)
            heads.forEach { head ->
                require(++count <= ProfileValidation.MAX_REVISIONS) { "画像目录超过上限" }
                val value = repository.profileRevision(head.id, head.revision) ?: throw StaleSnapshotException("画像在读取期间变化")
                if (!matches(value, query)) return@forEach
                if (value.updatedAt > at && value.updatedAt < validUntil) validUntil = value.updatedAt
                val ref = value.draft.knowledge
                if (!knowledgeCache.containsKey(ref)) knowledgeCache[ref] = knowledge.get(ref.knowledgeId, ref.revision)
                val linked = knowledgeCache[ref]
                val view = view(value, true, linked, query, at)
                linked?.value?.publishedAt?.let { if (it > at && it < validUntil) validUntil = it }
                if (linked?.evidenceState == KnowledgeEvidenceState.CURRENT && linked.isCurrentRevision)
                    linked.value.expiresAt?.let { if (it < validUntil) validUntil = it }
                val item = ProfileIndexItem(value.id, value.revision, value.draft, value.adoption, view.knowledgeState,
                    view.recentState, view.isCurrent, value.updatedAt)
                all += item
                if (!query.currentOnly || item.isCurrent) selected += item
            }
            cursor += heads.size
        } while (heads.size == 100)
        val dimensions = ProfileDimension.entries.map { dimension ->
            val items = all.filter { it.draft.dimension == dimension }
            ProfileDimensionSummary(dimension, items.size, items.count { it.isCurrent }, items.maxOfOrNull { it.updatedAt })
        }
        return ProfileSnapshot(UUID.randomUUID().toString(), query, source, generation, at, validUntil,
            freeze(dimensions), all.size - selected.size, freeze(selected)).also(::assertCurrent)
    }

    fun assertCurrent(snapshot: ProfileSnapshot) {
        val now = clock.instant()
        if (repository.analysisSourceStamp() != snapshot.source || repository.currentAnalysisGeneration() != snapshot.analysisGeneration ||
            now < snapshot.evaluatedAt || !now.isBefore(snapshot.validUntil))
            throw StaleSnapshotException("画像资料、采用决定或有效期已变化，请重新读取")
    }

    fun entries(snapshot: ProfileSnapshot, offset: Int = 0, limit: Int = 25): ProfilePage<ProfileIndexItem> =
        current(snapshot) { page(snapshot.index, offset, limit) }

    /** Explicit leaf read may include independent feedback and the one linked knowledge revision. */
    fun entry(snapshot: ProfileSnapshot, id: String, revision: Int? = null): ProfileEntryView = current(snapshot) {
        val head = registered(snapshot, id)
        val value = repository.profileRevision(id, revision ?: head.revision) ?: throw IllegalArgumentException("画像修订不存在")
        val knowledge = KnowledgeLibrary(repository, Clock.fixed(snapshot.evaluatedAt, clock.zone))
            .get(value.draft.knowledge.knowledgeId, value.draft.knowledge.revision)
        view(value, value.revision == head.revision, knowledge, snapshot.query, snapshot.evaluatedAt).also {
            // Old profile revisions can point to a different knowledge item than the indexed head.
            val now = clock.instant()
            if (knowledge?.evidenceState == KnowledgeEvidenceState.CURRENT &&
                knowledge.value.expiresAt?.let { expiry -> !now.isBefore(expiry) } == true ||
                knowledge?.value?.publishedAt?.let { published -> published > snapshot.evaluatedAt && !now.isBefore(published) } == true ||
                value.updatedAt > snapshot.evaluatedAt && !now.isBefore(value.updatedAt))
                throw StaleSnapshotException("历史画像或关联知识的时间状态已变化，请重新读取")
        }
    }

    fun history(snapshot: ProfileSnapshot, id: String, offset: Int = 0, limit: Int = 25): ProfilePage<StoredProfileRevision> = current(snapshot) {
        val head = registered(snapshot, id)
        require(offset >= 0 && limit in 1..100)
        val values = repository.profileHistory(id, offset, limit)
        ProfilePage(freeze(values), head.revision, (offset.toLong() + values.size).takeIf { it < head.revision }?.toInt())
    }

    fun get(id: String, revision: Int? = null, query: ProfileQuery = ProfileQuery(currentOnly = false)): ProfileEntryView? {
        val snapshot = open(query)
        if (snapshot.index.none { it.id == id }) return null
        return entry(snapshot, id, revision)
    }

    /** Reads only the registered raw citation, never expands it to an entire record. */
    fun evidence(snapshot: ProfileSnapshot, id: String, evidenceId: String, start: Int = 0,
        maxCodePoints: Int = 1_024): SourceFragment = current(snapshot) {
        require(start >= 0 && maxCodePoints in 1..4_096)
        val linked = currentKnowledge(entry(snapshot, id))
        val receipt = requireNotNull(KnowledgeValidation.allEvidence(linked.value).singleOrNull { it.id == evidenceId }) {
            "引用不属于此画像关联知识"
        }
        require(start < receipt.end - receipt.start)
        val absolute = receipt.start + start
        val length = minOf(maxCodePoints, receipt.end - absolute)
        repository.analysisSourceFragment(snapshot.source, linked.value.scope, receipt.recordId, receipt.field, absolute, length).also {
            if (it.revision != receipt.revision || it.totalCodePoints != receipt.totalCodePoints || it.start != absolute ||
                it.end != absolute + length || start == 0 && length == receipt.end - receipt.start && AnalysisRunCodec.sha256(it.text) != receipt.sha256)
                throw StaleSnapshotException("画像引用与来源修订不一致")
        }
    }

    fun metricSources(snapshot: ProfileSnapshot, id: String, metricId: String, offset: Int = 0, limit: Int = 25): SourcePage = current(snapshot) {
        val knowledge = currentKnowledge(entry(snapshot, id)).value
        val receipt = requireNotNull(knowledge.metricEvidence.singleOrNull { it.id == metricId }) { "指标不属于此画像关联知识" }
        RecordMetricEvidenceService(repository, clock).sources(receipt, offset, limit).let { page ->
            page.copy(items = freeze(page.items.map { it.copy(topics = Collections.unmodifiableSet(it.topics.toSet())) }))
        }
    }

    private fun command(id: String, expected: Int, operation: String, change: ProfileChange,
        transform: (StoredProfileRevision) -> StoredProfileRevision): ProfileEntryView {
        require(expected in 1 until Int.MAX_VALUE - 1)
        val previous = requireNotNull(repository.profileRevision(id, expected)) { "画像不存在或已删除" }
        val replay = repository.profileOperation(id, operation)
        // Replay keeps its original time; the store still compares the entire reconstructed command.
        val next = transform(previous).copy(revision = expected + 1, operationId = operation, change = change,
            updatedAt = replay?.updatedAt ?: clock.instant())
        return commandView(repository.saveProfile(next, expected))
    }

    private fun commandView(value: StoredProfileRevision): ProfileEntryView = requireNotNull(get(value.id, value.revision))

    private fun matches(value: StoredProfileRevision, query: ProfileQuery): Boolean =
        (query.dimension == null || value.draft.dimension == query.dimension) &&
            (query.layer == null || value.draft.layer == query.layer) && (query.adoption == null || value.adoption == query.adoption)

    private fun view(value: StoredProfileRevision, isCurrentRevision: Boolean, knowledge: KnowledgeView?, query: ProfileQuery,
        at: Instant): ProfileEntryView {
        knowledge?.let { ProfileValidation.validateKnowledge(value, it.value) }
        val state = when {
            knowledge == null -> ProfileKnowledgeState.MISSING
            !knowledge.isCurrentRevision -> ProfileKnowledgeState.SUPERSEDED
            knowledge.value.publishedAt > at -> ProfileKnowledgeState.NOT_YET_PUBLISHED
            else -> ProfileKnowledgeState.valueOf(knowledge.evidenceState.name)
        }
        val recent = if (knowledge != null) ProfileProjection.recentState(knowledge.value.scope, value.draft.layer, at, query.zoneId, query.recentDays)
            else if (value.draft.layer == ProfileLayer.RECENT_STATE) ProfileRecentState.UNKNOWN else ProfileRecentState.NOT_APPLICABLE
        return ProfileEntryView(value, isCurrentRevision, knowledge, state, recent,
            isCurrentRevision && value.updatedAt <= at && ProfileProjection.isCurrent(value.adoption, state == ProfileKnowledgeState.CURRENT, recent), at)
    }

    private fun currentKnowledge(view: ProfileEntryView): KnowledgeView {
        if (view.knowledgeState != ProfileKnowledgeState.CURRENT) throw StaleSnapshotException("画像关联知识当前不可取证")
        return requireNotNull(view.knowledge)
    }

    private fun registered(snapshot: ProfileSnapshot, id: String): ProfileIndexItem =
        requireNotNull(snapshot.index.singleOrNull { it.id == id }) { "条目不在本次画像目录中" }

    private fun <T> current(snapshot: ProfileSnapshot, block: () -> T): T {
        assertCurrent(snapshot)
        return block().also { assertCurrent(snapshot) }
    }

    private fun <T> page(values: List<T>, offset: Int, limit: Int): ProfilePage<T> {
        require(offset >= 0 && limit in 1..100)
        val end = minOf(values.size.toLong(), offset.toLong() + limit).toInt()
        return ProfilePage(freeze(if (offset >= values.size) emptyList() else values.subList(offset, end)), values.size,
            end.takeIf { offset < values.size && it < values.size })
    }
    private fun <T> freeze(values: List<T>) = Collections.unmodifiableList(values.toList())
}
