package dev.tracelife.analysis

import dev.tracelife.actions.ActionEvidenceReceipt
import dev.tracelife.actions.ActionEvidenceProtocol

/** Structural coverage and provenance checks; relevance classification remains a model judgment. */
object ReductionReviewProtocol {
    fun scanRef(call: AnalysisCall): ReductionScanRef {
        require(call.purpose == CallPurpose.REDUCTION_SCAN && call.status == CallStatus.SUCCEEDED)
        return ReductionScanRef(call.batchKey, call.attempt, call.requestSha256,
            AnalysisRunCodec.sha256(AnalysisRunCodec.json(scanMap(requireNotNull(call.reductionScan)))))
    }
    fun scanProof(call: AnalysisCall) = ReductionScanProof(scanRef(call), call.providerId, call.evidence, requireNotNull(call.reductionScan), call.actionEvidence)

    fun compatibleScans(state: AnalysisRunState, target: ReductionStatementRef, feedbackVersion: Int,
        tokens: List<ReductionFeedbackToken>): List<AnalysisCall> = state.calls.filter {
        it.purpose == CallPurpose.REDUCTION_SCAN && it.status == CallStatus.SUCCEEDED && it.reductionTarget == target &&
            it.feedbackVersion == feedbackVersion && it.reductionReviewTokens == tokens
    }

    fun requireWholeRoot(state: AnalysisRunState, target: ReductionStatementRef): List<ReductionNodeRef> {
        ReductionGraph.statement(state.calls, target)
        val leaves = ReductionGraph.leafRefs(state, target.root)
        val all = state.calls.filter { it.purpose == ReductionGraph.leafPurpose(state) && it.status == CallStatus.SUCCEEDED }.map(ReductionGraph::nodeRef)
        require(leaves.size == all.size && leaves.toSet() == all.toSet()) { "核对目标未覆盖当前全部综合批次" }
        require(state.actionPlan?.entries?.toSet()?.let { it == ReductionGraph.originalActionEvidence(state, target.root).toSet() } != false) { "核对目标未覆盖完整行动目录" }
        return leaves
    }

    fun proof(state: AnalysisRunState, finalCall: AnalysisCall): ReductionReviewProof {
        require(finalCall.purpose == CallPurpose.REDUCTION_REVIEW)
        val target = requireNotNull(finalCall.reductionTarget)
        val leaves = requireWholeRoot(state, target)
        val before = state.calls.indexOf(finalCall).takeIf { it >= 0 }?.let { state.copy(calls = state.calls.take(it)) } ?: state
        val scans = compatibleScans(before, target, finalCall.feedbackVersion, finalCall.reductionReviewTokens)
        require(scans.map(::scanRef) == finalCall.reductionScans) { "最终核对必须依赖全部当前成功扫描" }
        return ReductionReviewProof(target, ReductionGraph.statement(state.calls, target), finalCall.reductionReviewTokens,
            ReductionGraph.originalEvidence(state, target.root), leaves, scans.map(::scanProof), ReductionGraph.originalActionEvidence(state, target.root)).also {
            validateProof(it, finalCall.evidence, finalCall.review, finalCall.actionEvidence)
        }
    }

    fun validateScan(result: ReductionScanResult, evidence: List<EvidenceReceipt>, maxBytes: Int,
        actionEvidence: List<ActionEvidenceReceipt> = emptyList()) {
        val ids = evidence.map { it.id } + actionEvidence.map { it.id }
        require(ids.size in 1..32 && ids.distinct().size == ids.size)
        require(result.findings.size == ids.size && result.findings.map { it.receiptId }.toSet() == ids.toSet()) {
            "扫描必须逐一分类本次全部原文片段"
        }
        result.findings.forEach {
            require(it.reason.isNotBlank() && it.reason.length <= 500)
            require(it.issues.distinct().size == it.issues.size)
        }
        require(result.unknowns.size <= 20 && result.unknowns.all { it.isNotBlank() && it.length <= 1_000 })
        require(AnalysisRunCodec.json(scanMap(result)).toByteArray(Charsets.UTF_8).size <= maxBytes)
    }

    fun relevantEvidence(proof: ReductionReviewProof): List<EvidenceReceipt> {
        val findings = proof.scans.flatMap { it.result.findings }.associateBy { it.receiptId }
        return proof.originalEvidence.filter { findings[it.id]?.classification != ReductionRelevance.IRRELEVANT }
    }

    fun relevantActionEvidence(proof: ReductionReviewProof): List<ActionEvidenceReceipt> {
        val findings = proof.scans.flatMap { it.result.findings }.associateBy { it.receiptId }
        return proof.originalActionEvidence.filter { findings[it.id]?.classification != ReductionRelevance.IRRELEVANT }
    }

    fun validateProof(proof: ReductionReviewProof, finalEvidence: List<EvidenceReceipt>, finalReview: ClaimReview?,
        finalActionEvidence: List<ActionEvidenceReceipt> = emptyList()) {
        validateTarget(proof.target)
        require(proof.statement.text.isNotBlank() && proof.statement.text.length <= 2_000)
        require(proof.statement.inputIds.size in 1..4 && proof.statement.inputIds.distinct().size == proof.statement.inputIds.size && proof.statement.inputIds.all { HASH.matches(it) })
        require(ReductionGraph.statementSha256(proof.target.root, proof.target.index, proof.statement) == proof.target.statementSha256)
        require(proof.leafRefs.size in 2..100 && proof.leafRefs.distinct().size == proof.leafRefs.size)
        proof.leafRefs.forEach(::validateNode)
        val originalIds = proof.originalEvidence.map { it.id } + proof.originalActionEvidence.map { it.id }
        require(originalIds.size in 1..3_200 && originalIds.distinct().size == originalIds.size)
        proof.originalActionEvidence.forEach(ActionEvidenceProtocol::validateReceipt)
        require(proof.scans.size in 1..100 && proof.scans.map { it.ref }.distinct().size == proof.scans.size)
        proof.scans.forEach { scan ->
            AnalysisRunCodec.validId(scan.ref.batchKey); AnalysisRunCodec.validId(scan.providerId)
            require(scan.ref.attempt in 1..100 && HASH.matches(scan.ref.requestSha256))
            require(scan.ref.outputSha256 == AnalysisRunCodec.sha256(AnalysisRunCodec.json(scanMap(scan.result))))
            validateScan(scan.result, scan.evidence, 65_536, scan.actionEvidence)
        }
        val scanned = proof.scans.flatMap { it.evidence }
        require(scanned.size == proof.originalEvidence.size && scanned.toSet() == proof.originalEvidence.toSet()) { "完整原文清单必须恰好全部扫描一次" }
        val scannedActions = proof.scans.flatMap { it.actionEvidence }
        require(scannedActions.size == proof.originalActionEvidence.size && scannedActions.toSet() == proof.originalActionEvidence.toSet()) { "完整行动清单必须恰好全部扫描一次" }
        require(proof.scans.flatMap { it.result.findings }.none { it.classification == ReductionRelevance.NEEDS_CONTEXT }) { "仍有需要上下文的原文，不能最终核对" }
        val related = relevantEvidence(proof)
        val relatedActions = relevantActionEvidence(proof)
        require(related.size + relatedActions.size in 1..32 && related == finalEvidence && relatedActions == finalActionEvidence) { "最终核对必须重新提供全部相关原文且不能截断" }
        require(proof.dependencyTokens.size in 1..2_000 && proof.dependencyTokens.map { it.claim }.distinct().size == proof.dependencyTokens.size)
        proof.dependencyTokens.forEach { token ->
            AnalysisRunCodec.validId(token.claim.batchKey)
            require(token.version >= 0 && token.claim.claimIndex in 0..19 && HASH.matches(token.claim.claimSha256))
        }
        require(proof.dependencyTokens.any { it.claim == ReductionGraph.feedbackRef(proof.target) })
        finalReview?.let { review ->
            AnalysisRunCodec.validateReview(review, finalEvidence, 65_536, actionEvidence = finalActionEvidence)
            require(review.verdict != ReviewVerdict.SUPPORTED || proof.scans.flatMap { it.result.findings }.none {
                it.classification == ReductionRelevance.COUNTEREVIDENCE
            }) { "已找到反例的整体判断不能标为支持" }
        }
    }

    internal fun validateTarget(target: ReductionStatementRef) {
        validateNode(target.root)
        require(target.index in 0..19 && HASH.matches(target.statementSha256))
    }
    private fun validateNode(node: ReductionNodeRef) {
        AnalysisRunCodec.validId(node.batchKey)
        require(node.attempt in 1..100 && HASH.matches(node.outputSha256))
    }

    internal fun targetMap(target: ReductionStatementRef): Map<String, Any?> = mapOf("root" to ReductionGraph.nodeMap(target.root),
        "index" to target.index, "statementSha256" to target.statementSha256)
    internal fun scanMap(result: ReductionScanResult): Map<String, Any?> = mapOf("findings" to result.findings.map {
        mapOf("receiptId" to it.receiptId, "classification" to it.classification.name, "reason" to it.reason, "issues" to it.issues.map { issue -> issue.name })
    }, "unknowns" to result.unknowns)
    internal fun scanRefMap(ref: ReductionScanRef): Map<String, Any?> = mapOf("batchKey" to ref.batchKey, "attempt" to ref.attempt,
        "requestSha256" to ref.requestSha256, "outputSha256" to ref.outputSha256)
    internal fun proofMap(proof: ReductionReviewProof): Map<String, Any?> = mapOf("target" to targetMap(proof.target),
        "statement" to ReductionGraph.statementMap(proof.statement), "dependencyTokens" to proof.dependencyTokens.map(ReductionGraph::tokenMap),
        "originalEvidence" to proof.originalEvidence.map(AnalysisRunCodec::receiptMap), "leafRefs" to proof.leafRefs.map(ReductionGraph::nodeMap),
        "scans" to proof.scans.map { mapOf("ref" to scanRefMap(it.ref), "providerId" to it.providerId,
            "evidence" to it.evidence.map(AnalysisRunCodec::receiptMap), "result" to scanMap(it.result)) +
            if (it.actionEvidence.isNotEmpty()) mapOf("actionEvidence" to it.actionEvidence.map(ActionEvidenceProtocol::receiptMap)) else emptyMap() }) +
        if (proof.originalActionEvidence.isNotEmpty()) mapOf("originalActionEvidence" to proof.originalActionEvidence.map(ActionEvidenceProtocol::receiptMap)) else emptyMap()

    internal fun parseTarget(raw: Any?): ReductionStatementRef = raw.obj("root", "index", "statementSha256").let {
        ReductionStatementRef(parseNode(it["root"]), it.int("index"), it.string("statementSha256"))
    }
    internal fun parseNode(raw: Any?): ReductionNodeRef = raw.obj("batchKey", "attempt", "outputSha256").let {
        ReductionNodeRef(it.string("batchKey"), it.int("attempt"), it.string("outputSha256"))
    }
    internal fun parseToken(raw: Any?): ReductionFeedbackToken = raw.obj("claim", "version").let {
        val ref = it["claim"].obj("batchKey", "claimIndex", "claimSha256")
        ReductionFeedbackToken(ClaimRef(ref.string("batchKey"), ref.int("claimIndex"), ref.string("claimSha256")), it.int("version"))
    }
    internal fun parseScan(raw: Any?): ReductionScanResult = raw.obj("findings", "unknowns").let { result ->
        ReductionScanResult(result.list("findings").map { rawFinding -> rawFinding.obj("receiptId", "classification", "reason", "issues").let {
            ReductionFinding(it.string("receiptId"), ReductionRelevance.valueOf(it.string("classification")), it.string("reason"), it.strings("issues").map(ReviewIssue::valueOf))
        } }, result.strings("unknowns"))
    }
    internal fun parseScanRef(raw: Any?): ReductionScanRef = raw.obj("batchKey", "attempt", "requestSha256", "outputSha256").let {
        ReductionScanRef(it.string("batchKey"), it.int("attempt"), it.string("requestSha256"), it.string("outputSha256"))
    }
    internal fun parseProof(raw: Any?): ReductionReviewProof {
        val extra = raw is Map<*, *> && raw.containsKey("originalActionEvidence")
        val proof = raw.obj(*(arrayOf("target", "statement", "dependencyTokens", "originalEvidence", "leafRefs", "scans") +
            if (extra) arrayOf("originalActionEvidence") else emptyArray()))
        val statement = proof["statement"].obj("text", "inputIds")
        return ReductionReviewProof(parseTarget(proof["target"]), ReductionStatement(statement.string("text"), statement.strings("inputIds")),
            proof.list("dependencyTokens").map(::parseToken), proof.list("originalEvidence").map(::parseReceipt), proof.list("leafRefs").map(::parseNode),
            proof.list("scans").map { rawScan ->
                val hasActions = rawScan is Map<*, *> && rawScan.containsKey("actionEvidence")
                rawScan.obj(*(arrayOf("ref", "providerId", "evidence", "result") + if (hasActions) arrayOf("actionEvidence") else emptyArray())).let {
                    ReductionScanProof(parseScanRef(it["ref"]), it.string("providerId"), it.list("evidence").map(::parseReceipt), parseScan(it["result"]),
                        if (hasActions) it.list("actionEvidence").map(ActionEvidenceProtocol::parseReceipt).also { refs -> require(refs.isNotEmpty()) } else emptyList())
                }
            }, if (extra) proof.list("originalActionEvidence").map(ActionEvidenceProtocol::parseReceipt).also { require(it.isNotEmpty()) } else emptyList())
    }
    private fun parseReceipt(raw: Any?): EvidenceReceipt = raw.obj("id", "recordId", "revision", "field", "start", "end", "totalCodePoints", "sha256").let {
        EvidenceReceipt(it.string("id"), it.string("recordId"), it.int("revision"), it.string("field"), it.int("start"), it.int("end"), it.int("totalCodePoints"), it.string("sha256"))
    }
    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(vararg fields: String) = (this as? Map<String, Any?>)?.also { require(it.keys == fields.toSet()) } ?: throw IllegalArgumentException("核对证明需要对象")
    private fun Map<String, Any?>.string(key: String) = this[key] as? String ?: throw IllegalArgumentException("核对证明需要文字")
    private fun Map<String, Any?>.int(key: String): Int {
        val value = this[key]
        require(value is Int || value is Long) { "核对证明需要整数" }
        return (value as Number).toLong().also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    }
    private fun Map<String, Any?>.list(key: String) = this[key] as? List<*> ?: throw IllegalArgumentException("核对证明需要数组")
    private fun Map<String, Any?>.strings(key: String) = list(key).map { it as? String ?: throw IllegalArgumentException("核对证明需要文字") }
    private val HASH = Regex("[0-9a-f]{64}")
}
