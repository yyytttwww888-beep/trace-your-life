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

    fun deletedRecordIds(): Set<String> = access { db -> strings(db, "SELECT id FROM deleted_records").toSet() }

    fun currentGeneration(): Long = access { db -> storeState(db).second }

    fun currentStoreId(): String = access { db -> storeState(db).first }

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
                    records, templates, attachments, deletions(db, "deleted_records"), deletions(db, "deleted_templates"), allChanges(db))
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
     * The caller owns a unique destination name and its adjacent staging file. Same-parent atomic
     * moves are required; filesystems without atomic rename fail rather than exposing a partial file.
     */
    fun publishSnapshot(snapshot: RepositorySnapshot, stagedFile: File, destination: File) = access { db ->
        requireExternalPath(stagedFile)
        requireExternalPath(destination)
        require(stagedFile.isFile && !Files.isSymbolicLink(stagedFile.toPath())) { "发布暂存文件不存在" }
        require(stagedFile.canonicalFile.parentFile == destination.canonicalFile.parentFile) { "发布暂存文件必须与目标位于同一目录" }
        require(!destination.exists() && !Files.isSymbolicLink(destination.toPath())) { "导出目标已经存在" }
        transaction(db) {
            val state = storeState(db)
            if (snapshot.storeId != state.first || snapshot.generation != state.second) throw StaleSnapshotException("资料已变化，须重新生成导出")
            Files.move(stagedFile.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Restore replaces one logical store only after all structure and bytes pass validation. */
    fun restoreSnapshot(
        snapshot: RepositorySnapshot,
        attachmentSource: File,
        expectedGeneration: Long,
        replaceExisting: Boolean = false,
    ): RestoreResult = access { db ->
        SnapshotValidation.validate(snapshot)
        requireExternalPath(attachmentSource)
        require(attachmentSource.isDirectory && !Files.isSymbolicLink(attachmentSource.toPath())) { "恢复附件目录不存在" }
        if (storeState(db).second != expectedGeneration) throw StaleSnapshotException("目标资料已变化，请重新核对恢复")
        if (!replaceExisting && (exists(db, "records", "1 = 1") || exists(db, "templates", "1 = 1"))) {
            throw IllegalStateException("目标已有记录或模板，须明确允许替换后恢复")
        }
        val mergedRecords = mergeDeletions(deletions(db, "deleted_records"), snapshot.deletedRecords)
        val mergedTemplates = mergeDeletions(deletions(db, "deleted_templates"), snapshot.deletedTemplates)
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
                val oldAttachmentIds = strings(db, "SELECT id FROM attachments").toSet()
                db.delete("records", null, null)
                db.delete("templates", null, null)
                db.delete("attachments", null, null)
                db.delete("change_events", null, null)
                db.delete("deleted_records", null, null)
                db.delete("deleted_templates", null, null)
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
                // Even an empty→empty restore invalidates a previously captured publication token.
                db.execSQL("UPDATE store_state SET generation = generation + 1 WHERE singleton = 1")
                RestoreResult(records.map { it.id }.distinct().size, records.size, templates.map { it.id }.distinct().size,
                    attachments.size, snapshot.records.map { it.id }.distinct().count { it in recordTombstones }, storeState(db).second)
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

    private fun storeState(db: SQLiteDatabase): Pair<String, Long> = db.rawQuery("SELECT store_id, generation FROM store_state WHERE singleton = 1", null).use {
        check(it.moveToFirst()) { "主存身份缺失" }
        it.getString(0) to it.getLong(1)
    }

    /** Check row and byte budgets in SQLite before constructing a complete in-memory snapshot. */
    private fun ensureCaptureBounds(db: SQLiteDatabase) {
        val rowLimits = mapOf("record_revisions" to 100_000, "template_versions" to 10_000,
            "deleted_records" to 100_000, "deleted_templates" to 10_000, "change_events" to 200_000)
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
