package dev.tracelife.storage

import dev.tracelife.analysis.KnowledgeContextEntry
import dev.tracelife.analysis.KnowledgeContextKind
import dev.tracelife.knowledge.ExperienceRelation
import dev.tracelife.knowledge.StoredExperienceChange
import dev.tracelife.knowledge.StoredExperienceThread
import dev.tracelife.profile.ProfileAdoption
import dev.tracelife.profile.StoredProfileRevision

/** Fixed historical identity only. Later revisions and expiry do not invalidate a backup.
 * New experience references identify the adopted content change. Older previews fixed the whole
 * thread version instead; its exact historical prefix may prove the same adopted content. Neither
 * interpretation certifies that a reference is current (KnowledgeContextCatalog owns that check). */
internal object KnowledgeContextIdentity {
    fun matches(ref: KnowledgeContextEntry, knowledge: List<StoredKnowledgeRevision>, profiles: List<StoredProfileRevision>,
        threads: List<StoredExperienceThread>, changes: List<StoredExperienceChange>, allowLegacyThreadVersion: Boolean = false): Boolean {
        val value = knowledge.singleOrNull { it.id == ref.knowledgeId && it.revision == ref.knowledgeRevision } ?: return false
        if (value.runId != ref.runId || value.claim.text != ref.text || value.scope != ref.scope) return false
        return when (ref.kind) {
            KnowledgeContextKind.KNOWLEDGE -> ref.id == value.id && ref.revision == value.revision && ref.userNote == null && ref.title == "已保存的理解"
            KnowledgeContextKind.PROFILE -> profiles.singleOrNull { it.id == ref.id && it.revision == ref.revision }?.let {
                it.draft.knowledge.knowledgeId == value.id && it.draft.knowledge.revision == value.revision &&
                    it.userText == ref.userNote && it.adoption == ProfileAdoption.ADOPTED && it.draft.dimension.name == ref.title
            } == true
            KnowledgeContextKind.EXPERIENCE -> {
                val thread = threads.singleOrNull { it.id == ref.id } ?: return false
                if (thread.version < ref.revision || thread.title != ref.title) return false
                val prefix = changes.filter { it.threadId == ref.id && it.sequence <= ref.revision }.sortedBy { it.sequence }
                // Require the exact, contiguous historical boundary. Never use a later adoption to
                // justify an old reference or accept an arbitrary earlier matching publication.
                if (prefix.size != ref.revision || prefix.withIndex().any { (index, change) -> change.sequence != index + 1 }) return false
                if (!allowLegacyThreadVersion && prefix.last().relation !in adoptedRelations) return false
                val change = prefix.lastOrNull { it.relation in adoptedRelations } ?: return false
                change.knowledgeId == value.id && change.knowledgeRevision == value.revision && change.userNote == ref.userNote
            }
        }
    }

    private val adoptedRelations = setOf(ExperienceRelation.INITIAL, ExperienceRelation.NARROW, ExperienceRelation.REPLACE)
}
