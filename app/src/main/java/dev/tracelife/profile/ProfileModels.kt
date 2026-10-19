package dev.tracelife.profile

import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.SnapshotValidation
import dev.tracelife.storage.StoredKnowledgeRevision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ProfileDimension {
    VALUES_AND_DIRECTION, ROLES_AND_CONTEXT, INTERESTS_AND_PREFERENCES, BEHAVIOR_AND_DECISIONS,
    ABILITIES_AND_STRATEGIES, ENERGY_AND_RECOVERY, RELATIONSHIPS_AND_COLLABORATION, NARRATIVE_AND_CHANGES,
}
enum class ProfileLayer { LONG_TERM, CONTEXTUAL_PATTERN, RECENT_STATE }
enum class ProfileAdoption { CANDIDATE, ADOPTED, DECLINED, SUPERSEDED }
enum class ProfileChange { CREATED, ORGANIZED, ADOPTED, DECLINED, SUPERSEDED, FEEDBACK }
data class ProfileKnowledgeRef(val knowledgeId: String, val revision: Int)
data class ProfileDraft(val knowledge: ProfileKnowledgeRef, val dimension: ProfileDimension, val layer: ProfileLayer)
/** An explicit user's reconsideration, never a new original observation. */
data class ProfileMaintenanceApplicationRecord(val planSha256: String, val action: ProfileMaintenanceAction,
    val overrideReason: String? = null)
data class StoredProfileRevision(
    val id: String,
    val revision: Int,
    val draft: ProfileDraft,
    val adoption: ProfileAdoption,
    val createdAt: Instant,
    val updatedAt: Instant,
    val operationId: String? = null,
    val change: ProfileChange = ProfileChange.CREATED,
    /** Independent user text, never filled by copying a machine claim. */
    val userText: String? = null,
    val maintenanceApplication: ProfileMaintenanceApplicationRecord? = null,
)
data class ProfileHead(val id: String, val revision: Int)
enum class ProfileRecentState { NOT_APPLICABLE, IN_WINDOW, OUTSIDE_WINDOW, UNKNOWN }

/** This window is a viewing convention, not a scientific threshold for personality. */
object ProfileProjection {
    fun recentState(scope: AnalysisQuery, layer: ProfileLayer, at: Instant, zoneId: String,
        recentDays: Int = 30): ProfileRecentState {
        require(recentDays in 1..366)
        val today = at.atZone(ZoneId.of(zoneId)).toLocalDate()
        require(today.year in 1..9999) { "画像查看日期超出支持范围" }
        if (layer != ProfileLayer.RECENT_STATE) return ProfileRecentState.NOT_APPLICABLE
        val from = maxOf(LocalDate.of(1, 1, 1), today.minusDays(recentDays.toLong() - 1))
        return if (scope.from <= today && scope.through >= from) ProfileRecentState.IN_WINDOW else ProfileRecentState.OUTSIDE_WINDOW
    }

    fun isCurrent(adoption: ProfileAdoption, knowledgeIsCurrent: Boolean, recentState: ProfileRecentState): Boolean =
        adoption == ProfileAdoption.ADOPTED && knowledgeIsCurrent &&
            recentState in setOf(ProfileRecentState.NOT_APPLICABLE, ProfileRecentState.IN_WINDOW)
}

object ProfileValidation {
    const val MAX_REVISIONS = 10_000
    const val MAX_REVISION_BYTES = 32_768
    const val MAX_BYTES = 16L * 1024 * 1024
    const val MAX_DELETIONS = 100_000

    fun draft(value: ProfileDraft) {
        require(value.knowledge.knowledgeId.matches(Regex("[0-9a-f]{64}")))
        require(value.knowledge.revision in 1 until Int.MAX_VALUE)
    }

    fun validate(value: StoredProfileRevision) {
        SnapshotValidation.id(value.id)
        require(value.revision in 1 until Int.MAX_VALUE && value.updatedAt >= value.createdAt)
        draft(value.draft)
        require(value.userText == null || value.userText.isNotBlank() && value.userText.length <= 2_000)
        value.maintenanceApplication?.let {
            require(it.planSha256.matches(Regex("[0-9a-f]{64}")) && it.action != ProfileMaintenanceAction.KEEP)
            require(it.overrideReason == null || it.overrideReason.isNotBlank() && it.overrideReason.length <= 2_000)
            if (value.change == ProfileChange.CREATED) require(it.action == ProfileMaintenanceAction.NEW)
        }
        if (value.revision == 1) {
            require(value.change == ProfileChange.CREATED && value.operationId == null &&
                value.adoption == ProfileAdoption.CANDIDATE && value.createdAt == value.updatedAt)
        } else {
            require(value.change != ProfileChange.CREATED)
            SnapshotValidation.id(requireNotNull(value.operationId))
        }
        when (value.change) {
            ProfileChange.ADOPTED -> require(value.adoption == ProfileAdoption.ADOPTED)
            ProfileChange.DECLINED -> require(value.adoption == ProfileAdoption.DECLINED)
            ProfileChange.SUPERSEDED -> require(value.adoption == ProfileAdoption.SUPERSEDED)
            else -> Unit
        }
    }

    fun validateTransition(previous: StoredProfileRevision, next: StoredProfileRevision) {
        validate(previous); validate(next)
        require(previous.id == next.id && next.revision == previous.revision + 1 &&
            next.createdAt == previous.createdAt && next.updatedAt >= previous.updatedAt)
        when (next.change) {
            ProfileChange.CREATED -> error("画像创建不能追加为修订")
            ProfileChange.ORGANIZED -> {
                require(next.userText == previous.userText)
                require(next.adoption == if (next.draft == previous.draft) previous.adoption else ProfileAdoption.CANDIDATE)
                if (next.maintenanceApplication != previous.maintenanceApplication)
                    require(next.maintenanceApplication?.action in setOf(ProfileMaintenanceAction.NARROW, ProfileMaintenanceAction.REPLACE))
            }
            ProfileChange.FEEDBACK -> require(next.draft == previous.draft && next.adoption == previous.adoption && next.maintenanceApplication == previous.maintenanceApplication)
            else -> require(next.draft == previous.draft && next.userText == previous.userText && next.maintenanceApplication == previous.maintenanceApplication)
        }
    }

    fun validateKnowledge(value: StoredProfileRevision, knowledge: StoredKnowledgeRevision) {
        require(knowledge.id == value.draft.knowledge.knowledgeId && knowledge.revision == value.draft.knowledge.revision)
        require(knowledge.category == KnowledgeCategory.OBSERVATION || knowledge.category == KnowledgeCategory.INSIGHT) {
            "画像只引用已发布观察或洞察"
        }
    }

    fun validateCollection(revisions: List<StoredProfileRevision>, heads: List<ProfileHead>) {
        require(revisions.size <= MAX_REVISIONS && heads.size <= MAX_REVISIONS)
        require(revisions.map { it.id to it.revision }.distinct().size == revisions.size)
        require(heads.map { it.id }.distinct().size == heads.size)
        val groups = revisions.groupBy { it.id }
        require(groups.keys == heads.map { it.id }.toSet())
        val headMap = heads.associateBy { it.id }
        var bytes = 0L
        val slots = mutableSetOf<Triple<String, ProfileDimension, ProfileLayer>>()
        groups.forEach { (id, entries) ->
            val ordered = entries.sortedBy { it.revision }
            require(ordered.map { it.revision } == (1..ordered.size).toList()) { "画像修订链不完整" }
            require(headMap[id]?.revision == ordered.last().revision)
            require(ordered.mapNotNull { it.operationId }.distinct().size == ordered.count { it.operationId != null })
            ordered.forEach {
                validate(it)
                bytes += ProfileCodec.encode(it).toByteArray(Charsets.UTF_8).size
                require(bytes <= MAX_BYTES) { "画像总保存容量超限" }
            }
            ordered.zipWithNext().forEach { (previous, next) -> validateTransition(previous, next) }
            val draft = ordered.last().draft
            require(slots.add(Triple(draft.knowledge.knowledgeId, draft.dimension, draft.layer))) { "同一画像组织位置重复" }
        }
    }
}
