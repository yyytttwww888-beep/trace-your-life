package dev.tracelife.storage

import android.database.sqlite.SQLiteDatabase

/** Recreate the released pre-v9 source schema for migration fixtures, not runtime downgrade. */
internal fun removeVersionNineForMigration(db: SQLiteDatabase) {
    removeVersionTenForMigration(db)
    db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'generation_%'", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }.forEach { db.execSQL("DROP TRIGGER $it") }
    db.execSQL("DROP TABLE source_scope_changes")
    db.execSQL("ALTER TABLE store_state DROP COLUMN scope_generation_floor")
    listOf("templates", "template_versions", "template_questions", "records", "record_revisions", "record_topics",
        "record_answers", "attachments", "record_attachments", "deleted_records", "deleted_templates", "change_events").forEach { table ->
        listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
            db.execSQL("""CREATE TRIGGER generation_${table}_${operation.lowercase()} AFTER $operation ON $table
                BEGIN UPDATE store_state SET generation = generation + 1 WHERE singleton = 1; END""")
        }
    }
}

/** Recreate v9 without deleting any original analysis proof. */
internal fun removeVersionTenForMigration(db: SQLiteDatabase) {
    removeVersionElevenForMigration(db)
    listOf("experience_changes", "experience_topics", "experience_threads", "deleted_experience_threads",
        "historical_comparisons", "deleted_historical_comparison_runs").forEach {
        db.execSQL("DROP TABLE $it")
    }
    db.execSQL("DROP INDEX analysis_lifecycle_lookup")
    db.execSQL("ALTER TABLE analysis_runs DROP COLUMN archived_at")
}

internal fun removeVersionElevenForMigration(db: SQLiteDatabase) {
    db.execSQL("DROP TABLE conversation_turns")
    db.execSQL("DROP TABLE conversation_threads")
    db.execSQL("DROP TABLE deleted_conversations")
}
