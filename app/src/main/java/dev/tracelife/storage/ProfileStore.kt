package dev.tracelife.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.profile.ProfileChange
import dev.tracelife.profile.ProfileCodec
import dev.tracelife.profile.ProfileHead
import dev.tracelife.profile.ProfileKnowledgeRef
import dev.tracelife.profile.ProfileValidation
import dev.tracelife.profile.StoredProfileRevision
import dev.tracelife.transfer.Deletion
import java.time.Instant

/** Only organizing identities and independently authored user text. Knowledge is a soft reference. */
internal object ProfileStore {
    fun revision(db: SQLiteDatabase, id: String, revision: Int? = null): StoredProfileRevision? {
        SnapshotValidation.id(id)
        require(revision == null || revision > 0)
        val sql = if (revision == null) "SELECT r.payload FROM profile_revisions r JOIN profile_heads h ON h.id = r.id AND h.revision = r.revision WHERE r.id = ?"
            else "SELECT payload FROM profile_revisions WHERE id = ? AND revision = ?"
        val args = if (revision == null) arrayOf(id) else arrayOf(id, revision.toString())
        return db.rawQuery(sql, args).use { if (it.moveToFirst()) ProfileCodec.decode(it.getString(0)) else null }
    }

    fun heads(db: SQLiteDatabase, offset: Int, limit: Int): List<ProfileHead> {
        require(offset >= 0 && limit in 1..100)
        return db.rawQuery("SELECT id, revision FROM profile_heads ORDER BY id LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString())).use {
            buildList { while (it.moveToNext()) add(ProfileHead(it.getString(0), it.getInt(1))) }
        }
    }

    fun history(db: SQLiteDatabase, id: String, offset: Int, limit: Int): List<StoredProfileRevision> {
        SnapshotValidation.id(id)
        require(offset >= 0 && limit in 1..100)
        return db.rawQuery("SELECT payload FROM profile_revisions WHERE id = ? ORDER BY revision DESC LIMIT ? OFFSET ?",
            arrayOf(id, limit.toString(), offset.toString())).use { buildList { while (it.moveToNext()) add(ProfileCodec.decode(it.getString(0))) } }
    }

    fun operation(db: SQLiteDatabase, id: String, operationId: String): StoredProfileRevision? {
        SnapshotValidation.id(id); SnapshotValidation.id(operationId)
        return db.rawQuery("SELECT payload FROM profile_revisions WHERE id = ? AND operation_id = ?", arrayOf(id, operationId)).use {
            if (it.moveToFirst()) ProfileCodec.decode(it.getString(0)) else null
        }
    }

    fun save(db: SQLiteDatabase, value: StoredProfileRevision, expectedRevision: Int?, checkKnowledge: (ProfileKnowledgeRef) -> Unit): StoredProfileRevision {
        ProfileValidation.validate(value)
        require(expectedRevision == null || expectedRevision in 1 until Int.MAX_VALUE - 1)
        require(value.revision == (expectedRevision ?: 0) + 1)
        require(!deleted(db, value.id)) { "已删除的画像 ID 不可复用" }
        val current = revision(db, value.id)
        if (expectedRevision == null && current != null) {
            val initial = requireNotNull(revision(db, value.id, 1))
            require(value.copy(createdAt = initial.createdAt, updatedAt = initial.updatedAt) == initial) { "画像 ID 已绑定其他初始内容" }
            return initial
        }
        value.operationId?.let { operationId ->
            operation(db, value.id, operationId)?.let { saved ->
                require(value.copy(createdAt = saved.createdAt, updatedAt = saved.updatedAt) == saved) { "画像操作 ID 已绑定其他内容" }
                return saved
            }
        }
        if (current?.revision != expectedRevision) throw RevisionConflictException("画像已有新修订或不存在")
        if (current != null) ProfileValidation.validateTransition(current, value)
        db.rawQuery("SELECT 1 FROM profile_heads WHERE knowledge_id = ? AND dimension = ? AND layer = ? AND id <> ?",
            arrayOf(value.draft.knowledge.knowledgeId, value.draft.dimension.name, value.draft.layer.name, value.id)).use {
            require(!it.moveToFirst()) { "同一知识在此画像维度与层次已有条目，请维护原条目" }
        }
        if (current == null || current.draft.knowledge != value.draft.knowledge || value.change == ProfileChange.ADOPTED)
            checkKnowledge(value.draft.knowledge)
        val payload = ProfileCodec.encode(value)
        val bytes = payload.toByteArray(Charsets.UTF_8).size
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(encoded_bytes), 0) FROM profile_revisions", null).use {
            it.moveToFirst()
            require(it.getInt(0) < ProfileValidation.MAX_REVISIONS && it.getLong(1) + bytes <= ProfileValidation.MAX_BYTES) { "画像修订超过保存上限" }
        }
        insertRevision(db, value, payload)
        val head = headValues(value)
        if (current == null) db.insertOrThrow("profile_heads", null, head)
        else db.update("profile_heads", head, "id = ?", arrayOf(value.id))
        return value
    }

    fun delete(db: SQLiteDatabase, id: String, expectedRevision: Int, now: Instant) {
        SnapshotValidation.id(id)
        require(expectedRevision > 0)
        if (revision(db, id)?.revision != expectedRevision) throw RevisionConflictException("画像已有新修订或不存在")
        db.rawQuery("SELECT COUNT(*) FROM deleted_profiles", null).use {
            it.moveToFirst(); require(it.getInt(0) < ProfileValidation.MAX_DELETIONS) { "画像删除标记达到上限" }
        }
        db.delete("profile_heads", "id = ?", arrayOf(id))
        db.delete("profile_revisions", "id = ?", arrayOf(id))
        db.insertOrThrow("deleted_profiles", null, ContentValues().apply {
            put("id", id); put("last_revision", expectedRevision); put("deleted_at", now.toString())
        })
    }

    fun ensureBounds(db: SQLiteDatabase) {
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(length(CAST(payload AS BLOB))), 0), COALESCE(MAX(length(CAST(payload AS BLOB))), 0) FROM profile_revisions", null).use {
            it.moveToFirst()
            require(it.getInt(0) <= ProfileValidation.MAX_REVISIONS && it.getLong(1) <= ProfileValidation.MAX_BYTES &&
                it.getInt(2) <= ProfileValidation.MAX_REVISION_BYTES) { "画像修订超出保存上限" }
        }
    }

    fun allRevisions(db: SQLiteDatabase): List<StoredProfileRevision> {
        ensureBounds(db)
        return db.rawQuery("SELECT payload FROM profile_revisions ORDER BY id, revision", null).use {
            buildList { while (it.moveToNext()) add(ProfileCodec.decode(it.getString(0))) }
        }
    }

    fun allHeads(db: SQLiteDatabase): List<ProfileHead> = db.rawQuery("SELECT id, revision FROM profile_heads ORDER BY id", null).use {
        buildList { while (it.moveToNext()) add(ProfileHead(it.getString(0), it.getInt(1))) }
    }

    fun replace(db: SQLiteDatabase, revisions: List<StoredProfileRevision>, heads: List<ProfileHead>, deleted: List<Deletion>) {
        db.delete("profile_heads", null, null)
        db.delete("profile_revisions", null, null)
        db.delete("deleted_profiles", null, null)
        revisions.forEach { insertRevision(db, it, ProfileCodec.encode(it)) }
        val byRevision = revisions.associateBy { it.id to it.revision }
        heads.forEach { head ->
            db.insertOrThrow("profile_heads", null, headValues(requireNotNull(byRevision[head.id to head.revision])))
        }
        deleted.forEach { db.insertOrThrow("deleted_profiles", null, ContentValues().apply {
            put("id", it.id); put("last_revision", it.lastRevision); put("deleted_at", it.deletedAt.toString())
        }) }
    }

    private fun deleted(db: SQLiteDatabase, id: String) = db.rawQuery("SELECT 1 FROM deleted_profiles WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    private fun headValues(value: StoredProfileRevision) = ContentValues().apply {
        put("id", value.id); put("revision", value.revision)
        put("knowledge_id", value.draft.knowledge.knowledgeId)
        put("dimension", value.draft.dimension.name); put("layer", value.draft.layer.name)
    }

    private fun insertRevision(db: SQLiteDatabase, value: StoredProfileRevision, payload: String) {
        db.insertOrThrow("profile_revisions", null, ContentValues().apply {
            put("id", value.id); put("revision", value.revision); put("operation_id", value.operationId)
            put("payload", payload); put("encoded_bytes", payload.toByteArray(Charsets.UTF_8).size)
        })
    }
}
