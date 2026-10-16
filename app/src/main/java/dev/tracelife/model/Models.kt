package dev.tracelife.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Occurrence(
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = startDate,
    val zoneId: String = ZoneId.systemDefault().id,
) {
    init {
        require((startDate == null) == (endDate == null)) { "起止日期须同时填写或同时留空" }
        require(startDate == null || !endDate!!.isBefore(startDate)) { "结束日期不能早于开始日期" }
        ZoneId.of(zoneId)
    }
}

enum class Cadence { DAILY, WEEKLY, EVENT }

data class TemplateQuestion(
    val id: String,
    val prompt: String,
    val hint: String = "",
    val intent: String = "custom",
)

data class TemplateDraft(
    val name: String,
    val cadence: Cadence,
    val description: String = "",
    val questions: List<TemplateQuestion>,
    val sourceMarkdown: String = "",
)

data class TemplateRef(val id: String, val version: Int)

data class TemplateVersion(
    val id: String,
    val version: Int,
    val draft: TemplateDraft,
    val createdAt: Instant,
    val archived: Boolean = false,
)

data class RecordDraft(
    val body: String = "",
    val occurrence: Occurrence = Occurrence(),
    val topics: Set<String> = emptySet(),
    val template: TemplateRef? = null,
    val answers: Map<String, String> = emptyMap(),
    val attachmentIds: Set<String> = emptySet(),
)

data class RecordRevision(
    val id: String,
    val revision: Int,
    val draft: RecordDraft,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archived: Boolean = false,
)

data class RecordFilter(
    val from: LocalDate? = null,
    val through: LocalDate? = null,
    val topic: String? = null,
    val includeArchived: Boolean = false,
    val includeUnknown: Boolean = true,
    val limit: Int = 50,
    val offset: Int = 0,
)

data class RecordPage(
    val items: List<RecordRevision>,
    val total: Int,
    val unknownTimeCount: Int,
    val nextOffset: Int?,
)

data class Attachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class ChangeEvent(
    val id: Long,
    val entityId: String,
    val revision: Int,
    val kind: String,
    val createdAt: Instant,
)

class RevisionConflictException(message: String) : IllegalStateException(message)
