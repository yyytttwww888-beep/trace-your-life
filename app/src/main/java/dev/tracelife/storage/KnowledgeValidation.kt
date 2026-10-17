package dev.tracelife.storage

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.BatchProposal
import dev.tracelife.analysis.CallPurpose
import dev.tracelife.analysis.CallStatus
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.model.RecordRevision
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
        val extraction = requireNotNull(state.calls.singleOrNull {
            it.batchKey == claimRef.batchKey && it.purpose == CallPurpose.EXTRACTION && it.status == CallStatus.SUCCEEDED
        }) { "知识条目缺少成功提取" }
        val claim = requireNotNull(extraction.proposal?.claims?.getOrNull(claimRef.claimIndex)) { "知识目标不存在" }
        require(AnalysisRunCodec.claimSha256(claim) == claimRef.claimSha256) { "知识目标已变化" }
        val call = requireNotNull(state.calls.singleOrNull {
            it.batchKey == reviewKey && it.purpose == CallPurpose.REVIEW && it.status == CallStatus.SUCCEEDED && it.reviewTarget == claimRef
        }) { "知识条目缺少对应成功核对" }
        require(publishedAt >= requireNotNull(call.finishedAt) && createdAt >= run.createdAt) { "知识发布早于来源分析" }
        val value = StoredKnowledgeRevision(idFor(run.id, claimRef), revision, run.id, claimRef, run.source,
            state.request.scope, run.expiresAt, category, claim,
            call.batchKey, call.attempt, call.providerId, call.requestSha256, requireNotNull(call.review), call.evidence,
            call.feedbackVersion, topics, createdAt, publishedAt)
        validate(value)
        return value
    }

    fun validate(value: StoredKnowledgeRevision) {
        SnapshotValidation.id(value.runId); SnapshotValidation.id(value.source.storeId)
        SnapshotValidation.id(value.claimRef.batchKey); SnapshotValidation.id(value.reviewBatchKey)
        SnapshotValidation.id(value.reviewProviderId)
        require(value.id == idFor(value.runId, value.claimRef) && value.revision in 1 until Int.MAX_VALUE) { "知识身份或修订无效" }
        require(value.claimRef.claimIndex in 0..19 && digest.matches(value.claimRef.claimSha256) &&
            AnalysisRunCodec.claimSha256(value.claim) == value.claimRef.claimSha256) { "知识陈述摘要不一致" }
        require(value.source.generation >= 0 && value.feedbackVersion >= 0 && value.reviewAttempt in 1..100 &&
            digest.matches(value.reviewRequestSha256)) { "知识核对身份无效" }
        require(value.publishedAt >= value.createdAt && (value.expiresAt == null || value.expiresAt > value.publishedAt)) { "知识时间范围无效" }
        require(value.scope.from.year in 1..9999 && value.scope.through.year in 1..9999 && value.scope.from <= value.scope.through)
        ZoneId.of(value.scope.zoneId)
        value.scope.recordIds?.let { require(it.size in 1..100); it.forEach(SnapshotValidation::id) }
        require(value.topics.size <= 30 && value.topics.all { it.isNotBlank() && it == it.trim() && it.length <= 80 }) { "知识主题无效" }
        require(value.category != KnowledgeCategory.INSIGHT || value.feedbackVersion > 0) { "洞察缺少用户确认依赖" }
        require((value.category == KnowledgeCategory.INSIGHT) == (value.claim.kind == ClaimKind.POSSIBLE_INTERPRETATION)) { "知识类别与来源性质不一致" }
        require(value.evidence.size in 1..32 && value.evidence.map { it.id }.distinct().size == value.evidence.size) { "知识证据数量或身份无效" }
        value.evidence.forEach { ref ->
            SnapshotValidation.id(ref.recordId)
            require(digest.matches(ref.id) && digest.matches(ref.sha256) && ref.revision > 0)
            require(ref.field == "body" || ref.field.startsWith("answer:") && ref.field.length in 8..207)
            require(ref.start >= 0 && ref.end > ref.start && ref.end <= ref.totalCodePoints && ref.end - ref.start <= 4096)
            require(ref.id == AnalysisRunCodec.evidenceId(ref.recordId, ref.revision, ref.field, ref.start, ref.end, ref.sha256))
        }
        AnalysisRunCodec.validateProposal(BatchProposal(listOf(value.claim)), value.evidence, MAX_REVISION_BYTES)
        AnalysisRunCodec.validateReview(value.review, value.evidence, MAX_REVISION_BYTES)
        require(value.review.verdict == ReviewVerdict.SUPPORTED) { "只有获得支持的核对可以发布" }
    }

    /** The immutable proof survives an absent historical task. A retained task may add a newer
     * successful opinion, which must be used before the same claim can be current again. */
    fun reviewStillCurrent(value: StoredKnowledgeRevision, run: StoredAnalysisRun?): Boolean {
        if (run == null) return true
        if (run.id != value.runId || run.source != value.source) return false
        return reviewMatchesState(value, AnalysisRunCodec.decode(run.payload))
    }

    /** Callers examining a page can decode each retained task once. Null means no retained task. */
    fun reviewMatchesState(value: StoredKnowledgeRevision, state: AnalysisRunState?): Boolean {
        if (state == null) return true
        val latest = state.calls.lastOrNull {
            it.purpose == CallPurpose.REVIEW && it.status == CallStatus.SUCCEEDED &&
                it.reviewTarget == value.claimRef && it.feedbackVersion == value.feedbackVersion
        } ?: return false
        return latest.batchKey == value.reviewBatchKey && latest.attempt == value.reviewAttempt &&
            latest.providerId == value.reviewProviderId && latest.requestSha256 == value.reviewRequestSha256 &&
            latest.review == value.review && latest.evidence == value.evidence
    }

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
        value.evidence.forEach { ref ->
            val record = requireNotNull(sources[ref.recordId to ref.revision]) { "知识证据缺少原始修订" }
            val text = if (ref.field == "body") record.draft.body else requireNotNull(record.draft.answers[ref.field.removePrefix("answer:")]) { "知识证据字段缺失" }
            require(text.codePointCount(0, text.length) == ref.totalCodePoints && AnalysisRunCodec.sha256(AnalysisRunCodec.slice(text, ref.start, ref.end)) == ref.sha256) { "知识证据内容不一致" }
            require(value.scope.recordIds == null || ref.recordId in value.scope.recordIds)
            require(value.scope.includeArchived || !record.archived)
            val occurrence = record.draft.occurrence
            require(occurrence.startDate == null || occurrence.startDate <= value.scope.through && occurrence.endDate!! >= value.scope.from) { "知识证据超出范围" }
        }
    }
}
