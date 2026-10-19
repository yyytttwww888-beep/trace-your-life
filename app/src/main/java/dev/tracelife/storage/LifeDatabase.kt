package dev.tracelife.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/** Source writes and saved analysis progress have separate generations. */
internal class LifeDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 8) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        // Deleted material must not remain in SQLite's reusable pages.
        db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        statements.forEach(db::execSQL)
        upgradeToVersionTwo(db)
        upgradeToVersionThree(db)
        upgradeToVersionFour(db)
        upgradeToVersionFive(db)
        upgradeToVersionSix(db)
        upgradeToVersionSeven(db)
        upgradeToVersionEight(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..7 && newVersion == 8) { "缺少数据库迁移：$oldVersion → $newVersion" }
        if (oldVersion < 2) upgradeToVersionTwo(db)
        if (oldVersion < 3) upgradeToVersionThree(db)
        if (oldVersion < 4) upgradeToVersionFour(db)
        if (oldVersion < 5) upgradeToVersionFive(db)
        if (oldVersion < 6) upgradeToVersionSix(db)
        if (oldVersion < 7) upgradeToVersionSeven(db)
        upgradeToVersionEight(db)
    }

    private fun upgradeToVersionEight(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE store_state ADD COLUMN restore_epoch INTEGER NOT NULL DEFAULT 0 CHECK(restore_epoch >= 0)")
        db.execSQL("CREATE TABLE dashboard_preferences (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), payload TEXT NOT NULL)")
        listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
            db.execSQL("""CREATE TRIGGER analysis_generation_dashboard_preferences_${operation.lowercase()} AFTER $operation ON dashboard_preferences
                BEGIN UPDATE store_state SET analysis_generation = analysis_generation + 1 WHERE singleton = 1; END""")
        }
    }

    private fun upgradeToVersionSeven(db: SQLiteDatabase) {
        // No foreign key to knowledge: deleting machine content must preserve user profile notes.
        db.execSQL("""CREATE TABLE profile_revisions (
            id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0), operation_id TEXT,
            payload TEXT NOT NULL, encoded_bytes INTEGER NOT NULL CHECK(encoded_bytes > 0),
            PRIMARY KEY(id, revision), UNIQUE(id, operation_id))""")
        db.execSQL("""CREATE TABLE profile_heads (
            id TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK(revision > 0),
            knowledge_id TEXT NOT NULL, dimension TEXT NOT NULL, layer TEXT NOT NULL,
            UNIQUE(knowledge_id, dimension, layer),
            FOREIGN KEY(id, revision) REFERENCES profile_revisions(id, revision))""")
        db.execSQL("CREATE TABLE deleted_profiles (id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)")
        listOf("profile_revisions", "profile_heads", "deleted_profiles").forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                db.execSQL("""CREATE TRIGGER analysis_generation_${table}_${operation.lowercase()} AFTER $operation ON $table
                    BEGIN UPDATE store_state SET analysis_generation = analysis_generation + 1 WHERE singleton = 1; END""")
            }
        }
    }

    private fun upgradeToVersionSix(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE action_revisions (
            id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0), operation_id TEXT,
            payload TEXT NOT NULL, encoded_bytes INTEGER NOT NULL CHECK(encoded_bytes > 0),
            PRIMARY KEY(id, revision), UNIQUE(id, operation_id))""")
        db.execSQL("""CREATE TABLE action_heads (
            id TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK(revision > 0),
            status TEXT NOT NULL, review_on TEXT NOT NULL, zone_id TEXT NOT NULL,
            FOREIGN KEY(id, revision) REFERENCES action_revisions(id, revision))""")
        db.execSQL("CREATE INDEX action_due_lookup ON action_heads(status, review_on, id)")
        db.execSQL("CREATE TABLE deleted_actions (id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)")
        listOf("action_revisions", "action_heads", "deleted_actions").forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                db.execSQL("""CREATE TRIGGER analysis_generation_${table}_${operation.lowercase()} AFTER $operation ON $table
                    BEGIN UPDATE store_state SET analysis_generation = analysis_generation + 1 WHERE singleton = 1; END""")
            }
        }
    }

    private fun upgradeToVersionFive(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE knowledge_revisions (
            id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0),
            run_id TEXT NOT NULL, feedback_id TEXT NOT NULL, payload TEXT NOT NULL,
            encoded_bytes INTEGER NOT NULL CHECK(encoded_bytes > 0),
            PRIMARY KEY(id, revision))""")
        db.execSQL("""CREATE TABLE knowledge_heads (
            id TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK(revision > 0),
            FOREIGN KEY(id, revision) REFERENCES knowledge_revisions(id, revision))""")
        db.execSQL("CREATE INDEX knowledge_run_lookup ON knowledge_revisions(run_id)")
        db.execSQL("CREATE INDEX knowledge_feedback_lookup ON knowledge_revisions(feedback_id)")
        db.execSQL("CREATE TABLE deleted_knowledge (id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)")
        listOf("knowledge_revisions", "knowledge_heads", "deleted_knowledge").forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                db.execSQL("""CREATE TRIGGER analysis_generation_${table}_${operation.lowercase()} AFTER $operation ON $table
                    BEGIN UPDATE store_state SET analysis_generation = analysis_generation + 1 WHERE singleton = 1; END""")
            }
        }
    }

    private fun upgradeToVersionFour(db: SQLiteDatabase) {
        // No cascade to tasks: explicit user wording and rejection remain authoritative after
        // a task or source is deleted. Source availability is evaluated at read time.
        db.execSQL("""CREATE TABLE claim_feedback (
            id TEXT PRIMARY KEY, version INTEGER NOT NULL CHECK(version > 0),
            run_id TEXT NOT NULL, batch_key TEXT NOT NULL, claim_index INTEGER NOT NULL,
            claim_sha256 TEXT NOT NULL, source_store_id TEXT NOT NULL,
            source_generation INTEGER NOT NULL CHECK(source_generation >= 0),
            decision TEXT NOT NULL, user_text TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
            encoded_bytes INTEGER NOT NULL CHECK(encoded_bytes > 0))""")
        db.execSQL("CREATE INDEX claim_feedback_run_lookup ON claim_feedback(run_id)")
        db.execSQL("CREATE TABLE deleted_claim_feedback (id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)")
        listOf("claim_feedback", "deleted_claim_feedback").forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                db.execSQL("""CREATE TRIGGER analysis_generation_${table}_${operation.lowercase()} AFTER $operation ON $table
                    BEGIN UPDATE store_state SET analysis_generation = analysis_generation + 1 WHERE singleton = 1; END""")
            }
        }
    }

    private fun upgradeToVersionThree(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE store_state ADD COLUMN analysis_generation INTEGER NOT NULL DEFAULT 0 CHECK(analysis_generation >= 0)")
        db.execSQL("""CREATE TABLE analysis_runs (
            id TEXT PRIMARY KEY, version INTEGER NOT NULL CHECK(version > 0),
            source_store_id TEXT NOT NULL, source_generation INTEGER NOT NULL CHECK(source_generation >= 0),
            created_at TEXT NOT NULL, updated_at TEXT NOT NULL, expires_at TEXT, payload TEXT NOT NULL,
            reserved_bytes INTEGER NOT NULL DEFAULT 0 CHECK(reserved_bytes >= 0))""")
        db.execSQL("CREATE TABLE deleted_analysis_runs (id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)")
        listOf("analysis_runs", "deleted_analysis_runs").forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                db.execSQL("""CREATE TRIGGER analysis_generation_${table}_${operation.lowercase()} AFTER $operation ON $table
                    BEGIN UPDATE store_state SET analysis_generation = analysis_generation + 1 WHERE singleton = 1; END""")
            }
        }
    }

    private fun upgradeToVersionTwo(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE store_state (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), store_id TEXT NOT NULL, generation INTEGER NOT NULL CHECK(generation >= 0))")
        db.execSQL("INSERT INTO store_state(singleton, store_id, generation) VALUES(1, ?, 0)", arrayOf(UUID.randomUUID().toString()))
        db.execSQL("CREATE TABLE deleted_templates (id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)")
        // V1 already retained ID-only delete events. Preserve those identities during migration.
        db.execSQL("""INSERT INTO deleted_templates(id, last_revision, deleted_at)
            SELECT entity_id, MAX(revision), MAX(created_at) FROM change_events
            WHERE kind = 'TEMPLATE_DELETED' AND entity_id NOT IN (SELECT id FROM templates) GROUP BY entity_id""")
        db.execSQL("DELETE FROM change_events WHERE kind = 'TEMPLATE_DELETED' AND entity_id IN (SELECT id FROM templates)")
        val authoritativeTables = listOf("templates", "template_versions", "template_questions", "records",
            "record_revisions", "record_topics", "record_answers", "attachments", "record_attachments",
            "deleted_records", "deleted_templates", "change_events")
        authoritativeTables.forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                db.execSQL("""CREATE TRIGGER generation_${table}_${operation.lowercase()} AFTER $operation ON $table
                    BEGIN UPDATE store_state SET generation = generation + 1 WHERE singleton = 1; END""")
            }
        }
    }

    private val statements = listOf(
        """CREATE TABLE templates (
            id TEXT PRIMARY KEY, current_version INTEGER NOT NULL,
            archived INTEGER NOT NULL DEFAULT 0 CHECK(archived IN (0,1)))""",
        """CREATE TABLE template_versions (
            template_id TEXT NOT NULL, version INTEGER NOT NULL,
            name TEXT NOT NULL, cadence TEXT NOT NULL, description TEXT NOT NULL,
            source_markdown TEXT NOT NULL, created_at TEXT NOT NULL,
            PRIMARY KEY(template_id, version),
            FOREIGN KEY(template_id) REFERENCES templates(id) ON DELETE CASCADE)""",
        """CREATE TABLE template_questions (
            template_id TEXT NOT NULL, version INTEGER NOT NULL, question_id TEXT NOT NULL,
            ordinal INTEGER NOT NULL, prompt TEXT NOT NULL, hint TEXT NOT NULL, intent TEXT NOT NULL,
            PRIMARY KEY(template_id, version, question_id),
            FOREIGN KEY(template_id, version) REFERENCES template_versions(template_id, version) ON DELETE CASCADE)""",
        """CREATE TABLE records (
            id TEXT PRIMARY KEY, current_revision INTEGER NOT NULL, created_at TEXT NOT NULL)""",
        """CREATE TABLE record_revisions (
            record_id TEXT NOT NULL, revision INTEGER NOT NULL,
            body TEXT NOT NULL, start_date TEXT, end_date TEXT, zone_id TEXT NOT NULL,
            template_id TEXT, template_version INTEGER, updated_at TEXT NOT NULL,
            archived INTEGER NOT NULL CHECK(archived IN (0,1)),
            PRIMARY KEY(record_id, revision),
            CHECK((start_date IS NULL) = (end_date IS NULL)),
            CHECK((template_id IS NULL) = (template_version IS NULL)),
            FOREIGN KEY(record_id) REFERENCES records(id) ON DELETE CASCADE,
            FOREIGN KEY(template_id, template_version) REFERENCES template_versions(template_id, version))""",
        """CREATE TABLE record_topics (
            record_id TEXT NOT NULL, revision INTEGER NOT NULL, topic TEXT NOT NULL,
            PRIMARY KEY(record_id, revision, topic),
            FOREIGN KEY(record_id, revision) REFERENCES record_revisions(record_id, revision) ON DELETE CASCADE)""",
        """CREATE TABLE record_answers (
            record_id TEXT NOT NULL, revision INTEGER NOT NULL,
            question_id TEXT NOT NULL, answer TEXT NOT NULL,
            PRIMARY KEY(record_id, revision, question_id),
            FOREIGN KEY(record_id, revision) REFERENCES record_revisions(record_id, revision) ON DELETE CASCADE)""",
        """CREATE TABLE attachments (
            id TEXT PRIMARY KEY, display_name TEXT NOT NULL, mime_type TEXT NOT NULL,
            size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL)""",
        """CREATE TABLE record_attachments (
            record_id TEXT NOT NULL, revision INTEGER NOT NULL, attachment_id TEXT NOT NULL,
            PRIMARY KEY(record_id, revision, attachment_id),
            FOREIGN KEY(record_id, revision) REFERENCES record_revisions(record_id, revision) ON DELETE CASCADE,
            FOREIGN KEY(attachment_id) REFERENCES attachments(id))""",
        """CREATE TABLE deleted_records (
            id TEXT PRIMARY KEY, last_revision INTEGER NOT NULL, deleted_at TEXT NOT NULL)""",
        """CREATE TABLE change_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT, entity_id TEXT NOT NULL,
            revision INTEGER NOT NULL, kind TEXT NOT NULL, created_at TEXT NOT NULL)""",
        "CREATE TABLE attachment_garbage (id TEXT PRIMARY KEY)",
        "CREATE INDEX record_topic_lookup ON record_topics(topic, record_id, revision)",
        "CREATE INDEX record_attachment_lookup ON record_attachments(attachment_id)",
        "CREATE INDEX record_time_lookup ON record_revisions(start_date, end_date)",
        "CREATE INDEX record_template_lookup ON record_revisions(template_id, template_version)",
        "CREATE INDEX change_entity_lookup ON change_events(entity_id)",
    )
}
