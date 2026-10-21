package dev.tracelife.storage

import dev.tracelife.actions.ActionEvidenceProtocol
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.CallPurpose
import dev.tracelife.analysis.CallStatus
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReductionFeedbackToken
import dev.tracelife.analysis.ReductionGraph
import dev.tracelife.analysis.ReductionReviewProtocol
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.metrics.RecordMetricProtocol
import dev.tracelife.model.RecordRevision
import dev.tracelife.profile.ProfileIntegrationProtocol
import java.time.Instant
import java.time.ZoneId

/** Shared integrity rules for publication, portable snapshots and generated views. */
object KnowledgeValidation {
    const val MAX_REVISIONS = 10_000
    const val MAX_BYTES = 16L * 1024 * 1024
    const val MAX_REVISION_BYTES = 256 * 1024
    const val MAX_DELETIONS = 100_000
    private val digest = Regex("[0-9a-f]{64}")

    fun idFor(runId: String, claim: ClaimRef): String =
        AnalysisRunCodec.sha256("trace-life:knowledge:v1:" + AnalysisRunCodec.feedbackId(runId, claim))

    fun fromRun(
        run: StoredAnalysisRun, claimRef: ClaimRef, reviewKey: String, category: KnowledgeCategory,
        topics: Set<String>, revision: Int, createdAt: Instant, publishedAt: Instant,
    ): StoredKnowledgeRevision {
        AnalysisRunCodec.validateStored(run)
        val state = AnalysisRunCodec.decode(run.payload)
        val call = requireNotNull(state.calls.singleOrNull {
            it.batchKey == reviewKey && it.status == CallStatus.SUCCEEDED &&
                (it.purpose == CallPurpose.REVIEW && it.reviewTarget == claimRef ||
                    it.purpose == CallPurpose.REDUCTION_REVIEW && it.reductionTarget?.let(ReductionGraph::feedbackRef) == claimRef)
        }) { "知识条目缺少对应成功核对" }
        val reductionProof = if (call.purpose == CallPurpose.REDUCTION_REVIEW) {
            require(category == KnowledgeCategory.INSIGHT) { "周期归并陈述只能发布为洞察" }
            ReductionReviewProtocol.proof(state, call)
        } else null
        val integrated = call.profileMaintenanceProof?.plan?.integration
        val claim = if (integrated != null) {
            require(ProfileIntegrationProtocol.matches(state, integrated)) { "整体画像来源链不一致" }
            ProposedClaim(integrated.statement.text, requireNotNull(call.review).supportingEvidenceIds, ClaimKind.POSSIBLE_INTERPRETATION)
        } else if (reductionProof != null) {
            // Derived input IDs stay in the proof. The published statement cites only original
            // evidence actually supporting the final review, and keeps the exact draft wording.
            ProposedClaim(reductionProof.statement.text, requireNotNull(call.review).supportingEvidenceIds, ClaimKind.POSSIBLE_INTERPRETATION)
        } else {
            val extraction = requireNotNull(state.calls.singleOrNull {
                it.batchKey == claimRef.batchKey && it.purpose.producesClaims && it.status == CallStatus.SUCCEEDED
            }) { "知识条目缺少已成功生成的提案" }
            requireNotNull(extraction.proposal?.claims?.getOrNull(claimRef.claimIndex)).also {
                require(AnalysisRunCodec.claimSha256(it) == claimRef.claimSha256) { "知识目标已变化" }
            }
        }
        require(publishedAt >= requireNotNull(call.finishedAt) && createdAt >= run.createdAt) { "知识发布早于来源分析" }
        val value = StoredKnowledgeRevision(idFor(run.id, claimRef), revision, run.id, claimRef, run.source,
            state.request.scope, run.expiresAt, category, claim,
            call.batchKey, call.attempt, call.providerId, call.requestSha256, requireNotNull(call.review), call.evidence,
            call.feedbackVersion, topics, createdAt, publishedAt, reductionProof, call.metricEvidence, call.profileMaintenanceProof, state.actionPlan, call.actionEvidence)
        validate(value)
        return value
    }

    fun validate(value: StoredKnowledgeRevision) {
        SnapshotValidation.id(value.runId); SnapshotValidation.id(value.source.storeId)
        SnapshotValidation.id(value.claimRef.batchKey); SnapshotValidation.id(value.reviewBatchKey)
        SnapshotValidation.id(value.reviewProviderId)
        require(value.id == idFor(value.runId, value.claimRef) && value.revision in 1 until Int.MAX_VALUE) { "知识身份或修订无效" }
        require(value.claimRef.claimIndex in 0..19 && digest.matches(value.claimRef.claimSha256)) { "知识陈述身份无效" }
        val proof = value.reductionProof
        value.actionPlan?.let { plan ->
            ActionEvidenceProtocol.validatePlan(plan)
            require(value.profileMaintenanceProof == null && value.metricEvidence.isEmpty() && value.category == KnowledgeCategory.INSIGHT)
            require(allActionEvidence(value).all { it in plan.entries })
            require(proof == null || proof.originalActionEvidence.toSet() == plan.entries.toSet()) { "行动归并证明遗漏固定范围来源" }
        }
        require(allActionEvidence(value).isEmpty() || value.actionPlan != null)
        require(value.actionEvidence.map { it.id }.distinct().size == value.actionEvidence.size)
        allActionEvidence(value).forEach(ActionEvidenceProtocol::validateReceipt)
        value.profileMaintenanceProof?.let { maintenance ->
            require(proof == null && value.metricEvidence.isEmpty() && value.category == KnowledgeCategory.INSIGHT)
            require(maintenance.plan.target == value.claimRef)
            dev.tracelife.profile.LongitudinalProfileProtocol.validateScope(maintenance.plan.profileTarget, value.scope)
            dev.tracelife.profile.ProfileMaintenanceProtocol.validateProof(maintenance, value.evidence, value.review)
        }
        require(value.metricEvidence.isEmpty() || value.metricEvidence.size == 1 && value.evidence.isEmpty() && proof == null &&
            value.category == KnowledgeCategory.INSIGHT && value.claim.kind == ClaimKind.POSSIBLE_INTERPRETATION) {
            "指标知识只能保存独立指标核对后的可能解释"
        }
        value.metricEvidence.forEach { RecordMetricProtocol.validate(it, value.scope, value.source) }
        val integrated = value.profileMaintenanceProof?.plan?.integration
        if (integrated != null) {
            require(value.claim.text == integrated.statement.text && value.claim.evidenceIds == value.review.supportingEvidenceIds &&
                value.claim.kind == ClaimKind.POSSIBLE_INTERPRETATION && ReductionGraph.feedbackRef(integrated.target) == value.claimRef) {
                "整体画像陈述或原文支持与维护证明不一致"
            }
        } else if (proof == null) {
            require(AnalysisRunCodec.claimSha256(value.claim) == value.claimRef.claimSha256) { "知识陈述摘要不一致" }
        } else {
            require(value.category == KnowledgeCategory.INSIGHT && value.claim.kind == ClaimKind.POSSIBLE_INTERPRETATION)
            require(ReductionGraph.feedbackRef(proof.target) == value.claimRef && value.claim.text == proof.statement.text &&
                value.claim.evidenceIds == value.review.supportingEvidenceIds) { "周期洞察与归并陈述或最终原文支持不一致" }
            ReductionReviewProtocol.validateProof(proof, value.evidence, value.review, value.actionEvidence)
        }
        require(value.source.generation >= 0 && value.feedbackVersion >= 0 && value.reviewAttempt in 1..100 &&
            digest.matches(value.reviewRequestSha256)) { "知识核对身份无效" }
        require(value.publishedAt >= value.createdAt && (value.expiresAt == null || value.expiresAt > value.publishedAt)) { "知识时间范围无效" }
        require(value.scope.from.year in 1..9999 && value.scope.through.year in 1..9999 && value.scope.from <= value.scope.through)
        ZoneId.of(value.scope.zoneId)
        value.scope.recordIds?.let { require(it.size in 1..100); it.forEach(SnapshotValidation::id) }
        require(value.topics.size <= 30 && value.topics.all { it.isNotBlank() && it == it.trim() && it.length <= 80 }) { "知识主题无效" }
        require(value.category != KnowledgeCategory.INSIGHT || value.feedbackVersion > 0) { "洞察缺少用户确认依赖" }
        require((value.category == KnowledgeCategory.INSIGHT) == (value.claim.kind == ClaimKind.POSSIBLE_INTERPRETATION)) { "知识类别与来源性质不一致" }
        require(value.evidence.size + value.metricEvidence.size + value.actionEvidence.size in 1..32 && value.evidence.map { it.id }.distinct().size == value.evidence.size) { "知识证据数量或身份无效" }
        allEvidence(value).forEach { ref ->
            SnapshotValidation.id(ref.recordId)
            require(digest.matches(ref.id) && digest.matches(ref.sha256) && ref.revision > 0)
            require(ref.field == "body" || ref.field.startsWith("answer:") && ref.field.length in 8..207)
            require(ref.start >= 0 && ref.end > ref.start && ref.end <= ref.totalCodePoints && ref.end - ref.start <= 4096)
            require(ref.id == AnalysisRunCodec.evidenceId(ref.recordId, ref.revision, ref.field, ref.start, ref.end, ref.sha256))
        }
        AnalysisRunCodec.validateProposal(BatchProposal(listOf(value.claim)), value.evidence, MAX_REVISION_BYTES, value.metricEvidence, value.actionEvidence)
        AnalysisRunCodec.validateReview(value.review, value.evidence, MAX_REVISION_BYTES, value.metricEvidence, value.actionEvidence)
        value.review.factCheck?.let {
            require(it.targetText == value.claim.text && it.targetSha256 == AnalysisRunCodec.sha256(value.claim.text)) {
                "知识事实核验与发布正文不同"
            }
        }
        require(value.review.verdict == ReviewVerdict.SUPPORTED) { "只有获得支持的核对可以发布" }
        feedbackDependencies(value).forEach { token ->
            SnapshotValidation.id(token.claim.batchKey)
            require(token.claim.claimIndex in 0..19 && digest.matches(token.claim.claimSha256) && token.version >= 0)
        }
    }

    /** Direct proofs survive an absent task. Reduction publications retain their historical proof,
     * but need the graph to establish that all ancestor feedback dependencies are still present. */
    fun reviewStillCurrent(value: StoredKnowledgeRevision, run: StoredAnalysisRun?): Boolean {
        if (run == null) return value.reductionProof == null && value.profileMaintenanceProof == null
        if (run.id != value.runId || run.source != value.source) return false
        return reviewMatchesState(value, AnalysisRunCodec.decode(run.payload))
    }

    /** Callers examining a page can decode each retained task once. Null means no retained task. */
    fun reviewMatchesState(value: StoredKnowledgeRevision, state: AnalysisRunState?): Boolean {
        if (state == null) return value.reductionProof == null && value.profileMaintenanceProof == null
        val proof = value.reductionProof
        val latest = state.calls.lastOrNull {
            it.status == CallStatus.SUCCEEDED && it.feedbackVersion == value.feedbackVersion &&
                if (proof == null) it.purpose == CallPurpose.REVIEW && it.reviewTarget == value.claimRef
                else it.purpose == CallPurpose.REDUCTION_REVIEW && it.reductionTarget == proof.target
        } ?: return false
        if (!dev.tracelife.analysis.FeedbackContextProtocol.publicationReady(state, value.claimRef, latest)) return false
        if (value.profileMaintenanceProof?.plan?.integration?.let { !ProfileIntegrationProtocol.matches(state, it) } == true) return false
        if (latest.batchKey != value.reviewBatchKey || latest.attempt != value.reviewAttempt ||
            latest.providerId != value.reviewProviderId || latest.requestSha256 != value.reviewRequestSha256 ||
            latest.review != value.review || latest.evidence != value.evidence || latest.metricEvidence != value.metricEvidence ||
            latest.profileMaintenanceProof != value.profileMaintenanceProof || latest.actionEvidence != value.actionEvidence || state.actionPlan != value.actionPlan) return false
        return proof == null || try {
            ReductionReviewProtocol.proof(state, latest) == proof
        } catch (_: IllegalArgumentException) { false }
    }

    /** All feedback fences, including zero versions and the independent publication confirmation. */
    fun feedbackDependencies(value: StoredKnowledgeRevision): List<ReductionFeedbackToken> {
        val tokens = listOf(ReductionFeedbackToken(value.claimRef, value.feedbackVersion)) +
            value.reductionProof?.dependencyTokens.orEmpty() + value.profileMaintenanceProof?.plan?.integration?.feedbackTokens.orEmpty()
        require(tokens.groupBy { it.claim }.values.all { group -> group.map { it.version }.distinct().size == 1 }) {
            "知识反馈依赖版本不一致"
        }
        return tokens.distinctBy { it.claim }
    }

    /** Every scanned original is retained, even those classified irrelevant to the final statement. */
    fun allEvidence(value: StoredKnowledgeRevision): List<EvidenceReceipt> {
        val receipts = value.reductionProof?.originalEvidence.orEmpty() + value.profileMaintenanceProof?.originalEvidence.orEmpty() + value.evidence
        require(receipts.groupBy { it.id }.values.all { group -> group.distinct().size == 1 }) { "知识原文引用身份冲突" }
        return receipts.distinctBy { it.id }
    }

    /** Every action revision scanned is retained, including irrelevant context omitted from the final call. */
    fun allActionEvidence(value: StoredKnowledgeRevision): List<dev.tracelife.actions.ActionEvidenceReceipt> {
        val receipts = value.reductionProof?.originalActionEvidence.orEmpty() + value.actionEvidence
        require(receipts.groupBy { it.id }.values.all { group -> group.distinct().size == 1 }) { "知识行动引用身份冲突" }
        return receipts.distinctBy { it.id }
    }

    /** Raw fragments and program metrics own different proof types but share record dependencies. */
    fun allSourceRevisions(value: StoredKnowledgeRevision): List<Pair<String, Int>> =
        (allEvidence(value).map { it.recordId to it.revision } +
            value.metricEvidence.flatMap { metric -> metric.sources.map { it.recordId to it.revision } }).distinct()

    fun encodedBytes(value: StoredKnowledgeRevision): Int = KnowledgeCodec.encode(value).toByteArray(Charsets.UTF_8).size

    fun validateCollection(revisions: List<StoredKnowledgeRevision>, heads: List<KnowledgeHead>) {
        require(revisions.size <= MAX_REVISIONS && heads.size <= MAX_REVISIONS) { "知识修订数量超过上限" }
        require(revisions.map { it.id to it.revision }.distinct().size == revisions.size && heads.map { it.id }.distinct().size == heads.size) { "知识修订或当前指针重复" }
        val groups = revisions.groupBy { it.id }
        val headsById = heads.associateBy { it.id }
        require(groups.keys == heads.map { it.id }.toSet()) { "知识修订与当前指针不一致" }
        var bytes = 0L
        groups.forEach { (id, versions) ->
            val ordered = versions.sortedBy { it.revision }
            require(ordered.map { it.revision } == (1..ordered.size).toList()) { "知识修订链不完整" }
            require(headsById[id]?.revision == ordered.last().revision) { "知识当前指针未指向最新修订" }
            require(ordered.map { it.createdAt }.distinct().size == 1 && ordered.zipWithNext().all { (a, b) -> a.publishedAt <= b.publishedAt }) { "知识修订时间不一致" }
            ordered.forEach {
                validate(it)
                val size = encodedBytes(it)
                require(size <= MAX_REVISION_BYTES) { "知识修订超过大小上限" }
                bytes += size
                require(bytes <= MAX_BYTES) { "知识修订超过总保存上限" }
            }
        }
    }

    fun validateEvidence(value: StoredKnowledgeRevision, records: List<RecordRevision>) =
        validateEvidence(value, records.associateBy { it.id to it.revision })

    fun validateEvidence(value: StoredKnowledgeRevision, sources: Map<Pair<String, Int>, RecordRevision>) {
        value.review.factCheck?.let { EvidenceFactStorage.validateRecords(it.pack, sources) }
        value.metricEvidence.forEach { metric ->
            val originals = metric.sources.map { ref ->
                requireNotNull(sources[ref.recordId to ref.revision]) { "指标知识缺少原始来源修订" }
            }
            RecordMetricProtocol.validate(metric, value.scope, value.source, originals)
        }
        allEvidence(value).forEach { ref ->
            val record = requireNotNull(sources[ref.recordId to ref.revision]) { "知识证据缺少原始修订" }
            val text = if (ref.field == "body") record.draft.body else requireNotNull(record.draft.answers[ref.field.removePrefix("answer:")]) { "知识证据字段缺失" }
            require(text.codePointCount(0, text.length) == ref.totalCodePoints && AnalysisRunCodec.sha256(AnalysisRunCodec.slice(text, ref.start, ref.end)) == ref.sha256) { "知识证据内容不一致" }
            require(value.scope.recordIds == null || ref.recordId in value.scope.recordIds)
            require(value.scope.includeArchived || !record.archived)
            val occurrence = record.draft.occurrence
            require(occurrence.startDate == null || occurrence.startDate <= value.scope.through && occurrence.endDate!! >= value.scope.from) { "知识证据超出范围" }
        }
        value.profileMaintenanceProof?.plan?.profileTarget?.let { target ->
            val metadata = value.evidence.map { requireNotNull(sources[it.recordId to it.revision]) }.distinctBy { it.id to it.revision }.map {
                SourceDescriptor(it.id, it.revision, it.draft.occurrence, it.archived, it.draft.topics, SourceTimeClass.WITHIN)
            }
            dev.tracelife.profile.LongitudinalProfileProtocol.validateProposal(target, BatchProposal(listOf(value.claim)), value.evidence, metadata)
            dev.tracelife.profile.LongitudinalProfileProtocol.validateReview(target, value.review, value.evidence, metadata)
        }
    }
}
