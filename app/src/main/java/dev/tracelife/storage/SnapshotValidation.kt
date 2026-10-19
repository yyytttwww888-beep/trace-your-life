package dev.tracelife.storage

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.AnalysisRunState
import dev.tracelife.analysis.AnalysisRedactionCapacityException
import dev.tracelife.actions.ActionValidation
import dev.tracelife.profile.ProfileValidation
import dev.tracelife.profile.StoredProfileRevision
import dev.tracelife.metrics.RecordMetricProtocol
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.TemplateDraft
import dev.tracelife.transfer.InvalidBackupException
import dev.tracelife.transfer.RepositorySnapshot

/** Validate logical integrity before any target data is replaced. Not a migration/import parser. */
internal object SnapshotValidation {
    private val safeFileId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,199}")
    private val digest = Regex("[0-9a-f]{64}")
    private val kinds = setOf("RECORD_CREATED", "RECORD_UPDATED", "RECORD_ARCHIVED", "RECORD_UNARCHIVED", "RECORD_DELETED",
        "TEMPLATE_CREATED", "TEMPLATE_UPDATED", "TEMPLATE_ARCHIVED", "TEMPLATE_UNARCHIVED", "TEMPLATE_DELETED")

    fun validate(snapshot: RepositorySnapshot) {
        try {
            require(snapshot.sourceSchemaVersion in 2..7) { "不支持的资料版本" }
            require(snapshot.sourceSchemaVersion != 2 || snapshot.analysisRuns.isEmpty() && snapshot.analysisGeneration == 0L && snapshot.deletedAnalysisRuns.isEmpty()) { "旧版资料不能包含分析任务" }
            require(snapshot.sourceSchemaVersion >= 4 || snapshot.claimFeedback.isEmpty() && snapshot.deletedClaimFeedback.isEmpty()) { "旧版资料不能包含反馈" }
            require(snapshot.sourceSchemaVersion >= 5 || snapshot.knowledgeRevisions.isEmpty() && snapshot.knowledgeHeads.isEmpty() && snapshot.deletedKnowledge.isEmpty()) { "旧版资料不能包含正式知识" }
            require(snapshot.sourceSchemaVersion >= 6 || snapshot.actionRevisions.isEmpty() && snapshot.actionHeads.isEmpty() && snapshot.deletedActions.isEmpty()) { "旧版资料不能包含行动" }
            require(snapshot.sourceSchemaVersion >= 7 || snapshot.profileRevisions.isEmpty() && snapshot.profileHeads.isEmpty() && snapshot.deletedProfiles.isEmpty()) { "旧版资料不能包含画像" }
            id(snapshot.snapshotId); id(snapshot.storeId)
            require(snapshot.generation >= 0) { "快照代数无效" }
            require(snapshot.analysisGeneration >= 0 && snapshot.analysisRuns.size <= 100 &&
                snapshot.analysisRuns.map { it.id }.distinct().size == snapshot.analysisRuns.size) { "分析任务代数、数量或身份无效" }
            require(snapshot.deletedAnalysisRuns.size <= 100_000 && snapshot.deletedAnalysisRuns.map { it.id }.distinct().size == snapshot.deletedAnalysisRuns.size) { "分析任务删除标记过多或重复" }
            snapshot.deletedAnalysisRuns.forEach { id(it.id); require(it.lastRevision > 0) { "分析任务删除版本无效" } }
            require(snapshot.analysisRuns.map { it.id }.toSet().intersect(snapshot.deletedAnalysisRuns.map { it.id }.toSet()).isEmpty()) { "备份同时包含分析任务及其删除标记" }
            require(snapshot.analysisRuns.all { it.payload.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024 } &&
                snapshot.analysisRuns.sumOf { it.payload.toByteArray(Charsets.UTF_8).size.toLong() } <= 16L * 1024 * 1024) { "分析任务内容超过大小上限" }
            require(snapshot.records.size <= 100_000 && snapshot.templates.size <= 10_000 && snapshot.attachments.size <= 10_000 &&
                snapshot.deletedRecords.size <= 100_000 && snapshot.deletedTemplates.size <= 10_000 && snapshot.changes.size <= 200_000) { "备份超出当前恢复数量上限" }
            require(snapshot.records.sumOf { it.draft.body.length.toLong() + it.draft.answers.values.sumOf { answer -> answer.length.toLong() } } +
                snapshot.templates.sumOf { it.draft.sourceMarkdown.length.toLong() + it.draft.questions.sumOf { q -> q.prompt.length.toLong() + q.hint.length } } <= 64L * 1024 * 1024) { "备份文本超出当前恢复上限" }
            val recordGroups = snapshot.records.groupBy { it.id }
            recordGroups.forEach { (recordId, versions) ->
                id(recordId)
                require(versions.map { it.revision }.sorted() == (1..versions.size).toList()) { "记录修订链不完整或重复" }
                require(versions.map { it.createdAt }.distinct().size == 1) { "记录创建时间不一致" }
                versions.forEach { recordDraft(it.draft) }
            }
            val templateGroups = snapshot.templates.groupBy { it.id }
            templateGroups.forEach { (templateId, versions) ->
                id(templateId)
                require(versions.map { it.version }.sorted() == (1..versions.size).toList()) { "模板版本链不完整或重复" }
                require(versions.map { it.archived }.distinct().size == 1) { "模板归档状态不一致" }
                versions.forEach { templateDraft(it.draft) }
            }
            val deletedRecords = snapshot.deletedRecords.associateBy { it.id }
            val deletedTemplates = snapshot.deletedTemplates.associateBy { it.id }
            require(deletedRecords.size == snapshot.deletedRecords.size && deletedTemplates.size == snapshot.deletedTemplates.size) { "删除标记重复" }
            (snapshot.deletedRecords + snapshot.deletedTemplates).forEach { id(it.id); require(it.lastRevision in 1 until Int.MAX_VALUE) { "删除修订无效" } }
            require(recordGroups.keys.intersect(deletedRecords.keys).isEmpty()) { "备份同时包含记录及其删除标记" }
            require(templateGroups.keys.intersect(deletedTemplates.keys).isEmpty()) { "备份同时包含模板及其删除标记" }
            val attachments = snapshot.attachments.associateBy { it.id }
            require(attachments.size == snapshot.attachments.size) { "附件 ID 重复" }
            snapshot.attachments.forEach {
                require(safeFileId.matches(it.id)) { "附件 ID 不是安全文件标识" }
                require(it.displayName.isNotBlank() && it.displayName.length <= 255 && it.mimeType.isNotBlank() && it.mimeType.length <= 200) { "附件元数据无效" }
                require(it.sizeBytes in 1..LifeRepository.MAX_ATTACHMENT_BYTES && digest.matches(it.sha256)) { "附件长度或校验值无效" }
            }
            require(snapshot.attachments.sumOf { it.sizeBytes } <= 2L * 1024 * 1024 * 1024) { "附件总量超过 2 GiB" }
            val templatesByVersion = snapshot.templates.associateBy { it.id to it.version }
            val questionIdsByVersion = templatesByVersion.mapValues { (_, template) -> template.draft.questions.map { it.id }.toSet() }
            snapshot.records.forEach { record ->
                record.draft.template?.let { ref ->
                    val questionIds = requireNotNull(questionIdsByVersion[ref.id to ref.version]) { "记录引用的模板版本不存在" }
                    require(record.draft.answers.keys.all { it in questionIds }) { "复盘答案引用不存在的问题" }
                }
                require(record.draft.attachmentIds.all { it in attachments }) { "记录引用的附件不存在" }
            }
            require(snapshot.records.flatMap { it.draft.attachmentIds }.toSet() == attachments.keys) { "备份包含未被任何记录引用的附件" }
            require(snapshot.changes.map { it.id }.distinct().size == snapshot.changes.size) { "变更事件 ID 重复" }
            val recordVersionIds = snapshot.records.map { it.id to it.revision }.toSet()
            snapshot.changes.forEach { event ->
                id(event.entityId)
                require(event.id > 0 && event.revision > 0 && event.kind in kinds) { "变更事件无效" }
                when {
                    event.kind == "RECORD_DELETED" -> require(deletedRecords[event.entityId]?.let { it.lastRevision + 1 == event.revision && it.deletedAt == event.createdAt } == true) { "记录删除事件缺少匹配标记" }
                    event.kind == "TEMPLATE_DELETED" -> require(deletedTemplates[event.entityId]?.let { it.lastRevision == event.revision && it.deletedAt == event.createdAt } == true) { "模板删除事件缺少匹配标记" }
                    event.kind.startsWith("RECORD_") -> require((event.entityId to event.revision) in recordVersionIds) { "变更事件引用缺失修订" }
                    else -> require((event.entityId to event.revision) in templatesByVersion) { "变更事件引用缺失模板" }
                }
            }
            var retainedAnalysisBytes = 0L
            snapshot.analysisRuns.forEach { run ->
                id(run.id); id(run.source.storeId)
                require(run.version > 0 && run.source.generation >= 0 && run.updatedAt >= run.createdAt) { "分析任务版本或时间无效" }
                // Historical source revisions are retained; deleted or missing source material
                // fails validation instead of making a restored proposal look supported.
                try {
                    AnalysisRunCodec.validateStored(run, snapshot.records)
                    retainedAnalysisBytes += run.payload.toByteArray(Charsets.UTF_8).size + AnalysisRunCodec.reservedResponseBytes(run)
                }
                catch (failure: Exception) { throw InvalidBackupException("分析任务结构或来源引用无效", failure) }
                require(retainedAnalysisBytes <= 16L * 1024 * 1024) { "分析任务及待返回输出预留超过保存上限" }
            }
            ClaimFeedbackValidation.validateCollection(snapshot.claimFeedback)
            require(snapshot.deletedClaimFeedback.size <= ClaimFeedbackValidation.MAX_DELETIONS &&
                snapshot.deletedClaimFeedback.map { it.id }.distinct().size == snapshot.deletedClaimFeedback.size) { "反馈删除标记过多或重复" }
            snapshot.deletedClaimFeedback.forEach {
                require(it.id.matches(Regex("[0-9a-f]{64}")) && it.lastRevision in 1 until Int.MAX_VALUE) { "反馈删除标记无效" }
            }
            require(snapshot.claimFeedback.map { it.id }.toSet().intersect(snapshot.deletedClaimFeedback.map { it.id }.toSet()).isEmpty()) { "备份同时包含反馈及其删除标记" }
            val erasedFeedback = snapshot.deletedClaimFeedback.map { it.id }.toSet()
            // A backup already carrying a deletion fence must also carry its redaction. Older
            // backups without that fence are sanitized after merging the target's deletions.
            snapshot.analysisRuns.forEach { run ->
                val redacted = try { AnalysisRunCodec.redactDeletedFeedback(run, erasedFeedback, snapshot.capturedAt) }
                    catch (failure: AnalysisRedactionCapacityException) {
                        throw InvalidBackupException("备份已含反馈删除标记但保留了待清理的模型文字", failure)
                    }
                require(redacted == run) {
                    "备份包含已永久删除反馈影响的模型文字"
                }
            }
            val runs = snapshot.analysisRuns.associateBy { it.id }
            val feedbackStates = mutableMapOf<String, AnalysisRunState>()
            snapshot.claimFeedback.forEach { value -> runs[value.runId]?.let { run ->
                ClaimFeedbackValidation.validateTarget(value, run, feedbackStates.getOrPut(run.id) { AnalysisRunCodec.decode(run.payload) })
            } }
            KnowledgeValidation.validateCollection(snapshot.knowledgeRevisions, snapshot.knowledgeHeads)
            require(snapshot.deletedKnowledge.size <= KnowledgeValidation.MAX_DELETIONS &&
                snapshot.deletedKnowledge.map { it.id }.distinct().size == snapshot.deletedKnowledge.size) { "知识删除标记过多或重复" }
            snapshot.deletedKnowledge.forEach {
                require(digest.matches(it.id) && it.lastRevision in 1 until Int.MAX_VALUE) { "知识删除标记无效" }
            }
            require(snapshot.knowledgeHeads.map { it.id }.toSet().intersect(snapshot.deletedKnowledge.map { it.id }.toSet()).isEmpty()) { "备份同时包含知识及其删除标记" }
            val erasedRuns = snapshot.deletedAnalysisRuns.map { it.id }.toSet()
            require(snapshot.knowledgeRevisions.none { value -> value.runId in erasedRuns ||
                KnowledgeValidation.feedbackDependencies(value).any { dependency ->
                    AnalysisRunCodec.feedbackId(value.runId, dependency.claim) in erasedFeedback
                } }) { "备份包含已永久删除任务或自身、祖先反馈的知识副本" }
            // Publications own their immutable claim/review proof; later task edits or feedback
            // redaction must not retroactively change the meaning of historical revisions.
            val knowledgeSources = snapshot.records.associateBy { it.id to it.revision }
            snapshot.knowledgeRevisions.forEach { KnowledgeValidation.validateEvidence(it, knowledgeSources) }
            val metricProofs = snapshot.analysisRuns.flatMap { run ->
                feedbackStates.getOrPut(run.id) { AnalysisRunCodec.decode(run.payload) }.calls.flatMap { it.metricEvidence }
            } + snapshot.knowledgeRevisions.flatMap { it.metricEvidence }
            metricProofs.distinctBy { it.id }.forEach { metric ->
                RecordMetricProtocol.validateSnapshot(metric, SourceStamp(snapshot.storeId, snapshot.generation), snapshot.records)
            }
            ActionValidation.validateCollection(snapshot.actionRevisions, snapshot.actionHeads)
            require(snapshot.deletedActions.size <= ActionValidation.MAX_DELETIONS &&
                snapshot.deletedActions.map { it.id }.distinct().size == snapshot.deletedActions.size) { "行动删除标记过多或重复" }
            snapshot.deletedActions.forEach { id(it.id); require(it.lastRevision in 1 until Int.MAX_VALUE) { "行动删除版本无效" } }
            require(snapshot.actionHeads.map { it.id }.toSet().intersect(snapshot.deletedActions.map { it.id }.toSet()).isEmpty()) { "备份同时包含行动及其删除标记" }
            // Actions and follow-up text are independently authored. A missing or deleted
            // origin is a navigation gap, never grounds for discarding the user's text.
            ProfileValidation.validateCollection(snapshot.profileRevisions, snapshot.profileHeads)
            require(snapshot.deletedProfiles.size <= ProfileValidation.MAX_DELETIONS &&
                snapshot.deletedProfiles.map { it.id }.distinct().size == snapshot.deletedProfiles.size) { "画像删除标记过多或重复" }
            snapshot.deletedProfiles.forEach { id(it.id); require(it.lastRevision in 1 until Int.MAX_VALUE) { "画像删除版本无效" } }
            require(snapshot.profileHeads.map { it.id }.toSet().intersect(snapshot.deletedProfiles.map { it.id }.toSet()).isEmpty()) { "备份同时包含画像及其删除标记" }
            validateProfileReferences(snapshot.profileRevisions, snapshot.knowledgeRevisions)
            // Fixed references can outlive knowledge deletion or a restore that retains a different
            // local knowledge chain. Missing revisions stay unavailable; independent user text stays.
        } catch (failure: IllegalArgumentException) {
            if (failure is InvalidBackupException) throw failure
            throw InvalidBackupException(failure.message ?: "备份资料无效", failure)
        }
    }

    fun validateProfileReferences(profiles: List<StoredProfileRevision>, knowledge: List<StoredKnowledgeRevision>) {
        val byRevision = knowledge.associateBy { it.id to it.revision }
        profiles.forEach { profile ->
            val ref = profile.draft.knowledge
            byRevision[ref.knowledgeId to ref.revision]?.let { value ->
                ProfileValidation.validateKnowledge(profile, value)
            }
        }
    }

    fun recordDraft(draft: RecordDraft) {
        require(draft.body.length <= 200_000) { "正文过长" }
        require(listOfNotNull(draft.occurrence.startDate, draft.occurrence.endDate).all { it.year in 1..9999 }) { "发生日期须在公元 1 至 9999 年之间" }
        require(draft.body.isNotBlank() || draft.answers.values.any { it.isNotBlank() } || draft.attachmentIds.isNotEmpty()) { "请填写记录、复盘答案或添加附件" }
        require(draft.topics.size <= 30 && draft.topics.all { it.isNotBlank() && it == it.trim() && it.length <= 80 }) { "主题无效" }
        require(draft.answers.values.sumOf { it.length.toLong() } <= 200_000 && draft.answers.size <= 100) { "答案过长" }
        require(draft.attachmentIds.size <= 20) { "单条记录最多包含 20 个附件" }
        if (draft.template == null) require(draft.answers.isEmpty()) { "复盘答案必须绑定模板" }
    }

    fun templateDraft(draft: TemplateDraft) {
        require(draft.name.isNotBlank()) { "模板名称无效" }
        require(draft.sourceMarkdown.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "模板源文不能超过 256 KiB" }
        require(draft.questions.size in 1..100) { "模板须有 1 至 100 个问题" }
        val fields = listOf(draft.name, draft.description, draft.sourceMarkdown) + draft.questions.flatMap { listOf(it.prompt, it.hint, it.intent) }
        require(fields.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= 1024 * 1024) { "模板内容过长" }
        require(draft.questions.map { it.id }.distinct().size == draft.questions.size) { "问题 ID 不能重复" }
        draft.questions.forEach { id(it.id); require(it.prompt.isNotBlank() && it.intent.length <= 100) { "问题内容无效" } }
    }

    fun id(value: String) { require(value.isNotBlank() && value.length <= 200 && value.none { it.isISOControl() }) { "ID 无效" } }
}
