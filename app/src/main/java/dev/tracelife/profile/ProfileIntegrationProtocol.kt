package dev.tracelife.profile

import dev.tracelife.analysis.*

/** An integrated draft retains its complete graph. This proof is not an original-evidence review. */
data class ProfileIntegrationProof(
    val target: ReductionStatementRef,
    val statement: ReductionStatement,
    val leafRefs: List<ReductionNodeRef>,
    val originalEvidence: List<EvidenceReceipt>,
    val feedbackTokens: List<ReductionFeedbackToken>,
)

data class ProfileCandidateSource(val call: AnalysisCall, val claim: ProposedClaim,
    val originalEvidence: List<EvidenceReceipt>, val integration: ProfileIntegrationProof? = null)

object ProfileIntegrationProtocol {
    const val INSTRUCTIONS = "这是纵向画像的整体整理。比较所有阶段与所有派生批次，保留支持、反例、变化、适用情境及替代解释；不能只复制第一批。" +
        "后续阶段已描述的做法不能仍写成完全未知；仅部分得到回答时，未定部分放入相关候选的限定，不据单次后续记录宣称持续如此。" +
        "旧空批次的疑问也须跨批核对；新输出unknowns必须为空数组[]。反例可使候选收窄或不输出；没有可成立的候选可空claims，保留的候选必须保留相关已知反例。" +
        "每条仍是待核对的长期假设，不宣布已确认、已采用或永久成立；后续检查完整来源与历史意见。" + SemanticQualityGuidance.REDUCTION + ProfileAnalysisProtocol.CANDIDATE_SHAPE

    fun resolve(state: AnalysisRunState, ref: ClaimRef): ProfileCandidateSource {
        val target = requireNotNull(state.request.profileTarget) { "不是画像任务" }
        val call = requireNotNull(state.calls.lastOrNull { it.batchKey == ref.batchKey && it.status == CallStatus.SUCCEEDED })
        if (call.purpose == CallPurpose.SYNTHESIS) {
            val claim = requireNotNull(call.proposal?.claims?.getOrNull(ref.claimIndex))
            require(AnalysisRunCodec.claimSha256(claim) == ref.claimSha256)
            return ProfileCandidateSource(call, claim, call.evidence)
        }
        require(target.layer == ProfileLayer.LONG_TERM && call.purpose == CallPurpose.REDUCTION)
        val statementTarget = ReductionGraph.statementRef(call, ref.claimIndex)
        require(ReductionGraph.feedbackRef(statementTarget) == ref)
        val leaves = ReductionReviewProtocol.requireWholeRoot(state, statementTarget)
        val statement = ReductionGraph.statement(state.calls, statementTarget)
        val originals = ReductionGraph.originalEvidence(state, statementTarget.root)
        val proof = ProfileIntegrationProof(statementTarget, statement, leaves, originals,
            ReductionGraph.ancestorTokens(state.calls, statementTarget.root))
        validate(proof)
        return ProfileCandidateSource(call, ProposedClaim(statement.text, originals.map { it.id }, ClaimKind.POSSIBLE_INTERPRETATION), originals, proof)
    }

    fun validate(proof: ProfileIntegrationProof) {
        ReductionReviewProtocol.validateTarget(proof.target)
        require(proof.statement.text.isNotBlank() && proof.statement.text.length <= 2_000)
        require(proof.statement.inputIds.size in 1..4 && proof.statement.inputIds.distinct().size == proof.statement.inputIds.size &&
            proof.statement.inputIds.all { it.matches(HASH) })
        require(ReductionGraph.statementSha256(proof.target.root, proof.target.index, proof.statement) == proof.target.statementSha256)
        require(proof.leafRefs.size in 2..100 && proof.leafRefs.distinct().size == proof.leafRefs.size)
        proof.leafRefs.forEach { leaf ->
            AnalysisRunCodec.validId(leaf.batchKey)
            require(leaf.attempt in 1..100 && leaf.outputSha256.matches(HASH))
        }
        require(proof.originalEvidence.size in 1..3_200 && proof.originalEvidence.map { it.id }.distinct().size == proof.originalEvidence.size)
        require(proof.feedbackTokens.size <= 2_000 && proof.feedbackTokens.map { it.claim }.distinct().size == proof.feedbackTokens.size)
        proof.feedbackTokens.forEach { token ->
            AnalysisRunCodec.validId(token.claim.batchKey)
            require(token.claim.claimIndex in 0..19 && token.claim.claimSha256.matches(HASH) && token.version >= 0)
        }
    }

    fun matches(state: AnalysisRunState, proof: ProfileIntegrationProof): Boolean = try {
        resolve(state, ReductionGraph.feedbackRef(proof.target)).integration == proof
    } catch (_: IllegalArgumentException) { false }

    internal fun map(proof: ProfileIntegrationProof): Map<String, Any?> = mapOf(
        "target" to ReductionReviewProtocol.targetMap(proof.target), "statement" to ReductionGraph.statementMap(proof.statement),
        "leafRefs" to proof.leafRefs.map(ReductionGraph::nodeMap), "originalEvidence" to proof.originalEvidence.map(AnalysisRunCodec::receiptMap),
        "feedbackTokens" to proof.feedbackTokens.map(ReductionGraph::tokenMap))

    internal fun parse(raw: Any?): ProfileIntegrationProof {
        val value = obj(raw, setOf("target", "statement", "leafRefs", "originalEvidence", "feedbackTokens"))
        val statement = obj(value["statement"], setOf("text", "inputIds"))
        fun list(key: String) = value[key] as? List<*> ?: error("整体画像凭据需要数组")
        return ProfileIntegrationProof(ReductionReviewProtocol.parseTarget(value["target"]),
            ReductionStatement(statement["text"] as? String ?: error("缺少描述"),
                (statement["inputIds"] as? List<*>)?.map { it as? String ?: error("输入身份无效") } ?: error("缺少输入")),
            list("leafRefs").map(ReductionReviewProtocol::parseNode), list("originalEvidence").map { rawReceipt ->
                val receipt = obj(rawReceipt, setOf("id", "recordId", "revision", "field", "start", "end", "totalCodePoints", "sha256"))
                fun text(key: String) = receipt[key] as? String ?: error("原文凭据需要文字")
                fun number(key: String): Int { val n = receipt[key]; require(n is Int || n is Long); return (n as Number).toLong().also { require(it in 0..Int.MAX_VALUE) }.toInt() }
                EvidenceReceipt(text("id"), text("recordId"), number("revision"), text("field"), number("start"), number("end"), number("totalCodePoints"), text("sha256"))
            }, list("feedbackTokens").map(ReductionReviewProtocol::parseToken)).also(::validate)
    }
    private fun obj(raw: Any?, fields: Set<String>): Map<*, *> = (raw as? Map<*, *>)?.also { require(it.keys == fields) }
        ?: throw IllegalArgumentException("整体画像凭据需要对象")
    private val HASH = Regex("[0-9a-f]{64}")
}
