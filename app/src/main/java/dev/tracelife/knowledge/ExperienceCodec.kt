package dev.tracelife.knowledge

import android.util.JsonReader
import android.util.JsonToken
import dev.tracelife.analysis.AnalysisRunCodec
import java.io.StringReader
import java.time.Instant

/** Strict, bounded logical encoding. Titles and notes are independently authored user content. */
object ExperienceCodec {
    fun encodeThread(value: StoredExperienceThread): String {
        ExperienceValidation.validate(value)
        return encode(mapOf("formatVersion" to 1, "id" to value.id, "version" to value.version,
            "title" to value.title, "topics" to value.topics.sorted(), "createdAt" to value.createdAt.toString(),
            "updatedAt" to value.updatedAt.toString()))
    }
    fun encodeChange(value: StoredExperienceChange): String {
        ExperienceValidation.validate(value)
        return encode(mapOf("formatVersion" to 1, "threadId" to value.threadId, "sequence" to value.sequence,
            "knowledgeId" to value.knowledgeId, "knowledgeRevision" to value.knowledgeRevision,
            "relation" to value.relation.name, "userNote" to value.userNote, "createdAt" to value.createdAt.toString()))
    }
    fun decodeThread(payload: String): StoredExperienceThread {
        val data = decode(payload, setOf("formatVersion", "id", "version", "title", "topics", "createdAt", "updatedAt"))
        val topics = data["topics"] as? List<*> ?: error("经验主题需要列表")
        require(topics.all { it is String } && topics.distinct().size == topics.size)
        return StoredExperienceThread(data.string("id"), data.int("version"), data.string("title"),
            topics.map { it as String }.toSet(), Instant.parse(data.string("createdAt")), Instant.parse(data.string("updatedAt")))
            .also(ExperienceValidation::validate)
    }
    fun decodeChange(payload: String): StoredExperienceChange {
        val data = decode(payload, setOf("formatVersion", "threadId", "sequence", "knowledgeId", "knowledgeRevision", "relation", "userNote", "createdAt"))
        return StoredExperienceChange(data.string("threadId"), data.int("sequence"), data.string("knowledgeId"),
            data.int("knowledgeRevision"), ExperienceRelation.valueOf(data.string("relation")),
            data["userNote"]?.let { data.string("userNote") }, Instant.parse(data.string("createdAt")))
            .also(ExperienceValidation::validate)
    }
    private fun encode(value: Map<String, Any?>) = AnalysisRunCodec.json(value).also {
        require(it.toByteArray().size <= ExperienceValidation.MAX_ITEM_BYTES)
    }
    private fun decode(payload: String, fields: Set<String>): Map<String, Any?> {
        require(payload.length <= ExperienceValidation.MAX_ITEM_BYTES && payload.toByteArray().size <= ExperienceValidation.MAX_ITEM_BYTES)
        JsonReader(StringReader(payload)).use { reader ->
            reader.isLenient = false
            var nodes = 0
            fun read(depth: Int): Any? {
                require(depth <= 3 && ++nodes <= 100)
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            require(key.length <= 40 && !containsKey(key)) { "经验 JSON 字段重复" }
                            put(key, read(depth + 1))
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> buildList { reader.beginArray(); while (reader.hasNext()) add(read(depth + 1)); reader.endArray() }
                    JsonToken.STRING -> reader.nextString().also { require(it.length <= 2_000) }
                    JsonToken.NUMBER -> reader.nextString().let { require(it.matches(Regex("0|[1-9][0-9]*"))); requireNotNull(it.toIntOrNull()) }
                    JsonToken.NULL -> { reader.nextNull(); null }
                    else -> throw IllegalArgumentException("经验 JSON 类型无效")
                }
            }
            @Suppress("UNCHECKED_CAST") val value = read(0) as? Map<String, Any?> ?: error("经验 JSON 需要对象")
            require(reader.peek() == JsonToken.END_DOCUMENT && value.keys == fields && value["formatVersion"] == 1) { "经验 JSON 字段或版本无效" }
            return value
        }
    }
    private fun Map<String, Any?>.string(key: String) = this[key] as? String ?: error("经验 JSON 需要文字")
    private fun Map<String, Any?>.int(key: String) = this[key] as? Int ?: error("经验 JSON 需要整数")
}
