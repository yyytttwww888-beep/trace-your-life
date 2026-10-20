package dev.tracelife.storage

import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import android.util.JsonReader
import android.util.JsonToken
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.FeedbackContextProtocol
import dev.tracelife.analysis.HistoricalFeedbackResolution
import dev.tracelife.transfer.Deletion
import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/** Independently owned user wording; it contains identities and choices, never model text. */
data class HistoricalComparisonNote(val runId: String, val resolution: HistoricalFeedbackResolution) {
    val id: String get() = AnalysisRunCodec.sha256(AnalysisRunCodec.json(listOf("historical-comparison-note-v1", runId,
        resolution.target.batchKey, resolution.target.claimIndex, resolution.target.claimSha256, resolution.feedbackId, resolution.version)))
}
data class HistoricalComparisonRun(val runId: String, val noteCount: Int, val latestAt: Instant)

object HistoricalComparisonCodec {
    const val MAX_NOTES = 50_000
    const val MAX_BYTES = 16L * 1024 * 1024
    const val MAX_DELETIONS = 100_000
    fun encode(note: HistoricalComparisonNote): String {
        validate(note)
        return AnalysisRunCodec.json(mapOf("formatVersion" to 1, "runId" to note.runId,
            "resolution" to FeedbackContextProtocol.resolutionMap(note.resolution))).also {
            require(it.toByteArray(Charsets.UTF_8).size <= 8_192) { "独立对照说明载荷过大" }
        }
    }
    fun decode(payload: String): HistoricalComparisonNote {
        require(payload.toByteArray(Charsets.UTF_8).size <= 8_192) { "独立对照说明载荷过大" }
        val reader = JsonReader(StringReader(payload)).apply { isLenient = false }
        var nodes = 0
        fun read(depth: Int): Any? {
            require(depth <= 4 && ++nodes <= 40)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> linkedMapOf<String, Any?>().apply {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        require(name.length <= 40 && !containsKey(name))
                        put(name, read(depth + 1))
                    }
                    reader.endObject()
                }
                JsonToken.STRING -> reader.nextString().also { require(it.length <= 1_000) }
                JsonToken.NUMBER -> reader.nextString().let { require(Regex("0|[1-9][0-9]*").matches(it)); it.toLong() }
                else -> error("独立对照说明结构无效")
            }
        }
        @Suppress("UNCHECKED_CAST")
        val root = read(0) as? Map<String, Any?> ?: error("独立对照说明需要对象")
        require(reader.peek() == JsonToken.END_DOCUMENT && root.keys == setOf("formatVersion", "runId", "resolution") && root["formatVersion"] == 1L)
        return HistoricalComparisonNote(root["runId"] as? String ?: error("任务身份无效"), FeedbackContextProtocol.parseResolution(root["resolution"])).also(::validate)
    }
    fun validate(note: HistoricalComparisonNote) {
        AnalysisRunCodec.validId(note.runId)
        val value = note.resolution
        AnalysisRunCodec.validId(value.target.batchKey)
        require(value.target.claimIndex in 0..19 && value.target.claimSha256.matches(Regex("[0-9a-f]{64}")))
        require(value.feedbackId.matches(Regex("[0-9a-f]{64}")) && value.version in 1 until Int.MAX_VALUE)
        require(value.reason.isNotBlank() && value.reason.length <= 1_000)
    }
    fun validateCollection(notes: List<HistoricalComparisonNote>) {
        require(notes.size <= MAX_NOTES && notes.map { it.id }.distinct().size == notes.size) { "独立对照说明数量过多或身份重复" }
        require(notes.sumOf { encode(it).toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_BYTES) { "独立对照说明超过保存容量" }
        notes.groupBy { Triple(it.runId, it.resolution.target, it.resolution.feedbackId) }.values.forEach { chain ->
            val ordered = chain.sortedBy { it.resolution.version }
            require(ordered.map { it.resolution.version } == (1..ordered.size).toList()) { "独立对照说明版本链不完整" }
            require(ordered.zipWithNext().all { (a, b) -> b.resolution.createdAt >= a.resolution.createdAt })
        }
    }
}

/** The caller owns the transaction, database lock and analysis-generation notification. */
internal object HistoricalComparisonStore {
    private val sortableInstant = DateTimeFormatterBuilder().appendInstant(9).toFormatter()

    fun createTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS historical_comparisons (
            id TEXT PRIMARY KEY, run_id TEXT NOT NULL, payload TEXT NOT NULL,
            encoded_bytes INTEGER NOT NULL, created_at TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS historical_comparisons_run ON historical_comparisons(run_id, created_at DESC, id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_historical_comparison_runs (id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)")
        listOf("historical_comparisons", "deleted_historical_comparison_runs").forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { op ->
                db.execSQL("CREATE TRIGGER IF NOT EXISTS analysis_generation_${table}_${op.lowercase()} AFTER $op ON $table BEGIN UPDATE store_state SET analysis_generation = analysis_generation + 1 WHERE singleton = 1; END")
            }
        }
    }

    fun append(db: SQLiteDatabase, runId: String, resolutions: List<HistoricalFeedbackResolution>) {
        if (resolutions.isEmpty()) return
        require(db.rawQuery("SELECT 1 FROM deleted_historical_comparison_runs WHERE id = ?", arrayOf(runId)).use { !it.moveToFirst() }) { "该组独立对照说明已永久删除" }
        ensureBounds(db)
        val incoming = resolutions.map { HistoricalComparisonNote(runId, it) }
        val retained = list(db, runId, 0, HistoricalComparisonCodec.MAX_NOTES)
        val byId = retained.associateBy { it.id }
        incoming.forEach { require(byId[it.id]?.let { old -> old == it } != false) { "独立对照说明不可改写旧版本" } }
        val additions = incoming.filter { it.id !in byId }
        HistoricalComparisonCodec.validateCollection(retained + additions)
        val bytes = additions.sumOf { HistoricalComparisonCodec.encode(it).toByteArray(Charsets.UTF_8).size.toLong() }
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(encoded_bytes), 0) FROM historical_comparisons", emptyArray()).use {
            it.moveToFirst()
            require(it.getInt(0) + additions.size <= HistoricalComparisonCodec.MAX_NOTES && it.getLong(1) + bytes <= HistoricalComparisonCodec.MAX_BYTES) { "独立对照说明超过保存容量" }
        }
        additions.forEach { insert(db, it) }
    }

    fun list(db: SQLiteDatabase, runId: String? = null, offset: Int = 0, limit: Int = 25): List<HistoricalComparisonNote> {
        require(offset >= 0 && limit in 1..HistoricalComparisonCodec.MAX_NOTES)
        val where = if (runId == null) "" else " WHERE run_id = ?"
        val args = (listOfNotNull(runId) + listOf(limit.toString(), offset.toString())).toTypedArray()
        return db.rawQuery("SELECT id, payload FROM historical_comparisons$where ORDER BY created_at DESC, id LIMIT ? OFFSET ?", args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HistoricalComparisonCodec.decode(cursor.getString(1)).also { require(it.id == cursor.getString(0)) }) }
        }
    }
    fun runs(db: SQLiteDatabase, offset: Int = 0, limit: Int = 25): List<HistoricalComparisonRun> {
        require(offset >= 0 && limit in 1..100)
        return db.rawQuery("SELECT run_id, COUNT(*), MAX(created_at) AS latest FROM historical_comparisons GROUP BY run_id ORDER BY latest DESC, run_id LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(HistoricalComparisonRun(cursor.getString(0), cursor.getInt(1), Instant.parse(cursor.getString(2)))) }
        }
    }
    fun ensureBounds(db: SQLiteDatabase) {
        db.rawQuery("SELECT COUNT(*), COALESCE(SUM(encoded_bytes), 0), COALESCE(MAX(length(CAST(payload AS BLOB))), 0) FROM historical_comparisons", null).use {
            it.moveToFirst()
            require(it.getInt(0) <= HistoricalComparisonCodec.MAX_NOTES && it.getLong(1) <= HistoricalComparisonCodec.MAX_BYTES && it.getInt(2) <= 8_192) { "独立对照说明超过保存容量" }
        }
    }
    fun all(db: SQLiteDatabase): List<HistoricalComparisonNote> {
        ensureBounds(db)
        return list(db, limit = HistoricalComparisonCodec.MAX_NOTES).also(HistoricalComparisonCodec::validateCollection)
    }
    fun deletions(db: SQLiteDatabase): List<Deletion> = db.rawQuery("SELECT id, last_revision, deleted_at FROM deleted_historical_comparison_runs ORDER BY id", emptyArray()).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Deletion(cursor.getString(0), cursor.getInt(1), Instant.parse(cursor.getString(2)))) }
    }
    fun delete(db: SQLiteDatabase, runId: String, now: Instant) {
        val count = db.rawQuery("SELECT COUNT(*) FROM historical_comparisons WHERE run_id = ?", arrayOf(runId)).use { it.moveToFirst(); it.getInt(0) }
        require(count > 0) { "独立对照说明不存在或已删除" }
        require(deletions(db).size < HistoricalComparisonCodec.MAX_DELETIONS)
        db.delete("historical_comparisons", "run_id = ?", arrayOf(runId))
        db.insertOrThrow("deleted_historical_comparison_runs", null, ContentValues().apply {
            put("id", runId); put("last_revision", count); put("deleted_at", now.toString())
        })
    }
    fun merge(local: List<HistoricalComparisonNote>, incoming: List<HistoricalComparisonNote>, deletedRuns: Set<String>): List<HistoricalComparisonNote> {
        // A local chain is user authority; importing a longer foreign chain cannot replace it.
        val localKeys = local.map { Triple(it.runId, it.resolution.target, it.resolution.feedbackId) }.toSet()
        return (local + incoming.filter { Triple(it.runId, it.resolution.target, it.resolution.feedbackId) !in localKeys })
            .filterNot { it.runId in deletedRuns }.sortedBy { it.id }.also(HistoricalComparisonCodec::validateCollection)
    }
    fun replace(db: SQLiteDatabase, notes: List<HistoricalComparisonNote>, deleted: List<Deletion>) {
        HistoricalComparisonCodec.validateCollection(notes)
        require(deleted.size <= HistoricalComparisonCodec.MAX_DELETIONS && deleted.map { it.id }.distinct().size == deleted.size)
        val deletedIds = deleted.map { it.id }.toSet()
        require(notes.none { it.runId in deletedIds })
        db.delete("historical_comparisons", null, null)
        db.delete("deleted_historical_comparison_runs", null, null)
        notes.forEach { insert(db, it) }
        deleted.forEach { value -> db.insertOrThrow("deleted_historical_comparison_runs", null, ContentValues().apply {
            put("id", value.id); put("last_revision", value.lastRevision); put("deleted_at", value.deletedAt.toString())
        }) }
    }
    private fun insert(db: SQLiteDatabase, note: HistoricalComparisonNote) {
        val payload = HistoricalComparisonCodec.encode(note)
        db.insertOrThrow("historical_comparisons", null, ContentValues().apply {
            put("id", note.id); put("run_id", note.runId); put("payload", payload)
            put("encoded_bytes", payload.toByteArray(Charsets.UTF_8).size); put("created_at", sortableInstant.format(note.resolution.createdAt))
        })
    }
}
