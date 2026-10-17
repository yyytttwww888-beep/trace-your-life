package dev.tracelife.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.tracelife.actions.ActionCodec
import dev.tracelife.actions.ActionHead
import dev.tracelife.actions.ActionOrigin
import dev.tracelife.actions.ActionValidation
import dev.tracelife.actions.StoredActionRevision
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.transfer.Deletion
import java.time.Instant

/** Uses its owner's database lock and transaction. No separate connection or nested commit. */
internal object ActionStore {
    fun revision(db: SQLiteDatabase, id: String, revision: Int? = null): StoredActionRevision? {
        SnapshotValidation.id(id)
        require(revision == null || revision > 0)
        val sql = if (revision == null) "SELECT r.payload FROM action_revisions r JOIN action_heads h ON h.id = r.id AND h.revision = r.revision WHERE r.id = ?"
        else "SELECT payload FROM action_revisions WHERE id = ? AND revision = ?"
        val args = if (revision == null) arrayOf(id) else arrayOf(id, revision.toString())
        return db.rawQuery(sql, args).use { if (it.moveToFirst()) ActionCodec.decode(it.getString(0)) else null }
    }

    fun heads(db: SQLiteDatabase, offset: Int, limit: Int): List<ActionHead> {
        require(offset >= 0 && limit in 1..100)
        return db.rawQuery("SELECT id, revision FROM action_heads ORDER BY review_on, id LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString())).use {
            buildList { while (it.moveToNext()) add(ActionHead(it.getString(0), it.getInt(1))) }
        }
    }

    fun history(db: SQLiteDatabase, id: String, offset: Int, limit: Int): List<StoredActionRevision> {
        SnapshotValidation.id(id)
        require(offset >= 0 && limit in 1..100)
        return db.rawQuery("SELECT payload FROM action_revisions WHERE id = ? ORDER BY revision DESC LIMIT ? OFFSET ?",
            arrayOf(id, limit.toString(), offset.toString())).use { buildList { while (it.moveToNext()) add(ActionCodec.decode(it.getString(0))) } }
    }

    fun operation(db: SQLiteDatabase, id: String, operationId: String): StoredActionRevision? {
        SnapshotValidation.id(id); SnapshotValidation.id(operationId)
        return db.rawQuery("SELECT payload FROM action_revisions WHERE id = ? AND operation_id = ?", arrayOf(id, operationId)).use {
            if (it.moveToFirst()) ActionCodec.decode(it.getString(0)) else null
        }
    }

    fun save(db: SQLiteDatabase, value: StoredActionRevision, expectedRevision: Int?, checkOrigin: (ActionOrigin) -> Unit): StoredActionRevision {
        ActionValidation.validate(value)
        require(expectedRevision == null || expectedRevision in 1 until Int.MAX_VALUE - 1)
        require(value.revision == (expectedRevision ?: 0) + 1)
        require(!deleted(db, value.id)) { "已删除的行动 ID 不可复用" }
        val current = revision(db, value.id)
        if (expectedRevision == null && current != null) {
            val initial = requireNotNull(revision(db, value.id, 1))
            require(initial.draft == value.draft) { "行动 ID 已绑定其他计划" }
            return initial
        }
        value.operationId?.let { operationId ->
            operation(db, value.id, operationId)?.let { saved ->
                    require(saved == value.copy(createdAt = saved.createdAt, updatedAt = saved.updatedAt)) { "回看操作 ID 已绑定其他内容" }
                    return saved
            }
        }
        if (current?.revision != expectedRevision) throw RevisionConflictException("行动已有新修订或不存在")
        if (current != null) {
            ActionValidation.validateTransition(current, value)
            if (value.review == null && value.draft == current.draft) return current
        }
        if (value.draft.origin != null && (current == null || current.draft.origin != value.draft.origin)) checkOrigin(value.draft.origin)
        val payload = ActionCodec.encode(value)
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(encoded_bytes), 0) FROM action_revisions", null).use {
            it.moveToFirst()
            require(it.getInt(0) < ActionValidation.MAX_REVISIONS && it.getLong(1) + bytes <= ActionValidation.MAX_BYTES) { "行动修订超过保存上限" }
        }
        insertRevision(db, value, payload)
        val head = ContentValues().apply {
            put("id", value.id); put("revision", value.revision); put("status", value.status.name)
            put("review_on", value.draft.reviewOn.toString()); put("zone_id", value.draft.zoneId)
        }
        if (current == null) db.insertOrThrow("action_heads", null, head)
        else db.update("action_heads", head, "id = ?", arrayOf(value.id))
        return value
    }

    fun delete(db: SQLiteDatabase, id: String, expectedRevision: Int, now: Instant) {
        SnapshotValidation.id(id)
        require(expectedRevision > 0)
        if (revision(db, id)?.revision != expectedRevision) throw RevisionConflictException("行动已有新修订或不存在")
        db.rawQuery("SELECT COUNT(*) FROM deleted_actions", null).use {
            it.moveToFirst(); require(it.getInt(0) < ActionValidation.MAX_DELETIONS) { "行动删除标记达到上限" }
        }
        db.delete("action_heads", "id = ?", arrayOf(id))
        db.delete("action_revisions", "id = ?", arrayOf(id))
        db.insertOrThrow("deleted_actions", null, ContentValues().apply {
            put("id", id); put("last_revision", expectedRevision); put("deleted_at", now.toString())
        })
    }

    fun ensureBounds(db: SQLiteDatabase) {
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(CAST(payload AS BLOB))), 0), COALESCE(MAX(length(CAST(payload AS BLOB))), 0) FROM action_revisions", null).use {
            it.moveToFirst(); require(it.getInt(0) <= ActionValidation.MAX_REVISIONS && it.getLong(1) <= ActionValidation.MAX_BYTES &&
                it.getInt(2) <= ActionValidation.MAX_REVISION_BYTES) { "行动修订超出保存上限" }
        }
    }

    fun allRevisions(db: SQLiteDatabase): List<StoredActionRevision> {
        ensureBounds(db)
        return db.rawQuery("SELECT payload FROM action_revisions ORDER BY id, revision", null).use {
            buildList { while (it.moveToNext()) add(ActionCodec.decode(it.getString(0))) }
        }
    }
    fun allHeads(db: SQLiteDatabase): List<ActionHead> = db.rawQuery("SELECT id, revision FROM action_heads ORDER BY id", null).use {
        buildList { while (it.moveToNext()) add(ActionHead(it.getString(0), it.getInt(1))) }
    }

    fun replace(db: SQLiteDatabase, revisions: List<StoredActionRevision>, heads: List<ActionHead>, deleted: List<Deletion>) {
        db.delete("action_heads", null, null); db.delete("action_revisions", null, null); db.delete("deleted_actions", null, null)
        revisions.forEach { insertRevision(db, it, ActionCodec.encode(it)) }
        val byId = revisions.groupBy { it.id }
        heads.forEach { head ->
            val value = requireNotNull(byId[head.id]?.singleOrNull { it.revision == head.revision })
            db.insertOrThrow("action_heads", null, ContentValues().apply {
                put("id", head.id); put("revision", head.revision); put("status", value.status.name)
                put("review_on", value.draft.reviewOn.toString()); put("zone_id", value.draft.zoneId)
            })
        }
        deleted.forEach { db.insertOrThrow("deleted_actions", null, ContentValues().apply {
            put("id", it.id); put("last_revision", it.lastRevision); put("deleted_at", it.deletedAt.toString())
        }) }
    }

    private fun deleted(db: SQLiteDatabase, id: String) = db.rawQuery("SELECT 1 FROM deleted_actions WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }
    private fun insertRevision(db: SQLiteDatabase, value: StoredActionRevision, payload: String) {
        db.insertOrThrow("action_revisions", null, ContentValues().apply {
            put("id", value.id); put("revision", value.revision); put("operation_id", value.operationId)
            put("payload", payload); put("encoded_bytes", payload.toByteArray(Charsets.UTF_8).size)
        })
    }
}
