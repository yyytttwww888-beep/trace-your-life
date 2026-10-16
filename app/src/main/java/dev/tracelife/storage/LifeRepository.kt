package dev.tracelife.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.tracelife.model.Attachment
import dev.tracelife.model.Cadence
import dev.tracelife.model.ChangeEvent
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordFilter
import dev.tracelife.model.RecordPage
import dev.tracelife.model.RecordRevision
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.model.TemplateRef
import dev.tracelife.model.TemplateVersion
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

/**
 * Local source of truth. All data changes and their durable change events commit together.
 *
 * This repository is for trusted app operations; it is not an AI retrieval interface.
 * Call it off the main thread when records or attachments can be large.
 */
class LifeRepository(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
    databaseName: String = "trace-life.db",
) : Closeable {
    private val appContext = context.applicationContext
    private val databasePath = appContext.getDatabasePath(databaseName).absolutePath
    private val lock = locks.getOrPut(databasePath) { Any() }
    private val helper = LifeDatabase(appContext, databaseName)
    private val attachmentDirectory = File(appContext.filesDir, "attachments/${sha256(databasePath.toByteArray()).take(24)}")
    private var closed = false

    init {
        require(databaseName.isNotBlank() && !databaseName.contains('/') && !databaseName.contains('\\')) {
            "数据库名称不能包含目录"
        }
        synchronized(lock) {
            check(attachmentDirectory.isDirectory || attachmentDirectory.mkdirs()) { "无法创建附件目录" }
            recoverAttachmentFiles(db())
        }
    }

    fun createRecord(draft: RecordDraft, id: String = UUID.randomUUID().toString()): RecordRevision = access { db ->
        validateId(id)
        transaction(db) {
            require(!exists(db, "deleted_records", "id = ?", id)) { "已删除的记录 ID 不可复用" }
            val existing = readRecord(db, id)
            if (existing != null) {
                if (existing.draft != draft) throw RevisionConflictException("该记录 ID 已用于其他内容")
                return@transaction existing
            }
            validateRecord(db, draft)
            val now = clock.instant()
            db.insertOrThrow("records", null, values("id" to id, "current_revision" to 1, "created_at" to timestamp(now)))
            val revision = RecordRevision(id, 1, draft, now, now)
            insertRevision(db, revision)
            event(db, id, 1, "RECORD_CREATED", now)
            revision
        }
    }

    fun updateRecord(id: String, expectedRevision: Int, draft: RecordDraft): RecordRevision = access { db ->
        transaction(db) {
            val current = requireCurrent(db, id, expectedRevision)
            validateRecord(db, draft)
            val next = current.copy(revision = current.revision + 1, draft = draft, updatedAt = clock.instant())
            insertRevision(db, next)
            db.update("records", values("current_revision" to next.revision), "id = ?", arrayOf(id))
            event(db, id, next.revision, "RECORD_UPDATED", next.updatedAt)
            next
        }
    }

    fun record(id: String, revision: Int? = null): RecordRevision? = access { db ->
        transaction(db) { readRecord(db, id, revision) }
    }

    /** unknownTimeCount also reports excluded unknown dates, so exclusion does not hide the coverage gap. */
    fun listRecords(filter: RecordFilter = RecordFilter()): RecordPage = access { db ->
        require(filter.limit in 1..200 && filter.offset >= 0) { "分页范围无效" }
        require(listOfNotNull(filter.from, filter.through).all { it.year in 1..9999 }) {
            "筛选日期须在公元 1 至 9999 年之间"
        }
        require(filter.from == null || filter.through == null || filter.from <= filter.through) { "筛选结束日期早于开始日期" }
        transaction(db) {
            val common = mutableListOf("r.revision = h.current_revision")
            val commonArgs = mutableListOf<String>()
            if (!filter.includeArchived) common += "r.archived = 0"
            if (filter.topic != null) {
                common += "EXISTS (SELECT 1 FROM record_topics t WHERE t.record_id = r.record_id AND t.revision = r.revision AND t.topic = ?)"
                commonArgs += filter.topic
            }
            val join = " FROM records h JOIN record_revisions r ON r.record_id = h.id WHERE "
            val unknownCount = count(db, "SELECT COUNT(*)$join${(common + "r.start_date IS NULL").joinToString(" AND ")}", commonArgs)
            val where = common.toMutableList()
            val args = commonArgs.toMutableList()
            val dateConditions = mutableListOf("r.start_date IS NOT NULL")
            filter.from?.let { dateConditions += "r.end_date >= ?"; args += it.toString() }
            filter.through?.let { dateConditions += "r.start_date <= ?"; args += it.toString() }
            val knownDates = "(${dateConditions.joinToString(" AND ")})"
            where += if (filter.includeUnknown) "($knownDates OR r.start_date IS NULL)" else knownDates
            val selection = where.joinToString(" AND ")
            val total = count(db, "SELECT COUNT(*)$join$selection", args)
            val ids = db.rawQuery(
                "SELECT h.id$join$selection ORDER BY r.updated_at DESC, h.id ASC LIMIT ? OFFSET ?",
                (args + filter.limit.toString() + filter.offset.toString()).toTypedArray(),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            RecordPage(ids.map { requireNotNull(readRecord(db, it)) }, total, unknownCount,
                (filter.offset + ids.size).takeIf { it < total })
        }
    }

    fun history(id: String): List<RecordRevision> = access { db -> transaction(db) {
        val revisions = db.rawQuery("SELECT revision FROM record_revisions WHERE record_id = ? ORDER BY revision DESC", arrayOf(id))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) } }
        revisions.map { requireNotNull(readRecord(db, id, it)) }
    } }

    fun setArchived(id: String, expectedRevision: Int, archived: Boolean): RecordRevision = access { db -> transaction(db) {
        val current = requireCurrent(db, id, expectedRevision)
        if (current.archived == archived) return@transaction current
        val next = current.copy(revision = current.revision + 1, archived = archived, updatedAt = clock.instant())
        insertRevision(db, next)
        db.update("records", values("current_revision" to next.revision), "id = ?", arrayOf(id))
        event(db, id, next.revision, if (archived) "RECORD_ARCHIVED" else "RECORD_UNARCHIVED", next.updatedAt)
        next
    } }

    fun deleteRecord(id: String, expectedRevision: Int) = access { db ->
        transaction(db) {
            val current = requireCurrent(db, id, expectedRevision)
            val attachmentIds = strings(db, "SELECT DISTINCT attachment_id FROM record_attachments WHERE record_id = ?", id)
            db.delete("records", "id = ?", arrayOf(id))
            db.delete("change_events", "entity_id = ? AND kind LIKE 'RECORD_%'", arrayOf(id))
            val now = clock.instant()
            db.insertOrThrow("deleted_records", null, values("id" to id, "last_revision" to current.revision, "deleted_at" to timestamp(now)))
            event(db, id, current.revision + 1, "RECORD_DELETED", now)
            attachmentIds.forEach { queueUnreferencedAttachment(db, it) }
        }
        collectGarbage(db)
    }

    fun saveTemplate(draft: TemplateDraft, id: String? = null, expectedVersion: Int? = null): TemplateVersion = access { db ->
        validateTemplate(draft)
        val templateId = id ?: UUID.randomUUID().toString()
        validateId(templateId)
        transaction(db) {
            val current = readTemplate(db, templateId)
            if (current == null) {
                require(expectedVersion == null) { "模板不存在，不能指定旧版本" }
                db.insertOrThrow("templates", null, values("id" to templateId, "current_version" to 1, "archived" to false))
            } else if (expectedVersion != current.version) {
                throw RevisionConflictException("模板已有新版本，请重新加载")
            }
            if (current?.draft == draft) return@transaction current
            val version = (current?.version ?: 0) + 1
            val now = clock.instant()
            db.insertOrThrow("template_versions", null, values(
                "template_id" to templateId, "version" to version, "name" to draft.name,
                "cadence" to draft.cadence.name, "description" to draft.description,
                "source_markdown" to draft.sourceMarkdown, "created_at" to timestamp(now),
            ))
            draft.questions.forEachIndexed { index, question ->
                db.insertOrThrow("template_questions", null, values(
                    "template_id" to templateId, "version" to version, "question_id" to question.id,
                    "ordinal" to index, "prompt" to question.prompt, "hint" to question.hint, "intent" to question.intent,
                ))
            }
            db.update("templates", values("current_version" to version), "id = ?", arrayOf(templateId))
            event(db, templateId, version, if (current == null) "TEMPLATE_CREATED" else "TEMPLATE_UPDATED", now)
            TemplateVersion(templateId, version, draft, now, current?.archived ?: false)
        }
    }

    fun template(id: String, version: Int? = null): TemplateVersion? = access { db -> transaction(db) { readTemplate(db, id, version) } }

    fun listTemplates(includeArchived: Boolean = false): List<TemplateVersion> = access { db -> transaction(db) {
        val ids = strings(db, "SELECT id FROM templates ${if (includeArchived) "" else "WHERE archived = 0"} ORDER BY id")
        ids.map { requireNotNull(readTemplate(db, it)) }
    } }

    fun archiveTemplate(id: String, archived: Boolean = true) = access { db -> transaction(db) {
        val current = requireNotNull(readTemplate(db, id)) { "模板不存在" }
        if (current.archived != archived) {
            db.update("templates", values("archived" to archived), "id = ?", arrayOf(id))
            event(db, id, current.version, if (archived) "TEMPLATE_ARCHIVED" else "TEMPLATE_UNARCHIVED", clock.instant())
        }
    } }

    fun deleteTemplate(id: String) = access { db -> transaction(db) {
        val current = requireNotNull(readTemplate(db, id)) { "模板不存在" }
        require(!exists(db, "record_revisions", "template_id = ?", id)) { "模板仍被记录或历史修订引用，请归档" }
        db.delete("templates", "id = ?", arrayOf(id))
        db.delete("change_events", "entity_id = ? AND kind LIKE 'TEMPLATE_%'", arrayOf(id))
        event(db, id, current.version, "TEMPLATE_DELETED", clock.instant())
    } }

    /** Caller owns [input]; successful import is durable before any record can refer to it. */
    fun importAttachment(input: InputStream, displayName: String, mimeType: String): Attachment = access { db ->
        require(displayName.isNotBlank() && displayName.length <= 255) { "附件名称无效" }
        require(mimeType.isNotBlank() && mimeType.length <= 200) { "附件类型无效" }
        val id = UUID.randomUUID().toString()
        val temporary = File(attachmentDirectory, ".pending.$id")
        val target = attachmentFile(id)
        var committed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    size += read
                    require(size <= MAX_ATTACHMENT_BYTES) { "附件不能超过 20 MiB" }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                output.fd.sync()
            }
            require(size > 0) { "附件不能为空" }
            check(temporary.renameTo(target)) { "无法提交附件文件" }
            val attachment = Attachment(id, displayName, mimeType, size, digest.digest().hex())
            transaction(db) {
                db.insertOrThrow("attachments", null, values("id" to id, "display_name" to displayName,
                    "mime_type" to mimeType, "size_bytes" to size, "sha256" to attachment.sha256))
            }
            committed = true
            attachment
        } finally {
            temporary.delete()
            if (!committed) target.delete()
        }
    }

    fun attachment(id: String): Attachment? = access { db -> readAttachment(db, id) }

    fun openAttachment(id: String): InputStream = access { db ->
        if (readAttachment(db, id) == null) throw FileNotFoundException("附件不存在")
        attachmentFile(id).inputStream()
    }

    fun discardAttachment(id: String) = access { db ->
        transaction(db) {
            require(!exists(db, "record_attachments", "attachment_id = ?", id)) { "附件仍被记录或历史修订引用" }
            queueUnreferencedAttachment(db, id)
        }
        collectGarbage(db)
    }

    /**
     * Called by the single editor owner after restoring its draft. Imported files whose save
     * never completed can then be reclaimed without deleting restored or historical references.
     * The caller must serialize this with import and editor save on its IO queue.
     */
    fun recoverUnreferencedAttachments(retainedIds: Set<String>) = access { db ->
        transaction(db) {
            retainedIds.forEach { id ->
                requireNotNull(readAttachment(db, id)) { "要保留的草稿附件不存在" }
            }
            strings(db, """SELECT a.id FROM attachments a WHERE NOT EXISTS
                (SELECT 1 FROM record_attachments r WHERE r.attachment_id = a.id)""")
                .filterNot { it in retainedIds }
                .forEach { queueUnreferencedAttachment(db, it) }
        }
        collectGarbage(db)
    }

    fun pendingChanges(limit: Int = 100): List<ChangeEvent> = access { db ->
        require(limit in 1..1000)
        db.rawQuery("SELECT id, entity_id, revision, kind, created_at FROM change_events ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ChangeEvent(cursor.getLong(0), cursor.getString(1), cursor.getInt(2), cursor.getString(3), Instant.parse(cursor.getString(4)))) }
        }
    }

    fun deletedRecordIds(): Set<String> = access { db -> strings(db, "SELECT id FROM deleted_records").toSet() }

    override fun close() = synchronized(lock) { if (!closed) { helper.close(); closed = true } }

    private fun db(): SQLiteDatabase {
        check(!closed) { "数据库已关闭" }
        return helper.writableDatabase
    }

    private fun <T> access(block: (SQLiteDatabase) -> T): T = synchronized(lock) { block(db()) }

    private fun <T> transaction(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        return try { block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    private fun requireCurrent(db: SQLiteDatabase, id: String, expected: Int): RecordRevision {
        val current = readRecord(db, id) ?: throw RevisionConflictException("记录已删除或不存在")
        if (current.revision != expected) throw RevisionConflictException("记录已有新修订，请重新加载")
        return current
    }

    private fun validateRecord(db: SQLiteDatabase, draft: RecordDraft) {
        require(draft.body.length <= 200_000) { "正文过长" }
        require(draft.occurrence.startDate?.year?.let { it in 1..9999 } != false &&
            draft.occurrence.endDate?.year?.let { it in 1..9999 } != false) { "发生日期须在公元 1 至 9999 年之间" }
        require(draft.body.isNotBlank() || draft.answers.values.any { it.isNotBlank() } || draft.attachmentIds.isNotEmpty()) { "请填写记录、复盘答案或添加附件" }
        require(draft.topics.size <= 30 && draft.topics.all { it.isNotBlank() && it == it.trim() && it.length <= 80 }) { "主题无效" }
        require(draft.answers.values.sumOf { it.length.toLong() } <= 200_000) { "答案过长" }
        require(draft.attachmentIds.size <= 20) { "单条记录最多包含 20 个附件" }
        if (draft.template == null) require(draft.answers.isEmpty()) { "复盘答案必须绑定模板" }
        else {
            val template = requireNotNull(readTemplate(db, draft.template.id, draft.template.version)) { "模板版本不存在" }
            require(draft.answers.keys.all { key -> template.draft.questions.any { it.id == key } }) { "答案包含模板之外的问题" }
        }
        draft.attachmentIds.forEach { id ->
            val attachment = requireNotNull(readAttachment(db, id)) { "附件不存在" }
            require(attachmentFile(id).isFile && attachmentFile(id).length() == attachment.sizeBytes) { "附件文件缺失或不完整" }
        }
    }

    private fun validateTemplate(draft: TemplateDraft) {
        require(draft.name.isNotBlank()) { "模板名称无效" }
        require(draft.sourceMarkdown.toByteArray(Charsets.UTF_8).size <= MAX_TEMPLATE_SOURCE_BYTES) { "模板源文不能超过 256 KiB" }
        require(draft.questions.size in 1..100) { "模板须有 1 至 100 个问题" }
        // Parsed fields and source coexist. This admits every bounded importer result without
        // allowing callers to assemble unbounded drafts directly through the repository.
        val textFields = listOf(draft.name, draft.description, draft.sourceMarkdown) +
            draft.questions.flatMap { listOf(it.prompt, it.hint, it.intent) }
        require(textFields.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= 1024 * 1024) { "模板内容过长" }
        require(draft.questions.map { it.id }.distinct().size == draft.questions.size) { "问题 ID 不能重复" }
        draft.questions.forEach {
            validateId(it.id)
            require(it.prompt.isNotBlank() && it.intent.length <= 100) { "问题内容无效" }
        }
    }

    private fun validateId(id: String) { require(id.isNotBlank() && id.length <= 200 && id.none { it.isISOControl() }) { "ID 无效" } }

    private fun insertRevision(db: SQLiteDatabase, record: RecordRevision) {
        val draft = record.draft
        db.insertOrThrow("record_revisions", null, values("record_id" to record.id, "revision" to record.revision,
            "body" to draft.body, "start_date" to draft.occurrence.startDate?.toString(),
            "end_date" to draft.occurrence.endDate?.toString(), "zone_id" to draft.occurrence.zoneId,
            "template_id" to draft.template?.id, "template_version" to draft.template?.version,
            "updated_at" to timestamp(record.updatedAt), "archived" to record.archived))
        draft.topics.forEach { db.insertOrThrow("record_topics", null, values("record_id" to record.id, "revision" to record.revision, "topic" to it)) }
        draft.answers.forEach { (question, answer) -> db.insertOrThrow("record_answers", null, values("record_id" to record.id, "revision" to record.revision, "question_id" to question, "answer" to answer)) }
        draft.attachmentIds.forEach { db.insertOrThrow("record_attachments", null, values("record_id" to record.id, "revision" to record.revision, "attachment_id" to it)) }
    }

    private fun readRecord(db: SQLiteDatabase, id: String, revision: Int? = null): RecordRevision? {
        val condition = if (revision == null) "r.revision = h.current_revision" else "r.revision = ?"
        val args = if (revision == null) arrayOf(id) else arrayOf(id, revision.toString())
        return db.rawQuery("SELECT r.*, h.created_at FROM records h JOIN record_revisions r ON h.id = r.record_id WHERE h.id = ? AND $condition", args).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val version = cursor.int("revision")
            val relationArgs = arrayOf(id, version.toString())
            val topics = strings(db, "SELECT topic FROM record_topics WHERE record_id = ? AND revision = ? ORDER BY topic", *relationArgs).toSet()
            val attachments = strings(db, "SELECT attachment_id FROM record_attachments WHERE record_id = ? AND revision = ? ORDER BY attachment_id", *relationArgs).toSet()
            val answers = db.rawQuery("SELECT question_id, answer FROM record_answers WHERE record_id = ? AND revision = ? ORDER BY question_id", relationArgs).use { answersCursor ->
                buildMap { while (answersCursor.moveToNext()) put(answersCursor.getString(0), answersCursor.getString(1)) }
            }
            val templateId = cursor.nullableString("template_id")
            RecordRevision(id, version, RecordDraft(cursor.string("body"), Occurrence(
                cursor.nullableString("start_date")?.let(LocalDate::parse), cursor.nullableString("end_date")?.let(LocalDate::parse), cursor.string("zone_id")),
                topics, templateId?.let { TemplateRef(it, cursor.int("template_version")) }, answers, attachments),
                Instant.parse(cursor.string("created_at")), Instant.parse(cursor.string("updated_at")), cursor.int("archived") == 1)
        }
    }

    private fun readTemplate(db: SQLiteDatabase, id: String, version: Int? = null): TemplateVersion? {
        val condition = if (version == null) "v.version = t.current_version" else "v.version = ?"
        val args = if (version == null) arrayOf(id) else arrayOf(id, version.toString())
        return db.rawQuery("SELECT v.*, t.archived FROM templates t JOIN template_versions v ON t.id = v.template_id WHERE t.id = ? AND $condition", args).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val actualVersion = cursor.int("version")
            val questions = db.rawQuery("SELECT question_id, prompt, hint, intent FROM template_questions WHERE template_id = ? AND version = ? ORDER BY ordinal", arrayOf(id, actualVersion.toString())).use { questionsCursor ->
                buildList { while (questionsCursor.moveToNext()) add(TemplateQuestion(questionsCursor.getString(0), questionsCursor.getString(1), questionsCursor.getString(2), questionsCursor.getString(3))) }
            }
            TemplateVersion(id, actualVersion, TemplateDraft(cursor.string("name"), Cadence.valueOf(cursor.string("cadence")), cursor.string("description"), questions, cursor.string("source_markdown")), Instant.parse(cursor.string("created_at")), cursor.int("archived") == 1)
        }
    }

    private fun readAttachment(db: SQLiteDatabase, id: String): Attachment? = db.rawQuery("SELECT id, display_name, mime_type, size_bytes, sha256 FROM attachments WHERE id = ?", arrayOf(id)).use { cursor ->
        if (cursor.moveToFirst()) Attachment(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getString(4)) else null
    }

    private fun event(db: SQLiteDatabase, id: String, revision: Int, kind: String, now: Instant) {
        db.insertOrThrow("change_events", null, values("entity_id" to id, "revision" to revision, "kind" to kind, "created_at" to timestamp(now)))
    }

    private fun queueUnreferencedAttachment(db: SQLiteDatabase, id: String) {
        if (!exists(db, "record_attachments", "attachment_id = ?", id) && exists(db, "attachments", "id = ?", id)) {
            db.insertWithOnConflict("attachment_garbage", null, values("id" to id), SQLiteDatabase.CONFLICT_IGNORE)
            db.delete("attachments", "id = ?", arrayOf(id))
        }
    }

    private fun collectGarbage(db: SQLiteDatabase) {
        strings(db, "SELECT id FROM attachment_garbage").forEach { id ->
            val file = attachmentFile(id)
            if (!file.exists() || file.delete()) db.delete("attachment_garbage", "id = ?", arrayOf(id))
        }
    }

    private fun recoverAttachmentFiles(db: SQLiteDatabase) {
        collectGarbage(db)
        val known = strings(db, "SELECT id FROM attachments").toSet()
        attachmentDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(".pending.") || (file.extension == "blob" && file.nameWithoutExtension !in known)) file.delete()
        }
    }

    private fun attachmentFile(id: String) = File(attachmentDirectory, "$id.blob")

    private fun exists(db: SQLiteDatabase, table: String, where: String, vararg args: String): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE $where LIMIT 1", args).use { it.moveToFirst() }

    private fun count(db: SQLiteDatabase, sql: String, args: List<String>): Int = db.rawQuery(sql, args.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }

    private fun strings(db: SQLiteDatabase, sql: String, vararg args: String): List<String> = db.rawQuery(sql, args).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
        private const val MAX_TEMPLATE_SOURCE_BYTES = 256 * 1024
        private val locks = ConcurrentHashMap<String, Any>()
        private val instantFormat = DateTimeFormatterBuilder().appendInstant(9).toFormatter()
        private fun timestamp(instant: Instant): String = instantFormat.format(instant)
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun values(vararg entries: Pair<String, Any?>) = ContentValues().apply {
            entries.forEach { (key, value) -> when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, if (value) 1 else 0)
                else -> error("不支持的数据库值")
            } }
        }
        private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
        private fun Cursor.nullableString(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
        private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
    }
}
