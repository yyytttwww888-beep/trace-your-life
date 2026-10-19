package dev.tracelife.analysis

import dev.tracelife.actions.ActionEvidenceProtocol
import dev.tracelife.actions.ActionEvidenceReceipt
import dev.tracelife.profile.LongitudinalProfileProtocol
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import dev.tracelife.model.RecordRevision
import dev.tracelife.metrics.RecordMetricProtocol
import dev.tracelife.metrics.RecordMetricReceipt
import dev.tracelife.profile.ProfileAnalysisTarget
import dev.tracelife.profile.ProfileDimension
import dev.tracelife.profile.ProfileLayer
import dev.tracelife.profile.ProfileMaintenanceProtocol
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.StoredAnalysisRun
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bounded, strict, versioned task storage. Never stores copies of original evidence. */
object AnalysisRunCodec {
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
    private val digest = Regex("[0-9a-f]{64}")
    private val NEW_FIELDS = setOf("redactedReductionIdentity", "reductionTarget", "reductionReviewTokens", "reductionScan", "reductionScans")

    private val PROFILE_FIELDS = setOf("profileMaintenancePlanSha256", "profileReference", "profileRelation", "profileScan", "profileMaintenanceProof")

    fun encode(state: AnalysisRunState): String = encodeVersion(state, 10)

    private fun encodeVersion(state: AnalysisRunState, version: Int): String {
        validate(state)
        require(version >= 4 || state.calls.none { it.purpose == CallPurpose.SYNTHESIS }) { "旧分析格式不支持综合调用" }
        require(version >= 5 || state.calls.none { it.purpose == CallPurpose.REDUCTION }) { "旧分析格式不支持归并调用" }
        require(version >= 6 || state.calls.none { it.purpose in setOf(CallPurpose.REDUCTION_SCAN, CallPurpose.REDUCTION_REVIEW) ||
            it.reductionInputs.any { input -> input.includeDerivedFeedback } || it.redactedReductionIdentity != null })
        require(version >= 7 || state.calls.none { it.purpose == CallPurpose.METRIC_ANALYSIS || it.metricEvidence.isNotEmpty() })
        require(version >= 8 || state.request.profileTarget == null) { "旧分析格式不支持画像目标" }
        require(version >= 9 || state.profileMaintenancePlans.isEmpty() && state.calls.none { it.profileMaintenancePlanSha256 != null })
        require(version >= 10 || !state.request.actionPeriod && state.actionPlan == null && state.calls.all { it.actionEvidence.isEmpty() } && state.request.profileTarget?.layer != ProfileLayer.LONG_TERM)
        val reserved = responseReservation(state)
        val request = requestMap(state.request).let { raw ->
            var value = if (version < 10) raw - "actionPeriod" else raw
            if (version < 8) value = value - "profileTarget"
            else if (version < 10) value = value + ("profileTarget" to state.request.profileTarget?.let { mapOf("dimension" to it.dimension.name, "layer" to it.layer.name) })
            value
        }
        return json(mapOf("formatVersion" to version, "request" to request, "calls" to state.calls.map {
            var fields = callMap(it)
            if (version < 10) fields = fields - "actionEvidence"
            if (version < 9) fields = fields - PROFILE_FIELDS
            if (version < 7) fields = fields - "metricEvidence"
            if (version < 6) fields = (fields - NEW_FIELDS) + mapOf("reductionInputs" to it.reductionInputs.map { input -> ReductionGraph.inputMap(input) - "includeDerivedFeedback" })
            if (version < 5) fields = fields - setOf("reductionInputs", "reductionOutput")
            when (version) {
                1 -> fields - setOf("tokenCountKind", "usage", "purpose", "reviewTarget", "review", "feedbackVersion")
                2 -> fields - setOf("purpose", "reviewTarget", "review", "feedbackVersion")
                else -> fields
            }
        }) + (if (version >= 9) mapOf("profileMaintenancePlans" to state.profileMaintenancePlans.map(ProfileMaintenanceProtocol::planMap)) else emptyMap()) +
            (if (version >= 10) mapOf("actionPlan" to state.actionPlan?.let(ActionEvidenceProtocol::planMap)) else emptyMap()),
            nodeLimit = if (reserved > 0) 59_000 else 60_000)
            .also { if (it.toByteArray(Charsets.UTF_8).size + reserved > MAX_PAYLOAD_BYTES) throw AnalysisPayloadCapacityException("分析任务及输出预留超过保存上限") }
    }

    fun decode(payload: String): AnalysisRunState = decodeEnvelope(payload).first

    /** Erase model text derived from deleted user feedback without refunding consumed budgets.
     * An untouched historical payload is returned byte-for-byte, without a format upgrade. */
    fun redactDeletedFeedback(run: StoredAnalysisRun, deletedFeedbackIds: Set<String>, now: Instant): StoredAnalysisRun {
        if (deletedFeedbackIds.isEmpty()) return run
        val state = decode(run.payload)
        var changed = false
        val calls = state.calls.mapIndexed { index, call ->
            val ref = call.reviewTarget
            val ownReduction = if (call.purpose == CallPurpose.REDUCTION && call.reductionOutput != null)
                call.reductionOutput.statements.indices.map { ReductionGraph.feedbackRef(ReductionGraph.statementRef(call, it)) }
                else call.redactedReductionIdentity?.statements.orEmpty()
            val dependencies = if (call.purpose == CallPurpose.REDUCTION) call.reductionInputs.flatMap {
                ReductionGraph.feedbackClaims(state.calls.take(index), it.node, true, true)
            } + ownReduction else call.reductionReviewTokens.map { it.claim }
            val affected = call.purpose == CallPurpose.REVIEW && ref != null && call.feedbackVersion > 0 && feedbackId(run.id, ref) in deletedFeedbackIds ||
                dependencies.any { feedbackId(run.id, it) in deletedFeedbackIds }
            if (affected && call.failureCode != "FEEDBACK_DELETED") {
                changed = true
                val identity = if (call.purpose == CallPurpose.REDUCTION && call.reductionOutput != null)
                    RedactedReductionIdentity(ReductionGraph.outputSha256(call), ownReduction) else call.redactedReductionIdentity
                call.copy(status = CallStatus.FAILED, failureCode = "FEEDBACK_DELETED", finishedAt = maxOf(now, call.startedAt),
                    review = null, reductionOutput = null, reductionScan = null, usage = null, redactedReductionIdentity = identity)
            } else call
        }
        if (!changed) return run
        require(run.version < Int.MAX_VALUE) { "分析任务版本达到上限" }
        val payload = try { encode(state.copy(calls = calls)) }
            catch (_: AnalysisPayloadCapacityException) { throw AnalysisRedactionCapacityException() }
        return run.copy(version = run.version + 1, updatedAt = maxOf(now, run.updatedAt), payload = payload)
    }

    private fun decodeEnvelope(payload: String): Pair<AnalysisRunState, Int> {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "分析任务超过保存上限" }
        val reader = JsonReader(StringReader(payload)).apply { isLenient = false }
        var nodes = 0
        fun read(depth: Int): Any? {
            require(depth <= 12 && ++nodes <= 60_000) { "分析 JSON 结构过大" }
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(name.length <= 100 && !containsKey(name)) { "分析 JSON 字段重复或过长" }
                        put(name, read(depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> buildList {
                    reader.beginArray()
                    while (reader.hasNext()) add(read(depth + 1))
                    reader.endArray()
                }
                JsonToken.STRING -> reader.nextString().also { require(it.length <= 65_536) }
                JsonToken.NUMBER -> reader.nextString().let {
                    require(Regex("-?(0|[1-9][0-9]*)").matches(it))
                    it.toLong()
                }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> throw AnalysisValidationException("分析 JSON 无效")
            }
        }
        @Suppress("UNCHECKED_CAST")
        val rawRoot = read(0) as? Map<String, Any?> ?: error("分析 JSON 需要对象")
        val root = rawRoot.obj(*(arrayOf("formatVersion", "request", "calls") +
            (if (rawRoot.int("formatVersion") >= 9) arrayOf("profileMaintenancePlans") else emptyArray()) +
            (if (rawRoot.int("formatVersion") >= 10) arrayOf("actionPlan") else emptyArray())))
        require(reader.peek() == JsonToken.END_DOCUMENT && root.int("formatVersion") in 1..10)
        val format = root.int("formatVersion")
        val legacy = format == 1
        val request = root["request"].obj(*(arrayOf("question", "scope", "limits", "focusTopics", "expiresAt") +
            (if (format >= 8) arrayOf("profileTarget") else emptyArray()) + (if (format >= 10) arrayOf("actionPeriod") else emptyArray())))
        val profileTarget = request["profileTarget"]?.let { raw ->
            LongitudinalProfileProtocol.parseTarget(raw, allowLongTerm = format >= 10)
        }
        val scope = request["scope"].obj("from", "through", "zoneId", "includeArchived", "recordIds")
        val limits = request["limits"].obj("pageSize", "fragmentCodePoints", "maxInputBytes", "inputTokenLimit", "outputTokenReserve", "maxCalls", "totalInputBytes", "maxOutputBytes")
        val state = AnalysisRunState(
            AnalysisRequest(request.string("question"), AnalysisQuery(
                LocalDate.parse(scope.string("from")), LocalDate.parse(scope.string("through")), scope.string("zoneId"),
                scope["includeArchived"] as? Boolean ?: error("归档范围无效"),
                scope["recordIds"]?.let { scope.strings("recordIds").uniqueSet() },
            ), AnalysisLimits(limits.int("pageSize"), limits.int("fragmentCodePoints"), limits.int("maxInputBytes"),
                limits.int("inputTokenLimit"), limits.int("outputTokenReserve"), limits.int("maxCalls"),
                limits.long("totalInputBytes"), limits.int("maxOutputBytes")),
                request.strings("focusTopics").uniqueSet(), request.optionalInstant("expiresAt"), profileTarget,
                if (format >= 10) request["actionPeriod"] as? Boolean ?: error("行动复盘标记无效") else false),
            root.list("calls").map { raw ->
                val fields = arrayOf("batchKey", "attempt", "providerId", "requestSha256", "inputBytes", "inputTokens", "evidence", "startedAt", "status", "finishedAt", "failureCode", "proposal")
                val metered = fields + arrayOf("tokenCountKind", "usage")
                val reviewed = metered + arrayOf("purpose", "reviewTarget", "review", "feedbackVersion")
                val call = raw.obj(*when (format) {
                    1 -> fields
                    2 -> metered
                    3, 4 -> reviewed
                    5 -> reviewed + arrayOf("reductionInputs", "reductionOutput")
                    6 -> reviewed + arrayOf("reductionInputs", "reductionOutput") + NEW_FIELDS.toTypedArray()
                    7, 8 -> reviewed + arrayOf("reductionInputs", "reductionOutput", "metricEvidence") + NEW_FIELDS.toTypedArray()
                    else -> reviewed + arrayOf("reductionInputs", "reductionOutput", "metricEvidence") + NEW_FIELDS.toTypedArray() + PROFILE_FIELDS.toTypedArray() +
                        if (format >= 10) arrayOf("actionEvidence") else emptyArray()
                })
                AnalysisCall(call.string("batchKey"), call.int("attempt"), call.string("providerId"), call.string("requestSha256"),
                    call.int("inputBytes"), call.int("inputTokens"), call.list("evidence").map { item ->
                        val ref = item.obj("id", "recordId", "revision", "field", "start", "end", "totalCodePoints", "sha256")
                        EvidenceReceipt(ref.string("id"), ref.string("recordId"), ref.int("revision"), ref.string("field"),
                            ref.int("start"), ref.int("end"), ref.int("totalCodePoints"), ref.string("sha256"))
                    }, Instant.parse(call.string("startedAt")), CallStatus.valueOf(call.string("status")), call.optionalInstant("finishedAt"),
                    call["failureCode"]?.let { call.string("failureCode") }, call["proposal"]?.let(::parseProposal),
                    if (legacy) TokenCountKind.EXACT else TokenCountKind.valueOf(call.string("tokenCountKind")),
                    call["usage"]?.let { value -> value.obj("promptTokens", "completionTokens").let { ModelUsage(it.int("promptTokens"), it.int("completionTokens")) } },
                    if (format < 3) CallPurpose.EXTRACTION else CallPurpose.valueOf(call.string("purpose")),
                    call["reviewTarget"]?.let { value -> value.obj("batchKey", "claimIndex", "claimSha256").let {
                        ClaimRef(it.string("batchKey"), it.int("claimIndex"), it.string("claimSha256"))
                    } }, call["review"]?.let(::parseReview), if (format < 3) 0 else call.int("feedbackVersion"),
                    if (format < 5) emptyList() else call.list("reductionInputs").map { parseReductionInput(it, format) },
                    call["reductionOutput"]?.let(::parseReductionDigest),
                    call["redactedReductionIdentity"]?.let(::parseRedactedIdentity),
                    call["reductionTarget"]?.let(ReductionReviewProtocol::parseTarget),
                    if (format < 6) emptyList() else call.list("reductionReviewTokens").map(ReductionReviewProtocol::parseToken),
                    call["reductionScan"]?.let(ReductionReviewProtocol::parseScan),
                    if (format < 6) emptyList() else call.list("reductionScans").map(ReductionReviewProtocol::parseScanRef),
                    if (format < 7) emptyList() else call.list("metricEvidence").map(RecordMetricProtocol::parse),
                    call["profileMaintenancePlanSha256"]?.let { call.string("profileMaintenancePlanSha256") },
                    call["profileReference"]?.let(ProfileMaintenanceProtocol::parseReference),
                    call["profileRelation"]?.let(ProfileMaintenanceProtocol::parseRelation),
                    call["profileScan"]?.let(ReductionReviewProtocol::parseScan),
                    call["profileMaintenanceProof"]?.let(ProfileMaintenanceProtocol::parseProof),
                    if (format >= 10) call.list("actionEvidence").map(ActionEvidenceProtocol::parseReceipt) else emptyList())
            },
            if (format < 9) emptyList() else root.list("profileMaintenancePlans").map(ProfileMaintenanceProtocol::parsePlan),
            root["actionPlan"]?.let(ActionEvidenceProtocol::parsePlan),
        )
        require(format >= 10 || state.calls.none { it.purpose == CallPurpose.ACTION_SYNTHESIS })
        require(format >= 9 || state.calls.none { it.purpose in setOf(CallPurpose.PROFILE_RELATION, CallPurpose.PROFILE_SCAN) })
        require(format >= 4 || state.calls.none { it.purpose == CallPurpose.SYNTHESIS }) { "旧分析格式不支持综合调用" }
        require(format >= 5 || state.calls.none { it.purpose == CallPurpose.REDUCTION }) { "旧分析格式不支持归并调用" }
        require(format >= 6 || state.calls.none { it.purpose == CallPurpose.REDUCTION_SCAN || it.purpose == CallPurpose.REDUCTION_REVIEW })
        require(format >= 7 || state.calls.none { it.purpose == CallPurpose.METRIC_ANALYSIS || it.metricEvidence.isNotEmpty() })
        validate(state)
        return state to root.int("formatVersion")
    }

    fun validateStored(run: StoredAnalysisRun, records: List<RecordRevision>? = null) {
        validId(run.id); validId(run.source.storeId)
        require(run.version > 0 && run.source.generation >= 0 && run.updatedAt >= run.createdAt)
        val (state, format) = decodeEnvelope(run.payload)
        // Preserve valid near-limit historical v1 payloads without forcing an expanded v2 rewrite.
        encodeVersion(state, format)
        require(run.payload.toByteArray(Charsets.UTF_8).size + responseReservation(state) <= MAX_PAYLOAD_BYTES) { "分析任务及输出预留超过保存上限" }
        require(run.expiresAt == state.request.expiresAt)
        require(run.expiresAt == null || run.expiresAt > run.createdAt)
        state.calls.flatMap { it.metricEvidence }.forEach {
            RecordMetricProtocol.validate(it, state.request.scope, run.source, records)
        }
        records?.let { sources ->
            val byRevision = sources.associateBy { it.id to it.revision }
            state.calls.flatMap { it.evidence }.forEach { ref ->
                val record = requireNotNull(byRevision[ref.recordId to ref.revision]) { "分析证据引用缺少原始修订" }
                val text = if (ref.field == "body") record.draft.body else
                    requireNotNull(record.draft.answers[ref.field.removePrefix("answer:")]) { "分析证据字段不存在" }
                require(text.codePointCount(0, text.length) == ref.totalCodePoints)
                require(sha256(slice(text, ref.start, ref.end)) == ref.sha256) { "分析证据校验值不一致" }
                require(state.request.scope.recordIds == null || ref.recordId in state.request.scope.recordIds)
                require(state.request.scope.includeArchived || !record.archived)
                val occurrence = record.draft.occurrence
                require(occurrence.startDate == null || occurrence.startDate <= state.request.scope.through && occurrence.endDate!! >= state.request.scope.from)
            }
            state.request.profileTarget?.let { target ->
                state.calls.forEach { call ->
                    val metadata = call.evidence.map { ref -> requireNotNull(byRevision[ref.recordId to ref.revision]) }.distinctBy { it.id to it.revision }.map {
                        dev.tracelife.storage.SourceDescriptor(it.id, it.revision, it.draft.occurrence, it.archived, it.draft.topics,
                            dev.tracelife.storage.SourceTimeClass.WITHIN)
                    }
                    if (call.purpose == CallPurpose.SYNTHESIS) LongitudinalProfileProtocol.validateInputs(target, call.evidence, metadata)
                    call.proposal?.let { LongitudinalProfileProtocol.validateProposal(target, it, call.evidence, metadata) }
                    call.review?.let { LongitudinalProfileProtocol.validateReview(target, it, call.evidence, metadata) }
                }
            }
        }
    }

    internal fun reservedResponseBytes(run: StoredAnalysisRun): Int = responseReservation(decode(run.payload))
    private fun responseReservation(state: AnalysisRunState): Int =
        if (state.calls.any { it.status == CallStatus.IN_FLIGHT }) state.request.limits.maxOutputBytes + 256 else 0

    internal fun validateProposal(proposal: BatchProposal, evidence: List<EvidenceReceipt>, maxBytes: Int,
        metricEvidence: List<RecordMetricReceipt> = emptyList(), actionEvidence: List<ActionEvidenceReceipt> = emptyList()) {
        require(proposal.claims.size <= 20 && proposal.unknowns.size <= 20) { "提案条目过多" }
        val ids = (evidence.map { it.id } + metricEvidence.map { it.id } + actionEvidence.map { it.id }).toSet()
        proposal.claims.forEach { claim ->
            require(claim.text.isNotBlank() && claim.text.length <= 2_000) { "提案文字无效" }
            require(claim.evidenceIds.size in 1..32 && claim.evidenceIds.distinct().size == claim.evidenceIds.size && claim.evidenceIds.all { it in ids }) {
                "提案引用不属于本次实际提供的证据"
            }
        }
        require(proposal.unknowns.all { it.isNotBlank() && it.length <= 2_000 })
        require(json(proposalMap(proposal)).toByteArray(Charsets.UTF_8).size <= maxBytes) { "提案输出超过预算" }
    }

    internal fun reductionFromWire(proposal: BatchProposal, inputs: List<ReductionInput>, maxBytes: Int): ReductionDigest {
        require(proposal.claims.all { it.kind == ClaimKind.POSSIBLE_INTERPRETATION }) { "派生归并只能保留为可能解释" }
        require(json(proposalMap(proposal)).toByteArray(Charsets.UTF_8).size <= maxBytes)
        return ReductionDigest(proposal.claims.map { ReductionStatement(it.text, it.evidenceIds) }, proposal.unknowns).also {
            validateReduction(it, inputs, maxBytes)
        }
    }

    internal fun validateReduction(output: ReductionDigest, inputs: List<ReductionInput>, maxBytes: Int) {
        require(output.statements.size <= 20 && output.unknowns.size <= 20)
        val ids = inputs.map { it.id }.toSet()
        output.statements.forEach { statement ->
            require(statement.text.isNotBlank() && statement.text.length <= 2_000)
            require(statement.inputIds.size in 1..4 && statement.inputIds.distinct().size == statement.inputIds.size && statement.inputIds.all { it in ids }) {
                "归并只能关联本次派生输入，不得伪装成原文引用"
            }
        }
        require(output.unknowns.all { it.isNotBlank() && it.length <= 2_000 })
        require(json(ReductionGraph.digestMap(output)).toByteArray(Charsets.UTF_8).size <= maxBytes)
    }

    internal fun validateReview(review: ClaimReview, evidence: List<EvidenceReceipt>, maxBytes: Int,
        metricEvidence: List<RecordMetricReceipt> = emptyList(), actionEvidence: List<ActionEvidenceReceipt> = emptyList()) {
        val ids = (evidence.map { it.id } + metricEvidence.map { it.id } + actionEvidence.map { it.id }).toSet()
        listOf(review.supportingEvidenceIds, review.contradictingEvidenceIds).forEach { refs ->
            require(refs.size <= 32 && refs.distinct().size == refs.size && refs.all { it in ids }) { "核对引用不属于本次证据" }
        }
        require(review.supportingEvidenceIds.intersect(review.contradictingEvidenceIds.toSet()).isEmpty()) { "支持和反例引用不能重叠" }
        require(review.issues.distinct().size == review.issues.size)
        require(review.unknowns.size <= 20 && review.unknowns.all { it.isNotBlank() && it.length <= 2_000 })
        when (review.verdict) {
            ReviewVerdict.SUPPORTED -> require(review.supportingEvidenceIds.isNotEmpty() && review.contradictingEvidenceIds.isEmpty() && review.issues.isEmpty())
            ReviewVerdict.CONTRADICTED -> require(review.contradictingEvidenceIds.isNotEmpty())
            ReviewVerdict.INSUFFICIENT_CONTEXT -> require(review.unknowns.isNotEmpty() || ReviewIssue.INSUFFICIENT_CONTEXT in review.issues)
            ReviewVerdict.UNSUPPORTED -> Unit
        }
        require(json(reviewMap(review)).toByteArray(Charsets.UTF_8).size <= maxBytes) { "核对输出超过预算" }
    }

    private fun validate(state: AnalysisRunState) {
        require(state.request.actionPeriod == (state.actionPlan != null)) { "行动复盘缺少固定目录" }
        state.actionPlan?.let(ActionEvidenceProtocol::validatePlan)
        require(!state.request.actionPeriod || state.calls.all { it.purpose in setOf(CallPurpose.ACTION_SYNTHESIS, CallPurpose.REVIEW) })
        state.request.profileTarget?.let { LongitudinalProfileProtocol.validateScope(it, state.request.scope) }
        require(state.request.profileTarget == null || state.calls.all {
            it.purpose in setOf(CallPurpose.SYNTHESIS, CallPurpose.REVIEW, CallPurpose.PROFILE_RELATION, CallPurpose.PROFILE_SCAN)
        }) { "画像任务只允许原文候选综合与核对" }
        require(state.profileMaintenancePlans.size <= 2_000 && state.profileMaintenancePlans.map { it.target }.distinct().size == state.profileMaintenancePlans.size)
        state.profileMaintenancePlans.forEach { plan ->
            ProfileMaintenanceProtocol.validatePlan(plan)
            require(plan.profileTarget == state.request.profileTarget)
            val generated = requireNotNull(state.calls.lastOrNull { it.batchKey == plan.target.batchKey && it.purpose == CallPurpose.SYNTHESIS && it.status == CallStatus.SUCCEEDED })
            require(claimSha256(requireNotNull(generated.proposal).claims[plan.target.claimIndex]) == plan.target.claimSha256)
            require(plan.createdAt >= requireNotNull(generated.finishedAt))
        }
        val scope = state.request.scope
        require(scope.from.year in 1..9999 && scope.through.year in 1..9999 && scope.from <= scope.through)
        ZoneId.of(scope.zoneId)
        scope.recordIds?.let { require(it.size in 1..100); it.forEach(::validId) }
        val limits = state.request.limits
        require(state.calls.size <= limits.maxCalls)
        require(state.calls.sumOf { it.inputBytes.toLong() } <= limits.totalInputBytes)
        require(state.calls.count { it.status == CallStatus.IN_FLIGHT } <= 1)
        state.calls.groupBy { it.batchKey }.values.forEach { attempts ->
            require(attempts.map { it.attempt } == (1..attempts.size).toList())
            require(attempts.map { it.requestSha256 to it.providerId }.distinct().size == 1) { "重试改变了请求或模型" }
            require(attempts.map { Triple(it.purpose, it.reviewTarget, it.feedbackVersion) }.distinct().size == 1) { "重试改变了核对目标或反馈" }
            require(attempts.map { listOf(it.reductionTarget, it.reductionReviewTokens, it.reductionScans) }.distinct().size == 1)
            require(attempts.map { it.reductionInputs }.distinct().size == 1) { "重试改变了派生输入" }
            require(attempts.map { listOf(it.profileMaintenancePlanSha256, it.profileReference, it.profileMaintenanceProof) }.distinct().size == 1)
            require(attempts.map { it.actionEvidence }.distinct().size == 1) { "重试改变了行动证据" }
            require(attempts.map { it.metricEvidence }.distinct().size == 1) { "重试改变了指标证据" }
            require(attempts.dropLast(1).all { it.status == CallStatus.FAILED || it.status == CallStatus.INTERRUPTED })
        }
        state.calls.forEachIndexed { index, call ->
            validId(call.batchKey); validId(call.providerId)
            require(digest.matches(call.requestSha256) && call.inputBytes in 1..limits.maxInputBytes && call.inputTokens in 1..limits.inputTokenLimit)
            call.usage?.let { require(call.status == CallStatus.SUCCEEDED && it.promptTokens in 1..call.inputTokens && it.completionTokens in 0..limits.outputTokenReserve) }
            val actionCall = call.purpose == CallPurpose.ACTION_SYNTHESIS || call.purpose == CallPurpose.REVIEW && state.request.actionPeriod
            require(actionCall == state.request.actionPeriod) { "行动复盘调用目的不符" }
            require(actionCall || call.actionEvidence.isEmpty())
            require(call.actionEvidence.map { it.id }.distinct().size == call.actionEvidence.size)
            call.actionEvidence.forEach { ActionEvidenceProtocol.validateReceipt(it); require(it in requireNotNull(state.actionPlan).entries) }
            val metricCall = call.purpose == CallPurpose.METRIC_ANALYSIS ||
                call.purpose == CallPurpose.REVIEW && call.metricEvidence.isNotEmpty()
            if (metricCall) {
                require(call.evidence.isEmpty() && call.metricEvidence.size == 1) { "指标调用只能提供一个完整范围指标" }
                call.metricEvidence.forEach { RecordMetricProtocol.validate(it, scope) }
            } else require(call.metricEvidence.isEmpty()) { "本调用类型不支持指标证据" }
            if (call.purpose == CallPurpose.REDUCTION || call.purpose == CallPurpose.PROFILE_RELATION || metricCall) require(call.evidence.isEmpty())
            else require(call.evidence.size + call.actionEvidence.size in 1..32 && call.evidence.map { it.id }.distinct().size == call.evidence.size)
            call.evidence.forEach { ref ->
                validId(ref.recordId)
                require(digest.matches(ref.id) && digest.matches(ref.sha256) && ref.revision > 0)
                require(ref.field == "body" || ref.field.startsWith("answer:") && ref.field.length in 8..207)
                require(ref.start >= 0 && ref.end > ref.start && ref.end <= ref.totalCodePoints && ref.end - ref.start <= limits.fragmentCodePoints)
                require(ref.id == evidenceId(ref.recordId, ref.revision, ref.field, ref.start, ref.end, ref.sha256))
            }
            val maintenance = call.profileMaintenancePlanSha256 != null
            if (maintenance) {
                require(call.purpose in setOf(CallPurpose.PROFILE_RELATION, CallPurpose.PROFILE_SCAN, CallPurpose.REVIEW))
                val plan = requireNotNull(state.profileMaintenancePlans.singleOrNull { ProfileMaintenanceProtocol.planSha256(it) == call.profileMaintenancePlanSha256 })
                require(plan.target == call.reviewTarget && call.startedAt >= plan.createdAt)
                val earlier = state.calls.take(index).filter { it.status == CallStatus.SUCCEEDED && it.reviewTarget == plan.target &&
                    it.profileMaintenancePlanSha256 == call.profileMaintenancePlanSha256 }
                if (call.purpose == CallPurpose.PROFILE_RELATION) {
                    require(call.profileReference in plan.references && call.profileScan == null && call.profileMaintenanceProof == null)
                    require(earlier.none { it.purpose == CallPurpose.PROFILE_RELATION && it.profileReference == call.profileReference }) { "重复关系判断" }
                }
                else require(call.profileReference == null && call.profileRelation == null)
                if (call.purpose == CallPurpose.PROFILE_SCAN) {
                    require(call.profileMaintenanceProof == null)
                    require(plan.references.all { ref -> earlier.any { it.purpose == CallPurpose.PROFILE_RELATION && it.profileReference == ref } })
                    val occupied = earlier.filter { it.purpose == CallPurpose.PROFILE_SCAN }.flatMap { it.evidence }.map { it.id }.toSet()
                    require(call.evidence.none { it.id in occupied }) { "重复扫描片段" }
                }
                if (call.purpose == CallPurpose.REVIEW) {
                    require(call.profileScan == null)
                    val proof = requireNotNull(call.profileMaintenanceProof)
                    require(proof.plan == plan)
                    val allRelations = plan.references.map { ref -> requireNotNull(earlier.singleOrNull { it.purpose == CallPurpose.PROFILE_RELATION && it.profileReference == ref }) }
                    require(proof.relations.map { it.ref } == allRelations.map(ProfileMaintenanceProtocol::relationRef))
                    require(proof.scans.map { it.ref } == earlier.filter { it.purpose == CallPurpose.PROFILE_SCAN }.map(ProfileMaintenanceProtocol::scanRef)) { "维护核对遗漏历史扫描" }
                    ProfileMaintenanceProtocol.validateProof(proof, call.evidence, call.review)
                    proof.relations.forEach { item -> require(state.calls.take(index).any { previous ->
                        previous.status == CallStatus.SUCCEEDED && previous.purpose == CallPurpose.PROFILE_RELATION && previous.reviewTarget == plan.target &&
                            previous.profileMaintenancePlanSha256 == call.profileMaintenancePlanSha256 && previous.profileReference == item.reference &&
                            previous.profileRelation == item.result && previous.providerId == item.providerId && ProfileMaintenanceProtocol.relationRef(previous) == item.ref }) }
                    proof.scans.forEach { item -> require(state.calls.take(index).any { previous ->
                        previous.status == CallStatus.SUCCEEDED && previous.purpose == CallPurpose.PROFILE_SCAN && previous.reviewTarget == plan.target &&
                            previous.profileMaintenancePlanSha256 == call.profileMaintenancePlanSha256 && previous.profileScan == item.result &&
                            previous.evidence == item.evidence && previous.providerId == item.providerId && ProfileMaintenanceProtocol.scanRef(previous) == item.ref }) }
                }
            } else require(call.purpose !in setOf(CallPurpose.PROFILE_RELATION, CallPurpose.PROFILE_SCAN) && call.profileReference == null &&
                call.profileRelation == null && call.profileScan == null && call.profileMaintenanceProof == null)
            require(call.feedbackVersion >= 0)
            if (call.purpose != CallPurpose.REDUCTION) require(call.reductionInputs.isEmpty() && call.reductionOutput == null)
            val reductionReview = call.purpose in setOf(CallPurpose.REDUCTION_SCAN, CallPurpose.REDUCTION_REVIEW)
            if (!reductionReview) require(call.reductionTarget == null && call.reductionReviewTokens.isEmpty() && call.reductionScan == null && call.reductionScans.isEmpty())
            call.redactedReductionIdentity?.let { identity ->
                require(ReductionGraph.isRedacted(call) && digest.matches(identity.outputSha256) && identity.statements.size <= 20)
                require(identity.statements.map { it.claimIndex } == identity.statements.indices.toList())
                require(identity.statements.all { it.batchKey == call.batchKey && digest.matches(it.claimSha256) })
            }
            if (call.purpose.producesClaims) {
                require(call.reviewTarget == null && call.review == null && call.feedbackVersion == 0)
            } else if (call.purpose in setOf(CallPurpose.PROFILE_RELATION, CallPurpose.PROFILE_SCAN)) {
                require(call.proposal == null && call.review == null && call.feedbackVersion == 0)
                val ref = requireNotNull(call.reviewTarget)
                require(state.calls.take(index).any { it.purpose == CallPurpose.SYNTHESIS && it.status == CallStatus.SUCCEEDED && it.batchKey == ref.batchKey &&
                    it.proposal?.claims?.getOrNull(ref.claimIndex)?.let(::claimSha256) == ref.claimSha256 })
            } else if (call.purpose == CallPurpose.REDUCTION) {
                require(call.proposal == null && call.reviewTarget == null && call.review == null && call.feedbackVersion == 0)
                ReductionGraph.validateInputs(state.calls.take(index), call)
            } else if (reductionReview) {
                require(call.proposal == null && call.reviewTarget == null)
                validateReductionReviewCall(state.copy(calls = state.calls.take(index)), call)
            } else {
                require(call.proposal == null)
                val target = requireNotNull(call.reviewTarget)
                validId(target.batchKey)
                require(target.claimIndex in 0..19 && digest.matches(target.claimSha256))
                val extraction = requireNotNull(state.calls.take(index).lastOrNull {
                    it.batchKey == target.batchKey && it.purpose.producesClaims && it.status == CallStatus.SUCCEEDED
                }) { "核对缺少已成功生成的目标" }
                val claim = requireNotNull(extraction.proposal?.claims?.getOrNull(target.claimIndex))
                require(claimSha256(claim) == target.claimSha256) { "核对目标文字或引用已变化" }
                if (extraction.purpose == CallPurpose.METRIC_ANALYSIS) {
                    require(metricCall && call.metricEvidence == extraction.metricEvidence) { "核对没有重新提供目标完整指标" }
                } else {
                    require(!metricCall) { "原文核对不能替换成指标证据" }
                    require(call.actionEvidence == extraction.actionEvidence) { "核对没有重新提供全部行动上下文" }
                    val mandatory = if (extraction.purpose in setOf(CallPurpose.SYNTHESIS, CallPurpose.ACTION_SYNTHESIS)) extraction.evidence
                        else claim.evidenceIds.map { id -> extraction.evidence.single { it.id == id } }
                    require(mandatory.all { it in call.evidence }) { "核对没有重新提供全部目标原文及综合上下文" }
                    call.profileMaintenanceProof?.let { proof ->
                        val scanned = proof.scans.flatMap { it.evidence }.toSet()
                        require(proof.originalEvidence.filterNot { it in scanned }.all { it in mandatory }) { "未扫描的补充原文必须来自原生成批次" }
                    }
                }
            }
            if (call.status == CallStatus.SUCCEEDED) {
                require(call.finishedAt != null && call.failureCode == null)
                if (call.purpose.producesClaims) {
                    val proposal = requireNotNull(call.proposal)
                    validateProposal(proposal, call.evidence, limits.maxOutputBytes, call.metricEvidence, call.actionEvidence)
                    if (call.purpose in setOf(CallPurpose.SYNTHESIS, CallPurpose.ACTION_SYNTHESIS, CallPurpose.METRIC_ANALYSIS)) require(proposal.claims.all { it.kind == ClaimKind.POSSIBLE_INTERPRETATION }) {
                        "综合判断必须保留为可能解释"
                    }
                } else if (call.purpose == CallPurpose.PROFILE_RELATION) ProfileMaintenanceProtocol.validateRelation(requireNotNull(call.profileRelation), limits.maxOutputBytes)
                else if (call.purpose == CallPurpose.PROFILE_SCAN) ReductionReviewProtocol.validateScan(requireNotNull(call.profileScan), call.evidence, limits.maxOutputBytes)
                else if (call.purpose == CallPurpose.REDUCTION) validateReduction(requireNotNull(call.reductionOutput), call.reductionInputs, limits.maxOutputBytes)
                else if (call.purpose == CallPurpose.REDUCTION_SCAN) ReductionReviewProtocol.validateScan(requireNotNull(call.reductionScan), call.evidence, limits.maxOutputBytes)
                else validateReview(requireNotNull(call.review), call.evidence, limits.maxOutputBytes, call.metricEvidence, call.actionEvidence)
            } else {
                require(call.proposal == null && call.review == null && call.reductionOutput == null && call.reductionScan == null && call.profileRelation == null && call.profileScan == null)
                if (call.status == CallStatus.IN_FLIGHT) require(call.finishedAt == null && call.failureCode == null)
                else require(call.finishedAt != null && call.failureCode in setOf("PROVIDER_FAILED", "INVALID_RESPONSE", "INTERRUPTED", "FEEDBACK_DELETED") + ProviderFailureCode.entries.map { "PROVIDER_${it.name}" })
                if (call.failureCode == "FEEDBACK_DELETED") require(call.status == CallStatus.FAILED &&
                    (call.purpose == CallPurpose.REVIEW && call.feedbackVersion > 0 || call.purpose == CallPurpose.REDUCTION || reductionReview))
            }
        }
    }

    private fun validateReductionReviewCall(before: AnalysisRunState, call: AnalysisCall) {
        val target = requireNotNull(call.reductionTarget)
        ReductionReviewProtocol.validateTarget(target)
        val root = ReductionGraph.resolve(before.calls, target.root, allowRedacted = call.failureCode == "FEEDBACK_DELETED")
        require(call.reductionReviewTokens.map { it.claim }.distinct().size == call.reductionReviewTokens.size)
        require(call.reductionReviewTokens.all { it.version >= 0 })
        require(call.reductionReviewTokens.map { it.claim } == ReductionGraph.feedbackClaims(before.calls, target.root, true, call.failureCode == "FEEDBACK_DELETED"))
        require(call.reductionReviewTokens.single { it.claim == ReductionGraph.feedbackRef(target) }.version == call.feedbackVersion)
        if (call.failureCode == "FEEDBACK_DELETED") {
            require(root.reductionOutput == null || ReductionGraph.statementRef(root, target.index) == target)
            return
        }
        ReductionReviewProtocol.requireWholeRoot(before, target)
        val expected = ReductionGraph.ancestorTokens(before.calls, target.root).associateBy { it.claim }
        require(call.reductionReviewTokens.all { expected[it.claim]?.let { token -> token == it } != false })
        val originals = ReductionGraph.originalEvidence(before, target.root)
        if (call.purpose == CallPurpose.REDUCTION_SCAN) {
            require(call.review == null && call.reductionScans.isEmpty() && call.evidence.all { it in originals })
            val occupied = ReductionReviewProtocol.compatibleScans(before, target, call.feedbackVersion, call.reductionReviewTokens).flatMap { it.evidence }.map { it.id }.toSet()
            require(call.evidence.none { it.id in occupied })
        } else {
            require(call.reductionScan == null)
            ReductionReviewProtocol.proof(before, call)
        }
    }

    internal fun evidenceId(recordId: String, revision: Int, field: String, start: Int, end: Int, hash: String): String =
        sha256(json(listOf(recordId, revision, field, start, end, hash)))
    internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun claimSha256(claim: ProposedClaim): String = sha256(json(claimMap(claim)))
    fun feedbackId(runId: String, claim: ClaimRef): String = sha256(json(listOf(runId, claim.batchKey, claim.claimIndex, claim.claimSha256)))
    internal fun slice(value: String, start: Int, end: Int): String = value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end))
    internal fun validId(id: String) { require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }) }

    internal fun json(value: Any?, nodeLimit: Int = 60_000): String = StringWriter().also { output ->
        val writer = JsonWriter(output)
        var nodes = 0
        fun write(item: Any?, depth: Int = 0) {
            require(depth <= 12) { "分析 JSON 层级过深" }
            if (++nodes > nodeLimit) throw AnalysisPayloadCapacityException("分析 JSON 节点超过保存上限")
            when (item) {
                null -> writer.nullValue()
                is String -> writer.value(item)
                is Number -> writer.value(item)
                is Boolean -> writer.value(item)
                is Map<*, *> -> { writer.beginObject(); item.forEach { (key, child) -> writer.name(key as String); write(child, depth + 1) }; writer.endObject() }
                is Iterable<*> -> { writer.beginArray(); item.forEach { write(it, depth + 1) }; writer.endArray() }
                else -> error("不支持的 JSON 类型")
            }
        }
        write(value); writer.flush()
    }.toString()

    internal fun scopeMap(scope: AnalysisQuery): Map<String, Any?> = mapOf("from" to scope.from.toString(), "through" to scope.through.toString(),
        "zoneId" to scope.zoneId, "includeArchived" to scope.includeArchived, "recordIds" to scope.recordIds?.sorted())
    private fun requestMap(request: AnalysisRequest) = mapOf("question" to request.question, "scope" to scopeMap(request.scope),
        "limits" to request.limits.let { mapOf("pageSize" to it.pageSize, "fragmentCodePoints" to it.fragmentCodePoints,
            "maxInputBytes" to it.maxInputBytes, "inputTokenLimit" to it.inputTokenLimit, "outputTokenReserve" to it.outputTokenReserve,
            "maxCalls" to it.maxCalls, "totalInputBytes" to it.totalInputBytes, "maxOutputBytes" to it.maxOutputBytes) },
        "focusTopics" to request.focusTopics.sorted(), "expiresAt" to request.expiresAt?.toString(),
        "profileTarget" to request.profileTarget?.let(::profileTargetMap), "actionPeriod" to request.actionPeriod)

    internal fun profileTargetMap(target: ProfileAnalysisTarget): Map<String, Any?> =
        LongitudinalProfileProtocol.targetMap(target)
    internal fun receiptMap(ref: EvidenceReceipt): Map<String, Any?> = mapOf("id" to ref.id, "recordId" to ref.recordId, "revision" to ref.revision,
        "field" to ref.field, "start" to ref.start, "end" to ref.end, "totalCodePoints" to ref.totalCodePoints, "sha256" to ref.sha256)
    private fun callMap(call: AnalysisCall) = mapOf("batchKey" to call.batchKey, "attempt" to call.attempt, "providerId" to call.providerId,
        "requestSha256" to call.requestSha256, "inputBytes" to call.inputBytes, "inputTokens" to call.inputTokens,
        "evidence" to call.evidence.map(::receiptMap), "startedAt" to call.startedAt.toString(), "status" to call.status.name,
        "finishedAt" to call.finishedAt?.toString(), "failureCode" to call.failureCode, "proposal" to call.proposal?.let(::proposalMap),
        "tokenCountKind" to call.tokenCountKind.name, "usage" to call.usage?.let { mapOf("promptTokens" to it.promptTokens, "completionTokens" to it.completionTokens) },
        "purpose" to call.purpose.name, "reviewTarget" to call.reviewTarget?.let(::claimRefMap), "review" to call.review?.let(::reviewMap),
        "feedbackVersion" to call.feedbackVersion, "reductionInputs" to call.reductionInputs.map(ReductionGraph::inputMap),
        "reductionOutput" to call.reductionOutput?.let(ReductionGraph::digestMap),
        "redactedReductionIdentity" to call.redactedReductionIdentity?.let { mapOf("outputSha256" to it.outputSha256, "statements" to it.statements.map(::claimRefMap)) },
        "reductionTarget" to call.reductionTarget?.let(ReductionReviewProtocol::targetMap),
        "reductionReviewTokens" to call.reductionReviewTokens.map(ReductionGraph::tokenMap),
        "reductionScan" to call.reductionScan?.let(ReductionReviewProtocol::scanMap),
        "reductionScans" to call.reductionScans.map(ReductionReviewProtocol::scanRefMap),
        "metricEvidence" to call.metricEvidence.map(RecordMetricProtocol::map),
        "profileMaintenancePlanSha256" to call.profileMaintenancePlanSha256,
        "profileReference" to call.profileReference?.let(ProfileMaintenanceProtocol::referenceMap),
        "profileRelation" to call.profileRelation?.let(ProfileMaintenanceProtocol::relationMap),
        "profileScan" to call.profileScan?.let(ReductionReviewProtocol::scanMap),
        "profileMaintenanceProof" to call.profileMaintenanceProof?.let(ProfileMaintenanceProtocol::proofMap),
        "actionEvidence" to call.actionEvidence.map(ActionEvidenceProtocol::receiptMap))
    internal fun claimMap(claim: ProposedClaim) = mapOf("text" to claim.text, "evidenceIds" to claim.evidenceIds, "kind" to claim.kind.name)
    internal fun claimRefMap(ref: ClaimRef) = mapOf("batchKey" to ref.batchKey, "claimIndex" to ref.claimIndex, "claimSha256" to ref.claimSha256)
    private fun proposalMap(proposal: BatchProposal) = mapOf("claims" to proposal.claims.map(::claimMap), "unknowns" to proposal.unknowns)
    internal fun reviewMap(review: ClaimReview) = mapOf("verdict" to review.verdict.name,
        "supportingEvidenceIds" to review.supportingEvidenceIds, "contradictingEvidenceIds" to review.contradictingEvidenceIds,
        "issues" to review.issues.map { it.name }, "unknowns" to review.unknowns)
    private fun parseReview(raw: Any?): ClaimReview {
        val review = raw.obj("verdict", "supportingEvidenceIds", "contradictingEvidenceIds", "issues", "unknowns")
        return ClaimReview(ReviewVerdict.valueOf(review.string("verdict")), review.strings("supportingEvidenceIds"),
            review.strings("contradictingEvidenceIds"), review.strings("issues").map(ReviewIssue::valueOf), review.strings("unknowns"))
    }
    private fun parseProposal(raw: Any?): BatchProposal {
        val proposal = raw.obj("claims", "unknowns")
        return BatchProposal(proposal.list("claims").map { item ->
            val claim = item.obj("text", "evidenceIds", "kind")
            ProposedClaim(claim.string("text"), claim.strings("evidenceIds"), ClaimKind.valueOf(claim.string("kind")))
        }, proposal.strings("unknowns"))
    }
    private fun parseReductionInput(raw: Any?, format: Int): ReductionInput {
        val input = raw.obj(*(arrayOf("id", "node", "requestSha256", "feedbackTokens") + if (format >= 6) arrayOf("includeDerivedFeedback") else emptyArray()))
        val node = input["node"].obj("batchKey", "attempt", "outputSha256")
        return ReductionInput(input.string("id"), ReductionNodeRef(node.string("batchKey"), node.int("attempt"), node.string("outputSha256")),
            input.string("requestSha256"), input.list("feedbackTokens").map { rawToken ->
                val token = rawToken.obj("claim", "version")
                val claim = token["claim"].obj("batchKey", "claimIndex", "claimSha256")
                ReductionFeedbackToken(ClaimRef(claim.string("batchKey"), claim.int("claimIndex"), claim.string("claimSha256")), token.int("version"))
            }, if (format >= 6) input["includeDerivedFeedback"] as? Boolean ?: error("反馈闭包标记无效") else false)
    }
    private fun parseRedactedIdentity(raw: Any?): RedactedReductionIdentity {
        val identity = raw.obj("outputSha256", "statements")
        return RedactedReductionIdentity(identity.string("outputSha256"), identity.list("statements").map { value ->
            value.obj("batchKey", "claimIndex", "claimSha256").let { ClaimRef(it.string("batchKey"), it.int("claimIndex"), it.string("claimSha256")) }
        })
    }
    private fun parseReductionDigest(raw: Any?): ReductionDigest {
        val output = raw.obj("statements", "unknowns")
        return ReductionDigest(output.list("statements").map { rawStatement ->
            val statement = rawStatement.obj("text", "inputIds")
            ReductionStatement(statement.string("text"), statement.strings("inputIds"))
        }, output.strings("unknowns"))
    }
    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(vararg fields: String): Map<String, Any?> = (this as? Map<String, Any?>)?.also {
        require(it.keys == fields.toSet()) { "分析 JSON 字段缺失或未识别" }
    } ?: error("分析 JSON 需要对象")
    private fun Map<String, Any?>.string(key: String) = this[key] as? String ?: error("分析 JSON 需要文字")
    private fun Map<String, Any?>.long(key: String) = this[key] as? Long ?: error("分析 JSON 需要整数")
    private fun Map<String, Any?>.int(key: String) = long(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun Map<String, Any?>.list(key: String) = this[key] as? List<*> ?: error("分析 JSON 需要数组")
    private fun Map<String, Any?>.strings(key: String) = list(key).map { it as? String ?: error("分析 JSON 需要文字数组") }
    private fun List<String>.uniqueSet() = toSet().also { require(it.size == size) }
    private fun Map<String, Any?>.optionalInstant(key: String) = this[key]?.let { Instant.parse(string(key)) }
}
