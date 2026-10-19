package dev.tracelife.profile

import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.UUID

data class ProfileChangesQuery(val from: LocalDate, val through: LocalDate, val zoneId: String,
    val dimension: ProfileDimension? = null) {
    init { require(from.year in 1..9999 && through.year in 1..9999 && from <= through); ZoneId.of(zoneId) }
}
enum class ProfileChangedField { CREATED, KNOWLEDGE_REFERENCE, DIMENSION, LAYER, ADOPTION, USER_TEXT }
data class ProfileChangeIndexItem(val id: String, val revision: Int, val previousRevision: Int?,
    val changedAt: Instant, val change: ProfileChange, val fields: Set<ProfileChangedField>,
    val dimension: ProfileDimension, val layer: ProfileLayer, val adoption: ProfileAdoption,
    val previousKnowledge: ProfileKnowledgeRef?, val knowledge: ProfileKnowledgeRef,
    val maintenanceAction: ProfileMaintenanceAction?)
data class ProfileChangeDetail(val index: ProfileChangeIndexItem, val before: ProfileEntryView?, val after: ProfileEntryView)
class ProfileChangesSnapshot internal constructor(val id: String, val query: ProfileChangesQuery,
    val evaluatedAt: Instant, val validUntil: Instant, val counts: Map<ProfileChangedField, Int>,
    internal val profile: ProfileSnapshot, internal val index: List<ProfileChangeIndexItem>) {
    val total: Int get() = index.size
    val includesPermanentlyDeletedHistory: Boolean get() = false
    val measuresPersonalGrowth: Boolean get() = false
}
class ProfileChangesPage internal constructor(val items: List<ProfileChangeIndexItem>, val total: Int,
    val nextOffset: Int?, val pageSize: Int, internal val snapshot: ProfileChangesSnapshot)

/** Deterministic organization history. This service generates no psychological narrative or machine body copies. */
class ProfileChangeSummary(private val repository: LifeRepository, private val clock: Clock = Clock.systemUTC()) {
    private val profiles = PersonalProfile(repository, clock)

    fun open(query: ProfileChangesQuery): ProfileChangesSnapshot {
        val source = profiles.open(ProfileQuery(zoneId = query.zoneId, currentOnly = false))
        val zone = ZoneId.of(query.zoneId)
        val index = mutableListOf<ProfileChangeIndexItem>()
        var validUntil = source.validUntil
        var profileOffset = 0
        do {
            val page = profiles.entries(source, profileOffset, 100)
            for (head in page.items) {
                var offset = 0
                do {
                    val history = profiles.history(source, head.id, offset, 100)
                    for (value in history.items) {
                        if (value.updatedAt > source.evaluatedAt) {
                            if (value.updatedAt < validUntil) validUntil = value.updatedAt
                            continue
                        }
                        val date = value.updatedAt.atZone(zone).toLocalDate()
                        if (date < query.from || date > query.through) continue
                        val previous = if (value.revision > 1) requireNotNull(repository.profileRevision(value.id, value.revision - 1))
                            else null
                        if (query.dimension != null && value.draft.dimension != query.dimension && previous?.draft?.dimension != query.dimension) continue
                        val fields = linkedSetOf<ProfileChangedField>()
                        if (previous == null) fields += ProfileChangedField.CREATED
                        else {
                            if (value.draft.knowledge != previous.draft.knowledge) fields += ProfileChangedField.KNOWLEDGE_REFERENCE
                            if (value.draft.dimension != previous.draft.dimension) fields += ProfileChangedField.DIMENSION
                            if (value.draft.layer != previous.draft.layer) fields += ProfileChangedField.LAYER
                            if (value.adoption != previous.adoption) fields += ProfileChangedField.ADOPTION
                            if (value.userText != previous.userText) fields += ProfileChangedField.USER_TEXT
                        }
                        index += ProfileChangeIndexItem(value.id, value.revision, previous?.revision, value.updatedAt, value.change,
                            Collections.unmodifiableSet(fields), value.draft.dimension, value.draft.layer, value.adoption,
                            previous?.draft?.knowledge, value.draft.knowledge,
                            value.maintenanceApplication?.takeIf { it != previous?.maintenanceApplication }?.action)
                    }
                    offset = history.nextOffset ?: break
                } while (true)
            }
            profileOffset = page.nextOffset ?: break
        } while (true)
        val sorted = index.sortedWith(compareByDescending<ProfileChangeIndexItem> { it.changedAt }.thenBy { it.id }.thenByDescending { it.revision })
        val counts = ProfileChangedField.entries.associateWith { field -> sorted.count { field in it.fields } }
        return ProfileChangesSnapshot(UUID.randomUUID().toString(), query, source.evaluatedAt, validUntil,
            Collections.unmodifiableMap(counts), source, Collections.unmodifiableList(sorted)).also(::assertCurrent)
    }

    fun assertCurrent(snapshot: ProfileChangesSnapshot) {
        profiles.assertCurrent(snapshot.profile)
        val now = clock.instant()
        if (now < snapshot.evaluatedAt || now >= snapshot.validUntil) throw StaleSnapshotException("画像历史的时间状态已变化")
    }

    fun changes(snapshot: ProfileChangesSnapshot, offset: Int = 0, limit: Int = 25): ProfileChangesPage {
        require(offset >= 0 && limit in 1..100)
        assertCurrent(snapshot)
        val end = minOf(snapshot.total.toLong(), offset.toLong() + limit).toInt()
        return ProfileChangesPage(Collections.unmodifiableList(if (offset >= snapshot.total) emptyList()
            else ArrayList(snapshot.index.subList(offset, end))), snapshot.total,
            end.takeIf { offset < snapshot.total && it < snapshot.total }, limit, snapshot).also { assertCurrent(snapshot) }
    }

    fun next(page: ProfileChangesPage): ProfileChangesPage =
        changes(page.snapshot, requireNotNull(page.nextOffset) { "已到变化目录末页" }, page.pageSize)

    /** Exact registered change leaf; feedback and knowledge bodies are opened only here. */
    fun detail(page: ProfileChangesPage, id: String, revision: Int): ProfileChangeDetail {
        val item = requireNotNull(page.items.singleOrNull { it.id == id && it.revision == revision }) { "变化未在本页登记" }
        assertCurrent(page.snapshot)
        val before = item.previousRevision?.let { profiles.entry(page.snapshot.profile, id, it) }
        val after = profiles.entry(page.snapshot.profile, id, revision)
        if (after.value.updatedAt != item.changedAt || after.value.draft.knowledge != item.knowledge ||
            before?.value?.draft?.knowledge != item.previousKnowledge) throw StaleSnapshotException("画像变化已失效")
        return ProfileChangeDetail(item, before, after).also { assertCurrent(page.snapshot) }
    }
}
