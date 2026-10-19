package dev.tracelife.knowledge

import android.util.JsonReader
import android.util.JsonToken
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.EvidenceReceipt
import dev.tracelife.analysis.ProposedClaim
import dev.tracelife.analysis.ReductionReviewProtocol
import dev.tracelife.analysis.ReviewIssue
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.metrics.RecordMetricProtocol
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.StoredKnowledgeRevision
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate

/** A self-contained immutable publication, independent of the mutable analysis task ledger. */
object KnowledgeCodec {
    const val FORMAT_VERSION = 4

    fun encode(value: StoredKnowledgeRevision): String {
        KnowledgeValidation.validate(value)
        return AnalysisRunCodec.json(mapOf(
            "formatVersion" to FORMAT_VERSION,
            "id" to value.id, "revision" to value.revision, "runId" to value.runId,
            "claimRef" to AnalysisRunCodec.claimRefMap(value.claimRef),
            "source" to mapOf("storeId" to value.source.storeId, "generation" to value.source.generation),
            "scope" to AnalysisRunCodec.scopeMap(value.scope), "expiresAt" to value.expiresAt?.toString(),
            "category" to value.category.name, "claim" to AnalysisRunCodec.claimMap(value.claim),
            "reviewBatchKey" to value.reviewBatchKey,
            "reviewAttempt" to value.reviewAttempt, "reviewProviderId" to value.reviewProviderId,
            "reviewRequestSha256" to value.reviewRequestSha256, "review" to AnalysisRunCodec.reviewMap(value.review),
            "evidence" to value.evidence.map(AnalysisRunCodec::receiptMap), "feedbackVersion" to value.feedbackVersion,
            "topics" to value.topics.sorted(), "createdAt" to value.createdAt.toString(), "publishedAt" to value.publishedAt.toString(),
            "reductionProof" to value.reductionProof?.let(ReductionReviewProtocol::proofMap),
            "metricEvidence" to value.metricEvidence.map(RecordMetricProtocol::map),
            "profileMaintenanceProof" to value.profileMaintenanceProof?.let(dev.tracelife.profile.ProfileMaintenanceProtocol::proofMap),
        ), nodeLimit = 20_000).also { require(it.toByteArray(Charsets.UTF_8).size <= KnowledgeValidation.MAX_REVISION_BYTES) { "知识修订超过 256 KiB" } }
    }

    fun decode(payload: String): StoredKnowledgeRevision {
        require(payload.length <= KnowledgeValidation.MAX_REVISION_BYTES &&
            payload.toByteArray(Charsets.UTF_8).size <= KnowledgeValidation.MAX_REVISION_BYTES) { "知识修订超过 256 KiB" }
        val reader = JsonReader(StringReader(payload)).apply { isLenient = false }
        var nodes = 0
        fun read(depth: Int): Any? {
            require(depth <= 12 && ++nodes <= 20_000) { "知识 JSON 超出结构预算" }
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(name.length <= 100 && !containsKey(name)) { "知识 JSON 字段重复或过长" }
                        put(name, read(depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> buildList {
                    reader.beginArray()
                    while (reader.hasNext()) add(read(depth + 1))
                    reader.endArray()
                }
                JsonToken.STRING -> reader.nextString().also { require(it.length <= 8_192) { "知识文字过长" } }
                JsonToken.NUMBER -> reader.nextString().let {
                    require(it.matches(Regex("-?(0|[1-9][0-9]*)"))) { "知识数值须为整数" }
                    requireNotNull(it.toLongOrNull()) { "知识数值超出范围" }
                }
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> throw IllegalArgumentException("知识 JSON 无效")
            }
        }
        val raw = read(0)
        val format = (raw as? Map<*, *>)?.get("formatVersion") as? Long
        require(reader.peek() == JsonToken.END_DOCUMENT && format != null && format in 1L..FORMAT_VERSION.toLong()) { "知识 JSON 版本无效或含额外内容" }
        val fields = arrayOf("formatVersion", "id", "revision", "runId", "claimRef", "source", "scope", "expiresAt", "category", "claim",
            "reviewBatchKey", "reviewAttempt", "reviewProviderId", "reviewRequestSha256", "review", "evidence",
            "feedbackVersion", "topics", "createdAt", "publishedAt")
        val root = raw.obj(*(when (format) {
            1L -> fields
            2L -> fields + "reductionProof"
            3L -> fields + arrayOf("reductionProof", "metricEvidence")
            else -> fields + arrayOf("reductionProof", "metricEvidence", "profileMaintenanceProof")
        }))
        val claimRef = root["claimRef"].obj("batchKey", "claimIndex", "claimSha256")
        val source = root["source"].obj("storeId", "generation")
        val scope = root["scope"].obj("from", "through", "zoneId", "includeArchived", "recordIds")
        val claim = root["claim"].obj("text", "evidenceIds", "kind")
        val review = root["review"].obj("verdict", "supportingEvidenceIds", "contradictingEvidenceIds", "issues", "unknowns")
        return StoredKnowledgeRevision(
            root.string("id"), root.int("revision"), root.string("runId"),
            ClaimRef(claimRef.string("batchKey"), claimRef.int("claimIndex"), claimRef.string("claimSha256")),
            SourceStamp(source.string("storeId"), source.long("generation")),
            AnalysisQuery(LocalDate.parse(scope.string("from")), LocalDate.parse(scope.string("through")), scope.string("zoneId"),
                scope["includeArchived"] as? Boolean ?: throw IllegalArgumentException("知识归档范围须为布尔值"),
                scope["recordIds"]?.let { scope.strings("recordIds").uniqueSet() }),
            root.optionalInstant("expiresAt"), KnowledgeCategory.valueOf(root.string("category")),
            ProposedClaim(claim.string("text"), claim.strings("evidenceIds"), ClaimKind.valueOf(claim.string("kind"))),
            root.string("reviewBatchKey"), root.int("reviewAttempt"), root.string("reviewProviderId"),
            root.string("reviewRequestSha256"), ClaimReview(ReviewVerdict.valueOf(review.string("verdict")), review.strings("supportingEvidenceIds"),
                review.strings("contradictingEvidenceIds"), review.strings("issues").map(ReviewIssue::valueOf), review.strings("unknowns")),
            root.list("evidence").map { raw ->
                val ref = raw.obj("id", "recordId", "revision", "field", "start", "end", "totalCodePoints", "sha256")
                EvidenceReceipt(ref.string("id"), ref.string("recordId"), ref.int("revision"), ref.string("field"),
                    ref.int("start"), ref.int("end"), ref.int("totalCodePoints"), ref.string("sha256"))
            }, root.int("feedbackVersion"), root.strings("topics").uniqueSet(), root.instant("createdAt"), root.instant("publishedAt"),
            root["reductionProof"]?.let(ReductionReviewProtocol::parseProof),
            if (format >= 3L) root.list("metricEvidence").map(RecordMetricProtocol::parse) else emptyList(),
            root["profileMaintenanceProof"]?.let(dev.tracelife.profile.ProfileMaintenanceProtocol::parseProof),
        ).also(KnowledgeValidation::validate)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(vararg fields: String): Map<String, Any?> = (this as? Map<String, Any?>)?.also {
        require(it.keys == fields.toSet()) { "知识 JSON 字段缺失或不受支持" }
    } ?: throw IllegalArgumentException("知识 JSON 需要对象")
    private fun Map<String, Any?>.string(key: String): String = this[key] as? String ?: throw IllegalArgumentException("知识 JSON 需要文字")
    private fun Map<String, Any?>.long(key: String): Long = this[key] as? Long ?: throw IllegalArgumentException("知识 JSON 需要整数")
    private fun Map<String, Any?>.int(key: String): Int = long(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun Map<String, Any?>.list(key: String): List<*> = this[key] as? List<*> ?: throw IllegalArgumentException("知识 JSON 需要数组")
    private fun Map<String, Any?>.strings(key: String): List<String> = list(key).map { it as? String ?: throw IllegalArgumentException("知识 JSON 需要文字数组") }
    private fun List<String>.uniqueSet(): Set<String> = toSet().also { require(it.size == size) { "知识 JSON 包含重复身份" } }
    private fun Map<String, Any?>.instant(key: String): Instant = Instant.parse(string(key))
    private fun Map<String, Any?>.optionalInstant(key: String): Instant? = this[key]?.let { instant(key) }
}
