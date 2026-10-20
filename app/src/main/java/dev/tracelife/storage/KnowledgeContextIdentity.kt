package dev.tracelife.storage

import dev.tracelife.analysis.KnowledgeContextEntry
import dev.tracelife.analysis.KnowledgeContextKind
import dev.tracelife.knowledge.ExperienceRelation
import dev.tracelife.knowledge.StoredExperienceChange
import dev.tracelife.knowledge.StoredExperienceThread
import dev.tracelife.profile.ProfileAdoption
import dev.tracelife.profile.StoredProfileRevision

/** Fixed historical identity only. Later revisions and expiry do not invalidate a backup.
 * Experience revision identifies the adopted content change, not unrelated additions to its history. */
internal object KnowledgeContextIdentity {
    fun matches(ref: KnowledgeContextEntry, knowledge: List<StoredKnowledgeRevision>, profiles: List<StoredProfileRevision>,
        threads: List<StoredExperienceThread>, changes: List<StoredExperienceChange>): Boolean {
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
                val change = changes.singleOrNull { it.threadId == ref.id && it.sequence == ref.revision &&
                    it.relation in setOf(ExperienceRelation.INITIAL, ExperienceRelation.NARROW, ExperienceRelation.REPLACE) } ?: return false
                thread.version >= ref.revision && change.knowledgeId == value.id && change.knowledgeRevision == value.revision && change.userNote == ref.userNote
            }
        }
    }
}
