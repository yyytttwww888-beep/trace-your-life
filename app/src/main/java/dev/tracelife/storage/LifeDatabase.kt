package dev.tracelife.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Versioned source data. Derived search indexes and AI state are added by later migrations. */
internal class LifeDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        // Deleted material must not remain in SQLite's reusable pages.
        db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        statements.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("缺少数据库迁移：$oldVersion → $newVersion")
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
