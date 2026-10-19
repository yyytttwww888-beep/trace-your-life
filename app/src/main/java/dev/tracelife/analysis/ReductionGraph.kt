package dev.tracelife.analysis

/** Pure, bounded same-run provenance graph. Edges always point to earlier whole successful batches. */
object ReductionGraph {
    fun leafPurpose(state: AnalysisRunState): CallPurpose = if (state.request.actionPeriod) CallPurpose.ACTION_SYNTHESIS else CallPurpose.SYNTHESIS
    fun isLeaf(purpose: CallPurpose): Boolean = purpose == CallPurpose.SYNTHESIS || purpose == CallPurpose.ACTION_SYNTHESIS
    fun nodeRef(call: AnalysisCall): ReductionNodeRef {
        require(call.status == CallStatus.SUCCEEDED && call.purpose in setOf(CallPurpose.SYNTHESIS, CallPurpose.ACTION_SYNTHESIS, CallPurpose.REDUCTION))
        return ReductionNodeRef(call.batchKey, call.attempt, outputSha256(call))
    }

    fun outputSha256(call: AnalysisCall): String = AnalysisRunCodec.sha256(AnalysisRunCodec.json(when (call.purpose) {
        CallPurpose.SYNTHESIS, CallPurpose.ACTION_SYNTHESIS -> requireNotNull(call.proposal).let { mapOf("claims" to it.claims.map(AnalysisRunCodec::claimMap), "unknowns" to it.unknowns) }
        CallPurpose.REDUCTION -> digestMap(requireNotNull(call.reductionOutput))
        else -> throw IllegalArgumentException("只有综合与归并批次可以成为派生输入")
    }))

    fun input(node: ReductionNodeRef, requestSha256: String, feedbackTokens: List<ReductionFeedbackToken>, includeDerivedFeedback: Boolean = true): ReductionInput {
        val material = mapOf("node" to nodeMap(node), "requestSha256" to requestSha256,
            "feedbackTokens" to feedbackTokens.map(::tokenMap)) + if (includeDerivedFeedback) mapOf("includeDerivedFeedback" to true) else emptyMap()
        return ReductionInput(AnalysisRunCodec.sha256(AnalysisRunCodec.json(material)), node, requestSha256, feedbackTokens.toList(), includeDerivedFeedback)
    }

    fun leafRefs(state: AnalysisRunState, node: ReductionNodeRef): List<ReductionNodeRef> =
        leaves(state.calls, node, false).map(::nodeRef)

    fun originalEvidence(state: AnalysisRunState, node: ReductionNodeRef): List<EvidenceReceipt> =
        leaves(state.calls, node, false).flatMap { it.evidence }.distinctBy { it.id }

    fun originalActionEvidence(state: AnalysisRunState, node: ReductionNodeRef): List<dev.tracelife.actions.ActionEvidenceReceipt> =
        leaves(state.calls, node, false).flatMap { it.actionEvidence }.distinctBy { it.id }

    internal fun resolve(calls: List<AnalysisCall>, node: ReductionNodeRef, allowRedacted: Boolean = false): AnalysisCall {
        val call = requireNotNull(calls.singleOrNull { it.batchKey == node.batchKey && it.attempt == node.attempt }) { "派生输入不属于此前批次" }
        require(isLeaf(call.purpose) || call.purpose == CallPurpose.REDUCTION)
        if (allowRedacted && isRedacted(call)) {
            require(call.redactedReductionIdentity == null || call.redactedReductionIdentity.outputSha256 == node.outputSha256)
            return call
        }
        require(call.status == CallStatus.SUCCEEDED && outputSha256(call) == node.outputSha256) { "派生输入已变化或没有成功输出" }
        return call
    }

    private fun leaves(calls: List<AnalysisCall>, node: ReductionNodeRef, allowRedacted: Boolean): List<AnalysisCall> {
        val call = resolve(calls, node, allowRedacted)
        if (isLeaf(call.purpose)) return listOf(call)
        val earlier = calls.take(calls.indexOf(call))
        return call.reductionInputs.flatMap { leaves(earlier, it.node, allowRedacted) }
    }

    internal fun claimRefs(calls: List<AnalysisCall>, node: ReductionNodeRef, allowRedacted: Boolean = false): List<ClaimRef> =
        leaves(calls, node, allowRedacted).flatMap { leaf -> requireNotNull(leaf.proposal).claims.mapIndexed { index, claim ->
            ClaimRef(leaf.batchKey, index, AnalysisRunCodec.claimSha256(claim))
        } }

    fun statementRef(call: AnalysisCall, index: Int): ReductionStatementRef {
        require(call.purpose == CallPurpose.REDUCTION)
        val statement = requireNotNull(call.reductionOutput?.statements?.getOrNull(index))
        val root = nodeRef(call)
        return ReductionStatementRef(root, index, statementSha256(root, index, statement))
    }

    fun statementSha256(root: ReductionNodeRef, index: Int, statement: ReductionStatement): String =
        AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf(nodeMap(root), index, statementMap(statement))))
    fun feedbackRef(target: ReductionStatementRef) = ClaimRef(target.root.batchKey, target.index, target.statementSha256)

    fun matchesFeedbackTarget(state: AnalysisRunState, ref: ClaimRef): Boolean = state.calls.any { call ->
        if (call.batchKey != ref.batchKey) false
        else if (call.purpose.producesClaims && call.status == CallStatus.SUCCEEDED)
            call.proposal?.claims?.getOrNull(ref.claimIndex)?.let { AnalysisRunCodec.claimSha256(it) == ref.claimSha256 } == true
        else if (call.purpose == CallPurpose.REDUCTION && call.status == CallStatus.SUCCEEDED)
            call.reductionOutput?.statements?.getOrNull(ref.claimIndex)?.let { feedbackRef(statementRef(call, ref.claimIndex)) == ref } == true
        else call.redactedReductionIdentity?.statements?.contains(ref) == true
    }

    fun feedbackClaims(calls: List<AnalysisCall>, node: ReductionNodeRef, includeDerived: Boolean = true,
        allowRedacted: Boolean = false): List<ClaimRef> {
        val call = resolve(calls, node, allowRedacted)
        if (isLeaf(call.purpose)) return requireNotNull(call.proposal).claims.mapIndexed { index, claim ->
            ClaimRef(call.batchKey, index, AnalysisRunCodec.claimSha256(claim))
        }
        val earlier = calls.take(calls.indexOf(call))
        val parents = call.reductionInputs.flatMap { feedbackClaims(earlier, it.node, includeDerived, allowRedacted) }
        if (!includeDerived) return parents
        val own = if (isRedacted(call)) call.redactedReductionIdentity?.statements.orEmpty()
            else requireNotNull(call.reductionOutput).statements.indices.map { feedbackRef(statementRef(call, it)) }
        return parents + own
    }

    /** Frozen ancestor fences; missing derived entries in a legacy graph mean version zero. */
    fun feedbackDependencies(state: AnalysisRunState, ref: ClaimRef): List<ReductionFeedbackToken> {
        require(matchesFeedbackTarget(state, ref))
        val call = requireNotNull(state.calls.lastOrNull { it.batchKey == ref.batchKey && it.status == CallStatus.SUCCEEDED })
        if (call.purpose != CallPurpose.REDUCTION) return emptyList()
        return ancestorTokens(state.calls, nodeRef(call))
    }

    internal fun ancestorTokens(calls: List<AnalysisCall>, node: ReductionNodeRef, allowRedacted: Boolean = false): List<ReductionFeedbackToken> {
        val call = resolve(calls, node, allowRedacted)
        if (call.purpose != CallPurpose.REDUCTION) return emptyList()
        val known = linkedMapOf<ClaimRef, Int>()
        fun collect(available: List<AnalysisCall>, current: AnalysisCall) {
            current.reductionInputs.forEach { input ->
                input.feedbackTokens.forEach { token ->
                    val old = known.put(token.claim, token.version)
                    require(old == null || old == token.version)
                }
                val parent = resolve(available, input.node, allowRedacted)
                if (parent.purpose == CallPurpose.REDUCTION) collect(available.take(available.indexOf(parent)), parent)
            }
        }
        collect(calls.take(calls.indexOf(call)), call)
        val ancestors = call.reductionInputs.flatMap { feedbackClaims(calls.take(calls.indexOf(call)), it.node, true, allowRedacted) }
        return ancestors.map { ReductionFeedbackToken(it, known[it] ?: 0) }
    }

    internal fun statement(calls: List<AnalysisCall>, target: ReductionStatementRef): ReductionStatement {
        val call = resolve(calls, target.root)
        require(call.purpose == CallPurpose.REDUCTION && statementRef(call, target.index) == target)
        return requireNotNull(call.reductionOutput).statements[target.index]
    }

    internal fun validateInputs(earlier: List<AnalysisCall>, call: AnalysisCall) {
        val inputs = call.reductionInputs
        require(inputs.size in 2..4 && inputs.map { it.node }.distinct().size == inputs.size)
        val allowRedacted = isRedacted(call)
        val allLeaves = mutableSetOf<Pair<String, Int>>()
        inputs.forEach { input ->
            AnalysisRunCodec.validId(input.node.batchKey)
            require(input.node.attempt > 0 && input.node.outputSha256.matches(HASH) && input.requestSha256.matches(HASH))
            require(input == this.input(input.node, input.requestSha256, input.feedbackTokens, input.includeDerivedFeedback)) { "派生输入标识不一致" }
            val parent = resolve(earlier, input.node, allowRedacted)
            require(parent.requestSha256 == input.requestSha256)
            val leafCalls = leaves(earlier, input.node, allowRedacted)
            require(leafCalls.all { allLeaves.add(it.batchKey to it.attempt) }) { "派生输入重复或存在祖先重叠" }
            val claims = feedbackClaims(earlier, input.node, input.includeDerivedFeedback, allowRedacted)
            require(input.feedbackTokens.map { it.claim } == claims && input.feedbackTokens.all { it.version >= 0 }) {
                "派生输入须登记每个原始综合陈述的反馈版本"
            }
            if (parent.purpose == CallPurpose.REDUCTION) {
                val inherited = ancestorTokens(earlier, input.node, allowRedacted).associateBy { it.claim }
                require(input.feedbackTokens.all { token -> inherited[token.claim]?.let { it == token } != false }) {
                    "派生输入改变了祖先反馈版本"
                }
                require(input.includeDerivedFeedback || parent.reductionInputs.none { it.includeDerivedFeedback })
            }
        }
    }

    internal fun isRedacted(call: AnalysisCall) = call.purpose == CallPurpose.REDUCTION &&
        call.status == CallStatus.FAILED && call.failureCode == "FEEDBACK_DELETED"
    internal fun nodeMap(ref: ReductionNodeRef): Map<String, Any?> = mapOf("batchKey" to ref.batchKey, "attempt" to ref.attempt, "outputSha256" to ref.outputSha256)
    internal fun tokenMap(token: ReductionFeedbackToken): Map<String, Any?> = mapOf("claim" to AnalysisRunCodec.claimRefMap(token.claim), "version" to token.version)
    internal fun inputMap(input: ReductionInput): Map<String, Any?> = mapOf("id" to input.id, "node" to nodeMap(input.node),
        "requestSha256" to input.requestSha256, "feedbackTokens" to input.feedbackTokens.map(::tokenMap), "includeDerivedFeedback" to input.includeDerivedFeedback)
    internal fun statementMap(statement: ReductionStatement): Map<String, Any?> = mapOf("text" to statement.text, "inputIds" to statement.inputIds)
    internal fun digestMap(digest: ReductionDigest): Map<String, Any?> = mapOf("statements" to digest.statements.map {
        mapOf("text" to it.text, "inputIds" to it.inputIds)
    }, "unknowns" to digest.unknowns)
    private val HASH = Regex("[0-9a-f]{64}")
}
