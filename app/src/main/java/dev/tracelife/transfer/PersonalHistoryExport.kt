package dev.tracelife.transfer

import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.profile.ProfileValidation
import dev.tracelife.storage.HistoricalComparisonCodec
import dev.tracelife.storage.ClaimFeedbackValidation
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Independent user words are not recovered model claims. Missing targets remain identity-only. */
internal object PersonalHistoryExport {
    enum class Kind { PROFILE_REVISION, CLAIM_FEEDBACK, HISTORICAL_COMPARISON, ACTION_REVIEW, EXPERIENCE_CHANGE }
    data class Entry(val key: String, val kind: Kind, val entityId: String, val revision: Int,
        val occurredOn: LocalDate, val savedAt: Instant, val title: String, val userText: String?,
        val details: Map<String, Any?>, val knowledgeId: String? = null, val knowledgeRevision: Int? = null)
    data class Selection(val enabled: Boolean, val entries: List<Entry>, val sourceCounts: Map<String, Int>,
        val omittedByReason: Map<String, Int>, val zoneId: String) {
        fun count(kind: Kind) = entries.count { it.kind == kind }
    }
    const val MAX_ENTRIES = 25_000

    fun select(snapshot: RepositorySnapshot, options: ReadingExportOptions): Selection {
        val sourceCounts = mapOf("profileRevisions" to snapshot.profileRevisions.size,
            "claimFeedback" to snapshot.claimFeedback.size, "historicalComparisons" to snapshot.historicalComparisons.size,
            "actionReviews" to snapshot.actionRevisions.count { it.review != null },
            "experienceChanges" to snapshot.experienceChanges.size)
        val enabled = options.kind == ReadingPackageKind.KNOWLEDGE && options.includePersonalHistory
        if (!enabled) return Selection(false, emptyList(), sourceCounts,
            mapOf("not_selected" to sourceCounts.values.sum()), options.profileZoneId)
        ProfileValidation.validateCollection(snapshot.profileRevisions, snapshot.profileHeads)
        ClaimFeedbackValidation.validateCollection(snapshot.claimFeedback)
        HistoricalComparisonCodec.validateCollection(snapshot.historicalComparisons)
        dev.tracelife.knowledge.ExperienceValidation.validateCollection(snapshot.experienceThreads, snapshot.experienceChanges,
            snapshot.deletedExperienceThreads)
        val zone = ZoneId.of(options.profileZoneId)
        val sources = snapshot.records.associateBy { it.id to it.revision }
        val runs = snapshot.analysisRuns.associateBy { it.id }
        val knowledge = snapshot.knowledgeRevisions.associateBy { it.id to it.revision }
        val deletedKnowledge = snapshot.deletedKnowledge.map { it.id }.toSet()
        val deletedRuns = snapshot.deletedAnalysisRuns.map { it.id }.toSet()
        val deletedRecords = snapshot.deletedRecords.map { it.id }.toSet()
        val omitted = linkedMapOf<String, Int>(); val entries = mutableListOf<Entry>()
        val topicCache = mutableMapOf<String, Set<String>>()
        fun targetTopics(id: String, batchKey: String, claimIndex: Int, claimSha256: String): Set<String> =
            topicCache.getOrPut("$id:$batchKey:$claimIndex:$claimSha256") {
                if (id in deletedRuns) emptySet() else {
                    val published = knowledge.values.filter { it.id !in deletedKnowledge && it.runId == id &&
                        it.claimRef.batchKey == batchKey && it.claimRef.claimIndex == claimIndex && it.claimRef.claimSha256 == claimSha256 }
                    if (published.isNotEmpty()) published.flatMap { it.topics }.toSet()
                    else runs[id]?.let { run ->
                        val state = AnalysisRunCodec.decode(run.payload)
                        val call = state.calls.lastOrNull { it.batchKey == batchKey && it.proposal != null } ?: return@let emptySet()
                        val claim = call.proposal?.claims?.getOrNull(claimIndex) ?: return@let emptySet()
                        if (AnalysisRunCodec.claimSha256(claim) != claimSha256) return@let emptySet()
                        // A different claim in a mixed-topic task cannot prove this note's topic.
                        call.evidence.filter { it.id in claim.evidenceIds }.mapNotNull { receipt ->
                            sources[receipt.recordId to receipt.revision]?.takeIf { it.id !in deletedRecords }
                        }.flatMap { it.draft.topics }.toSet()
                    }.orEmpty()
                }
            }
        fun targetState(knowledgeId: String?, revision: Int?): String = when {
            knowledgeId == null -> "not_linked"
            knowledgeId in deletedKnowledge -> "deleted"
            knowledge[knowledgeId to revision] == null -> "missing"
            else -> "historical_reference_not_revalidated"
        }
        fun include(entry: Entry, topics: () -> Set<String>, deleted: Boolean = false) {
            val reason = when {
                deleted -> "deleted"
                entry.savedAt > snapshot.capturedAt -> "after_snapshot"
                options.scope.from?.let { entry.occurredOn < it } == true || options.scope.through?.let { entry.occurredOn > it } == true -> "outside_date"
                options.scope.topics?.let { requested -> topics().none { it in requested } } == true -> "topic_not_provable"
                else -> null
            }
            if (reason != null) omitted[reason] = omitted.getOrDefault(reason, 0) + 1
            else {
                require(entries.size < MAX_ENTRIES) { "个人说明与历史超过 $MAX_ENTRIES 项，请按日期分批导出" }
                entries += entry
            }
        }
        val profileHeads = snapshot.profileHeads.associate { it.id to it.revision }
        val deletedProfiles = snapshot.deletedProfiles.map { it.id }.toSet()
        snapshot.profileRevisions.forEach { value ->
            val ref = value.draft.knowledge
            include(Entry("profile:${value.id}:${value.revision}", Kind.PROFILE_REVISION, value.id, value.revision,
                value.updatedAt.atZone(zone).toLocalDate(), value.updatedAt, "画像组织与个人说明", value.userText,
                mapOf("adoptionAtRevision" to value.adoption.name, "dimension" to value.draft.dimension.name,
                    "layer" to value.draft.layer.name, "change" to value.change.name,
                    "isLatestStoredRevision" to (profileHeads[value.id] == value.revision), "isCurrentUnderstanding" to false,
                    "targetState" to targetState(ref.knowledgeId, ref.revision),
                    "knowledgeId" to ref.knowledgeId, "knowledgeRevision" to ref.revision,
                    "userTextIsIndependent" to true, "repeatedUserTextIsNotNewEvidence" to true), ref.knowledgeId, ref.revision),
                { knowledge[ref.knowledgeId to ref.revision]?.topics.orEmpty() }, value.id in deletedProfiles)
        }
        val deletedFeedback = snapshot.deletedClaimFeedback.map { it.id }.toSet()
        snapshot.claimFeedback.forEach { value ->
            include(Entry("feedback:${value.id}:${value.version}", Kind.CLAIM_FEEDBACK, value.id, value.version,
                value.updatedAt.atZone(zone).toLocalDate(), value.updatedAt, "用户核对意见", value.userText,
                mapOf("decision" to value.decision.name, "runId" to value.runId, "batchKey" to value.batchKey,
                    "claimIndex" to value.claimIndex, "claimSha256" to value.claimSha256,
                    "targetState" to if (value.runId in deletedRuns) "deleted" else if (value.runId !in runs) "missing" else "historical_reference_not_revalidated",
                    "machineClaimIncluded" to false, "userTextIsIndependent" to true)),
                { targetTopics(value.runId, value.batchKey, value.claimIndex, value.claimSha256) }, value.id in deletedFeedback)
        }
        val deletedComparisons = snapshot.historicalComparisonDeletions.map { it.id }.toSet()
        snapshot.historicalComparisons.forEach { value ->
            val note = value.resolution
            include(Entry("comparison:${value.id}", Kind.HISTORICAL_COMPARISON, value.runId, note.version,
                note.createdAt.atZone(zone).toLocalDate(), note.createdAt, "对照旧意见的个人说明", note.reason,
                mapOf("runId" to value.runId, "batchKey" to note.target.batchKey, "claimIndex" to note.target.claimIndex,
                    "claimSha256" to note.target.claimSha256, "feedbackId" to note.feedbackId,
                    "choice" to note.choice.name, "targetState" to if (value.runId in deletedRuns) "deleted" else if (value.runId !in runs) "missing" else "historical_reference_not_revalidated",
                    "machineClaimIncluded" to false, "userTextIsIndependent" to true)),
                { targetTopics(value.runId, note.target.batchKey, note.target.claimIndex, note.target.claimSha256) }, value.runId in deletedComparisons)
        }
        val deletedActions = snapshot.deletedActions.map { it.id }.toSet()
        snapshot.actionRevisions.filter { it.review != null }.forEach { value ->
            val review = requireNotNull(value.review)
            val origin = requireNotNull(value.reviewedDraft).origin
            include(Entry("action-review:${value.id}:${value.revision}", Kind.ACTION_REVIEW, value.id, value.revision,
                review.reviewedOn, value.updatedAt, "用户行动回看", review.observedText,
                mapOf("execution" to review.execution.name, "decision" to review.decision.name,
                    "reviewedPlanRevision" to value.revision - 1, "reviewedPlanZoneId" to value.reviewedDraft.zoneId,
                    "originKnowledgeId" to origin?.knowledgeId, "originKnowledgeRevision" to origin?.revision,
                    "originState" to targetState(origin?.knowledgeId, origin?.revision),
                    "planTextIncluded" to false, "nextPlanIncluded" to false, "userReportedNotDeviceMeasurement" to true),
                origin?.knowledgeId, origin?.revision),
                { origin?.let { knowledge[it.knowledgeId to it.revision]?.topics }.orEmpty() }, value.id in deletedActions)
        }
        val threads = snapshot.experienceThreads.associateBy { it.id }
        val deletedExperiences = snapshot.deletedExperienceThreads.map { it.id }.toSet()
        snapshot.experienceChanges.forEach { value ->
            val thread = requireNotNull(threads[value.threadId])
            include(Entry("experience:${value.threadId}:${value.sequence}", Kind.EXPERIENCE_CHANGE,
                value.threadId, value.sequence, value.createdAt.atZone(zone).toLocalDate(), value.createdAt,
                "经验形成过程与个人说明", value.userNote,
                mapOf("relation" to value.relation.name, "threadId" to value.threadId, "sequence" to value.sequence,
                    "knowledgeId" to value.knowledgeId, "knowledgeRevision" to value.knowledgeRevision,
                    "targetState" to targetState(value.knowledgeId, value.knowledgeRevision),
                    "isCurrentUnderstanding" to false, "userTextIsIndependent" to true),
                value.knowledgeId, value.knowledgeRevision), { thread.topics }, value.threadId in deletedExperiences)
        }
        return Selection(true, entries.sortedWith(compareBy<Entry> { it.occurredOn }.thenBy { it.key }), sourceCounts, omitted, options.profileZoneId)
    }

    fun json(entry: Entry): JSONObject = JSONObject().put("id", entry.key).put("kind", entry.kind.name)
        .put("entityId", entry.entityId).put("revision", entry.revision).put("occurredOn", entry.occurredOn.toString())
        .put("dateMeaning", if (entry.kind == Kind.ACTION_REVIEW) "user_review_date" else "saved_date_in_export_timezone")
        .put("savedAt", entry.savedAt.toString()).put("userText", entry.userText ?: JSONObject.NULL)
        .put("originalEvidence", entry.kind == Kind.ACTION_REVIEW).put("machineClaimIncluded", false)
        .put("details", JSONObject(entry.details))
}
