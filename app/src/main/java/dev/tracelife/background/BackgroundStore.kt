package dev.tracelife.background

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** Separate device-local operational database; deliberately absent from personal backup snapshots. */
internal class BackgroundStore(private val directory: File) : Closeable {
    private val path = File(directory, "queue.db")
    private val mutex = locks.computeIfAbsent(path.canonicalPath) { Any() }
    private val database: SQLiteDatabase

    init {
        check(directory.isDirectory || directory.mkdirs())
        database = synchronized(mutex) {
            SQLiteDatabase.openOrCreateDatabase(path, null).also { db ->
                db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
                db.beginTransaction()
                try {
                    require(db.version in 0..1) { "不支持的后台操作库版本" }
                    if (db.version == 0) {
                        db.execSQL("""CREATE TABLE settings (
                            singleton INTEGER PRIMARY KEY CHECK(singleton=1), version INTEGER NOT NULL,
                            enabled INTEGER NOT NULL, paused INTEGER NOT NULL, zone_id TEXT NOT NULL,
                            pass_limit INTEGER NOT NULL, daily_limit INTEGER NOT NULL, job_limit INTEGER NOT NULL,
                            unmetered INTEGER NOT NULL, store_id TEXT NOT NULL, restore_epoch INTEGER NOT NULL,
                            event_cursor INTEGER NOT NULL, usage_day TEXT NOT NULL, reserved_today INTEGER NOT NULL,
                            last_observed_at TEXT NOT NULL, restore_disabled INTEGER NOT NULL)""")
                        db.execSQL("""CREATE TABLE jobs (
                            id TEXT PRIMARY KEY, version INTEGER NOT NULL, record_id TEXT NOT NULL,
                            record_revision INTEGER NOT NULL, event_id INTEGER NOT NULL, status TEXT NOT NULL,
                            run_id TEXT, run_sequence INTEGER NOT NULL, reserved_requests INTEGER NOT NULL,
                            retry_authorized INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""")
                        db.execSQL("CREATE INDEX jobs_status ON jobs(status, event_id, id)")
                        db.execSQL("CREATE INDEX jobs_record ON jobs(record_id, record_revision)")
                        db.version = 1
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
    }

    fun <T> transaction(block: BackgroundStore.() -> T): T = synchronized(mutex) {
        database.beginTransaction()
        try { block().also { database.setTransactionSuccessful() } } finally { database.endTransaction() }
    }

    fun settings(): BackgroundSettings? = database.rawQuery("SELECT * FROM settings WHERE singleton=1", null).use { c ->
        if (!c.moveToFirst()) null else BackgroundSettings(c.int("version"), BackgroundPolicy(
            c.bool("enabled"), c.bool("paused"), c.string("zone_id"), c.int("pass_limit"),
            c.int("daily_limit"), c.int("job_limit"), c.bool("unmetered")), c.string("store_id"),
            c.long("restore_epoch"), c.long("event_cursor"), LocalDate.parse(c.string("usage_day")),
            c.int("reserved_today"), Instant.parse(c.string("last_observed_at")), c.bool("restore_disabled"))
    }

    fun save(value: BackgroundSettings) {
        val p = value.policy
        database.insertWithOnConflict("settings", null, values("singleton" to 1, "version" to value.version,
            "enabled" to p.enabled, "paused" to p.paused, "zone_id" to p.zoneId, "pass_limit" to p.maxRequestsPerPass,
            "daily_limit" to p.dailyRequestLimit, "job_limit" to p.maxRequestsPerJob, "unmetered" to p.unmeteredOnly,
            "store_id" to value.storeId, "restore_epoch" to value.restoreEpoch, "event_cursor" to value.eventCursor,
            "usage_day" to value.usageDay.toString(), "reserved_today" to value.reservedRequestsToday,
            "last_observed_at" to value.lastObservedAt.toString(), "restore_disabled" to value.requiresEnableAfterRestore),
            SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    fun job(id: String): BackgroundJob? = database.rawQuery("SELECT * FROM jobs WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) c.job() else null
    }

    fun jobs(offset: Int, limit: Int): List<BackgroundJob> {
        require(offset >= 0 && limit in 1..100)
        return query("SELECT * FROM jobs ORDER BY event_id, id LIMIT ? OFFSET ?", arrayOf(limit.toString(), offset.toString()))
    }

    fun next(): BackgroundJob? = query("SELECT * FROM jobs WHERE status='QUEUED' ORDER BY event_id,id LIMIT 1").firstOrNull()
    fun count(status: BackgroundJobStatus? = null): Int = database.rawQuery(
        if (status == null) "SELECT COUNT(*) FROM jobs" else "SELECT COUNT(*) FROM jobs WHERE status=?",
        status?.let { arrayOf(it.name) }).use { it.moveToFirst(); it.getInt(0) }

    fun save(value: BackgroundJob) {
        database.insertWithOnConflict("jobs", null, values("id" to value.id, "version" to value.version,
            "record_id" to value.recordId, "record_revision" to value.recordRevision, "event_id" to value.eventId,
            "status" to value.status.name, "run_id" to value.runId, "run_sequence" to value.runSequence,
            "reserved_requests" to value.reservedRequests, "retry_authorized" to value.retryAuthorized,
            "created_at" to value.createdAt.toString(), "updated_at" to value.updatedAt.toString()),
            SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    fun supersede(recordId: String, revision: Int, now: Instant) {
        database.execSQL("""UPDATE jobs SET status='SUPERSEDED',version=version+1,updated_at=?
            WHERE record_id=? AND record_revision<>? AND status IN ('QUEUED','RUNNING')""",
            arrayOf<Any>(now.toString(), recordId, revision))
    }

    fun unavailable(recordId: String, now: Instant) {
        database.execSQL("""UPDATE jobs SET status='SOURCE_UNAVAILABLE',version=version+1,updated_at=?
            WHERE record_id=? AND status IN ('QUEUED','RUNNING')""", arrayOf(now.toString(), recordId))
    }

    fun recoverOrphans(now: Instant) {
        database.execSQL("""UPDATE jobs SET status='NEEDS_RECOVERY',version=version+1,updated_at=?
            WHERE status='RUNNING'""", arrayOf(now.toString()))
    }

    fun clearJobs() { database.delete("jobs", null, null) }
    fun delete(id: String) { database.delete("jobs", "id=?", arrayOf(id)) }

    /** A process death releases the OS lock; RUNNING jobs then require explicit recovery. */
    fun lease(): Closeable? {
        val channel = FileChannel.open(File(directory, "dispatch.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val held: FileLock? = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
        if (held == null) { channel.close(); return null }
        return Closeable { try { held.release() } finally { channel.close() } }
    }

    override fun close() = synchronized(mutex) { database.close() }

    private fun query(sql: String, args: Array<String>? = null) = database.rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(c.job()) }
    }
    private fun Cursor.job() = BackgroundJob(string("id"), int("version"), string("record_id"), int("record_revision"),
        long("event_id"), BackgroundJobStatus.valueOf(string("status")), nullable("run_id"), int("run_sequence"),
        int("reserved_requests"), bool("retry_authorized"), Instant.parse(string("created_at")), Instant.parse(string("updated_at")))
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.nullable(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.bool(name: String) = int(name) == 1
    private fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        pairs.forEach { (key, value) -> when (value) {
            null -> putNull(key)
            is String -> put(key, value)
            is Int -> put(key, value)
            is Long -> put(key, value)
            is Boolean -> put(key, if (value) 1 else 0)
            else -> error("Unsupported operational value")
        } }
    }
    private companion object { val locks = ConcurrentHashMap<String, Any>() }
}
