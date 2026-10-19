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
import dev.tracelife.storage.SourceStamp
import dev.tracelife.storage.StoredAnalysisRun
import dev.tracelife.storage.StoredClaimFeedback
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.ClaimFeedbackValidation
import dev.tracelife.storage.KnowledgeHead
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.storage.StoredKnowledgeRevision
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.actions.ActionCodec
import dev.tracelife.actions.ActionHead
import dev.tracelife.actions.ActionValidation
import dev.tracelife.actions.StoredActionRevision
import dev.tracelife.profile.ProfileCodec
import dev.tracelife.profile.ProfileHead
import dev.tracelife.profile.ProfileValidation
import dev.tracelife.profile.StoredProfileRevision
import dev.tracelife.dashboard.DashboardPreferencesCodec
import dev.tracelife.dashboard.StoredDashboardPreferences
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate

/** Versioned logical transfer format. Streams belong to the caller; no credentials are serialized. */
object SnapshotJsonCodec {
    const val FORMAT_VERSION = 7
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
            field("analysisGeneration", snapshot.analysisGeneration)
            field("dashboardPreferences", snapshot.dashboardPreferences?.let(DashboardPreferencesCodec::encode))
            array("analysisRuns", snapshot.analysisRuns) { run -> objectValue {
                field("id", run.id); field("version", run.version)
                name("source").objectValue { field("storeId", run.source.storeId); field("generation", run.source.generation) }
                field("createdAt", run.createdAt.toString()); field("updatedAt", run.updatedAt.toString())
                field("expiresAt", run.expiresAt?.toString()); field("payload", run.payload)
            } }
            array("claimFeedback", snapshot.claimFeedback) { feedback -> ClaimFeedbackValidation.writeJson(this, feedback) }
            array("knowledgeRevisions", snapshot.knowledgeRevisions) { value(KnowledgeCodec.encode(it)) }
            array("knowledgeHeads", snapshot.knowledgeHeads) { head -> objectValue {
                field("id", head.id); field("revision", head.revision)
            } }
            array("actionRevisions", snapshot.actionRevisions) { value(ActionCodec.encode(it)) }
            array("actionHeads", snapshot.actionHeads) { head -> objectValue {
                field("id", head.id); field("revision", head.revision)
            } }
            array("profileRevisions", snapshot.profileRevisions) { value(ProfileCodec.encode(it)) }
            array("profileHeads", snapshot.profileHeads) { head -> objectValue {
                field("id", head.id); field("revision", head.revision)
            } }
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
            deletions("deletedAnalysisRuns", snapshot.deletedAnalysisRuns)
            deletions("deletedClaimFeedback", snapshot.deletedClaimFeedback)
            deletions("deletedKnowledge", snapshot.deletedKnowledge)
            deletions("deletedActions", snapshot.deletedActions)
            deletions("deletedProfiles", snapshot.deletedProfiles)
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
        val format = root.int("formatVersion")
        val legacy = format == 1
        checkBackup(format in 1..FORMAT_VERSION, "不支持的资料格式版本")
        checkBackup(root.int("sourceSchemaVersion") == format + 1, "不支持的来源数据库版本")
        val analysisFields = setOf("analysisRuns", "analysisGeneration", "deletedAnalysisRuns")
        checkBackup(if (legacy) analysisFields.none(root::containsKey) else analysisFields.all(root::containsKey), "分析任务字段与资料版本不一致")
        val feedbackFields = setOf("claimFeedback", "deletedClaimFeedback")
        checkBackup(if (format < 3) feedbackFields.none(root::containsKey) else feedbackFields.all(root::containsKey), "反馈字段与资料版本不一致")
        val knowledgeFields = setOf("knowledgeRevisions", "knowledgeHeads", "deletedKnowledge")
        checkBackup(if (format < 4) knowledgeFields.none(root::containsKey) else knowledgeFields.all(root::containsKey), "知识字段与资料版本不一致")
        val actionFields = setOf("actionRevisions", "actionHeads", "deletedActions")
        checkBackup(if (format < 5) actionFields.none(root::containsKey) else actionFields.all(root::containsKey), "行动字段与资料版本不一致")
        val profileFields = setOf("profileRevisions", "profileHeads", "deletedProfiles")
        checkBackup(if (format < 6) profileFields.none(root::containsKey) else profileFields.all(root::containsKey), "画像字段与资料版本不一致")
        checkBackup(root.containsKey("dashboardPreferences") == (format >= 7), "看板偏好字段与资料版本不一致")
        RepositorySnapshot(
            snapshotId = root.string("snapshotId"), storeId = root.string("storeId"), generation = root.long("generation"), capturedAt = root.instant("capturedAt"),
            sourceSchemaVersion = root.int("sourceSchemaVersion"),
            records = root.array("records").map { it as RecordRevision },
            templates = root.array("templates").map { it as TemplateVersion },
            attachments = root.array("attachments").map { it as Attachment },
            deletedRecords = root.array("deletedRecords").map { it as Deletion },
            deletedTemplates = root.array("deletedTemplates").map { it as Deletion },
            changes = root.array("changes").map { it as ChangeEvent },
            analysisRuns = if (legacy) emptyList() else root.array("analysisRuns").map { it as StoredAnalysisRun },
            analysisGeneration = if (legacy) 0 else root.long("analysisGeneration"),
            deletedAnalysisRuns = if (legacy) emptyList() else root.array("deletedAnalysisRuns").map { it as Deletion },
            claimFeedback = if (format < 3) emptyList() else root.array("claimFeedback").map { it as StoredClaimFeedback },
            deletedClaimFeedback = if (format < 3) emptyList() else root.array("deletedClaimFeedback").map { it as Deletion },
            knowledgeRevisions = if (format < 4) emptyList() else root.array("knowledgeRevisions").map { it as StoredKnowledgeRevision },
            knowledgeHeads = if (format < 4) emptyList() else root.array("knowledgeHeads").map { it as KnowledgeHead },
            deletedKnowledge = if (format < 4) emptyList() else root.array("deletedKnowledge").map { it as Deletion },
            actionRevisions = if (format < 5) emptyList() else root.array("actionRevisions").map { it as StoredActionRevision },
            actionHeads = if (format < 5) emptyList() else root.array("actionHeads").map { it as ActionHead },
            deletedActions = if (format < 5) emptyList() else root.array("deletedActions").map { it as Deletion },
            profileRevisions = if (format < 6) emptyList() else root.array("profileRevisions").map { it as StoredProfileRevision },
            profileHeads = if (format < 6) emptyList() else root.array("profileHeads").map { it as ProfileHead },
            deletedProfiles = if (format < 6) emptyList() else root.array("deletedProfiles").map { it as Deletion },
            dashboardPreferences = root["dashboardPreferences"] as StoredDashboardPreferences?,
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
    private data class ObjectShape(val fields: Map<String, Shape>, val optional: Set<String> = emptySet()) : Shape
    private data class ArrayShape(val item: Shape, val limit: Int) : Shape
    private data class NullableShape(val value: Shape) : Shape
    private data class MappedShape(val value: Shape, val convert: (Any?) -> Any) : Shape
    private object AnswersShape : Shape
    private object AnalysisPayloadShape : Shape
    private object KnowledgePayloadShape : Shape
    private object ActionPayloadShape : Shape
    private object ProfilePayloadShape : Shape
    private object PreferencesPayloadShape : Shape
    private object FeedbackTextShape : Shape
    private data class FeedbackShape(val value: Shape) : Shape
    private class NodeBudget(var remaining: Long = MAX_NODES, var analysisBytes: Long = 0, var feedbackBytes: Long = 0,
        var knowledgeBytes: Long = 0, var actionBytes: Long = 0, var profileBytes: Long = 0)
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
        val analysis = obj("id" to string, "version" to number, "source" to obj("storeId" to string, "generation" to number),
            "createdAt" to string, "updatedAt" to string, "expiresAt" to NullableShape(string), "payload" to AnalysisPayloadShape)
        val feedback = FeedbackShape(obj("id" to string, "version" to number, "runId" to string, "batchKey" to string,
            "claimIndex" to number, "claimSha256" to string, "source" to obj("storeId" to string, "generation" to number),
            "decision" to string, "userText" to NullableShape(FeedbackTextShape), "createdAt" to string, "updatedAt" to string))
        fun mappedDeletion() = MappedShape(deletion) { value -> value.objectFields().let { Deletion(it.string("id"), it.int("lastRevision"), it.instant("deletedAt")) } }
        obj("formatVersion" to number, "snapshotId" to string, "storeId" to string, "generation" to number, "capturedAt" to string, "sourceSchemaVersion" to number,
            "dashboardPreferences" to NullableShape(PreferencesPayloadShape),
            "analysisGeneration" to number, "analysisRuns" to ArrayShape(MappedShape(analysis) { value -> value.objectFields().let { fields ->
                val source = fields["source"].objectFields()
                StoredAnalysisRun(fields.string("id"), fields.int("version"), SourceStamp(source.string("storeId"), source.long("generation")),
                    fields.instant("createdAt"), fields.instant("updatedAt"), fields["expiresAt"]?.let { Instant.parse(it as String) }, fields.string("payload"))
            } }, 100),
            "claimFeedback" to ArrayShape(feedback, ClaimFeedbackValidation.MAX_ROWS),
            "knowledgeRevisions" to ArrayShape(KnowledgePayloadShape, KnowledgeValidation.MAX_REVISIONS),
            "knowledgeHeads" to ArrayShape(MappedShape(obj("id" to string, "revision" to number)) { value ->
                value.objectFields().let { KnowledgeHead(it.string("id"), it.int("revision")) }
            }, KnowledgeValidation.MAX_REVISIONS),
            "actionRevisions" to ArrayShape(ActionPayloadShape, ActionValidation.MAX_REVISIONS),
            "actionHeads" to ArrayShape(MappedShape(obj("id" to string, "revision" to number)) { value ->
                value.objectFields().let { ActionHead(it.string("id"), it.int("revision")) }
            }, ActionValidation.MAX_REVISIONS),
            "profileRevisions" to ArrayShape(ProfilePayloadShape, ProfileValidation.MAX_REVISIONS),
            "profileHeads" to ArrayShape(MappedShape(obj("id" to string, "revision" to number)) { value ->
                value.objectFields().let { ProfileHead(it.string("id"), it.int("revision")) }
            }, ProfileValidation.MAX_REVISIONS),
            "records" to ArrayShape(MappedShape(record, ::parseRecord), 100_000), "templates" to ArrayShape(MappedShape(template, ::parseTemplate), 10_000),
            "attachments" to ArrayShape(MappedShape(attachment) { value -> value.objectFields().let { Attachment(it.string("id"), it.string("displayName"), it.string("mimeType"), it.long("sizeBytes"), it.string("sha256")) } }, 10_000),
            "deletedRecords" to ArrayShape(mappedDeletion(), 100_000), "deletedTemplates" to ArrayShape(mappedDeletion(), 10_000),
            "deletedAnalysisRuns" to ArrayShape(mappedDeletion(), 100_000),
            "deletedClaimFeedback" to ArrayShape(mappedDeletion(), ClaimFeedbackValidation.MAX_DELETIONS),
            "deletedKnowledge" to ArrayShape(mappedDeletion(), KnowledgeValidation.MAX_DELETIONS),
            "deletedActions" to ArrayShape(mappedDeletion(), ActionValidation.MAX_DELETIONS),
            "deletedProfiles" to ArrayShape(mappedDeletion(), ProfileValidation.MAX_DELETIONS),
            "changes" to ArrayShape(MappedShape(change) { value -> value.objectFields().let { ChangeEvent(it.long("id"), it.string("entityId"), it.int("revision"), it.string("kind"), it.instant("createdAt")) } }, 200_000))
            .copy(optional = setOf("analysisGeneration", "analysisRuns", "deletedAnalysisRuns", "claimFeedback", "deletedClaimFeedback",
                "knowledgeRevisions", "knowledgeHeads", "deletedKnowledge", "actionRevisions", "actionHeads", "deletedActions",
                "profileRevisions", "profileHeads", "deletedProfiles", "dashboardPreferences"))
    }

    private fun readValue(reader: JsonReader, depth: Int, shape: Shape, budget: NodeBudget): Any? {
        if (shape is MappedShape) return shape.convert(readValue(reader, depth, shape.value, budget))
        if (shape is FeedbackShape) {
            val fields = readValue(reader, depth, shape.value, budget).objectFields()
            val source = fields["source"].objectFields()
            val value = StoredClaimFeedback(fields.string("id"), fields.int("version"), fields.string("runId"), fields.string("batchKey"),
                fields.int("claimIndex"), fields.string("claimSha256"), SourceStamp(source.string("storeId"), source.long("generation")),
                ClaimFeedbackDecision.valueOf(fields.string("decision")), fields["userText"] as String?, fields.instant("createdAt"), fields.instant("updatedAt"))
            ClaimFeedbackValidation.validate(value)
            budget.feedbackBytes += ClaimFeedbackValidation.encodedBytes(value)
            checkBackup(budget.feedbackBytes <= ClaimFeedbackValidation.MAX_BYTES, "反馈超过 4 MiB 保存上限")
            return value
        }
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
                if (shape is ObjectShape) checkBackup(keys.containsAll(shape.fields.keys - shape.optional), "资料字段缺失")
            }
            is ArrayShape -> mutableListOf<Any?>().apply {
                checkBackup(reader.peek() == JsonToken.BEGIN_ARRAY, "资料字段须为列表")
                reader.beginArray()
                while (reader.hasNext()) { checkBackup(size < shape.limit, "资料列表过大"); add(readValue(reader, depth + 1, shape.item, budget)) }
                reader.endArray()
            }
            Primitive.STRING -> { checkBackup(reader.peek() == JsonToken.STRING, "资料字段须为文字"); reader.nextString().also { checkBackup(it.length <= MAX_STRING_CHARACTERS, "资料文字过长") } }
            FeedbackTextShape -> { checkBackup(reader.peek() == JsonToken.STRING, "独立反馈须为文字"); reader.nextString().also { checkBackup(it.length <= 2_000, "独立文字超过 2000 字符") } }
            AnalysisPayloadShape -> {
                checkBackup(reader.peek() == JsonToken.STRING, "分析任务内容须为文字")
                reader.nextString().also {
                    checkBackup(it.length <= 2 * 1024 * 1024, "分析任务内容过长")
                    val bytes = it.toByteArray(Charsets.UTF_8).size
                    checkBackup(bytes <= 2 * 1024 * 1024, "分析任务内容超过 2 MiB")
                    budget.analysisBytes += bytes
                    checkBackup(budget.analysisBytes <= 16L * 1024 * 1024, "分析任务内容总量超过 16 MiB")
                }
            }
            KnowledgePayloadShape -> {
                checkBackup(reader.peek() == JsonToken.STRING, "知识修订须为版本化 JSON 文字")
                val payload = reader.nextString()
                checkBackup(payload.length <= KnowledgeValidation.MAX_REVISION_BYTES, "知识修订过长")
                val bytes = payload.toByteArray(Charsets.UTF_8).size
                checkBackup(bytes <= KnowledgeValidation.MAX_REVISION_BYTES, "知识修订超过 256 KiB")
                budget.knowledgeBytes += bytes
                checkBackup(budget.knowledgeBytes <= KnowledgeValidation.MAX_BYTES, "知识修订总量超过 16 MiB")
                KnowledgeCodec.decode(payload)
            }
            ActionPayloadShape -> {
                checkBackup(reader.peek() == JsonToken.STRING, "行动修订须为版本化 JSON 文字")
                val payload = reader.nextString()
                checkBackup(payload.length <= ActionValidation.MAX_REVISION_BYTES, "行动修订过长")
                val bytes = payload.toByteArray(Charsets.UTF_8).size
                checkBackup(bytes <= ActionValidation.MAX_REVISION_BYTES, "行动修订超过 32 KiB")
                budget.actionBytes += bytes
                checkBackup(budget.actionBytes <= ActionValidation.MAX_BYTES, "行动修订总量超过 16 MiB")
                ActionCodec.decode(payload)
            }
            ProfilePayloadShape -> {
                checkBackup(reader.peek() == JsonToken.STRING, "画像修订须为版本化 JSON 文字")
                val payload = reader.nextString()
                checkBackup(payload.length <= ProfileValidation.MAX_REVISION_BYTES, "画像修订过长")
                val bytes = payload.toByteArray(Charsets.UTF_8).size
                checkBackup(bytes <= ProfileValidation.MAX_REVISION_BYTES, "画像修订超过大小上限")
                budget.profileBytes += bytes
                checkBackup(budget.profileBytes <= ProfileValidation.MAX_BYTES, "画像修订总量超过保存上限")
                ProfileCodec.decode(payload)
            }
            PreferencesPayloadShape -> {
                checkBackup(reader.peek() == JsonToken.STRING, "看板偏好须为版本化 JSON 文字")
                val payload = reader.nextString()
                checkBackup(payload.length <= DashboardPreferencesCodec.MAX_BYTES &&
                    payload.toByteArray(Charsets.UTF_8).size <= DashboardPreferencesCodec.MAX_BYTES, "看板偏好超过大小上限")
                DashboardPreferencesCodec.decode(payload)
            }
            Primitive.INTEGER -> {
                checkBackup(reader.peek() == JsonToken.NUMBER, "资料字段须为整数")
                reader.nextString().let { value ->
                    checkBackup(value.matches(Regex("-?(0|[1-9][0-9]*)")), "资料数值须为整数")
                    value.toLongOrNull() ?: invalid("资料数值超出范围")
                }
            }
            Primitive.BOOLEAN -> { checkBackup(reader.peek() == JsonToken.BOOLEAN, "资料字段须为布尔值"); reader.nextBoolean() }
            is MappedShape -> invalid("资料结构无效")
            is FeedbackShape -> invalid("反馈结构无效")
        }
    }

    private fun validateWriteBudget(snapshot: RepositorySnapshot) {
        checkBackup(snapshot.sourceSchemaVersion == 8, "新建备份必须使用当前来源数据库版本")
        snapshot.dashboardPreferences?.let(DashboardPreferencesCodec::encode)
        ClaimFeedbackValidation.validateCollection(snapshot.claimFeedback)
        checkBackup(snapshot.deletedClaimFeedback.size <= ClaimFeedbackValidation.MAX_DELETIONS, "反馈删除标记过多")
        KnowledgeValidation.validateCollection(snapshot.knowledgeRevisions, snapshot.knowledgeHeads)
        checkBackup(snapshot.deletedKnowledge.size <= KnowledgeValidation.MAX_DELETIONS, "知识删除标记过多")
        ActionValidation.validateCollection(snapshot.actionRevisions, snapshot.actionHeads)
        checkBackup(snapshot.deletedActions.size <= ActionValidation.MAX_DELETIONS, "行动删除标记过多")
        ProfileValidation.validateCollection(snapshot.profileRevisions, snapshot.profileHeads)
        checkBackup(snapshot.deletedProfiles.size <= ProfileValidation.MAX_DELETIONS, "画像删除标记过多")
        checkBackup(snapshot.analysisRuns.size <= 100 && snapshot.analysisGeneration >= 0 && snapshot.deletedAnalysisRuns.size <= 100_000, "分析任务数量或代数无效")
        checkBackup(snapshot.analysisRuns.all { it.payload.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024 } &&
            snapshot.analysisRuns.sumOf { it.payload.toByteArray(Charsets.UTF_8).size.toLong() } <= 16L * 1024 * 1024, "分析任务内容超过大小上限")
        checkBackup(snapshot.records.size <= 100_000 && snapshot.templates.size <= 10_000 && snapshot.attachments.size <= 10_000 && snapshot.deletedRecords.size <= 100_000 && snapshot.deletedTemplates.size <= 10_000 && snapshot.changes.size <= 200_000, "资料列表过大")
        checkBackup(snapshot.records.all { it.draft.topics.size <= 30 && it.draft.answers.size <= 100 && it.draft.attachmentIds.size <= 20 } && snapshot.templates.all { it.draft.questions.size <= 100 }, "资料字段过多")
        val nodes = 28L + snapshot.analysisRuns.size * 10L + snapshot.claimFeedback.size * 15L + snapshot.knowledgeRevisions.size + snapshot.actionRevisions.size + snapshot.profileRevisions.size +
            3L * (snapshot.knowledgeHeads.size + snapshot.actionHeads.size + snapshot.profileHeads.size) + snapshot.records.sumOf { 16L + (if (it.draft.template == null) 0 else 2) + it.draft.topics.size + it.draft.answers.size + it.draft.attachmentIds.size } +
            snapshot.templates.sumOf { 11L + 5L * it.draft.questions.size } + 6L * snapshot.attachments.size + 4L * (snapshot.deletedRecords.size + snapshot.deletedTemplates.size + snapshot.deletedAnalysisRuns.size + snapshot.deletedClaimFeedback.size + snapshot.deletedKnowledge.size + snapshot.deletedActions.size + snapshot.deletedProfiles.size) + 6L * snapshot.changes.size
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
