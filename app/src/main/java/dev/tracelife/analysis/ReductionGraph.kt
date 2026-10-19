package dev.tracelife.analysis

/** Pure, bounded same-run provenance graph. Edges always point to earlier whole successful batches. */
object ReductionGraph {
    fun nodeRef(call: AnalysisCall): ReductionNodeRef {
        require(call.status == CallStatus.SUCCEEDED && call.purpose in setOf(CallPurpose.SYNTHESIS, CallPurpose.REDUCTION))
        return ReductionNodeRef(call.batchKey, call.attempt, outputSha256(call))
    }

    fun outputSha256(call: AnalysisCall): String = AnalysisRunCodec.sha256(AnalysisRunCodec.json(when (call.purpose) {
        CallPurpose.SYNTHESIS -> requireNotNull(call.proposal).let { mapOf("claims" to it.claims.map(AnalysisRunCodec::claimMap), "unknowns" to it.unknowns) }
        CallPurpose.REDUCTION -> digestMap(requireNotNull(call.reductionOutput))
        else -> throw IllegalArgumentException("只有综合与归并批次可以成为派生输入")
    }))

    fun input(node: ReductionNodeRef, requestSha256: String, feedbackTokens: List<ReductionFeedbackToken>): ReductionInput {
        val material = mapOf("node" to nodeMap(node), "requestSha256" to requestSha256,
            "feedbackTokens" to feedbackTokens.map(::tokenMap))
        return ReductionInput(AnalysisRunCodec.sha256(AnalysisRunCodec.json(material)), node, requestSha256, feedbackTokens.toList())
    }

    fun leafRefs(state: AnalysisRunState, node: ReductionNodeRef): List<ReductionNodeRef> =
        leaves(state.calls, node, false).map(::nodeRef)

    fun originalEvidence(state: AnalysisRunState, node: ReductionNodeRef): List<EvidenceReceipt> =
        leaves(state.calls, node, false).flatMap { it.evidence }.distinctBy { it.id }

    internal fun resolve(calls: List<AnalysisCall>, node: ReductionNodeRef, allowRedacted: Boolean = false): AnalysisCall {
        val call = requireNotNull(calls.singleOrNull { it.batchKey == node.batchKey && it.attempt == node.attempt }) { "派生输入不属于此前批次" }
        require(call.purpose == CallPurpose.SYNTHESIS || call.purpose == CallPurpose.REDUCTION)
        if (allowRedacted && isRedacted(call)) return call
        require(call.status == CallStatus.SUCCEEDED && outputSha256(call) == node.outputSha256) { "派生输入已变化或没有成功输出" }
        return call
    }

    private fun leaves(calls: List<AnalysisCall>, node: ReductionNodeRef, allowRedacted: Boolean): List<AnalysisCall> {
        val call = resolve(calls, node, allowRedacted)
        if (call.purpose == CallPurpose.SYNTHESIS) return listOf(call)
        val earlier = calls.take(calls.indexOf(call))
        return call.reductionInputs.flatMap { leaves(earlier, it.node, allowRedacted) }
    }

    internal fun claimRefs(calls: List<AnalysisCall>, node: ReductionNodeRef, allowRedacted: Boolean = false): List<ClaimRef> =
        leaves(calls, node, allowRedacted).flatMap { leaf -> requireNotNull(leaf.proposal).claims.mapIndexed { index, claim ->
            ClaimRef(leaf.batchKey, index, AnalysisRunCodec.claimSha256(claim))
        } }

    internal fun validateInputs(earlier: List<AnalysisCall>, call: AnalysisCall) {
        val inputs = call.reductionInputs
        require(inputs.size in 2..4 && inputs.map { it.node }.distinct().size == inputs.size)
        val allowRedacted = isRedacted(call)
        val allLeaves = mutableSetOf<Pair<String, Int>>()
        inputs.forEach { input ->
            AnalysisRunCodec.validId(input.node.batchKey)
            require(input.node.attempt > 0 && input.node.outputSha256.matches(HASH) && input.requestSha256.matches(HASH))
            require(input == this.input(input.node, input.requestSha256, input.feedbackTokens)) { "派生输入标识不一致" }
            val parent = resolve(earlier, input.node, allowRedacted)
            require(parent.requestSha256 == input.requestSha256)
            val leafCalls = leaves(earlier, input.node, allowRedacted)
            require(leafCalls.all { allLeaves.add(it.batchKey to it.attempt) }) { "派生输入重复或存在祖先重叠" }
            val claims = claimRefs(earlier, input.node, allowRedacted)
            require(input.feedbackTokens.map { it.claim } == claims && input.feedbackTokens.all { it.version >= 0 }) {
                "派生输入须登记每个原始综合陈述的反馈版本"
            }
            if (parent.purpose == CallPurpose.REDUCTION) require(input.feedbackTokens == parent.reductionInputs.flatMap { it.feedbackTokens }) {
                "派生输入改变了祖先反馈版本"
            }
        }
    }

    internal fun isRedacted(call: AnalysisCall) = call.purpose == CallPurpose.REDUCTION &&
        call.status == CallStatus.FAILED && call.failureCode == "FEEDBACK_DELETED"
    internal fun nodeMap(ref: ReductionNodeRef): Map<String, Any?> = mapOf("batchKey" to ref.batchKey, "attempt" to ref.attempt, "outputSha256" to ref.outputSha256)
    internal fun tokenMap(token: ReductionFeedbackToken): Map<String, Any?> = mapOf("claim" to AnalysisRunCodec.claimRefMap(token.claim), "version" to token.version)
    internal fun inputMap(input: ReductionInput): Map<String, Any?> = mapOf("id" to input.id, "node" to nodeMap(input.node),
        "requestSha256" to input.requestSha256, "feedbackTokens" to input.feedbackTokens.map(::tokenMap))
    internal fun digestMap(digest: ReductionDigest): Map<String, Any?> = mapOf("statements" to digest.statements.map {
        mapOf("text" to it.text, "inputIds" to it.inputIds)
    }, "unknowns" to digest.unknowns)
    private val HASH = Regex("[0-9a-f]{64}")
}
