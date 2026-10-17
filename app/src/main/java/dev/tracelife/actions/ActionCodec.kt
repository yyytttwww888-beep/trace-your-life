package dev.tracelife.actions

import android.util.JsonReader
import android.util.JsonToken
import dev.tracelife.analysis.AnalysisRunCodec
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate

object ActionCodec {
    const val FORMAT_VERSION = 1
    fun encode(value: StoredActionRevision): String {
        ActionValidation.validate(value)
        return AnalysisRunCodec.json(mapOf(
            "formatVersion" to FORMAT_VERSION, "id" to value.id, "revision" to value.revision,
            "draft" to draftMap(value.draft), "status" to value.status.name,
            "createdAt" to value.createdAt.toString(), "updatedAt" to value.updatedAt.toString(),
            "operationId" to value.operationId, "reviewedDraft" to value.reviewedDraft?.let(::draftMap),
            "review" to value.review?.let { mapOf("execution" to it.execution.name, "observedText" to it.observedText,
                "decision" to it.decision.name, "reviewedOn" to it.reviewedOn.toString()) },
        )).also { require(it.toByteArray(Charsets.UTF_8).size <= ActionValidation.MAX_REVISION_BYTES) { "行动修订超过 128 KiB" } }
    }

    fun decode(payload: String): StoredActionRevision {
        require(payload.length <= ActionValidation.MAX_REVISION_BYTES && payload.toByteArray(Charsets.UTF_8).size <= ActionValidation.MAX_REVISION_BYTES)
        val reader = JsonReader(StringReader(payload)).apply { isLenient = false }
        var nodes = 0
        fun read(depth: Int): Any? {
            require(depth <= 6 && ++nodes <= 100)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key.length <= 50 && !containsKey(key)) { "行动 JSON 字段重复" }
                        put(key, read(depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.STRING -> reader.nextString().also { require(it.length <= 4_000) }
                JsonToken.NUMBER -> reader.nextString().let {
                    require(it.matches(Regex("0|[1-9][0-9]*")))
                    requireNotNull(it.toIntOrNull())
                }
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> throw IllegalArgumentException("行动 JSON 类型无效")
            }
        }
        val root = read(0).obj("formatVersion", "id", "revision", "draft", "status", "createdAt", "updatedAt", "operationId", "review", "reviewedDraft")
        require(reader.peek() == JsonToken.END_DOCUMENT && root.int("formatVersion") == FORMAT_VERSION)
        return StoredActionRevision(root.string("id"), root.int("revision"), parseDraft(root["draft"]),
            ActionStatus.valueOf(root.string("status")), Instant.parse(root.string("createdAt")), Instant.parse(root.string("updatedAt")),
            root["operationId"]?.let { root.string("operationId") }, root["review"]?.let { raw ->
                val review = raw.obj("execution", "observedText", "decision", "reviewedOn")
                ActionReview(ActionExecution.valueOf(review.string("execution")), review.string("observedText"),
                    ActionDecision.valueOf(review.string("decision")), LocalDate.parse(review.string("reviewedOn")))
            }, root["reviewedDraft"]?.let(::parseDraft)).also(ActionValidation::validate)
    }

    private fun draftMap(draft: ActionDraft): Map<String, Any?> = mapOf(
        "title" to draft.title, "trigger" to draft.trigger, "behavior" to draft.behavior,
        "expectedSignal" to draft.expectedSignal, "reviewOn" to draft.reviewOn.toString(), "zoneId" to draft.zoneId,
        "origin" to draft.origin?.let { mapOf("knowledgeId" to it.knowledgeId, "revision" to it.revision) },
    )
    private fun parseDraft(raw: Any?): ActionDraft {
        val value = raw.obj("title", "trigger", "behavior", "expectedSignal", "reviewOn", "zoneId", "origin")
        return ActionDraft(value.string("title"), value.string("trigger"), value.string("behavior"), value.string("expectedSignal"),
            LocalDate.parse(value.string("reviewOn")), value.string("zoneId"), value["origin"]?.let {
                val origin = it.obj("knowledgeId", "revision")
                ActionOrigin(origin.string("knowledgeId"), origin.int("revision"))
            })
    }
    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(vararg fields: String): Map<String, Any?> = (this as? Map<String, Any?>)?.also {
        require(it.keys == fields.toSet()) { "行动 JSON 字段缺失或多余" }
    } ?: throw IllegalArgumentException("行动 JSON 需要对象")
    private fun Map<String, Any?>.string(key: String) = this[key] as? String ?: throw IllegalArgumentException("行动 JSON 需要文字")
    private fun Map<String, Any?>.int(key: String) = this[key] as? Int ?: throw IllegalArgumentException("行动 JSON 需要整数")
}
