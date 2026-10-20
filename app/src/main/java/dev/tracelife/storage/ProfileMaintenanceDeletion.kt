package dev.tracelife.storage

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.profile.ProfileMaintenanceReferenceKind
import dev.tracelife.profile.StoredProfileRevision

/** Dependency identities only; no deleted wording is needed to find downstream machine content. */
internal object ProfileMaintenanceDeletion {
    fun affectedRuns(runs: List<StoredAnalysisRun>, profiles: List<StoredProfileRevision>,
        knowledge: List<StoredKnowledgeRevision>, feedback: List<StoredClaimFeedback>,
        deletedProfiles: Set<String> = emptySet(), deletedFeedback: Set<String> = emptySet(),
        deletedKnowledge: Set<String> = emptySet(), deletedRuns: Set<String> = emptySet()): Set<String> {
        val plans = runs.associate { it.id to AnalysisRunCodec.decode(it.payload).profileMaintenancePlans }
        val histories = runs.associate { it.id to dev.tracelife.analysis.FeedbackContextProtocol.usedReferences(AnalysisRunCodec.decode(it.payload)) }
        val profileMap = profiles.groupBy { it.id to it.revision }
        val knowledgeMap = knowledge.groupBy { it.id to it.revision }
        val feedbackMap = feedback.groupBy { it.id }
        val forbiddenRuns = deletedRuns.toMutableSet()
        val forbiddenKnowledge = deletedKnowledge.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            knowledge.filter { it.runId in forbiddenRuns }.forEach { forbiddenKnowledge += it.id }
            runs.filter { it.id !in forbiddenRuns }.forEach { run ->
                val affected = histories.getValue(run.id).any { it.id in deletedFeedback || it.runId in forbiddenRuns } ||
                    plans.getValue(run.id).flatMap { it.references }.any { ref ->
                    ref.feedbackVersions.keys.any { it in deletedFeedback } || when (ref.kind) {
                        ProfileMaintenanceReferenceKind.PROFILE -> ref.id in deletedProfiles || profileMap[ref.id to ref.version].orEmpty().any { profile ->
                            profile.draft.knowledge.knowledgeId in forbiddenKnowledge || knowledgeMap[profile.draft.knowledge.knowledgeId to profile.draft.knowledge.revision]
                                .orEmpty().any { it.runId in forbiddenRuns }
                        }
                        ProfileMaintenanceReferenceKind.FEEDBACK -> ref.id in deletedFeedback || feedbackMap[ref.id].orEmpty().any { it.runId in forbiddenRuns }
                    }
                }
                if (affected) { forbiddenRuns += run.id; changed = true }
            }
        } while (changed)
        return forbiddenRuns - deletedRuns
    }
}
