package dev.tracelife.transfer

import dev.tracelife.profile.ProfileAdoption
import dev.tracelife.profile.ProfileProjection
import dev.tracelife.profile.ProfileRecentState
import dev.tracelife.profile.ProfileValidation
import dev.tracelife.profile.StoredProfileRevision
import dev.tracelife.storage.SnapshotValidation
import java.time.Instant
import java.time.ZoneId

/** Profile organization is a view over included knowledge, never an additional original source. */
internal object ProfileExport {
    data class Selection(
        val entries: List<StoredProfileRevision>,
        val sourceHeads: Int,
        val omittedHistoricalRevisions: Int,
        val omittedByReason: Map<String, Int>,
        val zoneId: String,
        val recentDays: Int,
        val validUntil: Instant?,
    ) { val omitted get() = sourceHeads - entries.size }

    fun select(snapshot: RepositorySnapshot, options: ReadingExportOptions,
        knowledge: PublishedKnowledgeExport.Selection): Selection {
        ProfileValidation.validateCollection(snapshot.profileRevisions, snapshot.profileHeads)
        SnapshotValidation.validateProfileReferences(snapshot.profileRevisions, snapshot.knowledgeRevisions)
        val revisions = snapshot.profileRevisions.associateBy { it.id to it.revision }
        val deleted = snapshot.deletedProfiles.map { it.id }.toSet()
        val includedKnowledge = knowledge.entries.associateBy { it.value.id to it.value.revision }
        val omitted = linkedMapOf<String, Int>()
        var validUntil: Instant? = null
        val entries = snapshot.profileHeads.sortedBy { it.id }.mapNotNull { head ->
            val value = revisions.getValue(head.id to head.revision)
            val ref = value.draft.knowledge
            val source = includedKnowledge[ref.knowledgeId to ref.revision]?.value
            val recent = source?.let { ProfileProjection.recentState(it.scope, value.draft.layer,
                snapshot.capturedAt, options.profileZoneId, options.profileRecentDays) } ?: ProfileRecentState.UNKNOWN
            val reason = when {
                options.kind == ReadingPackageKind.EVIDENCE -> "evidence_package"
                value.id in deleted -> "deleted"
                value.updatedAt > snapshot.capturedAt -> "not_yet_current"
                value.adoption != ProfileAdoption.ADOPTED -> "not_adopted"
                source == null -> "knowledge_not_included"
                !ProfileProjection.isCurrent(value.adoption, true, recent) -> "outside_recent_window"
                else -> null
            }
            if (reason != null) { omitted[reason] = omitted.getOrDefault(reason, 0) + 1; null }
            else {
                val zone = ZoneId.of(options.profileZoneId)
                val midnight = snapshot.capturedAt.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
                val expiry = requireNotNull(source).expiresAt?.let { minOf(midnight, it) } ?: midnight
                validUntil = validUntil?.let { minOf(it, expiry) } ?: expiry
                value
            }
        }
        return Selection(entries, snapshot.profileHeads.size, snapshot.profileRevisions.size - snapshot.profileHeads.size,
            omitted, options.profileZoneId, options.profileRecentDays, validUntil)
    }
}
