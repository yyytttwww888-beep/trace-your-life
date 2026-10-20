package dev.tracelife.storage

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.profile.ProfileMaintenanceReferenceKind
import dev.tracelife.profile.StoredProfileRevision

/** Dependency identities only; no deleted wording is needed to find downstream machine content. */
internal object ProfileMaintenanceDeletion {
    fun affectedRuns(runs: List<StoredAnalysisRun>, profiles: List<StoredProfileRevision>,
        knowledge: List<StoredKnowledgeRevision>, feedback: List<StoredClaimFeedback>,
        deletedProfiles: Set<String> = emptySet(), deletedFeedback: Set<String> = emptySet(),
        deletedKnowledge: Set<String> = emptySet(), deletedRuns: Set<String> = emptySet(),
        deletedExperiences: Set<String> = emptySet()): Set<String> {
        // Decode once per task and retain only dependency metadata, not the full call graph.
        val dependencies = runs.associate { run ->
            val state = AnalysisRunCodec.decode(run.payload)
            run.id to Triple(state.request.conversationContext, state.profileMaintenancePlans,
                dev.tracelife.analysis.FeedbackContextProtocol.usedReferences(state))
        }
        val knowledgeContexts = runs.associate { it.id to AnalysisRunCodec.decode(it.payload).knowledgeContexts.flatMap { plan -> plan.entries } }
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
                val (conversation, plans, history) = dependencies.getValue(run.id)
                val affected = knowledgeContexts[run.id]?.any { ref ->
                    ref.runId in forbiddenRuns || ref.knowledgeId in forbiddenKnowledge ||
                        knowledgeMap[ref.knowledgeId to ref.knowledgeRevision].orEmpty().any { value ->
                            KnowledgeValidation.feedbackDependencies(value).any { AnalysisRunCodec.feedbackId(value.runId, it.claim) in deletedFeedback }
                        } ||
                        ref.kind == dev.tracelife.analysis.KnowledgeContextKind.PROFILE && ref.id in deletedProfiles ||
                        ref.kind == dev.tracelife.analysis.KnowledgeContextKind.EXPERIENCE && ref.id in deletedExperiences
                } == true || plans.any { plan -> plan.integration?.let { integration ->
                    AnalysisRunCodec.feedbackId(run.id, plan.target) in deletedFeedback || integration.feedbackTokens.any {
                        AnalysisRunCodec.feedbackId(run.id, it.claim) in deletedFeedback
                    }
                } == true } || conversation?.let { context -> context.parentRunId in forbiddenRuns ||
                    context.targets.flatMap { it.feedbackTokens }.any { AnalysisRunCodec.feedbackId(context.parentRunId, it.claim) in deletedFeedback } } == true ||
                    history.any { it.id in deletedFeedback || it.runId in forbiddenRuns } ||
                    plans.flatMap { it.references }.any { ref ->
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
