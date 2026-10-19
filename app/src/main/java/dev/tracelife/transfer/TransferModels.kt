package dev.tracelife.transfer

import dev.tracelife.model.Attachment
import dev.tracelife.model.ChangeEvent
import dev.tracelife.model.RecordRevision
import dev.tracelife.model.TemplateVersion
import dev.tracelife.storage.StoredAnalysisRun
import dev.tracelife.storage.StoredClaimFeedback
import dev.tracelife.storage.StoredKnowledgeRevision
import dev.tracelife.storage.KnowledgeHead
import dev.tracelife.actions.ActionHead
import dev.tracelife.actions.StoredActionRevision
import dev.tracelife.profile.ProfileHead
import dev.tracelife.profile.StoredProfileRevision
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Trusted app transfer data, never an unrestricted model-reading interface. */
data class RepositorySnapshot(
    val snapshotId: String,
    val storeId: String,
    val generation: Long,
    val capturedAt: Instant,
    val records: List<RecordRevision>,
    val templates: List<TemplateVersion>,
    val attachments: List<Attachment>,
    val deletedRecords: List<Deletion>,
    val deletedTemplates: List<Deletion>,
    val changes: List<ChangeEvent>,
    val sourceSchemaVersion: Int = 7,
    val analysisRuns: List<StoredAnalysisRun> = emptyList(),
    val analysisGeneration: Long = 0,
    val deletedAnalysisRuns: List<Deletion> = emptyList(),
    val claimFeedback: List<StoredClaimFeedback> = emptyList(),
    val deletedClaimFeedback: List<Deletion> = emptyList(),
    val knowledgeRevisions: List<StoredKnowledgeRevision> = emptyList(),
    val knowledgeHeads: List<KnowledgeHead> = emptyList(),
    val deletedKnowledge: List<Deletion> = emptyList(),
    val actionRevisions: List<StoredActionRevision> = emptyList(),
    val actionHeads: List<ActionHead> = emptyList(),
    val deletedActions: List<Deletion> = emptyList(),
    val profileRevisions: List<StoredProfileRevision> = emptyList(),
    val profileHeads: List<ProfileHead> = emptyList(),
    val deletedProfiles: List<Deletion> = emptyList(),
)

data class Deletion(val id: String, val lastRevision: Int, val deletedAt: Instant)

data class RestoreResult(
    val records: Int,
    val revisions: Int,
    val templates: Int,
    val attachments: Int,
    val suppressedRecords: Int,
    val generation: Long,
    val capacityPurgedAnalysisRuns: Int = 0,
)

/** Null topics means every topic, including unclassified records. */
data class ExportScope(
    val from: LocalDate? = null,
    val through: LocalDate? = null,
    val topics: Set<String>? = null,
    val includeUnknown: Boolean = true,
    val includeArchived: Boolean = true,
) {
    init {
        require(listOfNotNull(from, through).all { it.year in 1..9999 }) { "日期范围无效" }
        require(from == null || through == null || from <= through) { "结束日期早于开始日期" }
        require(topics == null || topics.isNotEmpty() && topics.all { it.isNotBlank() }) { "主题范围无效" }
    }
}

enum class ReadingPackageKind { KNOWLEDGE, EVIDENCE }

data class ReadingExportOptions(
    val kind: ReadingPackageKind = ReadingPackageKind.KNOWLEDGE,
    val scope: ExportScope = ExportScope(),
    val includeAttachments: Boolean = false,
    val pageSize: Int = 50,
    val question: String? = null,
    val profileZoneId: String = "UTC",
    val profileRecentDays: Int = 30,
) {
    init {
        require(pageSize in 1..100) { "索引分页须为 1 至 100 条" }
        require(question == null || question.length <= 2_000) { "问题过长" }
        require(kind != ReadingPackageKind.EVIDENCE || !question.isNullOrBlank()) { "证据包须注明任务问题" }
        ZoneId.of(profileZoneId)
        require(profileRecentDays in 1..366) { "画像近期窗口须为 1 至 366 天" }
    }
}

data class ExportResult(
    val snapshotId: String,
    val records: Int,
    val attachments: Int,
    val files: Int,
    val bytes: Long,
)

class StaleSnapshotException(message: String) : IllegalStateException(message)
class InvalidBackupException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
