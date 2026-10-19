package dev.tracelife.profile

import android.util.JsonReader
import android.util.JsonToken
import dev.tracelife.analysis.AnalysisRunCodec
import java.io.StringReader
import java.time.Instant

object ProfileCodec {
    const val FORMAT_VERSION = 1
    fun encode(value: StoredProfileRevision): String {
        ProfileValidation.validate(value)
        return AnalysisRunCodec.json(mapOf(
            "formatVersion" to FORMAT_VERSION, "id" to value.id, "revision" to value.revision,
            "draft" to mapOf("knowledge" to mapOf("knowledgeId" to value.draft.knowledge.knowledgeId,
                "revision" to value.draft.knowledge.revision), "dimension" to value.draft.dimension.name, "layer" to value.draft.layer.name),
            "adoption" to value.adoption.name, "createdAt" to value.createdAt.toString(), "updatedAt" to value.updatedAt.toString(),
            "operationId" to value.operationId, "change" to value.change.name, "userText" to value.userText,
        )).also { require(it.toByteArray(Charsets.UTF_8).size <= ProfileValidation.MAX_REVISION_BYTES) { "画像修订超过 32 KiB" } }
    }

    fun decode(payload: String): StoredProfileRevision {
        require(payload.length <= ProfileValidation.MAX_REVISION_BYTES && payload.toByteArray(Charsets.UTF_8).size <= ProfileValidation.MAX_REVISION_BYTES)
        val reader = JsonReader(StringReader(payload)).apply { isLenient = false }
        var nodes = 0
        fun read(depth: Int): Any? {
            require(depth <= 5 && ++nodes <= 50)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        require(key.length <= 50 && !containsKey(key)) { "画像 JSON 字段重复" }
                        put(key, read(depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.STRING -> reader.nextString().also { require(it.length <= 2_000) }
                JsonToken.NUMBER -> reader.nextString().let {
                    require(it.matches(Regex("0|[1-9][0-9]*")))
                    requireNotNull(it.toIntOrNull())
                }
                JsonToken.NULL -> { reader.nextNull(); null }
                else -> throw IllegalArgumentException("画像 JSON 类型无效")
            }
        }
        val root = read(0).obj("formatVersion", "id", "revision", "draft", "adoption", "createdAt", "updatedAt", "operationId", "change", "userText")
        require(reader.peek() == JsonToken.END_DOCUMENT && root.int("formatVersion") == FORMAT_VERSION)
        val draft = root["draft"].obj("knowledge", "dimension", "layer")
        val knowledge = draft["knowledge"].obj("knowledgeId", "revision")
        return StoredProfileRevision(root.string("id"), root.int("revision"),
            ProfileDraft(ProfileKnowledgeRef(knowledge.string("knowledgeId"), knowledge.int("revision")),
                ProfileDimension.valueOf(draft.string("dimension")), ProfileLayer.valueOf(draft.string("layer"))),
            ProfileAdoption.valueOf(root.string("adoption")), Instant.parse(root.string("createdAt")), Instant.parse(root.string("updatedAt")),
            root["operationId"]?.let { root.string("operationId") }, ProfileChange.valueOf(root.string("change")),
            root["userText"]?.let { root.string("userText") }).also(ProfileValidation::validate)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(vararg fields: String): Map<String, Any?> = (this as? Map<String, Any?>)?.also {
        require(it.keys == fields.toSet()) { "画像 JSON 字段缺失或多余" }
    } ?: throw IllegalArgumentException("画像 JSON 需要对象")
    private fun Map<String, Any?>.string(key: String) = this[key] as? String ?: throw IllegalArgumentException("画像 JSON 需要文字")
    private fun Map<String, Any?>.int(key: String) = this[key] as? Int ?: throw IllegalArgumentException("画像 JSON 需要整数")
}
