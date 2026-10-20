package dev.tracelife.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.tracelife.knowledge.ExperienceCodec
import dev.tracelife.knowledge.ExperienceRelation
import dev.tracelife.knowledge.ExperienceValidation
import dev.tracelife.knowledge.StoredExperienceChange
import dev.tracelife.knowledge.StoredExperienceThread
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.transfer.Deletion
import java.time.Instant

/** Soft publication references deliberately have no cascade: independent user notes survive. */
internal object ExperienceThreadStore {
    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE experience_threads (id TEXT PRIMARY KEY, version INTEGER NOT NULL, updated_at TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE experience_changes (thread_id TEXT NOT NULL, sequence INTEGER NOT NULL, knowledge_id TEXT NOT NULL, knowledge_revision INTEGER NOT NULL, relation TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(thread_id, sequence), UNIQUE(thread_id, knowledge_id, knowledge_revision), FOREIGN KEY(thread_id) REFERENCES experience_threads(id))")
        db.execSQL("CREATE INDEX experience_change_knowledge ON experience_changes(knowledge_id)")
        db.execSQL("CREATE TABLE experience_topics (thread_id TEXT NOT NULL, topic TEXT NOT NULL, PRIMARY KEY(thread_id, topic), FOREIGN KEY(thread_id) REFERENCES experience_threads(id))")
        db.execSQL("CREATE INDEX experience_topic_name ON experience_topics(topic)")
        db.execSQL("CREATE TABLE deleted_experience_threads (id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)")
        listOf("experience_threads", "experience_changes", "experience_topics", "deleted_experience_threads").forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                db.execSQL("CREATE TRIGGER analysis_generation_${table}_${op.lowercase()} AFTER $op ON $table BEGIN UPDATE store_state SET analysis_generation = analysis_generation + 1 WHERE singleton = 1; END")
            }
        }
    }

    fun get(db: SQLiteDatabase, id: String): StoredExperienceThread? {
        ExperienceValidation.id(id)
        return db.rawQuery("SELECT payload FROM experience_threads WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) ExperienceCodec.decodeThread(it.getString(0)) else null
        }
    }

    fun list(db: SQLiteDatabase, topic: String?, knowledgeId: String?, offset: Int, limit: Int): List<StoredExperienceThread> {
        require(offset >= 0 && limit in 1..100)
        require(topic == null || topic.isNotBlank() && topic.length <= 80)
        knowledgeId?.let(ExperienceValidation::id)
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (topic != null) { clauses += "EXISTS (SELECT 1 FROM experience_topics t WHERE t.thread_id = e.id AND t.topic = ?)"; args += topic }
        if (knowledgeId != null) { clauses += "EXISTS (SELECT 1 FROM experience_changes c WHERE c.thread_id = e.id AND c.knowledge_id = ?)"; args += knowledgeId }
        val where = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = " WHERE ").orEmpty()
        return db.rawQuery("SELECT e.payload FROM experience_threads e$where ORDER BY e.updated_at DESC, e.id LIMIT ? OFFSET ?",
            (args + listOf(limit.toString(), offset.toString())).toTypedArray()).use {
            buildList { while (it.moveToNext()) add(ExperienceCodec.decodeThread(it.getString(0))) }
        }
    }

    fun history(db: SQLiteDatabase, id: String, offset: Int, limit: Int): List<StoredExperienceChange> {
        ExperienceValidation.id(id); require(offset >= 0 && limit in 1..100)
        return db.rawQuery("SELECT payload FROM experience_changes WHERE thread_id = ? ORDER BY sequence DESC LIMIT ? OFFSET ?",
            arrayOf(id, limit.toString(), offset.toString())).use { buildList { while (it.moveToNext()) add(ExperienceCodec.decodeChange(it.getString(0))) } }
    }

    fun currentChange(db: SQLiteDatabase, id: String): StoredExperienceChange? {
        ExperienceValidation.id(id)
        return db.rawQuery("SELECT payload FROM experience_changes WHERE thread_id = ? AND relation IN ('INITIAL', 'NARROW', 'REPLACE') ORDER BY sequence DESC LIMIT 1", arrayOf(id)).use {
            if (it.moveToFirst()) ExperienceCodec.decodeChange(it.getString(0)) else null
        }
    }

    fun save(db: SQLiteDatabase, value: StoredExperienceThread, change: StoredExperienceChange, expectedVersion: Int?,
        checkKnowledge: (String, Int) -> Unit,
    ): StoredExperienceThread {
        ExperienceValidation.validate(value); ExperienceValidation.validate(change)
        require(value.id == change.threadId && value.version == change.sequence && value.updatedAt == change.createdAt &&
            value.version == (expectedVersion ?: 0) + 1) { "经验形成过程与当前版本不一致" }
        db.rawQuery("SELECT 1 FROM deleted_experience_threads WHERE id = ?", arrayOf(value.id)).use {
            require(!it.moveToFirst()) { "已删除的经验线索不可复用" }
        }
        val previous = get(db, value.id)
        if (expectedVersion == null && previous != null) {
            val initial = db.rawQuery("SELECT payload FROM experience_changes WHERE thread_id = ? AND sequence = 1", arrayOf(value.id)).use {
                check(it.moveToFirst()); ExperienceCodec.decodeChange(it.getString(0))
            }
            require(previous.title == value.title && previous.topics == value.topics &&
                initial.copy(createdAt = change.createdAt) == change) { "经验身份已经绑定其他内容" }
            return previous
        }
        if (previous?.version != expectedVersion) throw RevisionConflictException("经验已有新变化，请重新打开")
        if (previous == null) require(change.relation == ExperienceRelation.INITIAL && value.createdAt == change.createdAt)
        else require(value.title == previous.title && value.topics == previous.topics && value.createdAt == previous.createdAt &&
            value.updatedAt >= previous.updatedAt) { "已有经验的身份或独立内容不能由关联操作改写" }
        db.rawQuery("SELECT 1 FROM experience_changes WHERE thread_id = ? AND knowledge_id = ? AND knowledge_revision = ?",
            arrayOf(value.id, change.knowledgeId, change.knowledgeRevision.toString())).use {
            require(!it.moveToFirst()) { "这条知识的此版本已经加入该经验" }
        }
        checkKnowledge(change.knowledgeId, change.knowledgeRevision)
        if (previous == null) insertThread(db, value)
        else db.update("experience_threads", threadValues(value), "id = ?", arrayOf(value.id))
        insertChange(db, change)
        ensureBounds(db)
        return value
    }

    fun delete(db: SQLiteDatabase, id: String, expectedVersion: Int, now: Instant) {
        require(expectedVersion > 0)
        if (get(db, id)?.version != expectedVersion) throw RevisionConflictException("经验已有新变化或已删除")
        db.rawQuery("SELECT COUNT(*) FROM deleted_experience_threads", null).use {
            it.moveToFirst(); require(it.getInt(0) < ExperienceValidation.MAX_DELETIONS) { "经验删除标记达到上限" }
        }
        db.delete("experience_changes", "thread_id = ?", arrayOf(id))
        db.delete("experience_topics", "thread_id = ?", arrayOf(id))
        db.delete("experience_threads", "id = ?", arrayOf(id))
        insertDeletion(db, Deletion(id, expectedVersion, now))
    }

    fun allThreads(db: SQLiteDatabase): List<StoredExperienceThread> {
        ensureBounds(db)
        return db.rawQuery("SELECT payload FROM experience_threads ORDER BY id", null).use {
            buildList { while (it.moveToNext()) add(ExperienceCodec.decodeThread(it.getString(0))) }
        }
    }
    fun allChanges(db: SQLiteDatabase): List<StoredExperienceChange> {
        ensureBounds(db)
        return db.rawQuery("SELECT payload FROM experience_changes ORDER BY thread_id, sequence", null).use {
            buildList { while (it.moveToNext()) add(ExperienceCodec.decodeChange(it.getString(0))) }
        }
    }
    fun deletions(db: SQLiteDatabase): List<Deletion> = db.rawQuery("SELECT id, last_revision, deleted_at FROM deleted_experience_threads ORDER BY id", null).use {
        buildList { while (it.moveToNext()) add(Deletion(it.getString(0), it.getInt(1), Instant.parse(it.getString(2)))) }
    }

    fun replace(db: SQLiteDatabase, threads: List<StoredExperienceThread>, changes: List<StoredExperienceChange>, deletions: List<Deletion>) {
        ExperienceValidation.validateCollection(threads, changes, deletions)
        db.delete("experience_changes", null, null); db.delete("experience_topics", null, null)
        db.delete("experience_threads", null, null); db.delete("deleted_experience_threads", null, null)
        threads.forEach { insertThread(db, it) }; changes.forEach { insertChange(db, it) }; deletions.forEach { insertDeletion(db, it) }
    }

    private fun ensureBounds(db: SQLiteDatabase) {
        var bytes = 0L
        listOf("experience_threads" to ExperienceValidation.MAX_THREADS, "experience_changes" to ExperienceValidation.MAX_CHANGES).forEach { (table, maxCount) ->
            db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(CAST(payload AS BLOB))), 0) FROM $table", null).use {
                it.moveToFirst(); require(it.getInt(0) <= maxCount) { "经验线索超过保存数量上限" }; bytes += it.getLong(1)
            }
        }
        require(bytes <= ExperienceValidation.MAX_BYTES) { "经验线索超过保存大小上限" }
    }
    private fun threadValues(value: StoredExperienceThread) = ContentValues().apply {
        put("id", value.id); put("version", value.version); put("updated_at", value.updatedAt.toString()); put("payload", ExperienceCodec.encodeThread(value))
    }
    private fun insertThread(db: SQLiteDatabase, value: StoredExperienceThread) {
        db.insertOrThrow("experience_threads", null, threadValues(value))
        value.topics.forEach { topic -> db.insertOrThrow("experience_topics", null, ContentValues().apply { put("thread_id", value.id); put("topic", topic) }) }
    }
    private fun insertChange(db: SQLiteDatabase, value: StoredExperienceChange) {
        db.insertOrThrow("experience_changes", null, ContentValues().apply {
            put("thread_id", value.threadId); put("sequence", value.sequence); put("knowledge_id", value.knowledgeId)
            put("knowledge_revision", value.knowledgeRevision); put("relation", value.relation.name); put("payload", ExperienceCodec.encodeChange(value))
        })
    }
    private fun insertDeletion(db: SQLiteDatabase, value: Deletion) {
        db.insertOrThrow("deleted_experience_threads", null, ContentValues().apply {
            put("id", value.id); put("last_revision", value.lastRevision); put("deleted_at", value.deletedAt.toString())
        })
    }
}
