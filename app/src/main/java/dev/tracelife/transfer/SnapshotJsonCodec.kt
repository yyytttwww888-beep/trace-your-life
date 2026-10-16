package dev.tracelife.transfer

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import dev.tracelife.model.Attachment
import dev.tracelife.model.Cadence
import dev.tracelife.model.ChangeEvent
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordRevision
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.model.TemplateVersion
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate

/** Versioned logical transfer format. Streams belong to the caller; no credentials are serialized. */
object SnapshotJsonCodec {
    const val FORMAT_VERSION = 1
    private const val MAX_DEPTH = 16
    internal const val MAX_NODES = 2_000_000L
    private const val MAX_STRING_CHARACTERS = 1_048_576

    fun write(snapshot: RepositorySnapshot, output: OutputStream) {
        validateWriteBudget(snapshot)
        val writer = JsonWriter(OutputStreamWriter(output, Charsets.UTF_8))
        writer.objectValue {
            field("formatVersion", FORMAT_VERSION)
            field("snapshotId", snapshot.snapshotId)
            field("storeId", snapshot.storeId)
            field("generation", snapshot.generation)
            field("capturedAt", snapshot.capturedAt.toString())
            field("sourceSchemaVersion", snapshot.sourceSchemaVersion)
            array("records", snapshot.records) { record -> objectValue {
                field("id", record.id); field("revision", record.revision)
                field("createdAt", record.createdAt.toString()); field("updatedAt", record.updatedAt.toString()); field("archived", record.archived)
                name("draft").objectValue {
                    field("body", record.draft.body)
                    name("occurrence").objectValue {
                        field("startDate", record.draft.occurrence.startDate?.toString())
                        field("endDate", record.draft.occurrence.endDate?.toString())
                        field("zoneId", record.draft.occurrence.zoneId)
                    }
                    array("topics", record.draft.topics.sorted()) { value(it) }
                    name("template")
                    record.draft.template?.let { ref -> objectValue { field("id", ref.id); field("version", ref.version) } } ?: nullValue()
                    name("answers").objectValue { record.draft.answers.toSortedMap().forEach { (key, answer) -> field(key, answer) } }
                    array("attachmentIds", record.draft.attachmentIds.sorted()) { value(it) }
                }
            } }
            array("templates", snapshot.templates) { template -> objectValue {
                field("id", template.id); field("version", template.version); field("createdAt", template.createdAt.toString()); field("archived", template.archived)
                name("draft").objectValue {
                    field("name", template.draft.name); field("cadence", template.draft.cadence.name)
                    field("description", template.draft.description); field("sourceMarkdown", template.draft.sourceMarkdown)
                    array("questions", template.draft.questions) { question -> objectValue {
                        field("id", question.id); field("prompt", question.prompt); field("hint", question.hint); field("intent", question.intent)
                    } }
                }
            } }
            array("attachments", snapshot.attachments) { attachment -> objectValue {
                field("id", attachment.id); field("displayName", attachment.displayName); field("mimeType", attachment.mimeType)
                field("sizeBytes", attachment.sizeBytes); field("sha256", attachment.sha256)
            } }
            fun deletions(name: String, values: List<Deletion>) = array(name, values) { deletion -> objectValue {
                field("id", deletion.id); field("lastRevision", deletion.lastRevision); field("deletedAt", deletion.deletedAt.toString())
            } }
            deletions("deletedRecords", snapshot.deletedRecords)
            deletions("deletedTemplates", snapshot.deletedTemplates)
            array("changes", snapshot.changes) { change -> objectValue {
                field("id", change.id); field("entityId", change.entityId); field("revision", change.revision)
                field("kind", change.kind); field("createdAt", change.createdAt.toString())
            } }
        }
        writer.flush()
    }

    fun read(input: InputStream): RepositorySnapshot = try {
        val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))
        reader.isLenient = false
        val root = readValue(reader, 0, rootShape, NodeBudget()).objectFields()
        checkBackup(reader.peek() == JsonToken.END_DOCUMENT, "资料 JSON 包含额外内容")
        checkBackup(root.int("formatVersion") == FORMAT_VERSION, "不支持的资料格式版本")
        checkBackup(root.int("sourceSchemaVersion") == 2, "不支持的来源数据库版本")
        RepositorySnapshot(
            snapshotId = root.string("snapshotId"), storeId = root.string("storeId"), generation = root.long("generation"), capturedAt = root.instant("capturedAt"),
            sourceSchemaVersion = root.int("sourceSchemaVersion"),
            records = root.array("records").map { it as RecordRevision },
            templates = root.array("templates").map { it as TemplateVersion },
            attachments = root.array("attachments").map { it as Attachment },
            deletedRecords = root.array("deletedRecords").map { it as Deletion },
            deletedTemplates = root.array("deletedTemplates").map { it as Deletion },
            changes = root.array("changes").map { it as ChangeEvent },
        )
    } catch (failure: InvalidBackupException) {
        throw failure
    } catch (failure: Exception) {
        throw InvalidBackupException("资料 JSON 无效或不完整", failure)
    }

    private fun parseRecord(value: Any?): RecordRevision {
        val record = value.objectFields()
        val draft = record["draft"].objectFields()
        val occurrence = draft["occurrence"].objectFields()
        val ref = draft["template"]?.objectFields()?.let { TemplateRef(it.string("id"), it.int("version")) }
        val answers = draft["answers"].objectFields().mapValues { (_, answer) -> answer as? String ?: invalid("回答必须是文字") }
        return RecordRevision(record.string("id"), record.int("revision"), RecordDraft(
            body = draft.string("body"), occurrence = Occurrence(occurrence.date("startDate"), occurrence.date("endDate"), occurrence.string("zoneId")),
            topics = draft.stringSet("topics"), template = ref, answers = answers, attachmentIds = draft.stringSet("attachmentIds"),
        ), record.instant("createdAt"), record.instant("updatedAt"), record.bool("archived"))
    }

    private fun parseTemplate(value: Any?): TemplateVersion {
        val template = value.objectFields()
        val draft = template["draft"].objectFields()
        return TemplateVersion(template.string("id"), template.int("version"), TemplateDraft(
            name = draft.string("name"), cadence = Cadence.valueOf(draft.string("cadence")), description = draft.string("description"), sourceMarkdown = draft.string("sourceMarkdown"),
            questions = draft.array("questions").map { question -> question.objectFields().let { TemplateQuestion(it.string("id"), it.string("prompt"), it.string("hint"), it.string("intent")) } },
        ), template.instant("createdAt"), template.bool("archived"))
    }

    private sealed interface Shape
    private enum class Primitive : Shape { STRING, INTEGER, BOOLEAN }
    private data class ObjectShape(val fields: Map<String, Shape>) : Shape
    private data class ArrayShape(val item: Shape, val limit: Int) : Shape
    private data class NullableShape(val value: Shape) : Shape
    private data class MappedShape(val value: Shape, val convert: (Any?) -> Any) : Shape
    private object AnswersShape : Shape
    private class NodeBudget(var remaining: Long = MAX_NODES)
    private fun obj(vararg fields: Pair<String, Shape>) = ObjectShape(linkedMapOf(*fields))

    // Convert each record/template before reading the next item: never retain an entire JSON AST.
    // Unknown fields and invalid shapes are rejected before consuming their potentially huge values.
    private val rootShape: Shape by lazy {
        val string = Primitive.STRING
        val number = Primitive.INTEGER
        val bool = Primitive.BOOLEAN
        val occurrence = obj("startDate" to NullableShape(string), "endDate" to NullableShape(string), "zoneId" to string)
        val ref = NullableShape(obj("id" to string, "version" to number))
        val record = obj("id" to string, "revision" to number, "createdAt" to string, "updatedAt" to string, "archived" to bool,
            "draft" to obj("body" to string, "occurrence" to occurrence, "topics" to ArrayShape(string, 30), "template" to ref,
                "answers" to AnswersShape, "attachmentIds" to ArrayShape(string, 20)))
        val question = obj("id" to string, "prompt" to string, "hint" to string, "intent" to string)
        val template = obj("id" to string, "version" to number, "createdAt" to string, "archived" to bool,
            "draft" to obj("name" to string, "cadence" to string, "description" to string, "sourceMarkdown" to string, "questions" to ArrayShape(question, 100)))
        val attachment = obj("id" to string, "displayName" to string, "mimeType" to string, "sizeBytes" to number, "sha256" to string)
        val deletion = obj("id" to string, "lastRevision" to number, "deletedAt" to string)
        val change = obj("id" to number, "entityId" to string, "revision" to number, "kind" to string, "createdAt" to string)
        fun mappedDeletion() = MappedShape(deletion) { value -> value.objectFields().let { Deletion(it.string("id"), it.int("lastRevision"), it.instant("deletedAt")) } }
        obj("formatVersion" to number, "snapshotId" to string, "storeId" to string, "generation" to number, "capturedAt" to string, "sourceSchemaVersion" to number,
            "records" to ArrayShape(MappedShape(record, ::parseRecord), 100_000), "templates" to ArrayShape(MappedShape(template, ::parseTemplate), 10_000),
            "attachments" to ArrayShape(MappedShape(attachment) { value -> value.objectFields().let { Attachment(it.string("id"), it.string("displayName"), it.string("mimeType"), it.long("sizeBytes"), it.string("sha256")) } }, 10_000),
            "deletedRecords" to ArrayShape(mappedDeletion(), 100_000), "deletedTemplates" to ArrayShape(mappedDeletion(), 10_000),
            "changes" to ArrayShape(MappedShape(change) { value -> value.objectFields().let { ChangeEvent(it.long("id"), it.string("entityId"), it.int("revision"), it.string("kind"), it.instant("createdAt")) } }, 200_000))
    }

    private fun readValue(reader: JsonReader, depth: Int, shape: Shape, budget: NodeBudget): Any? {
        if (shape is MappedShape) return shape.convert(readValue(reader, depth, shape.value, budget))
        if (shape is NullableShape && reader.peek() != JsonToken.NULL) return readValue(reader, depth, shape.value, budget)
        checkBackup(depth <= MAX_DEPTH && --budget.remaining >= 0, "资料 JSON 超出结构预算")
        return when (shape) {
            is NullableShape -> { reader.nextNull(); null }
            is ObjectShape, AnswersShape -> linkedMapOf<String, Any?>().apply {
                checkBackup(reader.peek() == JsonToken.BEGIN_OBJECT, "资料字段须为对象")
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    checkBackup(!containsKey(name), "资料 JSON 包含重复字段")
                    val valueShape = if (shape is ObjectShape) shape.fields[name] ?: invalid("资料包含不受支持的字段")
                        else { checkBackup(size < 100 && name.length <= 200, "回答字段过多或身份过长"); Primitive.STRING }
                    put(name, readValue(reader, depth + 1, valueShape, budget))
                }
                reader.endObject()
                if (shape is ObjectShape) checkBackup(keys == shape.fields.keys, "资料字段缺失")
            }
            is ArrayShape -> mutableListOf<Any?>().apply {
                checkBackup(reader.peek() == JsonToken.BEGIN_ARRAY, "资料字段须为列表")
                reader.beginArray()
                while (reader.hasNext()) { checkBackup(size < shape.limit, "资料列表过大"); add(readValue(reader, depth + 1, shape.item, budget)) }
                reader.endArray()
            }
            Primitive.STRING -> { checkBackup(reader.peek() == JsonToken.STRING, "资料字段须为文字"); reader.nextString().also { checkBackup(it.length <= MAX_STRING_CHARACTERS, "资料文字过长") } }
            Primitive.INTEGER -> {
                checkBackup(reader.peek() == JsonToken.NUMBER, "资料字段须为整数")
                reader.nextString().let { value ->
                    checkBackup(value.matches(Regex("-?(0|[1-9][0-9]*)")), "资料数值须为整数")
                    value.toLongOrNull() ?: invalid("资料数值超出范围")
                }
            }
            Primitive.BOOLEAN -> { checkBackup(reader.peek() == JsonToken.BOOLEAN, "资料字段须为布尔值"); reader.nextBoolean() }
            is MappedShape -> invalid("资料结构无效")
        }
    }

    private fun validateWriteBudget(snapshot: RepositorySnapshot) {
        checkBackup(snapshot.records.size <= 100_000 && snapshot.templates.size <= 10_000 && snapshot.attachments.size <= 10_000 && snapshot.deletedRecords.size <= 100_000 && snapshot.deletedTemplates.size <= 10_000 && snapshot.changes.size <= 200_000, "资料列表过大")
        checkBackup(snapshot.records.all { it.draft.topics.size <= 30 && it.draft.answers.size <= 100 && it.draft.attachmentIds.size <= 20 } && snapshot.templates.all { it.draft.questions.size <= 100 }, "资料字段过多")
        val nodes = 13L + snapshot.records.sumOf { 16L + (if (it.draft.template == null) 0 else 2) + it.draft.topics.size + it.draft.answers.size + it.draft.attachmentIds.size } +
            snapshot.templates.sumOf { 11L + 5L * it.draft.questions.size } + 6L * snapshot.attachments.size + 4L * (snapshot.deletedRecords.size + snapshot.deletedTemplates.size) + 6L * snapshot.changes.size
        checkBackup(nodes <= MAX_NODES, "资料 JSON 超出结构预算")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.objectFields(vararg expected: String): Map<String, Any?> {
        val value = this as? Map<String, Any?> ?: invalid("资料字段须为对象")
        if (expected.isNotEmpty()) checkBackup(value.keys == expected.toSet(), "资料字段缺失或不受支持")
        return value
    }
    private fun Map<String, Any?>.string(name: String): String = this[name] as? String ?: invalid("文字字段无效：$name")
    private fun Map<String, Any?>.long(name: String): Long = this[name] as? Long ?: invalid("整数无效：$name")
    private fun Map<String, Any?>.int(name: String): Int = long(name).also { checkBackup(it in Int.MIN_VALUE..Int.MAX_VALUE, "整数超出范围") }.toInt()
    private fun Map<String, Any?>.bool(name: String): Boolean = this[name] as? Boolean ?: invalid("布尔字段无效：$name")
    private fun Map<String, Any?>.instant(name: String): Instant = Instant.parse(string(name))
    private fun Map<String, Any?>.date(name: String): LocalDate? = this[name]?.let { LocalDate.parse(it as? String ?: invalid("日期字段无效")) }
    private fun Map<String, Any?>.array(name: String): List<Any?> = this[name] as? List<Any?> ?: invalid("列表字段无效：$name")
    private fun Map<String, Any?>.stringSet(name: String): Set<String> = array(name).map { it as? String ?: invalid("列表项目须为文字") }.let { values ->
        checkBackup(values.toSet().size == values.size, "列表包含重复身份")
        values.toSet()
    }
    private fun JsonWriter.objectValue(block: JsonWriter.() -> Unit) { beginObject(); block(); endObject() }
    private fun <T> JsonWriter.array(name: String, values: Collection<T>, writeItem: JsonWriter.(T) -> Unit) { name(name); beginArray(); values.forEach { writeItem(it) }; endArray() }
    private fun JsonWriter.field(name: String, value: String?) { name(name); value(value) }
    private fun JsonWriter.field(name: String, value: Number) { name(name); value(value) }
    private fun JsonWriter.field(name: String, value: Boolean) { name(name); value(value) }
    private fun checkBackup(condition: Boolean, message: String) { if (!condition) invalid(message) }
    private fun invalid(message: String): Nothing = throw InvalidBackupException(message)
}
