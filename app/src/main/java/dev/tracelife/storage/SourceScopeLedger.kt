package dev.tracelife.storage

import android.database.sqlite.SQLiteDatabase

/** Local invalidation history, containing identities and scope metadata only, never source text.
 * A restore starts a new floor: imported stamps cannot become valid by numeric coincidence.
 * The global source token stays immutable; unrelated changes do not rewrite old provenance. */
internal object SourceScopeLedger {
    fun install(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE store_state ADD COLUMN scope_generation_floor INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE store_state SET scope_generation_floor = generation WHERE singleton = 1")
        db.execSQL("""CREATE TABLE source_scope_changes (
            generation INTEGER NOT NULL, record_id TEXT NOT NULL,
            start_date TEXT, end_date TEXT, archived INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX source_scope_generation ON source_scope_changes(generation)")
        // For each identity/range only the latest change is necessary: every earlier snapshot
        // affected by an older matching change is also affected by this newer matching change.
        db.execSQL("CREATE UNIQUE INDEX source_scope_identity ON source_scope_changes(record_id, IFNULL(start_date, ''), IFNULL(end_date, ''), archived)")
        val tables = listOf("templates", "template_versions", "template_questions", "records",
            "record_revisions", "record_topics", "record_answers", "attachments", "record_attachments",
            "deleted_records", "deleted_templates", "change_events")
        // Both sides of updates matter. BEFORE DELETE retains the old metadata before cascades;
        // AFTER INSERT sees the new rows. Increment and journal entry share one SQLite trigger.
        tables.forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                db.execSQL("DROP TRIGGER generation_${table}_${operation.lowercase()}")
                val timings = if (operation == "UPDATE") listOf("BEFORE", "AFTER")
                    else listOf(if (operation == "DELETE") "BEFORE" else "AFTER")
                timings.forEach { timing ->
                    val row = if (timing == "BEFORE") "OLD" else "NEW"
                    val selection = selection(table, row)
                    val journal = selection?.let { "INSERT OR REPLACE INTO source_scope_changes $it;" }.orEmpty()
                    db.execSQL("""CREATE TRIGGER generation_${table}_${operation.lowercase()}_${timing.lowercase()}
                        $timing $operation ON $table BEGIN
                        UPDATE store_state SET generation = generation + 1 WHERE singleton = 1;
                        $journal END""")
                }
            }
        }
    }

    private fun selection(table: String, row: String): String? {
        val condition = when (table) {
            "records" -> "h.id = $row.id AND r.revision = $row.current_revision"
            "record_revisions", "record_topics", "record_answers", "record_attachments" ->
                "h.id = $row.record_id AND r.revision = $row.revision"
            "template_versions", "template_questions" -> "r.template_id = $row.template_id AND r.template_version = $row.version"
            "attachments" -> "EXISTS (SELECT 1 FROM record_attachments a WHERE a.record_id = r.record_id AND a.revision = r.revision AND a.attachment_id = $row.id)"
            // Template heads do not change the immutable version bound to a record. Deletes
            // of a used template are forbidden; its version/question rows also have triggers.
            else -> return null
        }
        return """SELECT s.generation, r.record_id, r.start_date, r.end_date, r.archived
            FROM record_revisions r JOIN records h ON h.id = r.record_id
            CROSS JOIN store_state s WHERE s.singleton = 1 AND r.revision = h.current_revision AND $condition"""
    }

    fun current(db: SQLiteDatabase, source: SourceStamp, query: AnalysisQuery): Boolean {
        val state = db.rawQuery("SELECT store_id, generation, scope_generation_floor FROM store_state WHERE singleton = 1", null).use {
            check(it.moveToFirst()); Triple(it.getString(0), it.getLong(1), it.getLong(2))
        }
        if (source.storeId != state.first || source.generation < state.third || source.generation > state.second) return false
        if (source.generation == state.second) return true
        val where = mutableListOf("generation > ?", "(start_date IS NULL OR (end_date >= ? AND start_date <= ?))")
        val args = mutableListOf(source.generation.toString(), query.from.toString(), query.through.toString())
        if (!query.includeArchived) where += "archived = 0"
        if (query.recordIds == null) {
            val (configurationOnly, values) = TopicAliasStore.entirelyConfiguration("source_scope_changes.record_id")
            where += "NOT ($configurationOnly)"
            args += values
        }
        query.recordIds?.let { ids ->
            where += "record_id IN (${ids.joinToString(",") { "?" }})"
            args += ids.sorted()
        }
        return db.rawQuery("SELECT 1 FROM source_scope_changes WHERE ${where.joinToString(" AND ")} LIMIT 1", args.toTypedArray()).use { !it.moveToFirst() }
    }

    fun resetAfterRestore(db: SQLiteDatabase) {
        db.delete("source_scope_changes", null, null)
        db.execSQL("UPDATE store_state SET scope_generation_floor = generation WHERE singleton = 1")
    }

    /** Caller first proves all revisions were configuration and erases explicit-scope tasks,
     * including their deletion fences. No life evidence ever depended on these journal rows. */
    fun forgetDeletedConfiguration(db: SQLiteDatabase, recordId: String) {
        require(db.inTransaction())
        db.delete("source_scope_changes", "record_id = ?", arrayOf(recordId))
    }
}
