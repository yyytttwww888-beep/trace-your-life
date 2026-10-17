package dev.tracelife.analysis

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import dev.tracelife.model.RecordRevision
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

    fun encode(state: AnalysisRunState): String = encodeVersion(state, 3)

    private fun encodeVersion(state: AnalysisRunState, version: Int): String {
        validate(state)
        val reserved = responseReservation(state)
        return json(mapOf("formatVersion" to version, "request" to requestMap(state.request), "calls" to state.calls.map {
            val fields = callMap(it)
            when (version) {
                1 -> fields - setOf("tokenCountKind", "usage", "purpose", "reviewTarget", "review", "feedbackVersion")
                2 -> fields - setOf("purpose", "reviewTarget", "review", "feedbackVersion")
                else -> fields
            }
        }),
            nodeLimit = if (reserved > 0) 59_000 else 60_000)
            .also { require(it.toByteArray(Charsets.UTF_8).size + reserved <= MAX_PAYLOAD_BYTES) { "分析任务及输出预留超过保存上限" } }
    }

    fun decode(payload: String): AnalysisRunState = decodeEnvelope(payload).first

    /** Erase model text derived from deleted user feedback without refunding consumed budgets.
     * An untouched historical payload is returned byte-for-byte, without a format upgrade. */
    fun redactDeletedFeedback(run: StoredAnalysisRun, deletedFeedbackIds: Set<String>, now: Instant): StoredAnalysisRun {
        if (deletedFeedbackIds.isEmpty()) return run
        val state = decode(run.payload)
        var changed = false
        val calls = state.calls.map { call ->
            val ref = call.reviewTarget
            if (call.purpose == CallPurpose.REVIEW && ref != null && call.feedbackVersion > 0 &&
                feedbackId(run.id, ref) in deletedFeedbackIds && call.status in setOf(CallStatus.SUCCEEDED, CallStatus.IN_FLIGHT)) {
                changed = true
                call.copy(status = CallStatus.FAILED, failureCode = "FEEDBACK_DELETED", finishedAt = maxOf(now, call.startedAt),
                    review = null, usage = null)
            } else call
        }
        if (!changed) return run
        require(run.version < Int.MAX_VALUE) { "分析任务版本达到上限" }
        return run.copy(version = run.version + 1, updatedAt = maxOf(now, run.updatedAt), payload = encode(state.copy(calls = calls)))
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
        val root = read(0).obj("formatVersion", "request", "calls")
        require(reader.peek() == JsonToken.END_DOCUMENT && root.int("formatVersion") in 1..3)
        val format = root.int("formatVersion")
        val legacy = format == 1
        val request = root["request"].obj("question", "scope", "limits", "focusTopics", "expiresAt")
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
                request.strings("focusTopics").uniqueSet(), request.optionalInstant("expiresAt")),
            root.list("calls").map { raw ->
                val fields = arrayOf("batchKey", "attempt", "providerId", "requestSha256", "inputBytes", "inputTokens", "evidence", "startedAt", "status", "finishedAt", "failureCode", "proposal")
                val metered = fields + arrayOf("tokenCountKind", "usage")
                val call = raw.obj(*when (format) {
                    1 -> fields
                    2 -> metered
                    else -> metered + arrayOf("purpose", "reviewTarget", "review", "feedbackVersion")
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
                    } }, call["review"]?.let(::parseReview), if (format < 3) 0 else call.int("feedbackVersion"))
            },
        )
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
        }
    }

    internal fun reservedResponseBytes(run: StoredAnalysisRun): Int = responseReservation(decode(run.payload))
    private fun responseReservation(state: AnalysisRunState): Int =
        if (state.calls.any { it.status == CallStatus.IN_FLIGHT }) state.request.limits.maxOutputBytes + 256 else 0

    internal fun validateProposal(proposal: BatchProposal, evidence: List<EvidenceReceipt>, maxBytes: Int) {
        require(proposal.claims.size <= 20 && proposal.unknowns.size <= 20) { "提案条目过多" }
        val ids = evidence.map { it.id }.toSet()
        proposal.claims.forEach { claim ->
            require(claim.text.isNotBlank() && claim.text.length <= 2_000) { "提案文字无效" }
            require(claim.evidenceIds.size in 1..32 && claim.evidenceIds.distinct().size == claim.evidenceIds.size && claim.evidenceIds.all { it in ids }) {
                "提案引用不属于本次实际提供的证据"
            }
        }
        require(proposal.unknowns.all { it.isNotBlank() && it.length <= 2_000 })
        require(json(proposalMap(proposal)).toByteArray(Charsets.UTF_8).size <= maxBytes) { "提案输出超过预算" }
    }

    internal fun validateReview(review: ClaimReview, evidence: List<EvidenceReceipt>, maxBytes: Int) {
        val ids = evidence.map { it.id }.toSet()
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
            require(attempts.dropLast(1).all { it.status == CallStatus.FAILED || it.status == CallStatus.INTERRUPTED })
        }
        state.calls.forEachIndexed { index, call ->
            validId(call.batchKey); validId(call.providerId)
            require(digest.matches(call.requestSha256) && call.inputBytes in 1..limits.maxInputBytes && call.inputTokens in 1..limits.inputTokenLimit)
            call.usage?.let { require(call.status == CallStatus.SUCCEEDED && it.promptTokens in 1..call.inputTokens && it.completionTokens in 0..limits.outputTokenReserve) }
            require(call.evidence.size in 1..32 && call.evidence.map { it.id }.distinct().size == call.evidence.size)
            call.evidence.forEach { ref ->
                validId(ref.recordId)
                require(digest.matches(ref.id) && digest.matches(ref.sha256) && ref.revision > 0)
                require(ref.field == "body" || ref.field.startsWith("answer:") && ref.field.length in 8..207)
                require(ref.start >= 0 && ref.end > ref.start && ref.end <= ref.totalCodePoints && ref.end - ref.start <= limits.fragmentCodePoints)
                require(ref.id == evidenceId(ref.recordId, ref.revision, ref.field, ref.start, ref.end, ref.sha256))
            }
            require(call.feedbackVersion >= 0)
            if (call.purpose == CallPurpose.EXTRACTION) {
                require(call.reviewTarget == null && call.review == null && call.feedbackVersion == 0)
            } else {
                require(call.proposal == null)
                val target = requireNotNull(call.reviewTarget)
                validId(target.batchKey)
                require(target.claimIndex in 0..19 && digest.matches(target.claimSha256))
                val extraction = requireNotNull(state.calls.take(index).lastOrNull {
                    it.batchKey == target.batchKey && it.purpose == CallPurpose.EXTRACTION && it.status == CallStatus.SUCCEEDED
                }) { "核对缺少成功提取目标" }
                val claim = requireNotNull(extraction.proposal?.claims?.getOrNull(target.claimIndex))
                require(claimSha256(claim) == target.claimSha256) { "核对目标文字或引用已变化" }
                require(claim.evidenceIds.all { id -> extraction.evidence.single { it.id == id } in call.evidence }) { "核对没有重新提供全部目标原文" }
            }
            if (call.status == CallStatus.SUCCEEDED) {
                require(call.finishedAt != null && call.failureCode == null)
                if (call.purpose == CallPurpose.EXTRACTION) validateProposal(requireNotNull(call.proposal), call.evidence, limits.maxOutputBytes)
                else validateReview(requireNotNull(call.review), call.evidence, limits.maxOutputBytes)
            } else {
                require(call.proposal == null && call.review == null)
                if (call.status == CallStatus.IN_FLIGHT) require(call.finishedAt == null && call.failureCode == null)
                else require(call.finishedAt != null && call.failureCode in setOf("PROVIDER_FAILED", "INVALID_RESPONSE", "INTERRUPTED", "FEEDBACK_DELETED") + ProviderFailureCode.entries.map { "PROVIDER_${it.name}" })
                if (call.failureCode == "FEEDBACK_DELETED") require(call.purpose == CallPurpose.REVIEW && call.feedbackVersion > 0 && call.status == CallStatus.FAILED)
            }
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
            require(depth <= 12 && ++nodes <= nodeLimit) { "分析 JSON 结构过大" }
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
        "focusTopics" to request.focusTopics.sorted(), "expiresAt" to request.expiresAt?.toString())
    internal fun receiptMap(ref: EvidenceReceipt): Map<String, Any?> = mapOf("id" to ref.id, "recordId" to ref.recordId, "revision" to ref.revision,
        "field" to ref.field, "start" to ref.start, "end" to ref.end, "totalCodePoints" to ref.totalCodePoints, "sha256" to ref.sha256)
    private fun callMap(call: AnalysisCall) = mapOf("batchKey" to call.batchKey, "attempt" to call.attempt, "providerId" to call.providerId,
        "requestSha256" to call.requestSha256, "inputBytes" to call.inputBytes, "inputTokens" to call.inputTokens,
        "evidence" to call.evidence.map(::receiptMap), "startedAt" to call.startedAt.toString(), "status" to call.status.name,
        "finishedAt" to call.finishedAt?.toString(), "failureCode" to call.failureCode, "proposal" to call.proposal?.let(::proposalMap),
        "tokenCountKind" to call.tokenCountKind.name, "usage" to call.usage?.let { mapOf("promptTokens" to it.promptTokens, "completionTokens" to it.completionTokens) },
        "purpose" to call.purpose.name, "reviewTarget" to call.reviewTarget?.let(::claimRefMap), "review" to call.review?.let(::reviewMap),
        "feedbackVersion" to call.feedbackVersion)
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
