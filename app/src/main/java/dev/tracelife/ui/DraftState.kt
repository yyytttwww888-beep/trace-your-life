package dev.tracelife.ui

import android.os.Bundle
import dev.tracelife.model.Cadence
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordRevision
import dev.tracelife.model.TemplateRef
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** UI state only: uncommitted text is kept verbatim across Activity recreation. */
internal data class EditorState(
    val id: String = UUID.randomUUID().toString(),
    val expectedRevision: Int? = null,
    var body: String = "",
    var dateMode: Int = 0,
    var start: String = LocalDate.now().toString(),
    var end: String = start,
    val zoneId: String = ZoneId.systemDefault().id,
    var topics: String = "",
    val template: TemplateRef? = null,
    val answers: MutableMap<String, String> = linkedMapOf(),
    val attachments: MutableSet<String> = linkedSetOf(),
) {
    fun draft() = RecordDraft(
        body = body,
        occurrence = if (dateMode == 2) Occurrence(zoneId = zoneId) else {
            val from = parseDate(start, "发生日期")
            Occurrence(from, if (dateMode == 1) parseDate(end, "结束日期") else from, zoneId)
        },
        topics = topics.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        template = template,
        answers = answers.filterValues { it.isNotBlank() },
        attachmentIds = attachments.toSet(),
    )

    fun save() = Bundle().apply {
        putString("id", id)
        putInt("revision", expectedRevision ?: -1)
        putString("body", body)
        putInt("dateMode", dateMode)
        putString("start", start)
        putString("end", end)
        putString("zoneId", zoneId)
        putString("topics", topics)
        putString("templateId", template?.id)
        putInt("templateVersion", template?.version ?: 0)
        putBundle("answers", Bundle().apply { answers.forEach { (key, value) -> putString(key, value) } })
        putStringArrayList("attachments", ArrayList(attachments))
    }

    companion object {
        fun from(record: RecordRevision) = EditorState(
            id = record.id,
            expectedRevision = record.revision,
            body = record.draft.body,
            dateMode = when {
                record.draft.occurrence.startDate == null -> 2
                record.draft.occurrence.startDate != record.draft.occurrence.endDate -> 1
                else -> 0
            },
            start = record.draft.occurrence.startDate?.toString().orEmpty(),
            end = record.draft.occurrence.endDate?.toString().orEmpty(),
            zoneId = record.draft.occurrence.zoneId,
            topics = record.draft.topics.joinToString("，"),
            template = record.draft.template,
            answers = record.draft.answers.toMutableMap(),
            attachments = record.draft.attachmentIds.toMutableSet(),
        )

        fun restore(bundle: Bundle) = EditorState(
            id = requireNotNull(bundle.getString("id")),
            expectedRevision = bundle.getInt("revision", -1).takeIf { it >= 0 },
            body = bundle.getString("body").orEmpty(),
            dateMode = bundle.getInt("dateMode"),
            start = bundle.getString("start").orEmpty(),
            end = bundle.getString("end").orEmpty(),
            zoneId = bundle.getString("zoneId") ?: ZoneId.systemDefault().id,
            topics = bundle.getString("topics").orEmpty(),
            template = bundle.getString("templateId")?.let { TemplateRef(it, bundle.getInt("templateVersion")) },
            answers = bundle.getBundle("answers")?.let { saved ->
                saved.keySet().associateWith { saved.getString(it).orEmpty() }.toMutableMap()
            } ?: linkedMapOf(),
            attachments = bundle.getStringArrayList("attachments")?.toMutableSet() ?: linkedSetOf(),
        )
    }
}

internal data class TemplateEditorState(
    val id: String = UUID.randomUUID().toString(),
    val expectedVersion: Int? = null,
    var name: String = "个人复盘",
    var cadence: Cadence = Cadence.DAILY,
    var markdown: String = "",
) {
    fun save() = Bundle().apply {
        putString("id", id)
        putInt("version", expectedVersion ?: -1)
        putString("name", name)
        putString("cadence", cadence.name)
        putString("markdown", markdown)
    }

    companion object {
        fun restore(bundle: Bundle) = TemplateEditorState(
            id = requireNotNull(bundle.getString("id")),
            expectedVersion = bundle.getInt("version", -1).takeIf { it >= 0 },
            name = bundle.getString("name").orEmpty(),
            cadence = Cadence.valueOf(bundle.getString("cadence") ?: Cadence.DAILY.name),
            markdown = bundle.getString("markdown").orEmpty(),
        )
    }
}

internal fun parseDate(text: String, label: String): LocalDate = try {
    LocalDate.parse(text.trim())
} catch (_: java.time.format.DateTimeParseException) {
    throw IllegalArgumentException("${label}请填写 YYYY-MM-DD，例如 2026-10-16")
}
