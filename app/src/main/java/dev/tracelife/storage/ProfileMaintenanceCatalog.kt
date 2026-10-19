package dev.tracelife.storage

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.profile.*
import dev.tracelife.transfer.StaleSnapshotException

/** Builds a bounded identity directory locally. Only an explicitly resolved leaf reaches a model. */
internal class ProfileMaintenanceCatalog(private val repository: LifeRepository) {
    fun catalog(target: ProfileAnalysisTarget, excludeRunId: String, excludeClaim: ClaimRef): List<ProfileMaintenanceReference> {
        val ownKnowledge = KnowledgeValidation.idFor(excludeRunId, excludeClaim)
        val ownFeedback = AnalysisRunCodec.feedbackId(excludeRunId, excludeClaim)
        val references = mutableListOf<ProfileMaintenanceReference>()
        var offset = 0
        do {
            val page = repository.profileHeads(offset, 100)
            page.forEach { head ->
                val value = requireNotNull(repository.profileRevision(head.id, head.revision))
                if (value.draft.dimension == target.dimension && value.draft.knowledge.knowledgeId != ownKnowledge)
                    references += profile(value).reference
            }
            offset += page.size
        } while (page.size == 100)
        val linkedFeedback = references.flatMap { it.feedbackVersions.keys }.toSet()
        offset = 0
        do {
            val page = repository.claimFeedbacks(offset, 100)
            page.forEach { value ->
                if (value.id != ownFeedback && value.decision != ClaimFeedbackDecision.CONFIRMED) {
                    val state = repository.analysisRun(value.runId)?.let { AnalysisRunCodec.decode(it.payload) }
                    // Unclassified and orphan decisions remain in the governance directory.
                    if (value.id in linkedFeedback || state?.request?.profileTarget?.dimension?.let { it == target.dimension } != false)
                        references += feedback(value).reference
                }
            }
            offset += page.size
        } while (page.size == 100)
        return references.sortedWith(compareBy({ it.kind.name }, { it.id }))
    }

    fun resolve(reference: ProfileMaintenanceReference): ProfileMaintenanceReferenceContent {
        val current = when (reference.kind) {
            ProfileMaintenanceReferenceKind.PROFILE -> repository.profileRevision(reference.id)?.let(::profile)
            ProfileMaintenanceReferenceKind.FEEDBACK -> repository.claimFeedback(reference.id)?.let(::feedback)
        } ?: throw StaleSnapshotException("画像维护参照已删除或不可用")
        if (current.reference != reference) throw StaleSnapshotException("画像维护参照内容或版本已变化")
        return current
    }

    private fun profile(value: StoredProfileRevision): ProfileMaintenanceReferenceContent {
        val knowledge = repository.knowledgeRevision(value.draft.knowledge.knowledgeId, value.draft.knowledge.revision)
        val head = repository.knowledgeRevision(value.draft.knowledge.knowledgeId)
        val response = knowledge?.let { repository.claimFeedback(AnalysisRunCodec.feedbackId(it.runId, it.claimRef)) }
        val dependencies = knowledge?.let(KnowledgeValidation::feedbackDependencies).orEmpty().associate { dependency ->
            val id = AnalysisRunCodec.feedbackId(requireNotNull(knowledge).runId, dependency.claim)
            id to repository.claimFeedbackVersion(id)
        } + knowledge?.profileMaintenanceProof?.plan?.references.orEmpty().flatMap { it.feedbackVersions.keys }
            .associateWith(repository::claimFeedbackVersion)
        val fingerprint = AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(
            "profile-reference-v1", ProfileCodec.encode(value), knowledge?.let(KnowledgeCodec::encode),
            head?.revision, dependencies, response?.userText,
        )))
        return ProfileMaintenanceReferenceContent(
            ProfileMaintenanceReference(ProfileMaintenanceReferenceKind.PROFILE, value.id, value.revision, fingerprint, dependencies),
            value.draft.dimension, value.draft.layer, value.adoption, knowledge?.claim,
            value.userText, response?.decision, knowledge != null, feedbackUserText = response?.userText,
        )
    }

    private fun feedback(value: StoredClaimFeedback): ProfileMaintenanceReferenceContent {
        val state = repository.analysisRun(value.runId)?.let { AnalysisRunCodec.decode(it.payload) }
        val proposal: ProposedClaim? = state?.calls?.lastOrNull { it.batchKey == value.batchKey }
            ?.proposal?.claims?.getOrNull(value.claimIndex)?.takeIf { AnalysisRunCodec.claimSha256(it) == value.claimSha256 }
        val fingerprint = AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(
            "feedback-reference-v1", value.id, value.version, value.runId, value.batchKey, value.claimIndex,
            value.claimSha256, value.source.storeId, value.source.generation, value.decision.name, value.userText,
            value.createdAt.toString(), value.updatedAt.toString(), proposal?.let(AnalysisRunCodec::claimSha256),
        )))
        return ProfileMaintenanceReferenceContent(
            ProfileMaintenanceReference(ProfileMaintenanceReferenceKind.FEEDBACK, value.id, value.version, fingerprint, mapOf(value.id to value.version)),
            state?.request?.profileTarget?.dimension, state?.request?.profileTarget?.layer, null,
            proposal, value.userText, value.decision, proposal != null,
        )
    }

    /** Publication fences are intentionally narrower than generation's full directory fence:
     * adopting or updating the same profile must not invalidate its newly linked knowledge. */
    fun publicationDependenciesCurrent(proof: ProfileMaintenanceProof): Boolean = proof.plan.references.all { ref ->
        ref.feedbackVersions.all { (id, version) -> repository.claimFeedbackVersion(id) == version &&
            (version == 0 || repository.claimFeedback(id) != null) } && when (ref.kind) {
            ProfileMaintenanceReferenceKind.PROFILE -> repository.profileRevision(ref.id, ref.version)?.let { value ->
                repository.knowledgeRevision(value.draft.knowledge.knowledgeId, value.draft.knowledge.revision) != null &&
                    profile(value).reference == ref
            } == true
            ProfileMaintenanceReferenceKind.FEEDBACK -> repository.claimFeedback(ref.id)?.let { value ->
                value.version == ref.version && feedback(value).claim != null
            } == true
        }
    }
}
