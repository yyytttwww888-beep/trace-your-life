package dev.tracelife.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.tracelife.dashboard.DashboardPreferences
import dev.tracelife.dashboard.StoredDashboardPreferences
import dev.tracelife.actions.ActionEvidencePlan
import dev.tracelife.actions.ActionEvidenceReceipt
import dev.tracelife.actions.ActionEvidenceProtocol
import dev.tracelife.actions.ActionHead
import dev.tracelife.actions.ActionValidation
import dev.tracelife.actions.StoredActionRevision
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRedactionCapacityException
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.ReductionGraph
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
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
import dev.tracelife.profile.ProfileHead
import dev.tracelife.profile.ProfileAnalysisTarget
import dev.tracelife.profile.ProfileAdoption
import dev.tracelife.profile.ProfileCandidateMaterialization
import dev.tracelife.profile.ProfileCandidateMaterializationStatus
import dev.tracelife.profile.ProfileDraft
import dev.tracelife.profile.ProfileKnowledgeRef
import dev.tracelife.profile.profileCandidateId
import dev.tracelife.profile.ProfileValidation
import dev.tracelife.profile.StoredProfileRevision
import dev.tracelife.profile.ProfileMaintenanceAction
import dev.tracelife.profile.ProfileMaintenanceApplication
import dev.tracelife.profile.ProfileMaintenanceApplicationRecord
import dev.tracelife.profile.ProfileMaintenanceProtocol
import dev.tracelife.profile.ProfileMaintenanceReferenceKind
import dev.tracelife.profile.ProfileRelation
import dev.tracelife.profile.ProfileChange
import dev.tracelife.transfer.Deletion
import dev.tracelife.transfer.InvalidBackupException
import dev.tracelife.transfer.RepositorySnapshot
import dev.tracelife.transfer.RestoreResult
import dev.tracelife.transfer.StaleSnapshotException
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel

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
            // Until dependencies are indexed, over-purge rather than retain copied deleted text.
            db.delete("analysis_runs", null, null)
            purgeKnowledge(db, allKnowledgeHeads(db).map { it.id })
            attachmentIds.forEach { queueUnreferencedAttachment(db, it) }
        }
        collectGarbage(db)
    }

    fun saveTemplate(draft: TemplateDraft, id: String? = null, expectedVersion: Int? = null): TemplateVersion = access { db ->
        validateTemplate(draft)
        val templateId = id ?: UUID.randomUUID().toString()
        validateId(templateId)
        transaction(db) {
            require(!exists(db, "deleted_templates", "id = ?", templateId)) { "已删除的模板 ID 不可复用" }
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
        val now = clock.instant()
        db.insertOrThrow("deleted_templates", null, values("id" to id, "last_revision" to current.version, "deleted_at" to timestamp(now)))
        event(db, id, current.version, "TEMPLATE_DELETED", now)
        db.delete("analysis_runs", null, null)
        purgeKnowledge(db, allKnowledgeHeads(db).map { it.id })
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
            syncDirectory(attachmentDirectory)
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

    internal fun changeEventsAfter(afterId: Long, limit: Int = 100): List<ChangeEvent> = access { db ->
        require(afterId >= 0 && limit in 1..1000)
        db.rawQuery("SELECT id, entity_id, revision, kind, created_at FROM change_events WHERE id > ? ORDER BY id LIMIT ?",
            arrayOf(afterId.toString(), limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ChangeEvent(cursor.getLong(0), cursor.getString(1), cursor.getInt(2),
                cursor.getString(3), Instant.parse(cursor.getString(4)))) }
        }
    }

    internal fun latestChangeEventId(): Long = access { db ->
        db.rawQuery("SELECT COALESCE(MAX(id), 0) FROM change_events", null).use { check(it.moveToFirst()); it.getLong(0) }
    }

    internal fun localRestoreEpoch(): Long = access { db ->
        db.rawQuery("SELECT restore_epoch FROM store_state WHERE singleton = 1", null).use { check(it.moveToFirst()); it.getLong(0) }
    }

    fun dashboardPreferences(): StoredDashboardPreferences? = access(DashboardPreferencesStore::read)

    fun saveDashboardPreferences(expectedRevision: Int, preferences: DashboardPreferences): StoredDashboardPreferences {
        require(expectedRevision >= 0)
        val fixed = preferences.validated()
        return access { db -> transaction(db) {
            val previous = DashboardPreferencesStore.read(db)
            if ((previous?.revision ?: 0) != expectedRevision) throw RevisionConflictException("看板偏好版本已变化")
            val now = clock.instant()
            require(previous == null || now >= previous.updatedAt) { "时钟早于看板偏好最后修改时间" }
            if (previous?.value == fixed) return@transaction previous
            require(expectedRevision < Int.MAX_VALUE - 1) { "看板偏好修订达到上限" }
            StoredDashboardPreferences(expectedRevision + 1, fixed, now).also { DashboardPreferencesStore.write(db, it) }
        } }
    }

    fun deletedRecordIds(): Set<String> = access { db -> strings(db, "SELECT id FROM deleted_records").toSet() }

    fun currentGeneration(): Long = access { db -> storeState(db).second }

    fun currentStoreId(): String = access { db -> storeState(db).first }

    fun currentAnalysisGeneration(): Long = access(::analysisGeneration)

    internal fun analysisSourceStamp(): SourceStamp = access { db ->
        storeState(db).let { SourceStamp(it.first, it.second) }
    }

    /** Metadata only: no record bodies, answers or attachment bytes are selected. */
    internal fun analysisSourcePage(stamp: SourceStamp, query: AnalysisQuery, offset: Int, limit: Int): SourcePage = access { db ->
        validateAnalysisQuery(query)
        require(offset >= 0 && limit in 1..100) { "分析分页范围无效" }
        transaction(db) {
            requireAnalysisSource(db, stamp)
            val (where, args) = analysisScope(query)
            val join = " FROM records h JOIN record_revisions r ON r.record_id = h.id WHERE $where"
            val total = count(db, "SELECT COUNT(*)$join", args)
            val unknown = count(db, "SELECT COUNT(*)$join AND r.start_date IS NULL", args)
            val overlap = count(db, "SELECT COUNT(*)$join AND (r.start_date < ? OR r.end_date > ?)",
                args + query.from.toString() + query.through.toString())
            val items = db.rawQuery("""SELECT h.id, r.revision, r.start_date, r.end_date, r.zone_id, r.archived$join
                ORDER BY (r.start_date IS NULL), r.start_date, h.id LIMIT ? OFFSET ?""",
                (args + limit.toString() + offset.toString()).toTypedArray()).use { cursor ->
                buildList { while (cursor.moveToNext()) add(sourceDescriptor(db, cursor, query)) }
            }
            SourcePage(items, total, unknown, overlap, (offset.toLong() + items.size).takeIf { it < total }?.toInt())
        }
    }

    /** Content-unit inventory. Attachment units stay separate and are never counted as text reads. */
    internal fun analysisSourceFields(stamp: SourceStamp, query: AnalysisQuery, id: String): List<SourceField> = access { db ->
        validateAnalysisQuery(query)
        validateId(id)
        transaction(db) {
            requireAnalysisSource(db, stamp)
            val revision = scopedRevision(db, query, id)
            val args = arrayOf(id, revision.toString())
            buildList {
                db.rawQuery("SELECT length(body), instr(body, char(0)) FROM record_revisions WHERE record_id = ? AND revision = ?", args).use {
                    check(it.moveToFirst())
                    if (it.getInt(1) != 0) throw UnreadableSourceException("正文含无法安全分片的 NUL 字符")
                    if (it.getInt(0) > 0) add(SourceField("body", it.getInt(0), SourceFieldKind.TEXT))
                }
                db.rawQuery("SELECT question_id, length(answer), instr(answer, char(0)) FROM record_answers WHERE record_id = ? AND revision = ? ORDER BY question_id", args).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(2) != 0) throw UnreadableSourceException("答案含无法安全分片的 NUL 字符")
                        if (cursor.getInt(1) > 0) add(SourceField("answer:${cursor.getString(0)}", cursor.getInt(1), SourceFieldKind.TEXT))
                    }
                }
                strings(db, "SELECT attachment_id FROM record_attachments WHERE record_id = ? AND revision = ? ORDER BY attachment_id", *args)
                    .forEach { add(SourceField("attachment:$it", 0, SourceFieldKind.ATTACHMENT, it)) }
            }
        }
    }

    /** Historical template identity only; does not load the record body or answer text. */
    internal fun analysisSourceTemplate(stamp: SourceStamp, query: AnalysisQuery, id: String): TemplateRef? = access { db ->
        validateAnalysisQuery(query)
        validateId(id)
        transaction(db) {
            requireAnalysisSource(db, stamp)
            val revision = scopedRevision(db, query, id)
            db.rawQuery("SELECT template_id, template_version FROM record_revisions WHERE record_id = ? AND revision = ?",
                arrayOf(id, revision.toString())).use {
                check(it.moveToFirst())
                if (it.isNull(0)) null else TemplateRef(it.getString(0), it.getInt(1))
            }
        }
    }

    /** Questions retain the exact template version used by this current record revision. */
    internal fun analysisSourceQuestions(stamp: SourceStamp, query: AnalysisQuery, id: String): Map<String, TemplateQuestion> = access { db ->
        validateAnalysisQuery(query)
        validateId(id)
        transaction(db) {
            requireAnalysisSource(db, stamp)
            val revision = scopedRevision(db, query, id)
            db.rawQuery("""SELECT q.question_id, q.prompt, q.hint, q.intent FROM record_revisions r
                JOIN template_questions q ON q.template_id = r.template_id AND q.version = r.template_version
                WHERE r.record_id = ? AND r.revision = ? ORDER BY q.ordinal""", arrayOf(id, revision.toString())).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val question = TemplateQuestion(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
                        put("answer:${question.id}", question)
                    }
                }
            }
        }
    }

    /** Slice in SQLite before returning any source text; offsets count Unicode code points. */
    internal fun analysisSourceFragment(
        stamp: SourceStamp, query: AnalysisQuery, id: String, field: String, start: Int, maxCodePoints: Int,
    ): SourceFragment = access { db ->
        validateAnalysisQuery(query)
        validateId(id)
        require(start >= 0 && maxCodePoints in 1..4096) { "分析原文分片范围无效" }
        transaction(db) {
            requireAnalysisSource(db, stamp)
            val revision = scopedRevision(db, query, id)
            val args = mutableListOf((start.toLong() + 1).toString(), maxCodePoints.toString(), id, revision.toString())
            val sql = when {
                field == "body" -> """SELECT length(body), substr(body, ?, ?), instr(body, char(0)), NULL
                    FROM record_revisions WHERE record_id = ? AND revision = ?"""
                field.startsWith("answer:") -> {
                    val questionId = field.removePrefix("answer:")
                    validateId(questionId)
                    args += questionId
                    """SELECT length(a.answer), substr(a.answer, ?, ?), instr(a.answer, char(0)),
                        q.prompt || CASE WHEN length(CAST(q.hint AS BLOB)) > 0 THEN char(10) || q.hint ELSE '' END
                        FROM record_answers a JOIN record_revisions r ON a.record_id = r.record_id AND a.revision = r.revision
                        JOIN template_questions q ON q.template_id = r.template_id AND q.version = r.template_version AND q.question_id = a.question_id
                        WHERE a.record_id = ? AND a.revision = ? AND a.question_id = ?"""
                }
                else -> throw IllegalArgumentException("原文字段不存在或不是文本")
            }
            db.rawQuery(sql, args.toTypedArray()).use { cursor ->
                require(cursor.moveToFirst()) { "原文字段不存在" }
                val total = cursor.getInt(0)
                if (cursor.getInt(2) != 0) throw UnreadableSourceException("原文含无法安全分片的 NUL 字符")
                require(start < total) { "原文分片超出字段范围" }
                val text = cursor.getString(1)
                SourceFragment(id, revision, field, start, start + text.codePointCount(0, text.length), total,
                    text, if (cursor.isNull(3)) null else cursor.getString(3))
            }
        }
    }

    internal fun analysisRun(id: String): StoredAnalysisRun? = access { db ->
        validateId(id)
        transaction(db) { readAnalysisRun(db, id) }
    }

    internal fun analysisRuns(offset: Int, limit: Int): List<StoredAnalysisRun> = access { db ->
        require(offset >= 0 && limit in 1..100) { "分析任务分页范围无效" }
        transaction(db) {
            db.rawQuery("SELECT $ANALYSIS_METADATA_COLUMNS FROM analysis_runs ORDER BY created_at, id LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString())).use { cursor ->
                buildList { while (cursor.moveToNext()) add(analysisRunFromCursor(db, cursor)) }
            }
        }
    }

    internal fun saveAnalysisRun(run: StoredAnalysisRun, expectedVersion: Int?): StoredAnalysisRun = access { db ->
        validateAnalysisRun(run)
        require(expectedVersion == null || expectedVersion in 1 until Int.MAX_VALUE) { "分析任务预期版本无效" }
        require(run.version == (expectedVersion?.plus(1) ?: 1)) { "分析任务新版本无效" }
        transaction(db) {
            requireAnalysisSource(db, run.source)
            require(!exists(db, "deleted_analysis_runs", "id = ?", run.id)) { "已删除的分析任务 ID 不可复用" }
            val current = readAnalysisRun(db, run.id)
            if (current?.version != expectedVersion) throw RevisionConflictException("分析任务已有新版本或不存在")
            if (current != null) {
                require(current.createdAt == run.createdAt && current.source == run.source && run.updatedAt >= current.updatedAt) { "分析任务身份或时间不一致" }
            }
            val state = AnalysisRunCodec.decode(run.payload)
            state.request.profileTarget?.takeIf { it.layer == dev.tracelife.profile.ProfileLayer.LONG_TERM }?.let { target ->
                state.calls.forEach { call ->
                    val metadata = call.evidence.map { it.recordId }.distinct().map { id ->
                        analysisSourcePage(run.source, state.request.scope.copy(recordIds = setOf(id)), 0, 1).items.single()
                    }
                    if (call.purpose == dev.tracelife.analysis.CallPurpose.SYNTHESIS)
                        dev.tracelife.profile.LongitudinalProfileProtocol.validateInputs(target, call.evidence, metadata)
                    call.proposal?.let { dev.tracelife.profile.LongitudinalProfileProtocol.validateProposal(target, it, call.evidence, metadata) }
                    call.review?.let { dev.tracelife.profile.LongitudinalProfileProtocol.validateReview(target, it, call.evidence, metadata) }
                }
            }
            val prior = current?.let { AnalysisRunCodec.decode(it.payload) }
            require(prior == null || prior.actionPlan == state.actionPlan) { "行动证据目录不可改变" }
            if (current == null || state.calls.any { it !in prior?.calls.orEmpty() && it.status in setOf(dev.tracelife.analysis.CallStatus.IN_FLIGHT, dev.tracelife.analysis.CallStatus.SUCCEEDED) })
                state.actionPlan?.let { if (!actionEvidencePlanCurrent(state.request.scope, it)) throw dev.tracelife.analysis.AnalysisDependencyBlockedException("行动证据目录已变化") }
            val changedPlans = state.profileMaintenancePlans.filter { it !in prior?.profileMaintenancePlans.orEmpty() }
            val changedCalls = state.calls.filter { it !in prior?.calls.orEmpty() && it.profileMaintenancePlanSha256 != null &&
                it.status in setOf(dev.tracelife.analysis.CallStatus.IN_FLIGHT, dev.tracelife.analysis.CallStatus.SUCCEEDED) }
            val checkedPlans = changedPlans + changedCalls.map { call -> requireNotNull(state.profileMaintenancePlans.singleOrNull {
                ProfileMaintenanceProtocol.planSha256(it) == call.profileMaintenancePlanSha256
            }) }
            checkedPlans.distinct().forEach { plan ->
                if (!profileMaintenanceCatalogCurrent(plan.profileTarget, run.id, plan.target, plan.references))
                    throw dev.tracelife.analysis.AnalysisDependencyBlockedException("画像维护目录在条件保存前发生变化")
            }
            db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(CAST(payload AS BLOB)) + reserved_bytes), 0) FROM analysis_runs WHERE id <> ?", arrayOf(run.id)).use {
                it.moveToFirst()
                require(it.getInt(0) < 100 && it.getLong(1) + run.payload.toByteArray(Charsets.UTF_8).size + AnalysisRunCodec.reservedResponseBytes(run) <= 16L * 1024 * 1024) { "分析任务超出本地保存上限" }
            }
            if (current == null) insertAnalysisRun(db, run)
            else db.update("analysis_runs", analysisRunValues(run), "id = ?", arrayOf(run.id))
            run
        }
    }

    /** User-directed removal works for stale or running tasks; its ID can never be reused. */
    internal fun deleteAnalysisRun(id: String, expectedVersion: Int) = access { db ->
        validateId(id)
        require(expectedVersion > 0) { "分析任务预期版本无效" }
        transaction(db) {
            val current = readAnalysisRun(db, id)
            if (current?.version != expectedVersion) throw RevisionConflictException("分析任务已有新版本或不存在")
            purgeProfileMaintenanceRuns(db, deletedRunId = id)
            purgeKnowledge(db, strings(db, "SELECT DISTINCT id FROM knowledge_revisions WHERE run_id = ?", id))
            db.delete("analysis_runs", "id = ?", arrayOf(id))
            insertDeletion(db, "deleted_analysis_runs", Deletion(id, expectedVersion, clock.instant()))
        }
    }

    fun claimFeedback(id: String): StoredClaimFeedback? = access { db ->
        validateId(id)
        transaction(db) { readClaimFeedback(db, id) }
    }

    /** Includes a permanent deletion fence, so create → delete never appears unchanged to a review. */
    fun claimFeedbackVersion(id: String): Int = access { db ->
        validateId(id)
        transaction(db) {
            readClaimFeedback(db, id)?.version ?: db.rawQuery("SELECT last_revision FROM deleted_claim_feedback WHERE id = ?", arrayOf(id))
                .use { if (it.moveToFirst()) it.getInt(0) + 1 else 0 }
        }
    }

    /** A statement's ancestry remains a dependency after its own decision is saved. */
    fun claimFeedbackDependenciesCurrent(runId: String, claim: ClaimRef, expectedRunVersion: Int): Boolean = access { db ->
        validateId(runId)
        transaction(db) {
            val run = readAnalysisRun(db, runId)
            run != null && run.version == expectedRunVersion && feedbackDependenciesCurrent(db, run, claim)
        }
    }

    fun claimFeedbacks(offset: Int = 0, limit: Int = 100): List<StoredClaimFeedback> = access { db ->
        require(offset >= 0 && limit in 1..100) { "反馈分页范围无效" }
        transaction(db) {
            ensureFeedbackBounds(db)
            db.rawQuery("SELECT * FROM claim_feedback ORDER BY created_at, id LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString()))
                .use { cursor -> buildList { while (cursor.moveToNext()) add(feedbackFromCursor(cursor)) } }
        }
    }

    /** Feedback changes never overwrite model text. They also advance the task CAS token, making
     * an already running model completion unable to silently commit over the user's decision. */
    fun saveClaimFeedback(value: StoredClaimFeedback, expectedVersion: Int?, expectedRunVersion: Int?): StoredClaimFeedback = access { db ->
        ClaimFeedbackValidation.validate(value)
        require(expectedVersion == null || expectedVersion in 1 until Int.MAX_VALUE - 1) { "反馈预期版本无效" }
        require(value.version == (expectedVersion?.plus(1) ?: 1)) { "反馈新版本无效" }
        transaction(db) {
            require(!exists(db, "deleted_claim_feedback", "id = ?", value.id)) { "已删除的反馈 ID 不可复用" }
            val current = readClaimFeedback(db, value.id)
            if (current?.version != expectedVersion) throw RevisionConflictException("反馈已有新版本或不存在")
            if (current != null) {
                require(value.copy(version = current.version, decision = current.decision, userText = current.userText,
                    updatedAt = current.updatedAt) == current && value.updatedAt >= current.updatedAt) { "反馈身份或时间不一致" }
            }
            val run = readAnalysisRun(db, value.runId)
            if (expectedRunVersion != null && run?.version != expectedRunVersion) throw RevisionConflictException("分析任务已有新版本或不存在")
            val sourceState = storeState(db)
            val active = run != null && run.source == SourceStamp(sourceState.first, sourceState.second) &&
                (run.expiresAt == null || clock.instant() < run.expiresAt) &&
                feedbackDependenciesCurrent(db, run, ClaimRef(value.batchKey, value.claimIndex, value.claimSha256))
            if (active) {
                if (expectedRunVersion == null) throw RevisionConflictException("当前分析反馈需要预期任务版本")
                ClaimFeedbackValidation.validateTarget(value, requireNotNull(run))
            } else {
                require(current != null) { "失效或缺失任务不能新增反馈" }
                require(value.decision == current.decision || value.decision in setOf(ClaimFeedbackDecision.REJECTED, ClaimFeedbackDecision.WITHDRAWN)) {
                    "失效反馈只能维护独立文字、否定或撤回，不能重新确认结论"
                }
            }
            val bytes = ClaimFeedbackValidation.encodedBytes(value)
            db.rawQuery("SELECT COUNT(*), COALESCE(SUM(encoded_bytes), 0) FROM claim_feedback WHERE id <> ?", arrayOf(value.id)).use {
                it.moveToFirst()
                require(it.getInt(0) < ClaimFeedbackValidation.MAX_ROWS && it.getLong(1) + bytes <= ClaimFeedbackValidation.MAX_BYTES) { "反馈超过本地保存上限" }
            }
            if (current == null) insertClaimFeedback(db, value) else db.update("claim_feedback", feedbackValues(value), "id = ?", arrayOf(value.id))
            bumpFeedbackRun(db, run)
            value
        }
    }

    fun deleteClaimFeedback(id: String, expectedVersion: Int): FeedbackDeletionResult = access { db ->
        validateId(id)
        require(expectedVersion in 1 until Int.MAX_VALUE) { "反馈预期版本无效" }
        transaction(db) {
            val current = readClaimFeedback(db, id)
            if (current?.version != expectedVersion) throw RevisionConflictException("反馈已有新版本或不存在")
            require(count(db, "SELECT COUNT(*) FROM deleted_claim_feedback", emptyList()) < ClaimFeedbackValidation.MAX_DELETIONS) { "反馈删除标记达到保存上限" }
            val purgedMaintenance = purgeProfileMaintenanceRuns(db, deletedFeedbackId = id)
            // Every retained revision can contain wording derived from a parent decision.
            // Deleting only the current head or its own feedback would leave historical copies.
            ensureKnowledgeBounds(db)
            purgeKnowledge(db, allKnowledgeRevisions(db).filter { value ->
                KnowledgeValidation.feedbackDependencies(value).any { dependency ->
                    AnalysisRunCodec.feedbackId(value.runId, dependency.claim) == id
                }
            }.map { it.id }.distinct())
            db.delete("claim_feedback", "id = ?", arrayOf(id))
            insertDeletion(db, "deleted_claim_feedback", Deletion(id, expectedVersion, clock.instant()))
            val run = readAnalysisRun(db, current.runId)
            val purgedRun = if (run != null) {
                try {
                    val redacted = AnalysisRunCodec.redactDeletedFeedback(run, setOf(id), clock.instant())
                    if (redacted == run) bumpFeedbackRun(db, run)
                    else {
                        validateAnalysisRun(redacted)
                        val otherBytes = db.rawQuery("SELECT COALESCE(SUM(length(CAST(payload AS BLOB)) + reserved_bytes), 0) FROM analysis_runs WHERE id <> ?", arrayOf(run.id))
                            .use { check(it.moveToFirst()); it.getLong(0) }
                        if (otherBytes + analysisStorageBytes(redacted) > 16L * 1024 * 1024) throw AnalysisRedactionCapacityException()
                        db.update("analysis_runs", analysisRunValues(redacted), "id = ?", arrayOf(run.id))
                    }
                    null
                } catch (_: AnalysisRedactionCapacityException) {
                    // A valid near-limit legacy ledger can grow when redaction introduces pure
                    // identity fences. Removing the whole task is safer than retaining its text.
                    require(count(db, "SELECT COUNT(*) FROM deleted_analysis_runs", emptyList()) < 100_000) { "分析任务删除标记达到上限" }
                    purgeKnowledge(db, strings(db, "SELECT DISTINCT id FROM knowledge_revisions WHERE run_id = ?", run.id))
                    db.delete("analysis_runs", "id = ?", arrayOf(run.id))
                    insertDeletion(db, "deleted_analysis_runs", Deletion(run.id, run.version, clock.instant()))
                    run.id
                }
            } else null
            FeedbackDeletionResult(purgedRun ?: current.runId.takeIf { it in purgedMaintenance })
        }
    }

    fun knowledgeRevision(id: String, revision: Int? = null): StoredKnowledgeRevision? = access { db ->
        validateId(id)
        require(revision == null || revision > 0) { "知识修订无效" }
        transaction(db) { readKnowledgeRevision(db, id, revision) }
    }

    fun knowledgeHeads(offset: Int = 0, limit: Int = 100): List<KnowledgeHead> = access { db ->
        require(offset >= 0 && limit in 1..100) { "知识分页范围无效" }
        transaction(db) {
            db.rawQuery("SELECT id, revision FROM knowledge_heads ORDER BY id LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString()))
                .use { cursor -> buildList { while (cursor.moveToNext()) add(KnowledgeHead(cursor.getString(0), cursor.getInt(1))) } }
        }
    }

    fun knowledgeHistory(id: String, offset: Int = 0, limit: Int = 100): List<StoredKnowledgeRevision> = access { db ->
        validateId(id)
        require(offset >= 0 && limit in 1..100) { "知识分页范围无效" }
        transaction(db) {
            db.rawQuery("SELECT payload FROM knowledge_revisions WHERE id = ? ORDER BY revision DESC LIMIT ? OFFSET ?", arrayOf(id, limit.toString(), offset.toString()))
                .use { cursor -> buildList { while (cursor.moveToNext()) add(KnowledgeCodec.decode(cursor.getString(0))) } }
        }
    }

    /** Final proof verification and head switch are one transaction; callers cannot submit invented
     * source wording or reuse a review after feedback changes. This never calls a model. */
    fun publishKnowledge(value: StoredKnowledgeRevision, expectedRevision: Int?, expectedRunVersion: Int): StoredKnowledgeRevision = access { db ->
        KnowledgeValidation.validate(value)
        require(expectedRevision == null || expectedRevision in 1 until Int.MAX_VALUE) { "预期知识修订无效" }
        require(expectedRunVersion > 0) { "预期分析版本无效" }
        transaction(db) {
            requireAnalysisSource(db, value.source)
            require(!exists(db, "deleted_knowledge", "id = ?", value.id)) { "已删除的知识 ID 不可复用" }
            val now = clock.instant()
            require(value.publishedAt <= now && (value.expiresAt == null || now < value.expiresAt)) { "知识发布已过期或时间无效" }
            val run = readAnalysisRun(db, value.runId)
            if (run?.version != expectedRunVersion) throw RevisionConflictException("分析任务已有新版本或不存在")
            val proof = KnowledgeValidation.fromRun(requireNotNull(run), value.claimRef, value.reviewBatchKey,
                value.category, value.topics, value.revision, value.createdAt, value.publishedAt)
            require(proof == value) { "知识内容与已保存核对不一致" }
            require(KnowledgeValidation.reviewStillCurrent(value, run)) { "核对已有新结果，不能发布旧意见" }
            value.profileMaintenanceProof?.let { maintenance ->
                require(profileMaintenanceCatalogCurrent(maintenance.plan.profileTarget, value.runId, value.claimRef,
                    maintenance.plan.references)) { "画像维护历史目录已变化" }
                require(ProfileMaintenanceCatalog(this).publicationDependenciesCurrent(maintenance)) { "画像维护依据或历史反馈已变化" }
            }
            KnowledgeValidation.feedbackDependencies(value).forEach { dependency ->
                val dependencyId = AnalysisRunCodec.feedbackId(value.runId, dependency.claim)
                require(!exists(db, "deleted_claim_feedback", "id = ?", dependencyId)) { "已删除的自身或祖先反馈阻止发布" }
                val response = readClaimFeedback(db, dependencyId)
                if ((response?.version ?: 0) != dependency.version || response != null && response.source != value.source) {
                    throw RevisionConflictException("自身或祖先反馈已变化，须重新生成并核对")
                }
                require(response == null || response.decision == ClaimFeedbackDecision.CONFIRMED) { "用户已纠正、否定或撤回自身或祖先陈述" }
            }
            val feedback = readClaimFeedback(db, AnalysisRunCodec.feedbackId(value.runId, value.claimRef))
            require(value.category != KnowledgeCategory.INSIGHT || feedback?.decision == ClaimFeedbackDecision.CONFIRMED) { "洞察需要用户确认" }
            // Metrics represent a complete query, including negative/zero results. Recompute
            // while the publication transaction still owns the current source snapshot.
            value.metricEvidence.forEach { dev.tracelife.metrics.RecordMetricProtocol.validateCurrent(this, it) }
            value.actionPlan?.let {
                require(now >= run.updatedAt) { "行动复盘发布时钟已回拨" }
                require(actionEvidencePlanCurrent(value.scope, it)) { "行动原始资料已变化" }
                ActionEvidenceProtocol.validateSources(it, value.actionEvidence, ActionStore.allRevisions(db), value.scope)
            }
            val current = readKnowledgeRevision(db, value.id, null)
            // A retry may repeat an already switched head, but never bypass current dependencies.
            if (current != null && sameKnowledgePublication(current, value) && value.createdAt == current.createdAt &&
                value.revision in current.revision..(current.revision + 1) && value.publishedAt >= current.publishedAt &&
                (expectedRevision == current.revision || expectedRevision == current.revision - 1 || expectedRevision == null && current.revision == 1)) {
                return@transaction current
            }
            AnalysisRunCodec.decode(run.payload).request.profileTarget?.let { target ->
                require(value.profileMaintenanceProof != null || target.layer != dev.tracelife.profile.ProfileLayer.LONG_TERM && profileMaintenanceCatalog(target, value.runId, value.claimRef).isEmpty()) {
                    "画像定向知识发布需要先完成历史关联与反例维护"
                }
            }
            if (current?.revision != expectedRevision) throw RevisionConflictException("知识已有新修订或不存在")
            require(value.revision == (expectedRevision?.plus(1) ?: 1)) { "知识新修订无效" }
            if (current == null) require(value.createdAt == value.publishedAt) { "首个知识修订时间不一致" }
            else require(value.createdAt == current.createdAt && value.publishedAt >= current.publishedAt) { "知识修订时间不一致" }
            val sources = KnowledgeValidation.allSourceRevisions(value).map { (id, revision) ->
                requireNotNull(readRecord(db, id, revision)) { "知识原始证据已不存在" }
            }
            KnowledgeValidation.validateEvidence(value, sources)
            val bytes = KnowledgeValidation.encodedBytes(value)
            require(bytes <= KnowledgeValidation.MAX_REVISION_BYTES) { "知识修订超过大小上限" }
            db.rawQuery("SELECT COUNT(*), COALESCE(SUM(encoded_bytes), 0) FROM knowledge_revisions", null).use {
                it.moveToFirst()
                require(it.getInt(0) < KnowledgeValidation.MAX_REVISIONS && it.getLong(1) + bytes <= KnowledgeValidation.MAX_BYTES) { "知识修订超过保存上限" }
            }
            insertKnowledgeRevision(db, value)
            if (current == null) db.insertOrThrow("knowledge_heads", null, values("id" to value.id, "revision" to value.revision))
            else db.update("knowledge_heads", values("revision" to value.revision), "id = ?", arrayOf(value.id))
            value
        }
    }

    fun knowledgeDeleted(id: String): Boolean = access { db ->
        validateId(id)
        exists(db, "deleted_knowledge", "id = ?", id)
    }

    internal fun actionEvidencePlan(scope: AnalysisQuery): ActionEvidencePlan = access { db ->
        transaction(db) { ActionEvidenceProtocol.plan(scope, ActionStore.allRevisions(db)) }
    }
    internal fun actionEvidencePlanCurrent(scope: AnalysisQuery, plan: ActionEvidencePlan): Boolean =
        actionEvidencePlan(scope) == plan
    internal fun actionEvidenceBody(ref: ActionEvidenceReceipt): Map<String, Any?> = access { db ->
        transaction(db) { ActionEvidenceProtocol.modelMap(requireNotNull(ActionStore.revision(db, ref.actionId, ref.revision)) {
            "行动证据已不存在"
        }, ref) }
    }

    fun actionRevision(id: String, revision: Int? = null): StoredActionRevision? = access { db ->
        transaction(db) { ActionStore.revision(db, id, revision) }
    }
    fun actionHeads(offset: Int = 0, limit: Int = 100): List<ActionHead> = access { db -> ActionStore.heads(db, offset, limit) }
    fun actionHistory(id: String, offset: Int = 0, limit: Int = 25): List<StoredActionRevision> = access { db ->
        transaction(db) { ActionStore.history(db, id, offset, limit) }
    }
    internal fun actionOperation(id: String, operationId: String): StoredActionRevision? = access { db ->
        ActionStore.operation(db, id, operationId)
    }
    internal fun saveAction(value: StoredActionRevision, expectedRevision: Int?): StoredActionRevision = access { db ->
        transaction(db) {
            ActionStore.save(db, value, expectedRevision) { origin ->
                val knowledge = KnowledgeLibrary(this, clock).get(origin.knowledgeId, origin.revision)
                require(knowledge != null && knowledge.isCurrentRevision && knowledge.evidenceState == KnowledgeEvidenceState.CURRENT) {
                    "行动来源知识已变化或失效，请重新查看后选择"
                }
            }
        }
    }
    internal fun deleteAction(id: String, expectedRevision: Int) = access { db ->
        transaction(db) {
            ActionStore.delete(db, id, expectedRevision, clock.instant())
            allAnalysisRuns(db).filter { run -> AnalysisRunCodec.decode(run.payload).actionPlan?.entries?.any { it.actionId == id } == true }
                .forEach { run -> if (readAnalysisRun(db, run.id) != null) deleteAnalysisRun(run.id, run.version) }
            allKnowledgeRevisions(db).filter { value -> value.actionPlan?.entries?.any { it.actionId == id } == true }.map { it.id }.distinct()
                .forEach { knowledgeId ->
                    purgeProfileMaintenanceRuns(db, deletedKnowledgeId = knowledgeId)
                    if (readKnowledgeRevision(db, knowledgeId, null) != null) purgeKnowledge(db, listOf(knowledgeId))
                }
        }
    }

    fun profileRevision(id: String, revision: Int? = null): StoredProfileRevision? = access { db ->
        transaction(db) { ProfileStore.revision(db, id, revision) }
    }
    fun profileHeads(offset: Int = 0, limit: Int = 100): List<ProfileHead> = access { db -> ProfileStore.heads(db, offset, limit) }
    fun profileHistory(id: String, offset: Int = 0, limit: Int = 25): List<StoredProfileRevision> = access { db ->
        transaction(db) { ProfileStore.history(db, id, offset, limit) }
    }
    internal fun profileOperation(id: String, operationId: String): StoredProfileRevision? = access { db ->
        ProfileStore.operation(db, id, operationId)
    }
    internal fun saveProfile(value: StoredProfileRevision, expectedRevision: Int?): StoredProfileRevision = access { db ->
        transaction(db) {
            ProfileStore.save(db, value, expectedRevision) { ref ->
                val evaluatedAt = clock.instant()
                val knowledge = KnowledgeLibrary(this, Clock.fixed(evaluatedAt, clock.zone)).get(ref.knowledgeId, ref.revision)
                require(knowledge != null && knowledge.isCurrentRevision && knowledge.evidenceState == KnowledgeEvidenceState.CURRENT &&
                    knowledge.value.publishedAt <= evaluatedAt &&
                    knowledge.value.category in setOf(KnowledgeCategory.OBSERVATION, KnowledgeCategory.INSIGHT)) {
                    "画像来源须为当前有效的观察或洞察，请重新查看后选择"
                }
            }
        }
    }
    internal fun deleteProfile(id: String, expectedRevision: Int) = access { db ->
        transaction(db) {
            purgeProfileMaintenanceRuns(db, deletedProfileId = id)
            ProfileStore.delete(db, id, expectedRevision, clock.instant())
        }
    }

    internal fun profileForSlot(knowledgeId: String, target: ProfileAnalysisTarget): StoredProfileRevision? = access { db ->
        ProfileStore.forSlot(db, knowledgeId, target)
    }

    internal fun profileDeleted(id: String): Boolean = access { db -> ProfileStore.deleted(db, id) }

    internal fun profileMaintenanceCatalog(target: ProfileAnalysisTarget, excludeRunId: String,
        excludeClaim: ClaimRef): List<dev.tracelife.profile.ProfileMaintenanceReference> = access { db ->
        transaction(db) { ProfileMaintenanceCatalog(this).catalog(target, excludeRunId, excludeClaim) }
    }

    internal fun profileMaintenanceReference(reference: dev.tracelife.profile.ProfileMaintenanceReference):
        dev.tracelife.profile.ProfileMaintenanceReferenceContent = access { db ->
        transaction(db) { ProfileMaintenanceCatalog(this).resolve(reference) }
    }

    internal fun profileMaintenanceCatalogCurrent(target: ProfileAnalysisTarget, excludeRunId: String,
        excludeClaim: ClaimRef, references: List<dev.tracelife.profile.ProfileMaintenanceReference>): Boolean = access { db ->
        transaction(db) { ProfileMaintenanceCatalog(this).catalog(target, excludeRunId, excludeClaim) == references }
    }

    internal fun profileMaintenanceDependenciesCurrent(proof: dev.tracelife.profile.ProfileMaintenanceProof): Boolean = access { db ->
        transaction(db) { ProfileMaintenanceCatalog(this).publicationDependenciesCurrent(proof) }
    }

    internal fun applyProfileMaintenance(runId: String, claim: ClaimRef, expectedRunVersion: Int, planId: String,
        action: ProfileMaintenanceAction, targetProfileId: String? = null, expectedProfileRevision: Int? = null,
        operationId: String, overrideReason: String? = null): ProfileMaintenanceApplication = access { db -> transaction(db) {
        SnapshotValidation.id(operationId)
        require(overrideReason == null || overrideReason.isNotBlank() && overrideReason.length <= 2_000)
        val run = readAnalysisRun(db, runId) ?: throw dev.tracelife.analysis.AnalysisTaskUnavailableException()
        if (run.version != expectedRunVersion) throw RevisionConflictException("画像维护任务版本已变化")
        requireAnalysisSource(db, run.source)
        val now = clock.instant()
        require(now >= run.updatedAt && now >= run.createdAt && (run.expiresAt == null || now < run.expiresAt)) { "画像维护任务已到期或时钟回拨" }
        fun finish(value: ProfileMaintenanceApplication): ProfileMaintenanceApplication {
            val end = clock.instant()
            require(end >= now && (run.expiresAt == null || end < run.expiresAt)) { "画像维护应用期间有效时间已变化" }
            requireAnalysisSource(db, run.source)
            if (readAnalysisRun(db, runId)?.version != expectedRunVersion) throw RevisionConflictException("画像维护应用期间任务已变化")
            return value
        }
        val state = AnalysisRunCodec.decode(run.payload)
        val plan = requireNotNull(state.profileMaintenancePlans.singleOrNull { ProfileMaintenanceProtocol.planSha256(it) == planId && it.target == claim })
        val feedback = requireNotNull(readClaimFeedback(db, AnalysisRunCodec.feedbackId(runId, claim))) { "维护候选需要明确确认" }
        require(feedback.decision == ClaimFeedbackDecision.CONFIRMED && feedback.source == run.source)
        val review = requireNotNull(state.calls.lastOrNull { it.purpose == dev.tracelife.analysis.CallPurpose.REVIEW &&
            it.status == dev.tracelife.analysis.CallStatus.SUCCEEDED && it.reviewTarget == claim && it.feedbackVersion == feedback.version })
        val proof = requireNotNull(review.profileMaintenanceProof) { "当前核对缺少完整画像维护证明" }
        require(proof.plan == plan && review.review?.verdict == dev.tracelife.analysis.ReviewVerdict.SUPPORTED)
        ProfileMaintenanceProtocol.validateProof(proof, review.evidence, review.review)
        require(profileMaintenanceDependenciesCurrent(proof)) { "历史反馈或参照已变化" }
        val knowledgeId = KnowledgeValidation.idFor(runId, claim)
        val application = ProfileMaintenanceApplicationRecord(planId, action, overrideReason)
        val generatedId = profileCandidateId(knowledgeId, plan.profileTarget)
        val selectedId = if (action == ProfileMaintenanceAction.NEW) generatedId else requireNotNull(targetProfileId)
        if (action == ProfileMaintenanceAction.NEW) require(targetProfileId == null && expectedProfileRevision == null)
        val selected = ProfileStore.revision(db, selectedId)
        selected?.let { profile ->
            require(profile.updatedAt <= now) { "画像维护应用遇到未来的采用决定，请校正时间后重试" }
            val linked = readKnowledgeRevision(db, profile.draft.knowledge.knowledgeId, profile.draft.knowledge.revision)
            require(linked == null || linked.publishedAt <= now) { "画像维护应用遇到未来的知识发布" }
        }
        val replay = if (action == ProfileMaintenanceAction.NEW) selected?.takeIf { it.maintenanceApplication == application && it.draft.knowledge.knowledgeId == knowledgeId }
            else ProfileStore.operation(db, selectedId, operationId)
        if (replay != null && action != ProfileMaintenanceAction.KEEP) {
            require(replay.maintenanceApplication == application && replay.draft.knowledge.knowledgeId == knowledgeId &&
                (action == ProfileMaintenanceAction.NEW || replay.revision == requireNotNull(expectedProfileRevision) + 1)) { "维护操作 ID 已绑定其他选择" }
            val head = requireNotNull(selected)
            return@transaction finish(ProfileMaintenanceApplication(action, head.draft.knowledge, ProfileHead(head.id, head.revision), head.adoption, true))
        }
        require(profileMaintenanceCatalogCurrent(plan.profileTarget, runId, claim, plan.references)) { "画像维护目录已变化，请重新核对" }
        val related = proof.relations.filter { it.result.relation != ProfileRelation.UNRELATED }
        require(related.none { it.result.relation == ProfileRelation.UNCERTAIN })
        val profiles = related.filter { it.reference.kind == ProfileMaintenanceReferenceKind.PROFILE }
        val targetRelation = profiles.singleOrNull { it.reference.id == targetProfileId }
        if (action == ProfileMaintenanceAction.NEW) require(profiles.isEmpty()) { "已有相关画像，须维护原条目" }
        else {
            require(selected != null && selected.revision == expectedProfileRevision && targetRelation != null) { "目标画像或关联已变化" }
            require(targetRelation.reference.version == selected.revision)
            if (action == ProfileMaintenanceAction.KEEP)
                return@transaction finish(ProfileMaintenanceApplication(action, selected.draft.knowledge, ProfileHead(selected.id, selected.revision), selected.adoption))
            require(profiles.size == 1) { "多个相关画像须先明确整理，不能覆盖其中一个" }
            require(targetRelation.result.relation in if (action == ProfileMaintenanceAction.NARROW) setOf(ProfileRelation.SUPPLEMENT)
                else setOf(ProfileRelation.SUPPLEMENT, ProfileRelation.CONFLICT)) { "此关联没有可采用的收窄或替换建议" }
        }
        val blocked = related.any { relation ->
            val content = profileMaintenanceReference(relation.reference)
            relation.reference.kind == ProfileMaintenanceReferenceKind.FEEDBACK || content.adoption in setOf(ProfileAdoption.DECLINED, ProfileAdoption.SUPERSEDED) ||
                content.feedbackDecision in setOf(ClaimFeedbackDecision.CORRECTED, ClaimFeedbackDecision.REJECTED, ClaimFeedbackDecision.WITHDRAWN)
        }
        require(!blocked || overrideReason != null) { "历史否定或限定需要独立说明重新考虑的理由" }
        require(!ProfileStore.deleted(db, selectedId)) { "已删除的画像 ID 不可复用" }
        val previousKnowledge = readKnowledgeRevision(db, knowledgeId, null)
        val published = KnowledgeLibrary(this, Clock.fixed(now, clock.zone)).publish(runId, claim, review.batchKey,
            KnowledgeCategory.INSIGHT, expectedRunVersion, previousKnowledge?.revision, previousKnowledge?.topics ?: emptySet())
        val draft = ProfileDraft(ProfileKnowledgeRef(published.id, published.revision), plan.profileTarget.dimension, plan.profileTarget.layer)
        val updated = if (action == ProfileMaintenanceAction.NEW) StoredProfileRevision(selectedId, 1, draft, ProfileAdoption.CANDIDATE,
            now, now, maintenanceApplication = application)
        else requireNotNull(selected).copy(revision = selected.revision + 1, draft = draft, adoption = ProfileAdoption.CANDIDATE,
            updatedAt = now, operationId = operationId, change = ProfileChange.ORGANIZED, maintenanceApplication = application)
        val saved = saveProfile(updated, selected?.revision)
        finish(ProfileMaintenanceApplication(action, saved.draft.knowledge, ProfileHead(saved.id, saved.revision), saved.adoption))
    } }

    /** User-selected publication and candidate creation commit together. No model, confirmation,
     * adoption, rebinding or independent user text is synthesized by this bridge. */
    internal fun materializeProfileCandidate(runId: String, claim: ClaimRef, expectedRunVersion: Int): ProfileCandidateMaterialization = access { db ->
        require(expectedRunVersion > 0)
        transaction(db) {
            val run = readAnalysisRun(db, runId) ?: throw dev.tracelife.analysis.AnalysisTaskUnavailableException()
            if (run.version != expectedRunVersion) throw RevisionConflictException("画像候选任务已有新版本")
            requireAnalysisSource(db, run.source)
            val now = clock.instant()
            require(now >= run.createdAt && now >= run.updatedAt && (run.expiresAt == null || now < run.expiresAt)) {
                "画像候选任务已到期或时钟回拨"
            }
            fun finish(result: ProfileCandidateMaterialization): ProfileCandidateMaterialization {
                val finishedAt = clock.instant()
                require(finishedAt >= now && (run.expiresAt == null || finishedAt < run.expiresAt)) { "画像候选发布期间有效时间已变化" }
                requireAnalysisSource(db, run.source)
                if (readAnalysisRun(db, runId)?.version != expectedRunVersion) throw RevisionConflictException("画像候选任务发布期间发生变化")
                return result
            }
            val state = AnalysisRunCodec.decode(run.payload)
            val target = requireNotNull(state.request.profileTarget) { "任务不是画像候选分析" }
            require(target.layer != dev.tracelife.profile.ProfileLayer.LONG_TERM) { "长期画像必须通过完整维护核对及明确应用" }
            val generated = requireNotNull(state.calls.lastOrNull {
                it.purpose == dev.tracelife.analysis.CallPurpose.SYNTHESIS && it.status == dev.tracelife.analysis.CallStatus.SUCCEEDED && it.batchKey == claim.batchKey
            }) { "画像候选不属于成功的原文综合" }
            val proposal = requireNotNull(generated.proposal?.claims?.getOrNull(claim.claimIndex))
            require(AnalysisRunCodec.claimSha256(proposal) == claim.claimSha256)
            val knowledgeId = KnowledgeValidation.idFor(runId, claim)
            val id = profileCandidateId(knowledgeId, target)
            if (ProfileStore.deleted(db, id)) return@transaction finish(ProfileCandidateMaterialization(
                ProfileCandidateMaterializationStatus.DELETED, null, null, null))
            require(!exists(db, "deleted_knowledge", "id = ?", knowledgeId)) { "知识已永久删除" }
            val feedbackId = AnalysisRunCodec.feedbackId(runId, claim)
            require(!exists(db, "deleted_claim_feedback", "id = ?", feedbackId)) { "用户反馈已永久删除" }
            val feedback = requireNotNull(readClaimFeedback(db, feedbackId)) { "画像候选需要用户明确确认" }
            require(feedback.decision == ClaimFeedbackDecision.CONFIRMED && feedback.source == run.source) { "画像候选尚未确认或已撤回" }
            val review = requireNotNull(state.calls.lastOrNull {
                it.purpose == dev.tracelife.analysis.CallPurpose.REVIEW && it.status == dev.tracelife.analysis.CallStatus.SUCCEEDED &&
                    it.reviewTarget == claim && it.feedbackVersion == feedback.version
            }) { "当前反馈尚未经过独立核对" }
            require(review.review?.verdict == dev.tracelife.analysis.ReviewVerdict.SUPPORTED) { "最新核对不支持此画像候选" }
            val existing = ProfileStore.revision(db, id) ?: ProfileStore.forSlot(db, knowledgeId, target)
            val library = KnowledgeLibrary(this, Clock.fixed(now, clock.zone))
            if (existing != null) {
                val linked = library.get(existing.draft.knowledge.knowledgeId, existing.draft.knowledge.revision)
                val matches = existing.draft.knowledge.knowledgeId == knowledgeId && existing.draft.dimension == target.dimension &&
                    existing.draft.layer == target.layer && linked != null && linked.isCurrentRevision &&
                    linked.evidenceState == KnowledgeEvidenceState.CURRENT && linked.value.publishedAt <= now &&
                    linked.value.category == KnowledgeCategory.INSIGHT && existing.updatedAt <= now
                return@transaction finish(ProfileCandidateMaterialization(
                    if (matches) ProfileCandidateMaterializationStatus.EXISTING else ProfileCandidateMaterializationStatus.CONFLICT,
                    existing.draft.knowledge, ProfileHead(existing.id, existing.revision), existing.adoption))
            }
            require(profileMaintenanceCatalog(target, runId, claim).isEmpty()) { "已有画像或历史纠正，请完成跨任务维护并明确应用" }
            val previous = readKnowledgeRevision(db, knowledgeId, null)
            require(previous == null || previous.category == KnowledgeCategory.INSIGHT) { "现有知识类别不同，须明确维护原知识" }
            val published = library.publish(runId, claim, review.batchKey, KnowledgeCategory.INSIGHT, expectedRunVersion,
                previous?.revision, previous?.topics ?: emptySet())
            val ref = ProfileKnowledgeRef(published.id, published.revision)
            val candidate = StoredProfileRevision(id, 1, ProfileDraft(ref, target.dimension, target.layer), ProfileAdoption.CANDIDATE, now, now)
            val saved = saveProfile(candidate, null)
            finish(ProfileCandidateMaterialization(ProfileCandidateMaterializationStatus.CREATED, ref, ProfileHead(saved.id, saved.revision), saved.adoption))
        }
    }

    fun deleteKnowledge(id: String, expectedRevision: Int) = access { db ->
        validateId(id)
        require(expectedRevision > 0)
        transaction(db) {
            if (readKnowledgeRevision(db, id, null)?.revision != expectedRevision) throw RevisionConflictException("知识已有新修订或不存在")
            purgeProfileMaintenanceRuns(db, deletedKnowledgeId = id)
            purgeKnowledge(db, listOf(id))
        }
    }

    /** Copies only attachments referenced by saved revisions, never unsaved editor imports. */
    fun captureSnapshot(attachmentDestination: File): RepositorySnapshot = access { db ->
        requireExternalPath(attachmentDestination)
        require(attachmentDestination.isDirectory && attachmentDestination.listFiles()?.isEmpty() == true) { "快照附件目标须为已创建的空目录" }
        try {
            transaction(db) {
                ensureCaptureBounds(db)
                val state = storeState(db)
                val records = db.rawQuery("SELECT record_id, revision FROM record_revisions ORDER BY record_id, revision", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(requireNotNull(readRecord(db, cursor.getString(0), cursor.getInt(1)))) }
                }
                val templates = db.rawQuery("SELECT template_id, version FROM template_versions ORDER BY template_id, version", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(requireNotNull(readTemplate(db, cursor.getString(0), cursor.getInt(1)))) }
                }
                val attachments = records.flatMap { it.draft.attachmentIds }.toSortedSet().map { requireNotNull(readAttachment(db, it)) }
                val snapshot = RepositorySnapshot(UUID.randomUUID().toString(), state.first, state.second, clock.instant(),
                    records, templates, attachments, deletions(db, "deleted_records"), deletions(db, "deleted_templates"), allChanges(db),
                    analysisRuns = allAnalysisRuns(db), analysisGeneration = analysisGeneration(db), deletedAnalysisRuns = deletions(db, "deleted_analysis_runs"),
                    claimFeedback = allClaimFeedback(db), deletedClaimFeedback = deletions(db, "deleted_claim_feedback"),
                    knowledgeRevisions = allKnowledgeRevisions(db), knowledgeHeads = allKnowledgeHeads(db), deletedKnowledge = deletions(db, "deleted_knowledge"),
                    actionRevisions = ActionStore.allRevisions(db), actionHeads = ActionStore.allHeads(db), deletedActions = deletions(db, "deleted_actions"),
                    profileRevisions = ProfileStore.allRevisions(db), profileHeads = ProfileStore.allHeads(db), deletedProfiles = deletions(db, "deleted_profiles"),
                    dashboardPreferences = DashboardPreferencesStore.read(db))
                SnapshotValidation.validate(snapshot)
                attachments.forEach { copyVerifiedAttachment(attachmentFile(it.id), File(attachmentDestination, "${it.id}.blob"), it) }
                snapshot
            }
        } catch (failure: Throwable) {
            attachmentDestination.listFiles()?.forEach { it.delete() }
            throw failure
        }
    }

    /**
     * The caller owns the adjacent immutable staging file and removes it after publication. Atomic
     * link creation never replaces a concurrently created destination. Unsupported filesystems fail
     * rather than falling back to a replacing rename or exposing a partially copied final file.
     */
    fun publishSnapshot(snapshot: RepositorySnapshot, stagedFile: File, destination: File) = publishDerivedFile(
        SourceStamp(snapshot.storeId, snapshot.generation), snapshot.analysisGeneration, null, stagedFile, destination,
    )

    /** A derived export can bind both ends of its evaluation interval at atomic publication. */
    fun publishDerivedFile(
        source: SourceStamp, analysisGeneration: Long, validUntil: Instant?, stagedFile: File, destination: File,
        validFrom: Instant? = null,
    ) = access { db ->
        requireExternalPath(stagedFile)
        requireExternalPath(destination)
        require(stagedFile.isFile && !Files.isSymbolicLink(stagedFile.toPath())) { "发布暂存文件不存在" }
        require(stagedFile.canonicalFile.parentFile == destination.canonicalFile.parentFile) { "发布暂存文件必须与目标位于同一目录" }
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "导出目标已经存在" }
        transaction(db) {
            val state = storeState(db)
            if (source.storeId != state.first || source.generation != state.second || analysisGeneration != analysisGeneration(db)) {
                throw StaleSnapshotException("资料或分析已变化，须重新生成导出")
            }
            if (validFrom != null || validUntil != null) {
                val now = clock.instant()
                if (validFrom != null && now < validFrom || validUntil != null && !now.isBefore(validUntil)) {
                    throw StaleSnapshotException("导出材料的评估时间或有效期已变化，须重新生成导出")
                }
            }
            Files.createLink(destination.toPath(), stagedFile.toPath())
        }
    }

    /** Restore replaces one logical store only after all structure and bytes pass validation. */
    fun restoreSnapshot(
        snapshot: RepositorySnapshot,
        attachmentSource: File,
        expectedGeneration: Long,
        replaceExisting: Boolean = false,
        expectedAnalysisGeneration: Long? = null,
    ): RestoreResult = access { db ->
        SnapshotValidation.validate(snapshot)
        requireExternalPath(attachmentSource)
        require(attachmentSource.isDirectory && !Files.isSymbolicLink(attachmentSource.toPath())) { "恢复附件目录不存在" }
        if (storeState(db).second != expectedGeneration) throw StaleSnapshotException("目标资料已变化，请重新核对恢复")
        if (expectedAnalysisGeneration != null && analysisGeneration(db) != expectedAnalysisGeneration) throw StaleSnapshotException("目标分析已变化，请重新核对恢复")
        if (!replaceExisting && (exists(db, "records", "1 = 1") || exists(db, "templates", "1 = 1") || exists(db, "analysis_runs", "1 = 1") || exists(db, "claim_feedback", "1 = 1") || exists(db, "knowledge_heads", "1 = 1") || exists(db, "action_heads", "1 = 1") || exists(db, "profile_heads", "1 = 1"))) {
            throw IllegalStateException("目标已有记录、模板、分析、反馈、知识、行动或画像，须明确允许替换后恢复")
        }
        val restoredPreferences = DashboardPreferencesStore.read(db) ?: snapshot.dashboardPreferences
        val mergedProfileDeletions = mergeDeletions(deletions(db, "deleted_profiles"), snapshot.deletedProfiles)
        require(mergedProfileDeletions.size <= ProfileValidation.MAX_DELETIONS) { "画像删除标记超过上限" }
        val deletedProfileIds = mergedProfileDeletions.map { it.id }.toSet()
        // Metadata and independent user wording survive removal of the referenced knowledge.
        // Preserve the whole local chain rather than rolling decisions back to imported versions.
        val profileRevisions = (snapshot.profileRevisions.groupBy { it.id } + ProfileStore.allRevisions(db).groupBy { it.id })
            .filterKeys { it !in deletedProfileIds }.toSortedMap().values.flatten()
        val profileHeads = profileRevisions.groupBy { it.id }.map { (id, values) -> ProfileHead(id, values.maxOf { it.revision }) }
        ProfileValidation.validateCollection(profileRevisions, profileHeads)
        val mergedActionDeletions = mergeDeletions(deletions(db, "deleted_actions"), snapshot.deletedActions)
        require(mergedActionDeletions.size <= ActionValidation.MAX_DELETIONS) { "行动删除标记超过上限" }
        val deletedActionIds = mergedActionDeletions.map { it.id }.toSet()
        // User-owned action plans and review history survive source replacement. The entire local
        // chain wins, even if an incoming backup carries higher revisions or later timestamps.
        val actionRevisions = (snapshot.actionRevisions.groupBy { it.id } + ActionStore.allRevisions(db).groupBy { it.id })
            .filterKeys { it !in deletedActionIds }.toSortedMap().values.flatten()
        val actionHeads = actionRevisions.groupBy { it.id }.map { (id, values) -> ActionHead(id, values.maxOf { it.revision }) }
        ActionValidation.validateCollection(actionRevisions, actionHeads)
        val mergedRecords = mergeDeletions(deletions(db, "deleted_records"), snapshot.deletedRecords)
        val mergedTemplates = mergeDeletions(deletions(db, "deleted_templates"), snapshot.deletedTemplates)
        val mergedAnalyses = mergeDeletions(deletions(db, "deleted_analysis_runs"), snapshot.deletedAnalysisRuns).toMutableList()
        require(mergedAnalyses.size <= 100_000) { "分析任务删除标记超过上限" }
        val mergedFeedbackDeletions = mergeDeletions(deletions(db, "deleted_claim_feedback"), snapshot.deletedClaimFeedback)
        require(mergedFeedbackDeletions.size <= ClaimFeedbackValidation.MAX_DELETIONS) { "反馈删除标记超过保存上限" }
        val deletedFeedbackIds = mergedFeedbackDeletions.map { it.id }.toSet()
        // Present user authority wins even over a backup with a higher revision or timestamp.
        // Feedback can outlive the original task; restoring must not erase that independent text.
        val feedback = (snapshot.claimFeedback.associateBy { it.id } + allClaimFeedback(db).associateBy { it.id })
            .values.filterNot { it.id in deletedFeedbackIds }.sortedBy { it.id }
        ClaimFeedbackValidation.validateCollection(feedback)
        val recordDeletionsById = mergedRecords.associateBy { it.id }
        val templateDeletionsById = mergedTemplates.associateBy { it.id }
        val recordTombstones = recordDeletionsById.keys
        val templateTombstones = templateDeletionsById.keys
        val records = snapshot.records.filterNot { it.id in recordTombstones }
        val templates = snapshot.templates.filterNot { it.id in templateTombstones }
        if (records.any { it.draft.template?.id in templateTombstones }) {
            throw InvalidBackupException("目标已删除的模板仍被待恢复记录引用，不能恢复该冲突备份")
        }
        val requiredAttachmentIds = records.flatMap { it.draft.attachmentIds }.toSet()
        val attachments = snapshot.attachments.filter { it.id in requiredAttachmentIds }
        // A historical analysis may copy text from any source. Additional local deletions suppress
        // all imported analyses until a dependency-specific deletion index is available.
        val importedRecordDeletions = snapshot.deletedRecords.associateBy { it.id }
        val importedTemplateDeletions = snapshot.deletedTemplates.associateBy { it.id }
        val hasAdditionalDeletions = mergedRecords.any { importedRecordDeletions[it.id] != it } ||
            mergedTemplates.any { importedTemplateDeletions[it.id] != it }
        val deletedAnalysisIds = mergedAnalyses.map { it.id }.toMutableSet()
        // Local user action chains win. Machine text based on a replaced/deleted action body cannot return.
        snapshot.analysisRuns.filter { run ->
            val state = AnalysisRunCodec.decode(run.payload)
            try { ActionEvidenceProtocol.validateSources(state.actionPlan, state.calls.flatMap { it.actionEvidence }, actionRevisions, state.request.scope); false }
            catch (_: IllegalArgumentException) { true }
        }.filterNot { it.id in deletedAnalysisIds }.forEach { run ->
            require(mergedAnalyses.size < 100_000)
            mergedAnalyses += Deletion(run.id, run.version, clock.instant())
            deletedAnalysisIds += run.id
        }
        val maintenanceDeleted = ProfileMaintenanceDeletion.affectedRuns(snapshot.analysisRuns,
            snapshot.profileRevisions + ProfileStore.allRevisions(db), snapshot.knowledgeRevisions + allKnowledgeRevisions(db),
            snapshot.claimFeedback + allClaimFeedback(db), deletedProfileIds, deletedFeedbackIds,
            (snapshot.deletedKnowledge + deletions(db, "deleted_knowledge")).map { it.id }.toSet(), deletedAnalysisIds)
        snapshot.analysisRuns.filter { it.id in maintenanceDeleted }.forEach { run ->
            require(mergedAnalyses.size < 100_000) { "维护依赖清理超过任务删除上限" }
            mergedAnalyses += Deletion(run.id, run.version, clock.instant())
            deletedAnalysisIds += run.id
        }
        val feedbackByRun = feedback.groupBy { it.runId }
        var capacityPurgedAnalysisRuns = 0
        val analysisCandidates = if (hasAdditionalDeletions) emptyList() else snapshot.analysisRuns.filterNot { it.id in deletedAnalysisIds }
        val originalAnalyses = analysisCandidates.associateBy { it.id }
        fun discardForCapacity(run: StoredAnalysisRun) {
            require(mergedAnalyses.size < 100_000) { "分析任务删除标记达到上限" }
            val original = originalAnalyses.getValue(run.id)
            val lastVersion = maxOf(original.version, readAnalysisRun(db, run.id)?.version ?: 0)
            mergedAnalyses += Deletion(run.id, lastVersion, clock.instant())
            deletedAnalysisIds += run.id
            capacityPurgedAnalysisRuns++
        }
        val redactedAnalyses = analysisCandidates
            .filter { run ->
                // A valid older backup can predate the claim a user has since corrected. Keep
                // the independent feedback and omit only that incompatible historical task.
                val related = feedbackByRun[run.id].orEmpty()
                if (related.isEmpty()) true else {
                    val state = AnalysisRunCodec.decode(run.payload)
                    related.all { ClaimFeedbackValidation.matchesTarget(it, run, state) }
                }
            }
            .mapNotNull { run ->
                try { AnalysisRunCodec.redactDeletedFeedback(run, deletedFeedbackIds, clock.instant()) }
                catch (_: AnalysisRedactionCapacityException) { discardForCapacity(run); null }
            }
        // Evaluate aggregate capacity only after all redactions and compatibility exclusions.
        // Later removals may free enough space; input order must not cause needless tombstones.
        var analysisBytes = redactedAnalyses.sumOf(::analysisStorageBytes)
        if (analysisBytes > 16L * 1024 * 1024) {
            redactedAnalyses.map { it to analysisStorageBytes(it) - analysisStorageBytes(originalAnalyses.getValue(it.id)) }
                .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<StoredAnalysisRun, Long>> { it.second }.thenBy { it.first.id })
                .forEach { (run, _) ->
                    if (analysisBytes > 16L * 1024 * 1024) {
                        discardForCapacity(run)
                        analysisBytes -= analysisStorageBytes(run)
                    }
                }
        }
        require(analysisBytes <= 16L * 1024 * 1024) { "分析任务超过本地保存上限" }
        var analyses = redactedAnalyses.filterNot { it.id in deletedAnalysisIds }
        // Present heads are never rolled back by an older backup. Their entire immutable chain
        // wins; if replacement sources cannot support it, retain a deletion fence instead.
        ensureKnowledgeBounds(db)
        val localKnowledge = allKnowledgeRevisions(db).groupBy { it.id }
        val incomingKnowledge = snapshot.knowledgeRevisions.groupBy { it.id }
        val mergedKnowledgeDeletions = mergeDeletions(deletions(db, "deleted_knowledge"), snapshot.deletedKnowledge).toMutableList()
        val knownKnowledgeDeletions = mergedKnowledgeDeletions.map { it.id }.toSet()
        val candidateKnowledge = incomingKnowledge + localKnowledge
        val retainedKnowledge = mutableListOf<StoredKnowledgeRevision>()
        val recordsByRevision = records.associateBy { it.id to it.revision }
        candidateKnowledge.toSortedMap().forEach { (id, revisions) ->
            if (id !in knownKnowledgeDeletions) {
                val forbidden = hasAdditionalDeletions || revisions.any { value ->
                    value.runId in deletedAnalysisIds || KnowledgeValidation.feedbackDependencies(value).any { dependency ->
                        AnalysisRunCodec.feedbackId(value.runId, dependency.claim) in deletedFeedbackIds
                    }
                }
                val sourcesRetained = !forbidden && revisions.all { value ->
                    try {
                        KnowledgeValidation.validateEvidence(value, recordsByRevision)
                        ActionEvidenceProtocol.validateSources(value.actionPlan, value.actionEvidence, actionRevisions, value.scope)
                        true
                    } catch (_: IllegalArgumentException) { false }
                }
                if (sourcesRetained) retainedKnowledge += revisions
                else mergedKnowledgeDeletions += Deletion(id, revisions.maxOf { it.revision }, clock.instant())
            }
        }
        // Capacity redaction and source validation can introduce further deletions after the
        // first pass. Close the cross-task graph over those final fences as well.
        val retainedProfileIdentities = profileRevisions.map { it.id to it.revision }.toSet()
        val missingReferenceRuns = analyses.filter { run ->
            AnalysisRunCodec.decode(run.payload).profileMaintenancePlans.flatMap { it.references }.any { ref ->
                ref.kind == ProfileMaintenanceReferenceKind.PROFILE && (ref.id to ref.version) !in retainedProfileIdentities
            }
        }.map { it.id }.toSet()
        analyses.filter { it.id in missingReferenceRuns }.forEach { run ->
            require(mergedAnalyses.size < 100_000) { "维护依赖缺口清理超过任务删除上限" }
            mergedAnalyses += Deletion(run.id, run.version, clock.instant())
            deletedAnalysisIds += run.id
        }
        val finalMaintenanceDeletions = ProfileMaintenanceDeletion.affectedRuns(analyses,
            snapshot.profileRevisions + ProfileStore.allRevisions(db), candidateKnowledge.values.flatten(),
            snapshot.claimFeedback + allClaimFeedback(db), deletedProfileIds, deletedFeedbackIds,
            mergedKnowledgeDeletions.map { it.id }.toSet(), deletedAnalysisIds) + missingReferenceRuns
        analyses.filter { it.id in finalMaintenanceDeletions && it.id !in deletedAnalysisIds }.forEach { run ->
            require(mergedAnalyses.size < 100_000) { "维护依赖清理超过任务删除上限" }
            mergedAnalyses += Deletion(run.id, run.version, clock.instant())
            deletedAnalysisIds += run.id
        }
        analyses = analyses.filterNot { it.id in finalMaintenanceDeletions }
        retainedKnowledge.filter { it.runId in finalMaintenanceDeletions }.groupBy { it.id }.forEach { (id, revisions) ->
            mergedKnowledgeDeletions += Deletion(id, revisions.maxOf { it.revision }, clock.instant())
        }
        retainedKnowledge.removeAll { it.runId in finalMaintenanceDeletions }
        require(mergedKnowledgeDeletions.size <= KnowledgeValidation.MAX_DELETIONS) { "知识删除标记超过上限" }
        val retainedKnowledgeHeads = retainedKnowledge.groupBy { it.id }.map { (id, versions) -> KnowledgeHead(id, versions.maxOf { it.revision }) }
        KnowledgeValidation.validateCollection(retainedKnowledge, retainedKnowledgeHeads)
        SnapshotValidation.validateProfileReferences(profileRevisions, retainedKnowledge)
        val createdFiles = mutableListOf<File>()
        var committed = false
        try {
            // Validate even suppressed source bytes, so corruption is not mistaken for a valid backup.
            snapshot.attachments.forEach { verifyAttachment(File(attachmentSource, "${it.id}.blob"), it) }
            attachments.forEach { attachment ->
                val destination = attachmentFile(attachment.id)
                if (destination.exists()) {
                    // An identity collision must never modify an existing live file before commit.
                    verifyAttachment(destination, attachment)
                } else {
                    copyVerifiedAttachment(File(attachmentSource, "${attachment.id}.blob"), destination, attachment)
                    createdFiles += destination
                }
            }
            val result = transaction(db) {
                if (storeState(db).second != expectedGeneration) throw StaleSnapshotException("目标资料已变化，请重新核对恢复")
                if (expectedAnalysisGeneration != null && analysisGeneration(db) != expectedAnalysisGeneration) throw StaleSnapshotException("目标分析已变化，请重新核对恢复")
                val oldAttachmentIds = strings(db, "SELECT id FROM attachments").toSet()
                db.delete("knowledge_heads", null, null)
                db.delete("knowledge_revisions", null, null)
                db.delete("deleted_knowledge", null, null)
                db.delete("records", null, null)
                db.delete("templates", null, null)
                db.delete("attachments", null, null)
                db.delete("change_events", null, null)
                db.delete("deleted_records", null, null)
                db.delete("deleted_templates", null, null)
                db.delete("analysis_runs", null, null)
                db.delete("deleted_analysis_runs", null, null)
                db.delete("claim_feedback", null, null)
                db.delete("deleted_claim_feedback", null, null)
                // Some queued files may be deliberately restored from a later valid snapshot.
                attachments.forEach { db.delete("attachment_garbage", "id = ?", arrayOf(it.id)) }
                (oldAttachmentIds - requiredAttachmentIds).forEach { id ->
                    db.insertWithOnConflict("attachment_garbage", null, values("id" to id), SQLiteDatabase.CONFLICT_IGNORE)
                }
                templates.groupBy { it.id }.forEach { (id, versions) ->
                    val latest = versions.maxBy { it.version }
                    db.insertOrThrow("templates", null, values("id" to id, "current_version" to latest.version, "archived" to latest.archived))
                    versions.sortedBy { it.version }.forEach { insertTemplateVersion(db, it) }
                }
                attachments.forEach { attachment ->
                    db.insertOrThrow("attachments", null, values("id" to attachment.id, "display_name" to attachment.displayName,
                        "mime_type" to attachment.mimeType, "size_bytes" to attachment.sizeBytes, "sha256" to attachment.sha256))
                }
                records.groupBy { it.id }.forEach { (id, revisions) ->
                    val latest = revisions.maxBy { it.revision }
                    db.insertOrThrow("records", null, values("id" to id, "current_revision" to latest.revision, "created_at" to timestamp(latest.createdAt)))
                    revisions.sortedBy { it.revision }.forEach { insertRevision(db, it) }
                }
                mergedRecords.forEach { insertDeletion(db, "deleted_records", it) }
                mergedTemplates.forEach { insertDeletion(db, "deleted_templates", it) }
                val retainedChanges = snapshot.changes.filter { change ->
                    when {
                        change.kind.startsWith("RECORD_") && change.entityId in recordTombstones ->
                            change.kind == "RECORD_DELETED" && recordDeletionsById[change.entityId]!!.let {
                                it.lastRevision + 1 == change.revision && it.deletedAt == change.createdAt
                            }
                        change.kind.startsWith("TEMPLATE_") && change.entityId in templateTombstones ->
                            change.kind == "TEMPLATE_DELETED" && templateDeletionsById[change.entityId]!!.let {
                                it.lastRevision == change.revision && it.deletedAt == change.createdAt
                            }
                        else -> true
                    }
                }
                retainedChanges.forEach { change ->
                    db.insertOrThrow("change_events", null, values("id" to change.id, "entity_id" to change.entityId,
                        "revision" to change.revision, "kind" to change.kind, "created_at" to timestamp(change.createdAt)))
                }
                val retainedRecordDeletions = retainedChanges.filter { it.kind == "RECORD_DELETED" }.map { it.entityId }.toSet()
                val retainedTemplateDeletions = retainedChanges.filter { it.kind == "TEMPLATE_DELETED" }.map { it.entityId }.toSet()
                mergedRecords.filterNot { it.id in retainedRecordDeletions }
                    .forEach { event(db, it.id, it.lastRevision + 1, "RECORD_DELETED", it.deletedAt) }
                mergedTemplates.filterNot { it.id in retainedTemplateDeletions }
                    .forEach { event(db, it.id, it.lastRevision, "TEMPLATE_DELETED", it.deletedAt) }
                // Keep imported source stamps unchanged: restored results are historical until a
                // fresh run validates against this store, even when restoring on the same device.
                analyses.forEach { insertAnalysisRun(db, it) }
                mergedAnalyses.forEach { insertDeletion(db, "deleted_analysis_runs", it) }
                feedback.forEach { insertClaimFeedback(db, it) }
                mergedFeedbackDeletions.forEach { insertDeletion(db, "deleted_claim_feedback", it) }
                retainedKnowledge.forEach { insertKnowledgeRevision(db, it) }
                retainedKnowledgeHeads.forEach { db.insertOrThrow("knowledge_heads", null, values("id" to it.id, "revision" to it.revision)) }
                mergedKnowledgeDeletions.forEach { insertDeletion(db, "deleted_knowledge", it) }
                ActionStore.replace(db, actionRevisions, actionHeads, mergedActionDeletions)
                ProfileStore.replace(db, profileRevisions, profileHeads, mergedProfileDeletions)
                restoredPreferences?.let { DashboardPreferencesStore.write(db, it) }
                val restoreEpoch = localRestoreEpoch()
                require(restoreEpoch < Long.MAX_VALUE) { "恢复代数达到上限" }
                db.execSQL("UPDATE store_state SET restore_epoch = restore_epoch + 1 WHERE singleton = 1")
                // A same-store backup may carry a source generation ahead of the target. Move
                // beyond every imported stamp, not merely +1, so no restored task becomes current
                // by numeric coincidence. Even empty→empty restore invalidates publication.
                val targetStore = storeState(db)
                val importedGeneration = (analyses.map { it.source } + feedback.map { it.source } + retainedKnowledge.map { it.source })
                    .filter { it.storeId == targetStore.first }.maxOfOrNull { it.generation } ?: 0L
                val generationFloor = maxOf(targetStore.second, snapshot.generation, importedGeneration)
                require(generationFloor < Long.MAX_VALUE) { "来源代数达到上限，无法安全恢复" }
                db.execSQL("UPDATE store_state SET generation = ?, analysis_generation = analysis_generation + 1 WHERE singleton = 1", arrayOf(generationFloor + 1))
                RestoreResult(records.map { it.id }.distinct().size, records.size, templates.map { it.id }.distinct().size,
                    attachments.size, snapshot.records.map { it.id }.distinct().count { it in recordTombstones }, storeState(db).second,
                    capacityPurgedAnalysisRuns)
            }
            committed = true
            // Cleanup is recoverable after the authoritative commit; failure here must not report
            // that the already committed restore was rolled back.
            try { collectGarbage(db) } catch (_: Exception) { /* durable garbage queue retries on open */ }
            result
        } finally {
            if (!committed) createdFiles.forEach { it.delete() }
        }
    }

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
        SnapshotValidation.recordDraft(draft)
        if (draft.template != null) {
            val template = requireNotNull(readTemplate(db, draft.template.id, draft.template.version)) { "模板版本不存在" }
            require(draft.answers.keys.all { key -> template.draft.questions.any { it.id == key } }) { "答案包含模板之外的问题" }
        }
        draft.attachmentIds.forEach { id ->
            val attachment = requireNotNull(readAttachment(db, id)) { "附件不存在" }
            require(attachmentFile(id).isFile && attachmentFile(id).length() == attachment.sizeBytes) { "附件文件缺失或不完整" }
        }
    }

    private fun validateTemplate(draft: TemplateDraft) {
        SnapshotValidation.templateDraft(draft)
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

    private fun validateAnalysisQuery(query: AnalysisQuery) {
        require(query.from.year in 1..9999 && query.through.year in 1..9999 && query.from <= query.through) { "分析日期范围无效" }
        ZoneId.of(query.zoneId)
        query.recordIds?.let { ids ->
            require(ids.size in 1..100) { "精确记录范围须包含 1 至 100 个 ID" }
            ids.forEach(::validateId)
        }
    }

    private fun analysisScope(query: AnalysisQuery): Pair<String, List<String>> {
        val where = mutableListOf("r.revision = h.current_revision", "(r.start_date IS NULL OR (r.end_date >= ? AND r.start_date <= ?))")
        val args = mutableListOf(query.from.toString(), query.through.toString())
        if (!query.includeArchived) where += "r.archived = 0"
        query.recordIds?.let { ids ->
            where += "h.id IN (${ids.joinToString(",") { "?" }})"
            args += ids.sorted()
        }
        return where.joinToString(" AND ") to args
    }

    private fun requireAnalysisSource(db: SQLiteDatabase, source: SourceStamp) {
        val state = storeState(db)
        if (source.storeId != state.first || source.generation != state.second) {
            throw StaleSnapshotException("分析来源已变化，须重新建立任务")
        }
    }

    private fun scopedRevision(db: SQLiteDatabase, query: AnalysisQuery, id: String): Int {
        val (where, args) = analysisScope(query)
        return db.rawQuery("SELECT r.revision FROM records h JOIN record_revisions r ON r.record_id = h.id WHERE $where AND h.id = ?", (args + id).toTypedArray()).use {
            require(it.moveToFirst()) { "记录不存在或不在分析范围内" }
            it.getInt(0)
        }
    }

    private fun sourceDescriptor(db: SQLiteDatabase, cursor: Cursor, query: AnalysisQuery): SourceDescriptor {
        val id = cursor.getString(0)
        val revision = cursor.getInt(1)
        val start = if (cursor.isNull(2)) null else LocalDate.parse(cursor.getString(2))
        val end = if (cursor.isNull(3)) null else LocalDate.parse(cursor.getString(3))
        val topics = strings(db, "SELECT topic FROM record_topics WHERE record_id = ? AND revision = ? ORDER BY topic", id, revision.toString()).toSet()
        val timeClass = when {
            start == null -> SourceTimeClass.UNKNOWN
            start < query.from || end!! > query.through -> SourceTimeClass.OVERLAP
            else -> SourceTimeClass.WITHIN
        }
        return SourceDescriptor(id, revision, Occurrence(start, end, cursor.getString(4)), cursor.getInt(5) == 1, topics, timeClass)
    }

    private fun validateAnalysisRun(run: StoredAnalysisRun) {
        validateId(run.id)
        validateId(run.source.storeId)
        require(run.version > 0 && run.source.generation >= 0 && run.updatedAt >= run.createdAt) { "分析任务版本或时间无效" }
        require(run.payload.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "单个分析任务超过 2 MiB" }
        AnalysisRunCodec.validateStored(run)
    }

    private fun analysisStorageBytes(run: StoredAnalysisRun): Long =
        run.payload.toByteArray(Charsets.UTF_8).size.toLong() + AnalysisRunCodec.reservedResponseBytes(run)

    private fun analysisRunValues(run: StoredAnalysisRun) = values(
        "id" to run.id, "version" to run.version, "source_store_id" to run.source.storeId, "source_generation" to run.source.generation,
        "created_at" to timestamp(run.createdAt), "updated_at" to timestamp(run.updatedAt), "expires_at" to run.expiresAt?.let(::timestamp), "payload" to run.payload,
        "reserved_bytes" to AnalysisRunCodec.reservedResponseBytes(run),
    )

    private fun insertAnalysisRun(db: SQLiteDatabase, run: StoredAnalysisRun) {
        db.insertOrThrow("analysis_runs", null, analysisRunValues(run))
    }

    private fun analysisRunFromCursor(db: SQLiteDatabase, cursor: Cursor): StoredAnalysisRun {
        val id = cursor.string("id")
        val bytes = cursor.getLong(cursor.getColumnIndexOrThrow("payload_bytes"))
        require(bytes in 1..2L * 1024 * 1024) { "保存的分析任务超过大小上限" }
        val points = cursor.int("payload_points")
        // Android cursor windows have their own byte ceiling. Never select a possible 2 MiB
        // payload and its metadata into one row; reconstruct it from bounded SQLite slices.
        val payload = buildString {
            var offset = 0
            while (offset < points) {
                val fragment = db.rawQuery("SELECT substr(payload, ?, ?) FROM analysis_runs WHERE id = ?",
                    arrayOf((offset + 1).toString(), "131072", id)).use { check(it.moveToFirst()); it.getString(0) }
                check(fragment.isNotEmpty()) { "分析任务内容不完整" }
                append(fragment)
                offset += fragment.codePointCount(0, fragment.length)
            }
        }
        return StoredAnalysisRun(id, cursor.int("version"), SourceStamp(cursor.string("source_store_id"), cursor.getLong(cursor.getColumnIndexOrThrow("source_generation"))),
            Instant.parse(cursor.string("created_at")), Instant.parse(cursor.string("updated_at")), cursor.nullableString("expires_at")?.let(Instant::parse), payload)
    }

    private fun readAnalysisRun(db: SQLiteDatabase, id: String): StoredAnalysisRun? =
        db.rawQuery("SELECT $ANALYSIS_METADATA_COLUMNS FROM analysis_runs WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) analysisRunFromCursor(db, it) else null }

    private fun allAnalysisRuns(db: SQLiteDatabase): List<StoredAnalysisRun> =
        db.rawQuery("SELECT $ANALYSIS_METADATA_COLUMNS FROM analysis_runs ORDER BY created_at, id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(analysisRunFromCursor(db, cursor)) }
        }

    private fun readKnowledgeRevision(db: SQLiteDatabase, id: String, revision: Int?): StoredKnowledgeRevision? {
        val sql = if (revision == null) "SELECT r.payload FROM knowledge_revisions r JOIN knowledge_heads h ON h.id = r.id AND h.revision = r.revision WHERE h.id = ?"
            else "SELECT payload FROM knowledge_revisions WHERE id = ? AND revision = ?"
        val args = if (revision == null) arrayOf(id) else arrayOf(id, revision.toString())
        return db.rawQuery(sql, args).use { if (it.moveToFirst()) KnowledgeCodec.decode(it.getString(0)) else null }
    }

    private fun allKnowledgeRevisions(db: SQLiteDatabase): List<StoredKnowledgeRevision> =
        db.rawQuery("SELECT payload FROM knowledge_revisions ORDER BY id, revision", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(KnowledgeCodec.decode(cursor.getString(0))) }
        }

    private fun allKnowledgeHeads(db: SQLiteDatabase): List<KnowledgeHead> =
        db.rawQuery("SELECT id, revision FROM knowledge_heads ORDER BY id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(KnowledgeHead(cursor.getString(0), cursor.getInt(1))) }
        }

    private fun insertKnowledgeRevision(db: SQLiteDatabase, value: StoredKnowledgeRevision) {
        val payload = KnowledgeCodec.encode(value)
        db.insertOrThrow("knowledge_revisions", null, values("id" to value.id, "revision" to value.revision,
            "run_id" to value.runId, "feedback_id" to AnalysisRunCodec.feedbackId(value.runId, value.claimRef),
            "payload" to payload, "encoded_bytes" to payload.toByteArray(Charsets.UTF_8).size))
    }

    private fun sameKnowledgePublication(a: StoredKnowledgeRevision, b: StoredKnowledgeRevision): Boolean =
        a == b.copy(revision = a.revision, createdAt = a.createdAt, publishedAt = a.publishedAt)

    /** Pure identity references let us erase complete affected tasks without retaining or
     * searching deleted prose. Follow the closure because another task may quote their knowledge. */
    private fun purgeProfileMaintenanceRuns(db: SQLiteDatabase, deletedProfileId: String? = null,
        deletedFeedbackId: String? = null, deletedKnowledgeId: String? = null, deletedRunId: String? = null): Set<String> {
        val runs = db.rawQuery("SELECT $ANALYSIS_METADATA_COLUMNS FROM analysis_runs ORDER BY id", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(analysisRunFromCursor(db, cursor)) }
        }
        val affected = ProfileMaintenanceDeletion.affectedRuns(runs, ProfileStore.allRevisions(db),
            allKnowledgeRevisions(db), allClaimFeedback(db), listOfNotNull(deletedProfileId).toSet(),
            listOfNotNull(deletedFeedbackId).toSet(), listOfNotNull(deletedKnowledgeId).toSet(), listOfNotNull(deletedRunId).toSet())
        val purged = runs.filter { it.id in affected }
        require(count(db, "SELECT COUNT(*) FROM deleted_analysis_runs", emptyList()) + purged.size <= 100_000) { "分析任务删除标记达到上限" }
        purged.forEach { run ->
            purgeKnowledge(db, strings(db, "SELECT DISTINCT id FROM knowledge_revisions WHERE run_id = ?", run.id))
            db.delete("analysis_runs", "id = ?", arrayOf(run.id))
            insertDeletion(db, "deleted_analysis_runs", Deletion(run.id, run.version, clock.instant()))
        }
        return purged.map { it.id }.toSet()
    }

    private fun feedbackDependenciesCurrent(db: SQLiteDatabase, run: StoredAnalysisRun, claim: ClaimRef): Boolean {
        val dependencies = try {
            ReductionGraph.feedbackDependencies(AnalysisRunCodec.decode(run.payload), claim)
        } catch (_: IllegalArgumentException) { return false }
        return dependencies.all { dependency ->
            val id = AnalysisRunCodec.feedbackId(run.id, dependency.claim)
            val response = readClaimFeedback(db, id)
            !exists(db, "deleted_claim_feedback", "id = ?", id) && (response?.version ?: 0) == dependency.version &&
                (response == null || response.source == run.source && response.decision == ClaimFeedbackDecision.CONFIRMED)
        }
    }

    private fun purgeKnowledge(db: SQLiteDatabase, ids: List<String>) {
        val unique = ids.distinct()
        require(count(db, "SELECT COUNT(*) FROM deleted_knowledge", emptyList()) + unique.size <= KnowledgeValidation.MAX_DELETIONS) { "知识删除标记达到上限" }
        unique.forEach { id ->
            val head = requireNotNull(readKnowledgeRevision(db, id, null)) { "知识当前指针缺失" }
            db.delete("knowledge_heads", "id = ?", arrayOf(id))
            db.delete("knowledge_revisions", "id = ?", arrayOf(id))
            insertDeletion(db, "deleted_knowledge", Deletion(id, head.revision, clock.instant()))
        }
    }

    private fun ensureKnowledgeBounds(db: SQLiteDatabase) {
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(encoded_bytes), 0), COALESCE(MAX(length(CAST(payload AS BLOB))), 0) FROM knowledge_revisions", null).use {
            it.moveToFirst()
            require(it.getInt(0) <= KnowledgeValidation.MAX_REVISIONS && it.getLong(1) <= KnowledgeValidation.MAX_BYTES && it.getLong(2) <= KnowledgeValidation.MAX_REVISION_BYTES) { "知识修订超过保存上限" }
        }
    }

    private fun feedbackValues(value: StoredClaimFeedback) = values(
        "id" to value.id, "version" to value.version, "run_id" to value.runId, "batch_key" to value.batchKey,
        "claim_index" to value.claimIndex, "claim_sha256" to value.claimSha256,
        "source_store_id" to value.source.storeId, "source_generation" to value.source.generation,
        "decision" to value.decision.name, "user_text" to value.userText,
        "created_at" to timestamp(value.createdAt), "updated_at" to timestamp(value.updatedAt),
        "encoded_bytes" to ClaimFeedbackValidation.encodedBytes(value),
    )

    private fun feedbackFromCursor(cursor: Cursor): StoredClaimFeedback = StoredClaimFeedback(
        cursor.string("id"), cursor.int("version"), cursor.string("run_id"), cursor.string("batch_key"),
        cursor.int("claim_index"), cursor.string("claim_sha256"),
        SourceStamp(cursor.string("source_store_id"), cursor.getLong(cursor.getColumnIndexOrThrow("source_generation"))),
        ClaimFeedbackDecision.valueOf(cursor.string("decision")), cursor.nullableString("user_text"),
        Instant.parse(cursor.string("created_at")), Instant.parse(cursor.string("updated_at")),
    ).also(ClaimFeedbackValidation::validate)

    private fun readClaimFeedback(db: SQLiteDatabase, id: String): StoredClaimFeedback? =
        db.rawQuery("SELECT * FROM claim_feedback WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) feedbackFromCursor(it) else null }

    private fun allClaimFeedback(db: SQLiteDatabase): List<StoredClaimFeedback> {
        ensureFeedbackBounds(db)
        return db.rawQuery("SELECT * FROM claim_feedback ORDER BY created_at, id", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(feedbackFromCursor(cursor)) } }
    }

    private fun insertClaimFeedback(db: SQLiteDatabase, value: StoredClaimFeedback) {
        db.insertOrThrow("claim_feedback", null, feedbackValues(value))
    }

    private fun bumpFeedbackRun(db: SQLiteDatabase, run: StoredAnalysisRun?) {
        if (run == null) return
        require(run.version < Int.MAX_VALUE) { "分析任务版本达到上限" }
        db.update("analysis_runs", values("version" to run.version + 1,
            "updated_at" to timestamp(maxOf(clock.instant(), run.updatedAt))), "id = ?", arrayOf(run.id))
    }

    private fun ensureFeedbackBounds(db: SQLiteDatabase) {
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(encoded_bytes), 0), COALESCE(MAX(length(user_text)), 0) FROM claim_feedback", null).use {
            it.moveToFirst()
            require(it.getInt(0) <= ClaimFeedbackValidation.MAX_ROWS && it.getLong(1) <= ClaimFeedbackValidation.MAX_BYTES && it.getInt(2) <= 2_000) { "反馈超过本地保存上限" }
        }
    }

    private fun analysisGeneration(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT analysis_generation FROM store_state WHERE singleton = 1", null).use { check(it.moveToFirst()); it.getLong(0) }

    private fun storeState(db: SQLiteDatabase): Pair<String, Long> = db.rawQuery("SELECT store_id, generation FROM store_state WHERE singleton = 1", null).use {
        check(it.moveToFirst()) { "主存身份缺失" }
        it.getString(0) to it.getLong(1)
    }

    /** Check row and byte budgets in SQLite before constructing a complete in-memory snapshot. */
    private fun ensureCaptureBounds(db: SQLiteDatabase) {
        ensureFeedbackBounds(db)
        ensureKnowledgeBounds(db)
        ActionStore.ensureBounds(db)
        ProfileStore.ensureBounds(db)
        val rowLimits = mapOf("record_revisions" to 100_000, "template_versions" to 10_000,
            "deleted_records" to 100_000, "deleted_templates" to 10_000, "change_events" to 200_000, "analysis_runs" to 100,
            "deleted_analysis_runs" to 100_000, "claim_feedback" to ClaimFeedbackValidation.MAX_ROWS,
            "deleted_claim_feedback" to ClaimFeedbackValidation.MAX_DELETIONS, "knowledge_revisions" to KnowledgeValidation.MAX_REVISIONS,
            "knowledge_heads" to KnowledgeValidation.MAX_REVISIONS, "deleted_knowledge" to KnowledgeValidation.MAX_DELETIONS,
            "action_revisions" to ActionValidation.MAX_REVISIONS, "action_heads" to ActionValidation.MAX_REVISIONS,
            "deleted_actions" to ActionValidation.MAX_DELETIONS,
            "profile_revisions" to ProfileValidation.MAX_REVISIONS, "profile_heads" to ProfileValidation.MAX_REVISIONS,
            "deleted_profiles" to ProfileValidation.MAX_DELETIONS)
        rowLimits.forEach { (table, limit) ->
            require(count(db, "SELECT COUNT(*) FROM $table", emptyList()) <= limit) { "资料超过当前快照数量上限：$table" }
        }
        val textColumns = mapOf(
            "record_revisions" to listOf("record_id", "body", "zone_id", "template_id"),
            "record_answers" to listOf("record_id", "question_id", "answer"),
            "record_topics" to listOf("record_id", "topic"),
            "record_attachments" to listOf("record_id", "attachment_id"),
            "template_versions" to listOf("template_id", "name", "description", "source_markdown"),
            "template_questions" to listOf("template_id", "question_id", "prompt", "hint", "intent"),
            "deleted_records" to listOf("id"), "deleted_templates" to listOf("id"),
            "change_events" to listOf("entity_id", "kind"),
            "analysis_runs" to listOf("id", "source_store_id", "payload"),
            "deleted_analysis_runs" to listOf("id"),
            "claim_feedback" to listOf("id", "run_id", "batch_key", "claim_sha256", "source_store_id", "decision", "user_text"),
            "deleted_claim_feedback" to listOf("id"),
            "knowledge_revisions" to listOf("id", "run_id", "feedback_id", "payload"), "knowledge_heads" to listOf("id"), "deleted_knowledge" to listOf("id"),
            "profile_revisions" to listOf("id", "operation_id", "payload"),
            "profile_heads" to listOf("id", "knowledge_id", "dimension", "layer"), "deleted_profiles" to listOf("id"),
        )
        var textBytes = 0L
        textColumns.forEach { (table, columns) ->
            val expression = columns.joinToString(" + ") { "COALESCE(LENGTH(CAST($it AS BLOB)), 0)" }
            textBytes += db.rawQuery("SELECT COALESCE(SUM($expression), 0) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }
            require(textBytes <= 64L * 1024 * 1024) { "资料超过当前快照 64 MiB 文本上限" }
        }
        db.rawQuery("""SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM attachments a
            WHERE EXISTS(SELECT 1 FROM record_attachments r WHERE r.attachment_id = a.id)""", null).use {
            it.moveToFirst()
            require(it.getLong(0) <= 10_000 && it.getLong(1) <= 2L * 1024 * 1024 * 1024) { "资料超过当前快照附件上限" }
        }
    }

    private fun deletions(db: SQLiteDatabase, table: String): List<Deletion> = db.rawQuery("SELECT id, last_revision, deleted_at FROM $table ORDER BY id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Deletion(cursor.getString(0), cursor.getInt(1), Instant.parse(cursor.getString(2)))) }
    }

    private fun allChanges(db: SQLiteDatabase): List<ChangeEvent> = db.rawQuery("SELECT id, entity_id, revision, kind, created_at FROM change_events ORDER BY id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(ChangeEvent(cursor.getLong(0), cursor.getString(1), cursor.getInt(2), cursor.getString(3), Instant.parse(cursor.getString(4)))) }
    }

    private fun mergeDeletions(first: List<Deletion>, second: List<Deletion>): List<Deletion> = (first + second).groupBy { it.id }.map { (id, versions) ->
        Deletion(id, versions.maxOf { it.lastRevision }, versions.maxOf { it.deletedAt })
    }.sortedBy { it.id }

    private fun insertDeletion(db: SQLiteDatabase, table: String, deletion: Deletion) {
        db.insertOrThrow(table, null, values("id" to deletion.id, "last_revision" to deletion.lastRevision, "deleted_at" to timestamp(deletion.deletedAt)))
    }

    private fun insertTemplateVersion(db: SQLiteDatabase, template: TemplateVersion) {
        val draft = template.draft
        db.insertOrThrow("template_versions", null, values("template_id" to template.id, "version" to template.version,
            "name" to draft.name, "cadence" to draft.cadence.name, "description" to draft.description,
            "source_markdown" to draft.sourceMarkdown, "created_at" to timestamp(template.createdAt)))
        draft.questions.forEachIndexed { index, question ->
            db.insertOrThrow("template_questions", null, values("template_id" to template.id, "version" to template.version,
                "question_id" to question.id, "ordinal" to index, "prompt" to question.prompt, "hint" to question.hint, "intent" to question.intent))
        }
    }

    private fun requireExternalPath(file: File) {
        val candidate = file.canonicalFile.toPath()
        val databaseDirectory = File(databasePath).parentFile!!.canonicalFile.toPath()
        val managedAttachments = File(appContext.filesDir, "attachments").canonicalFile.toPath()
        require(!candidate.startsWith(databaseDirectory) && !candidate.startsWith(managedAttachments)) { "传输暂存和目标不能位于受管数据库或附件目录" }
    }

    private fun verifyAttachment(file: File, attachment: Attachment) {
        if (!file.isFile || Files.isSymbolicLink(file.toPath()) || file.length() != attachment.sizeBytes) {
            throw InvalidBackupException("附件 ${attachment.id} 缺失或长度不符")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                total += size
                if (total > attachment.sizeBytes) throw InvalidBackupException("附件读取长度变化")
                digest.update(buffer, 0, size)
            }
            if (total != attachment.sizeBytes || digest.digest().hex() != attachment.sha256) throw InvalidBackupException("附件 ${attachment.id} 校验失败")
        }
    }

    private fun copyVerifiedAttachment(source: File, destination: File, attachment: Attachment) {
        if (!source.isFile || Files.isSymbolicLink(source.toPath()) || source.length() != attachment.sizeBytes) throw InvalidBackupException("附件 ${attachment.id} 缺失或长度不符")
        require(destination.createNewFile()) { "附件暂存目标已存在" }
        var successful = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(destination).use { output -> source.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    total += size
                    if (total > attachment.sizeBytes) throw InvalidBackupException("附件读取长度变化")
                    digest.update(buffer, 0, size)
                    output.write(buffer, 0, size)
                }
                if (total != attachment.sizeBytes || digest.digest().hex() != attachment.sha256) throw InvalidBackupException("附件 ${attachment.id} 校验失败")
                output.fd.sync()
            } }
            syncDirectory(destination.parentFile!!)
            successful = true
        } finally { if (!successful) destination.delete() }
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
            if (!file.exists() || file.delete()) {
                syncDirectory(attachmentDirectory)
                db.delete("attachment_garbage", "id = ?", arrayOf(id))
            }
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

    private fun syncDirectory(directory: File) {
        require(directory.isDirectory) { "附件持久目录不存在" }
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun exists(db: SQLiteDatabase, table: String, where: String, vararg args: String): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE $where LIMIT 1", args).use { it.moveToFirst() }

    private fun count(db: SQLiteDatabase, sql: String, args: List<String>): Int = db.rawQuery(sql, args.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }

    private fun strings(db: SQLiteDatabase, sql: String, vararg args: String): List<String> = db.rawQuery(sql, args).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    companion object {
        private const val ANALYSIS_METADATA_COLUMNS = "id, version, source_store_id, source_generation, created_at, updated_at, expires_at, length(CAST(payload AS BLOB)) AS payload_bytes, length(payload) AS payload_points"
        const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
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
