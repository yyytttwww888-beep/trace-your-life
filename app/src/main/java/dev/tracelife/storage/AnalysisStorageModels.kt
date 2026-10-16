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
)

class UnreadableSourceException(message: String) : IllegalArgumentException(message)
