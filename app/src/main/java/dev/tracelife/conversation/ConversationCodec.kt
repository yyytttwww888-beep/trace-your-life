package dev.tracelife.conversation

import android.util.JsonReader
import android.util.JsonToken
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.storage.AnalysisQuery
import java.io.StringReader
import java.time.Instant
import java.time.LocalDate

object ConversationCodec {
    fun encodeThread(value: ConversationThread): String {
        ConversationValidation.validate(value)
        return encode(mapOf("formatVersion" to 1, "id" to value.id, "createdAt" to value.createdAt.toString(), "updatedAt" to value.updatedAt.toString()))
    }
    fun encodeTurn(value: ConversationTurn): String {
        ConversationValidation.validate(value)
        return encode(mapOf("formatVersion" to 1, "id" to value.id, "threadId" to value.threadId, "position" to value.position,
            "question" to value.question, "from" to value.scope.from.toString(), "through" to value.scope.through.toString(),
            "zoneId" to value.scope.zoneId, "includeArchived" to value.scope.includeArchived, "recordIds" to value.scope.recordIds?.sorted(),
            "focusTopics" to value.focusTopics.sorted(), "parentTurnId" to value.parentTurnId, "parentFindingIndex" to value.parentFindingIndex,
            "runId" to value.runId, "createdAt" to value.createdAt.toString()))
    }
    fun decodeThread(payload: String): ConversationThread {
        val data = decode(payload, setOf("formatVersion", "id", "createdAt", "updatedAt"))
        return ConversationThread(data.string("id"), Instant.parse(data.string("createdAt")), Instant.parse(data.string("updatedAt")))
            .also(ConversationValidation::validate)
    }
    fun decodeTurn(payload: String): ConversationTurn {
        val data = decode(payload, setOf("formatVersion", "id", "threadId", "position", "question", "from", "through", "zoneId",
            "includeArchived", "recordIds", "focusTopics", "parentTurnId", "parentFindingIndex", "runId", "createdAt"))
        return ConversationTurn(data.string("id"), data.string("threadId"), data.int("position"), data.string("question"),
            AnalysisQuery(LocalDate.parse(data.string("from")), LocalDate.parse(data.string("through")), data.string("zoneId"),
                data["includeArchived"] as? Boolean ?: error("归档范围须为布尔值"), data["recordIds"]?.let { data.strings("recordIds") }),
            data.strings("focusTopics"), data["parentTurnId"]?.let { data.string("parentTurnId") },
            data["parentFindingIndex"]?.let { data.int("parentFindingIndex") }, data.string("runId"), Instant.parse(data.string("createdAt")))
            .also(ConversationValidation::validate)
    }
    private fun encode(value: Map<String, Any?>) = AnalysisRunCodec.json(value).also { require(it.toByteArray().size <= ConversationValidation.MAX_ITEM_BYTES) }
    private fun decode(payload: String, fields: Set<String>): Map<String, Any?> {
        require(payload.length <= ConversationValidation.MAX_ITEM_BYTES && payload.toByteArray().size <= ConversationValidation.MAX_ITEM_BYTES)
        JsonReader(StringReader(payload)).use { reader ->
            reader.isLenient = false
            var nodes = 0
            fun read(depth: Int): Any? {
                require(depth <= 3 && ++nodes <= 200)
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                        reader.beginObject()
                        while (reader.hasNext()) { val key = reader.nextName(); require(key.length <= 40 && !containsKey(key)); put(key, read(depth + 1)) }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> buildList { reader.beginArray(); while (reader.hasNext()) add(read(depth + 1)); reader.endArray() }
                    JsonToken.STRING -> reader.nextString().also { require(it.length <= 2_000) }
                    JsonToken.NUMBER -> reader.nextString().let { require(it.matches(Regex("0|[1-9][0-9]*"))); requireNotNull(it.toIntOrNull()) }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> { reader.nextNull(); null }
                    else -> throw IllegalArgumentException("对话 JSON 类型无效")
                }
            }
            @Suppress("UNCHECKED_CAST") val value = read(0) as? Map<String, Any?> ?: error("对话 JSON 需要对象")
            require(reader.peek() == JsonToken.END_DOCUMENT && value.keys == fields && value["formatVersion"] == 1) { "对话 JSON 字段或版本无效" }
            return value
        }
    }
    private fun Map<String, Any?>.string(key: String) = this[key] as? String ?: error("对话 JSON 需要文字")
    private fun Map<String, Any?>.int(key: String) = this[key] as? Int ?: error("对话 JSON 需要整数")
    private fun Map<String, Any?>.strings(key: String): Set<String> {
        val values = this[key] as? List<*> ?: error("对话 JSON 需要列表")
        require(values.all { it is String } && values.distinct().size == values.size)
        return values.map { it as String }.toSet()
    }
}
