package dev.tracelife.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.tracelife.conversation.ConversationCodec
import dev.tracelife.conversation.ConversationCollection
import dev.tracelife.conversation.ConversationThread
import dev.tracelife.conversation.ConversationTurn
import dev.tracelife.conversation.ConversationValidation
import dev.tracelife.transfer.Deletion
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/** Questions do not cascade with machine tasks or deleted sources. */
internal object ConversationStore {
    private val sortableInstant = DateTimeFormatterBuilder().appendInstant(9).toFormatter()
    fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversation_threads (id TEXT PRIMARY KEY, updated_at TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX conversation_updated_lookup ON conversation_threads(updated_at, id)")
        db.execSQL("CREATE TABLE conversation_turns (id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, position INTEGER NOT NULL, run_id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, UNIQUE(thread_id, position), FOREIGN KEY(thread_id) REFERENCES conversation_threads(id))")
        db.execSQL("CREATE TABLE deleted_conversations (id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)")
        listOf("conversation_threads", "conversation_turns", "deleted_conversations").forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                db.execSQL("CREATE TRIGGER analysis_generation_${table}_${operation.lowercase()} AFTER $operation ON $table BEGIN UPDATE store_state SET analysis_generation = analysis_generation + 1 WHERE singleton = 1; END")
            }
        }
    }
    fun create(db: SQLiteDatabase, id: String, now: Instant): ConversationThread {
        ConversationValidation.id(id)
        db.rawQuery("SELECT 1 FROM deleted_conversations WHERE id = ?", arrayOf(id)).use { require(!it.moveToFirst()) { "已删除的对话身份不可复用" } }
        get(db, id)?.let { return it }
        return ConversationThread(id, now, now).also { insertThread(db, it); ensureBounds(db) }
    }
    fun get(db: SQLiteDatabase, id: String): ConversationThread? {
        ConversationValidation.id(id)
        return db.rawQuery("SELECT payload FROM conversation_threads WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) ConversationCodec.decodeThread(it.getString(0)) else null
        }
    }
    fun turn(db: SQLiteDatabase, id: String): ConversationTurn? {
        ConversationValidation.id(id)
        return db.rawQuery("SELECT payload FROM conversation_turns WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) ConversationCodec.decodeTurn(it.getString(0)) else null
        }
    }
    fun latestTurn(db: SQLiteDatabase, id: String): ConversationTurn? {
        ConversationValidation.id(id)
        return db.rawQuery("SELECT payload FROM conversation_turns WHERE thread_id = ? ORDER BY position DESC LIMIT 1", arrayOf(id)).use {
            if (it.moveToFirst()) ConversationCodec.decodeTurn(it.getString(0)) else null
        }
    }
    fun page(db: SQLiteDatabase, offset: Int, limit: Int): List<ConversationThread> {
        require(offset >= 0 && limit in 1..100)
        return db.rawQuery("SELECT payload FROM conversation_threads ORDER BY updated_at DESC, id LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString())).use {
            buildList { while (it.moveToNext()) add(ConversationCodec.decodeThread(it.getString(0))) }
        }
    }
    fun turns(db: SQLiteDatabase, id: String, offset: Int, limit: Int): List<ConversationTurn> {
        ConversationValidation.id(id); require(offset >= 0 && limit in 1..100)
        return db.rawQuery("SELECT payload FROM conversation_turns WHERE thread_id = ? ORDER BY position LIMIT ? OFFSET ?", arrayOf(id, limit.toString(), offset.toString())).use {
            buildList { while (it.moveToNext()) add(ConversationCodec.decodeTurn(it.getString(0))) }
        }
    }
    fun append(db: SQLiteDatabase, value: ConversationTurn, checkRun: () -> Unit): ConversationTurn {
        ConversationValidation.validate(value)
        val thread = requireNotNull(get(db, value.threadId)) { "对话已删除或不存在" }
        turn(db, value.id)?.let { require(it == value) { "提问身份已绑定其他内容" }; return it }
        val count = db.rawQuery("SELECT COUNT(*) FROM conversation_turns WHERE thread_id = ?", arrayOf(value.threadId)).use { it.moveToFirst(); it.getInt(0) }
        require(value.position == count + 1) { "对话已有新问题，请重新打开" }
        require(value.createdAt >= thread.updatedAt) { "问题时间早于已有问题" }
        value.parentTurnId?.let { parentId ->
            val parent = requireNotNull(turn(db, parentId)) { "追问目标不存在" }
            require(parent.threadId == value.threadId && parent.position < value.position) { "追问须指向同一对话中的已有问题" }
        }
        checkRun()
        insertTurn(db, value)
        db.update("conversation_threads", threadValues(thread.copy(updatedAt = value.createdAt)), "id = ?", arrayOf(thread.id))
        ensureBounds(db)
        return value
    }
    fun ownedRuns(db: SQLiteDatabase, id: String): List<String> = db.rawQuery("SELECT run_id FROM conversation_turns WHERE thread_id = ? ORDER BY position", arrayOf(id)).use {
        buildList { while (it.moveToNext()) add(it.getString(0)) }
    }
    fun delete(db: SQLiteDatabase, id: String, now: Instant) {
        if (get(db, id) == null) return
        db.rawQuery("SELECT COUNT(*) FROM deleted_conversations", null).use { it.moveToFirst(); require(it.getInt(0) < ConversationValidation.MAX_DELETIONS) { "对话删除标记达到上限" } }
        val count = db.rawQuery("SELECT COUNT(*) FROM conversation_turns WHERE thread_id = ?", arrayOf(id)).use { it.moveToFirst(); it.getInt(0) }
        db.delete("conversation_turns", "thread_id = ?", arrayOf(id))
        db.delete("conversation_threads", "id = ?", arrayOf(id))
        insertDeletion(db, Deletion(id, count + 1, now))
    }
    fun all(db: SQLiteDatabase): ConversationCollection {
        ensureBounds(db)
        val threads = db.rawQuery("SELECT payload FROM conversation_threads ORDER BY id", null).use {
            buildList { while (it.moveToNext()) add(ConversationCodec.decodeThread(it.getString(0))) }
        }
        val turns = db.rawQuery("SELECT payload FROM conversation_turns ORDER BY thread_id, position", null).use {
            buildList { while (it.moveToNext()) add(ConversationCodec.decodeTurn(it.getString(0))) }
        }
        val deleted = db.rawQuery("SELECT id, last_revision, deleted_at FROM deleted_conversations ORDER BY id", null).use {
            buildList { while (it.moveToNext()) add(Deletion(it.getString(0), it.getInt(1), Instant.parse(it.getString(2)))) }
        }
        return ConversationCollection(threads, turns, deleted).also { ConversationValidation.validateCollection(it.threads, it.turns, it.deletions) }
    }
    fun replace(db: SQLiteDatabase, value: ConversationCollection) {
        ConversationValidation.validateCollection(value.threads, value.turns, value.deletions)
        db.delete("conversation_turns", null, null); db.delete("conversation_threads", null, null); db.delete("deleted_conversations", null, null)
        value.threads.forEach { insertThread(db, it) }; value.turns.forEach { insertTurn(db, it) }; value.deletions.forEach { insertDeletion(db, it) }
    }
    fun ensureBounds(db: SQLiteDatabase) {
        var bytes = 0L
        listOf("conversation_threads" to ConversationValidation.MAX_THREADS, "conversation_turns" to ConversationValidation.MAX_TURNS).forEach { (table, limit) ->
            db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(CAST(payload AS BLOB))), 0), COALESCE(MAX(length(CAST(payload AS BLOB))), 0) FROM $table", null).use {
                it.moveToFirst(); require(it.getInt(0) <= limit && it.getInt(2) <= ConversationValidation.MAX_ITEM_BYTES) { "对话超过保存数量或单项上限" }; bytes += it.getLong(1)
            }
        }
        require(bytes <= ConversationValidation.MAX_BYTES) { "对话超过保存大小上限" }
        db.rawQuery("SELECT COUNT(*) FROM deleted_conversations", null).use { it.moveToFirst(); require(it.getInt(0) <= ConversationValidation.MAX_DELETIONS) }
    }
    private fun threadValues(value: ConversationThread) = ContentValues().apply {
        put("id", value.id); put("updated_at", sortableInstant.format(value.updatedAt)); put("payload", ConversationCodec.encodeThread(value))
    }
    private fun insertThread(db: SQLiteDatabase, value: ConversationThread) { db.insertOrThrow("conversation_threads", null, threadValues(value)) }
    private fun insertTurn(db: SQLiteDatabase, value: ConversationTurn) {
        db.insertOrThrow("conversation_turns", null, ContentValues().apply {
            put("id", value.id); put("thread_id", value.threadId); put("position", value.position); put("run_id", value.runId); put("payload", ConversationCodec.encodeTurn(value))
        })
    }
    private fun insertDeletion(db: SQLiteDatabase, value: Deletion) {
        db.insertOrThrow("deleted_conversations", null, ContentValues().apply { put("id", value.id); put("last_revision", value.lastRevision); put("deleted_at", value.deletedAt.toString()) })
    }
}
