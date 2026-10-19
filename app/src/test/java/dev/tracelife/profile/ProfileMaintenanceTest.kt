package dev.tracelife.profile

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import dev.tracelife.transfer.StaleSnapshotException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** All records, judgments and clock changes in this suite are synthetic. No network is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileMaintenanceTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var candidates: ProfileCandidateGenerator
    private lateinit var maintenance: ProfileMaintenance
    private val database = "synthetic-profile-maintenance-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val date = LocalDate.parse("2026-10-19")
    private val target = ProfileAnalysisTarget(ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.CONTEXTUAL_PATTERN)
    private val provider = SyntheticProvider()
    private val relation = SyntheticRelation()
    private val scan = SyntheticScan()
    private val reviewer = SyntheticReviewer()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        open()
        repository.createRecord(RecordDraft("合成资料：完成小练习后检查错误，这次帮助发现遗漏。", Occurrence(date)), "source")
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `explicit plan finite passes and reopening retain every stage without publishing`() {
        val old = existingProfile()
        repository.createRecord(RecordDraft("合成补记：不同任务还有时间和疲劳的影响。", Occurrence(date)), "later")
        val claim = candidate("new")
        confirm("new", claim)
        assertEquals(ProfileMaintenanceStatus.PLAN_REQUIRED, maintenance.inspect("new", claim).status)
        val began = maintenance.begin("new", claim)
        assertEquals(ProfileMaintenanceStatus.RELATIONS_READY, began.status)
        assertEquals(1, began.totalReferences)
        assertEquals(old.id, began.references.single().reference.id)
        assertEquals(ProfileKnowledgeState.STALE, PersonalProfile(repository, clock).get(old.id)!!.knowledgeState)
        val first = process("new", claim, 1)
        assertEquals(ProfileMaintenanceStatus.PASS_LIMIT_REACHED, first.status)
        assertEquals(1, first.modelRequestsThisPass)
        assertEquals(1, first.progress.comparedReferences)
        repository.close(); open()
        assertEquals(first.progress, maintenance.inspect("new", claim))
        val finished = process("new", claim)
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, finished.status)
        assertTrue(finished.progress.allAvailableTextScanned)
        assertEquals(2, finished.progress.scannedFragments)
        assertEquals(2, finished.progress.finalRereadFragments)
        assertFalse(finished.progress.counterevidenceSearchExhaustive)
        assertEquals(1, relation.calls)
        assertEquals(1, repository.profileHeads().size)
        assertEquals(old.revision, repository.profileRevision(old.id)!!.revision)
        assertEquals(0, process("new", claim).modelRequestsThisPass)
    }

    @Test fun `confirmed narrow update stays on original profile and retains independent text and history`() {
        val old = existingProfile()
        repository.createRecord(RecordDraft("合成新资料：边界明确时小练习有帮助，疲劳时仍需调整。", Occurrence(date)), "later")
        val claim = candidate("new")
        confirm("new", claim)
        maintenance.begin("new", claim)
        val finished = process("new", claim)
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, finished.status)
        assertEquals(ProfileMaintenanceAction.NARROW, maintenance.suggestions(finished.progress).single().suggestedAction)
        val applied = maintenance.apply("new", claim, finished.progress.taskVersion!!, finished.progress.planId!!,
            ProfileMaintenanceAction.NARROW, old.id, old.revision, "explicit-narrow")
        assertEquals(old.id, applied.profile!!.id)
        assertEquals(ProfileAdoption.CANDIDATE, applied.adoption)
        val updated = repository.profileRevision(old.id)!!
        assertEquals("合成用户文字：保留这个情境限制。", updated.userText)
        assertEquals(old.revision + 1, updated.revision)
        assertEquals(1, repository.profileHeads().size)
        assertEquals(ProfileAdoption.ADOPTED, repository.profileRevision(old.id, old.revision)!!.adoption)
        assertEquals(0, PersonalProfile(repository, clock).open().total)
        PersonalProfile(repository, clock).adopt(old.id, updated.revision, "explicit-adopt")
        assertEquals(1, PersonalProfile(repository, clock).open().total)
    }

    @Test fun `whole scope scan reaches long record tail and contradicted result cannot become an update`() {
        repository.updateRecord("source", 1, RecordDraft("合成资料：" + "正文".repeat(70) + "TAIL_COUNTER：这次小练习没有帮助。", Occurrence(date)))
        val claim = candidate("tail", AnalysisLimits(fragmentCodePoints = 32))
        confirm("tail", claim)
        maintenance.begin("tail", claim)
        val first = process("tail", claim, 1)
        assertEquals(ProfileMaintenanceStatus.PASS_LIMIT_REACHED, first.status)
        assertTrue(first.progress.coverage!!.remainingTextFragments > 0)
        assertEquals(0, reviewer.calls)
        reviewer.verdict = ReviewVerdict.CONTRADICTED
        val finished = process("tail", claim)
        assertEquals(ProfileMaintenanceStatus.NOT_SUPPORTED, finished.status)
        assertTrue(finished.progress.allAvailableTextScanned)
        assertTrue(scan.texts.any { "TAIL_COUNTER" in it })
        assertTrue(finished.progress.counterevidenceFragments > 0)
        assertThrows(IllegalArgumentException::class.java) {
            maintenance.apply("tail", claim, finished.progress.taskVersion!!, finished.progress.planId!!,
                ProfileMaintenanceAction.NEW, operationId = "do-not-publish")
        }
        assertTrue(repository.profileHeads().isEmpty())
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `rejected unmaterialized prior judgment participates and explicit override is separate from user text`() {
        val prior = candidate("rejected")
        AnalysisFeedback(repository, clock).respond("rejected", prior, analysis.task("rejected").stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成纠正：这个说法只适用于有明确边界的小任务。")
        val claim = candidate("new")
        confirm("new", claim)
        relation.result = ProfileRelationResult(ProfileRelation.SUPPLEMENT, ProfileMaintenanceRecommendation.NARROW,
            "合成判断：新描述补足了历史纠正的边界。")
        val began = maintenance.begin("new", claim)
        assertEquals(ProfileMaintenanceReferenceKind.FEEDBACK, began.references.single().reference.kind)
        val finished = process("new", claim)
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, finished.status)
        assertTrue(maintenance.suggestions(finished.progress).single { it.suggestedAction == ProfileMaintenanceAction.NEW }.requiresOverrideReason)
        assertThrows(IllegalArgumentException::class.java) {
            maintenance.apply("new", claim, finished.progress.taskVersion!!, finished.progress.planId!!,
                ProfileMaintenanceAction.NEW, operationId = "explicit-new")
        }
        val applied = maintenance.apply("new", claim, finished.progress.taskVersion!!, finished.progress.planId!!,
            ProfileMaintenanceAction.NEW, operationId = "explicit-new", overrideReason = "合成用户选择：保留收窄后的新判断，旧纠正仍有效。")
        assertEquals(ProfileAdoption.CANDIDATE, applied.adoption)
        assertNull(repository.profileRevision(applied.profile!!.id)!!.userText)
        assertEquals(ClaimFeedbackDecision.CORRECTED, repository.claimFeedback(AnalysisRunCodec.feedbackId("rejected", prior))!!.decision)
    }

    @Test fun `deleted historical machine body is uncertain and does not trigger more paid work`() {
        val prior = candidate("gone")
        AnalysisFeedback(repository, clock).respond("gone", prior, analysis.task("gone").stored.version,
            ClaimFeedbackDecision.REJECTED, "合成用户文字：这个解释不符合我的情况。")
        analysis.delete("gone", analysis.task("gone").stored.version)
        val claim = candidate("new")
        relation.result = ProfileRelationResult(ProfileRelation.UNCERTAIN, ProfileMaintenanceRecommendation.REVIEW,
            "合成历史正文缺失。", listOf("缺少被删除的原判断，不能推断不相关。"))
        maintenance.begin("new", claim)
        val result = process("new", claim)
        assertEquals(ProfileMaintenanceStatus.NEEDS_ATTENTION, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertEquals(0, scan.calls)
        assertEquals(0, reviewer.calls)
        assertEquals(0, process("new", claim).modelRequestsThisPass)
    }

    @Test fun `scan receipts from another candidate never satisfy this candidate coverage`() {
        provider.claimCount = 2
        val first = candidate("two")
        val second = analysis.claimRef("two", "candidate", 1)
        confirm("two", first); confirm("two", second)
        maintenance.begin("two", first)
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, process("two", first).status)
        maintenance.begin("two", second)
        val pending = maintenance.inspect("two", second)
        assertEquals(ProfileMaintenanceStatus.SCAN_READY, pending.status)
        assertEquals(0, pending.scannedFragments)
        assertTrue(pending.coverage!!.remainingTextFragments > 0)
        val count = scan.calls
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, process("two", second).status)
        assertTrue(scan.calls > count)
    }

    @Test fun `confirmation refreshes final review without repeating relation or full scans`() {
        existingProfile()
        val claim = candidate("new")
        maintenance.begin("new", claim)
        val first = process("new", claim)
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, first.status)
        val relationCount = relation.calls
        val scanCount = scan.calls
        confirm("new", claim)
        val pending = maintenance.inspect("new", claim)
        assertEquals(ProfileMaintenanceStatus.FINAL_READY, pending.status)
        val second = process("new", claim)
        assertEquals(1, second.modelRequestsThisPass)
        assertEquals(relationCount, relation.calls)
        assertEquals(scanCount, scan.calls)
        assertEquals(2, reviewer.calls)
        assertTrue(analysis.task("new").state.calls.filter { it.purpose == CallPurpose.REVIEW }.map { it.batchKey }.distinct().size == 2)
    }

    @Test fun `new governance entries invalidate pinned directory and old pages`() {
        val old = existingProfile()
        val claim = candidate("new")
        val page = maintenance.begin("new", claim)
        PersonalProfile(repository, clock).feedback(old.id, old.revision, "new-user-context", "合成用户新补充。")
        assertThrows(StaleSnapshotException::class.java) { maintenance.relation(page, page.references.single().reference) }
        val stopped = process("new", claim)
        assertTrue(stopped.status in setOf(ProfileMaintenanceStatus.DEPENDENCY_BLOCKED, ProfileMaintenanceStatus.CONFLICT))
        assertEquals(0, stopped.modelRequestsThisPass)
    }

    @Test fun `cancellation and provider failure preserve progress for explicit continuation`() {
        existingProfile()
        val claim = candidate("new")
        maintenance.begin("new", claim)
        val cancelled = maintenance.process("new", claim, relation, scan, reviewer, cancelled = { true })
        assertEquals(ProfileMaintenanceStatus.CANCELLED, cancelled.status)
        assertEquals(0, cancelled.modelRequestsThisPass)
        relation.failNext = true
        val failed = process("new", claim)
        assertEquals(ProfileMaintenanceStatus.RETRY_NEEDED, failed.status)
        assertEquals(1, failed.modelRequestsThisPass)
        assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, process("new", claim).status)
        assertEquals(2, relation.calls)
        assertEquals(0, process("new", claim).modelRequestsThisPass)
    }

    @Test fun `source mutation during scan blocks late result and preserves the old user decision`() {
        val old = existingProfile()
        val claim = candidate("new")
        maintenance.begin("new", claim)
        scan.onScan = { repository.updateRecord("source", 1, RecordDraft("合成已更正的记录。", Occurrence(date))) }
        val result = process("new", claim)
        assertEquals(ProfileMaintenanceStatus.STALE, result.status)
        assertEquals(0, reviewer.calls)
        assertEquals(ProfileAdoption.ADOPTED, repository.profileRevision(old.id)!!.adoption)
        assertEquals("合成用户文字：保留这个情境限制。", repository.profileRevision(old.id)!!.userText)
    }

    @Test fun `task deadline and clock rollback stop before provider dispatch`() {
        val expiry = clock.now.plusSeconds(60)
        val claim = candidate("timed", expiresAt = expiry)
        maintenance.begin("timed", claim)
        clock.now = expiry
        assertEquals(ProfileMaintenanceStatus.EXPIRED, process("timed", claim).status)
        clock.now = expiry.minusSeconds(120)
        assertEquals(ProfileMaintenanceStatus.CLOCK_ROLLBACK, process("timed", claim).status)
        assertEquals(0, scan.calls)
        assertEquals(0, reviewer.calls)
    }

    @Test fun `metadata page and selected relationship are bounded and immutable`() {
        existingProfile()
        val rejected = candidate("rejected")
        AnalysisFeedback(repository, clock).respond("rejected", rejected, analysis.task("rejected").stored.version,
            ClaimFeedbackDecision.REJECTED, "合成用户否定。")
        val claim = candidate("new")
        maintenance.begin("new", claim)
        val first = maintenance.inspect("new", claim, limit = 1)
        assertEquals(2, first.totalReferences)
        assertEquals(1, first.references.size)
        val second = maintenance.next(first)
        assertNotEquals(first.references.single().reference, second.references.single().reference)
        assertThrows(IllegalArgumentException::class.java) { maintenance.relation(first, second.references.single().reference) }
        assertThrows(UnsupportedOperationException::class.java) { (first.references as MutableList<ProfileMaintenanceIndexItem>).clear() }
        assertThrows(IllegalArgumentException::class.java) { maintenance.inspect("new", claim, limit = 101) }
    }

    @Test fun `necessary related originals over the final limit remain visible instead of being truncated`() {
        repeat(32) { index -> repository.createRecord(RecordDraft("合成相关记录 $index：小练习用于检查遗漏。", Occurrence(date)), "extra-$index") }
        val claim = candidate("wide")
        maintenance.begin("wide", claim)
        val result = process("wide", claim, 50)
        assertEquals(ProfileMaintenanceStatus.RELATED_EVIDENCE_LIMIT, result.status)
        assertTrue(result.progress.allAvailableTextScanned)
        assertEquals(33, result.progress.relatedFragments)
        assertEquals(33, result.modelRequestsThisPass)
        assertEquals(0, reviewer.calls)
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `shared task budget stops before final dispatch and preserves complete scan progress`() {
        val claim = candidate("budget", AnalysisLimits(maxCalls = 2))
        maintenance.begin("budget", claim)
        val result = process("budget", claim)
        assertEquals(ProfileMaintenanceStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(1, result.modelRequestsThisPass)
        assertTrue(result.progress.allAvailableTextScanned)
        assertEquals(0, reviewer.calls)
        assertEquals(2, analysis.task("budget").state.calls.size)
        assertEquals(0, process("budget", claim).modelRequestsThisPass)
    }

    @Test fun `unreadable source remains a gap and never falls through to final dispatch`() {
        repository.createRecord(RecordDraft("合成资料\u0000无法安全分片的尾部。", Occurrence(date)), "unreadable")
        val claim = candidate("gap")
        maintenance.begin("gap", claim)
        val result = process("gap", claim)
        assertEquals(ProfileMaintenanceStatus.CONTEXT_REQUIRED, result.status)
        assertEquals(1, result.progress.coverage!!.unreadableRecords)
        assertEquals(0, reviewer.calls)
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `apply retry preserves later adoption and rejects clock rollback before that decision`() {
        val old = existingProfile()
        val claim = candidate("new")
        confirm("new", claim)
        maintenance.begin("new", claim)
        val completed = process("new", claim).progress
        val originalTime = clock.now
        clock.now = originalTime.plusSeconds(60)
        val applied = maintenance.apply("new", claim, completed.taskVersion!!, completed.planId!!,
            ProfileMaintenanceAction.NARROW, old.id, old.revision, "stable-update")
        clock.now = originalTime.plusSeconds(120)
        val adopted = PersonalProfile(repository, clock).adopt(old.id, applied.profile!!.revision, "later-adoption")
        val before = repository.currentAnalysisGeneration()
        clock.now = originalTime.plusSeconds(90)
        assertThrows(IllegalArgumentException::class.java) {
            maintenance.apply("new", claim, completed.taskVersion, completed.planId,
                ProfileMaintenanceAction.NARROW, old.id, old.revision, "stable-update")
        }
        clock.now = originalTime.plusSeconds(120)
        val repeat = maintenance.apply("new", claim, completed.taskVersion, completed.planId,
            ProfileMaintenanceAction.NARROW, old.id, old.revision, "stable-update")
        assertTrue(repeat.reused)
        assertEquals(ProfileAdoption.ADOPTED, repeat.adoption)
        assertEquals(ProfileHead(old.id, adopted.value.revision), repeat.profile)
        assertEquals(before, repository.currentAnalysisGeneration())
        assertEquals(1, repository.knowledgeHistory(applied.knowledge!!.knowledgeId).size)
    }

    @Test fun `keep does not append history and rejects a future profile decision`() {
        val old = existingProfile()
        relation.result = ProfileRelationResult(ProfileRelation.SAME, ProfileMaintenanceRecommendation.KEEP, "合成同义判断，无需另建条目。")
        val claim = candidate("new")
        confirm("new", claim)
        maintenance.begin("new", claim)
        val completed = process("new", claim).progress
        val before = repository.currentAnalysisGeneration()
        val kept = maintenance.apply("new", claim, completed.taskVersion!!, completed.planId!!,
            ProfileMaintenanceAction.KEEP, old.id, old.revision, "keep-existing")
        assertEquals(old, kept.profile)
        assertEquals(before, repository.currentAnalysisGeneration())
        val originalTime = clock.now
        clock.now = originalTime.plusSeconds(60)
        PersonalProfile(repository, clock).decline(old.id, old.revision, "future-decision")
        clock.now = originalTime.plusSeconds(30)
        assertThrows(IllegalArgumentException::class.java) {
            maintenance.apply("new", claim, completed.taskVersion, completed.planId,
                ProfileMaintenanceAction.KEEP, old.id, old.revision, "keep-existing")
        }
        assertEquals(ProfileAdoption.DECLINED, repository.profileRevision(old.id)!!.adoption)
    }

    @Test fun `ordinary local review reports maintenance required and cannot create a duplicate profile`() {
        existingProfile()
        val claim = candidate("new")
        confirm("new", claim)
        analysis.reviewClaim("new", "ordinary-review", claim, provider = reviewer)
        val current = candidates.inspect("new")
        assertEquals(ProfileCandidateDisposition.MAINTENANCE_REQUIRED, current.candidates.single().disposition)
        assertThrows(IllegalArgumentException::class.java) {
            candidates.materialize("new", claim, analysis.task("new").stored.version)
        }
        assertEquals(1, repository.profileHeads().size)
    }

    private fun open() {
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        candidates = ProfileCandidateGenerator(repository, analysis, clock)
        maintenance = ProfileMaintenance(repository, analysis, clock)
    }

    private fun candidate(runId: String, limits: AnalysisLimits = AnalysisLimits(), expiresAt: Instant? = null): ClaimRef {
        candidates.start(AnalysisRequest("合成任务：检查具体学习策略及其情境边界。", AnalysisQuery(date, date, "Asia/Shanghai"),
            limits = limits, expiresAt = expiresAt, profileTarget = target), runId)
        analysis.synthesize(runId, "candidate", listOf(EvidenceSelection("source", "body")), provider)
        return analysis.claimRef(runId, "candidate", 0)
    }

    private fun existingProfile(): ProfileHead {
        val claim = candidate("old")
        confirm("old", claim)
        analysis.reviewClaim("old", "old-review", claim, provider = reviewer)
        val result = candidates.materialize("old", claim, analysis.task("old").stored.version)
        val id = result.profile!!.id
        val profile = PersonalProfile(repository, clock)
        profile.feedback(id, 1, "independent-context", "合成用户文字：保留这个情境限制。")
        profile.adopt(id, 2, "adopt-old")
        reviewer.calls = 0
        return ProfileHead(id, 3)
    }

    private fun confirm(runId: String, claim: ClaimRef) = AnalysisFeedback(repository, clock).respond(runId, claim,
        analysis.task(runId).stored.version, ClaimFeedbackDecision.CONFIRMED)
    private fun process(runId: String, claim: ClaimRef, max: Int = 30) =
        maintenance.process(runId, claim, relation, scan, reviewer, maxModelRequests = max)

    private class MutableClock : Clock() {
        var now: Instant = Instant.parse("2026-10-19T03:00:00Z")
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private class SyntheticProvider : AnalysisProvider {
        override val id = "synthetic-profile-maintenance-generator-v1"
        override val contextWindowTokens = 20_000
        var claimCount = 1
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal((0 until claimCount).map { ProposedClaim("合成判断 $it：小练习可能帮助发现遗漏，仅限本次记录的具体任务。",
                listOf(id), ClaimKind.POSSIBLE_INTERPRETATION) })
        }
    }
    private class SyntheticRelation : SemanticProfileRelationProvider {
        override val id = "synthetic-profile-maintenance-relation-v1"
        override val contextWindowTokens = 20_000
        var calls = 0
        var failNext = false
        var result = ProfileRelationResult(ProfileRelation.SUPPLEMENT, ProfileMaintenanceRecommendation.NARROW,
            "合成判断：描述包含相同策略，新增材料建议明确任务情境。")
        override fun prepareRelation(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun relatePrepared(request: PreparedModelRequest): ProfileRelationCompletion {
            calls++
            if (failNext) { failNext = false; throw AnalysisProviderException(ProviderFailureCode.NETWORK) }
            return ProfileRelationCompletion(result)
        }
    }
    private class SyntheticScan : SemanticReductionScanProvider {
        override val id = "synthetic-profile-maintenance-scan-v1"
        override val contextWindowTokens = 20_000
        var calls = 0
        var onScan: (() -> Unit)? = null
        val texts = mutableListOf<String>()
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            calls++; onScan?.invoke()
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            return ReductionScanCompletion(ReductionScanResult((0 until evidence.length()).map { index ->
                val item = evidence.getJSONObject(index)
                val text = item.getString("text")
                texts += text
                ReductionFinding(item.getString("id"), if ("TAIL_COUNTER" in text) ReductionRelevance.COUNTEREVIDENCE else ReductionRelevance.SUPPORT,
                    "合成原文分类。")
            }))
        }
    }
    private class SyntheticReviewer : SemanticReviewProvider {
        override val id = "synthetic-profile-maintenance-review-v1"
        override val contextWindowTokens = 20_000
        var calls = 0
        var verdict = ReviewVerdict.SUPPORTED
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            calls++
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            val id = (0 until evidence.length()).map { evidence.getJSONObject(it) }
                .firstOrNull { "TAIL_COUNTER" in it.getString("text") }?.getString("id") ?: evidence.getJSONObject(0).getString("id")
            return ReviewCompletion(when (verdict) {
                ReviewVerdict.SUPPORTED -> ClaimReview(verdict, supportingEvidenceIds = listOf(id))
                ReviewVerdict.CONTRADICTED -> ClaimReview(verdict, contradictingEvidenceIds = listOf(id))
                else -> ClaimReview(verdict, unknowns = listOf("合成上下文不足。"))
            })
        }
    }
}
