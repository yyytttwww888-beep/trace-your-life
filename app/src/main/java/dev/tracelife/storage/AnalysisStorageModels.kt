package dev.tracelife.storage

import dev.tracelife.model.Occurrence
import java.time.Instant
import java.time.LocalDate

/** A source-only token: saving analysis progress does not change it. */
data class SourceStamp(val storeId: String, val generation: Long)

/** Known occurrence intervals overlap this fixed scope; unknown dates remain explicit candidates. */
data class AnalysisQuery(
    val from: LocalDate,
    val through: LocalDate,
    val zoneId: String,
    val includeArchived: Boolean = false,
    val recordIds: Set<String>? = null,
)

enum class SourceTimeClass { WITHIN, OVERLAP, UNKNOWN }

data class SourceDescriptor(
    val id: String,
    val revision: Int,
    val occurrence: Occurrence,
    val archived: Boolean,
    val topics: Set<String>,
    val timeClass: SourceTimeClass,
)

data class SourcePage(
    val items: List<SourceDescriptor>,
    val total: Int,
    val unknown: Int,
    val overlap: Int,
    val nextOffset: Int?,
)

enum class SourceFieldKind { TEXT, ATTACHMENT }

data class SourceField(
    val key: String,
    val codePoints: Int,
    val kind: SourceFieldKind,
    val attachmentId: String? = null,
)

/** [start] and [end] are zero-based Unicode code point offsets; end is exclusive. */
data class SourceFragment(
    val recordId: String,
    val revision: Int,
    val field: String,
    val start: Int,
    val end: Int,
    val totalCodePoints: Int,
    val text: String,
    val question: String? = null,
)

/** Durable bounded runtime envelope. Its version is an optimistic concurrency token. */
data class StoredAnalysisRun(
    val id: String,
    val version: Int,
    val source: SourceStamp,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant?,
    val payload: String,
    val archivedAt: Instant? = null,
)

class UnreadableSourceException(message: String) : IllegalArgumentException(message)

/** Payload-free history row; lifecycle never changes evidence validity. */
data class AnalysisRunIndex(
    val id: String, val version: Int, val createdAt: Instant, val updatedAt: Instant,
    val expiresAt: Instant?, val archivedAt: Instant?,
)

data class AnalysisStorageUsage(val activeTasks: Int, val activeBytes: Long, val archivedTasks: Int, val archivedBytes: Long)

internal object AnalysisStorageLimits {
    const val ACTIVE_TASKS = 100
    const val ACTIVE_BYTES = 16L * 1024 * 1024
    const val ARCHIVED_TASKS = 10_000
    const val ARCHIVED_BYTES = 64L * 1024 * 1024

    fun validate(runs: List<StoredAnalysisRun>, includeReservations: Boolean = true) {
        require(runs.count { it.archivedAt == null } <= ACTIVE_TASKS && runs.count { it.archivedAt != null } <= ARCHIVED_TASKS) { "分析活动任务或归档数量超过上限" }
        var active = 0L
        var archived = 0L
        runs.forEach { run ->
            val bytes = run.payload.toByteArray(Charsets.UTF_8).size.toLong()
            require(bytes in 1..2L * 1024 * 1024) { "单个分析任务超过大小上限" }
            if (run.archivedAt == null) active += bytes + if (includeReservations) dev.tracelife.analysis.AnalysisRunCodec.reservedResponseBytes(run) else 0
            else {
                require(run.archivedAt >= run.createdAt) { "归档时间早于任务创建" }
                require(dev.tracelife.analysis.AnalysisRunCodec.reservedResponseBytes(run) == 0) { "归档任务不能包含在途调用" }
                archived += bytes
            }
        }
        require(active <= ACTIVE_BYTES && archived <= ARCHIVED_BYTES) { "分析活动任务或归档内容超过大小上限" }
    }
}
