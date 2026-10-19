package dev.tracelife.profile

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.*
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

/** Synthetic source and model stubs. Profile commands themselves never call any model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PersonalProfileTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var profiles: PersonalProfile
    private val name = "synthetic-profile-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val today = LocalDate.parse("2026-10-19")
    private val oldDate = today.minusDays(29)
    private val body = "合成资料：我在这个项目里偏好先拆解问题，再写一个小例子；这不是普遍人格。"
    private val query = ProfileQuery(zoneId = "Asia/Shanghai")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        profiles = PersonalProfile(repository, clock)
        repository.createRecord(RecordDraft(body, Occurrence(oldDate)), "source")
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(name) }

    @Test fun `profile candidate is explicit and adopted leaf reuses the exact shared observation`() {
        val knowledge = publish()
        val source = repository.analysisSourceStamp()
        val before = repository.currentAnalysisGeneration()
        val candidate = profiles.create(draft(knowledge), "entry", "合成独立反馈：仅讨论这个项目。")
        assertEquals(ProfileAdoption.CANDIDATE, candidate.value.adoption)
        assertEquals(0, profiles.open(query).total)
        assertEquals(8, profiles.open(query).dimensions.size)
        assertTrue(profiles.open(query).dimensions.all { it.unknown })
        val adopted = profiles.adopt("entry", 1, "adopt")
        val current = profiles.open(query)
        assertEquals(1, current.total)
        assertEquals(1, current.dimensions.single { it.dimension == adopted.value.draft.dimension }.currentEntries)
        assertEquals(knowledge, profiles.entry(current, "entry").knowledge!!.value)
        assertTrue(profiles.entry(current, "entry").isCurrent)
        assertEquals(source, repository.analysisSourceStamp())
        assertTrue(repository.currentAnalysisGeneration() > before)
        assertFalse(ProfileCodec.encode(adopted.value).contains(knowledge.claim.text))
        assertFalse(ProfileCodec.encode(adopted.value).contains(body))
        assertEquals("合成独立反馈：仅讨论这个项目。", adopted.value.userText)
    }

    @Test fun `organization changes return to candidate while independent text and prior adoption remain historical`() {
        val knowledge = publish()
        profiles.create(draft(knowledge), "entry", "合成独立背景")
        profiles.adopt("entry", 1, "adopt")
        val changed = profiles.update("entry", 2, "organize", draft(knowledge).copy(layer = ProfileLayer.LONG_TERM))
        assertEquals(ProfileAdoption.CANDIDATE, changed.value.adoption)
        assertEquals("合成独立背景", changed.value.userText)
        assertEquals(0, profiles.open(query).total)
        val all = profiles.open(query.copy(currentOnly = false))
        assertEquals(listOf(3, 2, 1), profiles.history(all, "entry").items.map { it.revision })
        val old = profiles.entry(all, "entry", 2)
        assertEquals(ProfileAdoption.ADOPTED, old.value.adoption)
        assertFalse(old.isCurrentRevision)
        assertFalse(old.isCurrent)
        assertEquals(ProfileLayer.LONG_TERM, profiles.adopt("entry", 3, "re-adopt").value.draft.layer)
    }

    @Test fun `all commands bind idempotency to their complete payload and reject stale versions`() {
        val knowledge = publish()
        val original = profiles.create(draft(knowledge), "entry", "合成说明")
        clock.now = clock.now.plusSeconds(1)
        assertEquals(original.value, profiles.create(draft(knowledge), "entry", "合成说明").value)
        val adopted = profiles.adopt("entry", 1, "decision")
        clock.now = clock.now.plusSeconds(1)
        assertEquals(adopted.value, profiles.adopt("entry", 1, "decision").value)
        assertThrows(IllegalArgumentException::class.java) { profiles.decline("entry", 1, "decision") }
        assertThrows(RevisionConflictException::class.java) { profiles.decline("entry", 1, "stale") }
        val note = profiles.feedback("entry", 2, "note", "合成新背景")
        assertEquals(note.value, profiles.feedback("entry", 2, "note", "合成新背景").value)
        assertThrows(IllegalArgumentException::class.java) { profiles.feedback("entry", 2, "note", "不同文字") }
        assertEquals(3, profiles.get("entry")!!.value.revision)
    }

    @Test fun `no op organization and feedback still reserve their operation identity`() {
        val knowledge = publish()
        profiles.create(draft(knowledge), "entry")
        val adopted = profiles.adopt("entry", 1, "adopt")
        val organized = profiles.update("entry", 2, "same", adopted.value.draft)
        assertEquals(ProfileAdoption.ADOPTED, organized.value.adoption)
        assertEquals(3, organized.value.revision)
        assertThrows(IllegalArgumentException::class.java) {
            profiles.update("entry", 2, "same", adopted.value.draft.copy(layer = ProfileLayer.LONG_TERM))
        }
        val feedback = profiles.feedback("entry", 3, "same-note", null)
        assertEquals(4, feedback.value.revision)
        assertThrows(IllegalArgumentException::class.java) { profiles.feedback("entry", 3, "same-note", "新内容") }
    }

    @Test fun `successful operation retries survive rollback before creation without allowing changed payload or new backdated writes`() {
        val knowledge = publish()
        val creationTime = clock.now
        profiles.create(draft(knowledge), "entry")
        clock.now = clock.now.plusSeconds(1)
        val adopted = profiles.adopt("entry", 1, "adopt")
        clock.now = clock.now.plusSeconds(1)
        val feedback = profiles.feedback("entry", 2, "feedback", "合成已保存背景")
        clock.now = creationTime.minusSeconds(60)
        assertEquals(adopted.value, profiles.adopt("entry", 1, "adopt").value)
        assertEquals(feedback.value, profiles.feedback("entry", 2, "feedback", "合成已保存背景").value)
        assertThrows(IllegalArgumentException::class.java) { profiles.decline("entry", 1, "adopt") }
        assertThrows(IllegalArgumentException::class.java) { profiles.feedback("entry", 2, "feedback", "合成不同内容") }
        assertThrows(IllegalArgumentException::class.java) { profiles.decline("entry", 3, "new-backdated-operation") }
        assertEquals(3, profiles.get("entry")!!.value.revision)
    }

    @Test fun `declining and superseding profile never reject the shared observation`() {
        val knowledge = publish()
        profiles.create(draft(knowledge), "entry")
        profiles.adopt("entry", 1, "adopt")
        assertEquals(ProfileAdoption.DECLINED, profiles.decline("entry", 2, "decline").value.adoption)
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(knowledge.id)!!.evidenceState)
        assertThrows(IllegalArgumentException::class.java) { profiles.create(draft(knowledge), "duplicate") }
        assertEquals(ProfileAdoption.SUPERSEDED, profiles.supersede("entry", 3, "supersede").value.adoption)
        assertEquals(0, profiles.open(query).total)
        assertEquals(1, profiles.open(query.copy(currentOnly = false, adoption = ProfileAdoption.SUPERSEDED)).total)
        assertTrue(profiles.adopt("entry", 4, "deliberate-re-adopt").isCurrent)
    }

    @Test fun `source deletion retains independent profile text while removing every linked machine body`() {
        val knowledge = publish()
        profiles.create(draft(knowledge), "entry", "合成独立文字")
        profiles.adopt("entry", 1, "adopt")
        val before = profiles.open(query)
        repository.deleteRecord("source", 1)
        assertThrows(StaleSnapshotException::class.java) { profiles.entries(before) }
        assertEquals(0, profiles.open(query).total)
        val leaf = profiles.get("entry")!!
        assertEquals(ProfileKnowledgeState.MISSING, leaf.knowledgeState)
        assertNull(leaf.knowledge)
        assertEquals("合成独立文字", leaf.value.userText)
        assertThrows(IllegalArgumentException::class.java) { profiles.adopt("entry", 2, "invalid-adopt") }
        val declined = profiles.decline("entry", 2, "decline")
        assertEquals(ProfileAdoption.DECLINED, declined.value.adoption)
        assertEquals("合成仍可补充", profiles.feedback("entry", 3, "feedback", "合成仍可补充").value.userText)
        assertThrows(IllegalArgumentException::class.java) { profiles.create(draft(knowledge), "missing") }
    }

    @Test fun `shared feedback and source correction invalidate current profile without deleting user text`() {
        val knowledge = publish()
        profiles.create(draft(knowledge), "entry", "合成独立内容")
        profiles.adopt("entry", 1, "adopt")
        val before = profiles.open(query)
        val analysis = ControlledAnalysis(repository, clock)
        AnalysisFeedback(repository, clock).respond("run", knowledge.claimRef, analysis.task("run").stored.version,
            ClaimFeedbackDecision.REJECTED, "合成否定共享解释")
        assertThrows(StaleSnapshotException::class.java) { profiles.entry(before, "entry") }
        assertEquals(ProfileKnowledgeState.FEEDBACK_CHANGED, profiles.get("entry")!!.knowledgeState)
        assertEquals(0, profiles.open(query).total)
        repository.updateRecord("source", 1, RecordDraft("合成更正", Occurrence(oldDate)))
        assertEquals(ProfileKnowledgeState.STALE, profiles.get("entry")!!.knowledgeState)
        assertEquals("合成独立内容", profiles.get("entry")!!.value.userText)
    }

    @Test fun `new shared revision never silently retargets profile and changing reference needs new adoption`() {
        val first = publish()
        profiles.create(draft(first), "entry")
        profiles.adopt("entry", 1, "adopt")
        val analysis = ControlledAnalysis(repository, clock)
        analysis.reviewClaim("run", "review-later", first.claimRef, provider = Provider(false, false))
        val second = KnowledgeLibrary(repository, clock).publish("run", first.claimRef, "review-later", KnowledgeCategory.OBSERVATION,
            analysis.task("run").stored.version, first.revision)
        assertEquals(first.revision + 1, second.revision)
        assertEquals(ProfileKnowledgeState.SUPERSEDED, profiles.get("entry")!!.knowledgeState)
        assertEquals(first.revision, profiles.get("entry")!!.value.draft.knowledge.revision)
        val changed = profiles.update("entry", 2, "new-revision", draft(second))
        assertEquals(ProfileAdoption.CANDIDATE, changed.value.adoption)
        assertTrue(profiles.adopt("entry", 3, "accept-new").isCurrent)
    }

    @Test fun `general knowledge cannot be used as a personal observation and confirmed insight can`() {
        val note = publish(category = KnowledgeCategory.KNOWLEDGE_NOTE)
        assertThrows(IllegalArgumentException::class.java) { profiles.create(draft(note), "not-personal") }
        val insight = publish("insight", category = KnowledgeCategory.INSIGHT)
        profiles.create(draft(insight), "interpretation")
        assertEquals(KnowledgeCategory.INSIGHT, profiles.adopt("interpretation", 1, "adopt").knowledge!!.value.category)
    }

    @Test fun `recent states leave current view at query midnight with retained adoption and original date labels`() {
        val knowledge = publish(scope = AnalysisQuery(oldDate, oldDate, "UTC"))
        profiles.create(draft(knowledge).copy(layer = ProfileLayer.RECENT_STATE), "recent")
        profiles.adopt("recent", 1, "adopt")
        val before = profiles.open(query)
        assertEquals(1, before.total)
        assertEquals(ProfileRecentState.IN_WINDOW, profiles.entry(before, "recent").recentState)
        clock.now = before.validUntil
        assertThrows(StaleSnapshotException::class.java) { profiles.entries(before) }
        assertEquals(0, profiles.open(query).total)
        val old = profiles.get("recent", query = query.copy(currentOnly = false))!!
        assertEquals(ProfileAdoption.ADOPTED, old.value.adoption)
        assertEquals(ProfileRecentState.OUTSIDE_WINDOW, old.recentState)
        assertEquals(1, profiles.open(query.copy(recentDays = 31)).total)
        // UTC remains on the previous date at Shanghai midnight.
        assertEquals(1, profiles.open(ProfileQuery(zoneId = "UTC")).total)
    }

    @Test fun `expiry and clock rollback are fences without database writes`() {
        val expiry = clock.now.plusSeconds(60)
        val knowledge = publish(expiresAt = expiry)
        profiles.create(draft(knowledge), "entry")
        profiles.adopt("entry", 1, "adopt")
        val before = profiles.open(query)
        assertEquals(expiry, before.validUntil)
        clock.now = before.evaluatedAt.minusSeconds(1)
        assertThrows(StaleSnapshotException::class.java) { profiles.assertCurrent(before) }
        clock.now = expiry
        assertThrows(StaleSnapshotException::class.java) { profiles.entries(before) }
        assertEquals(ProfileKnowledgeState.EXPIRED, profiles.get("entry")!!.knowledgeState)
        assertEquals(0, profiles.open(query).total)
    }

    @Test fun `historical leaf checks its own earlier knowledge expiry beyond the head expiry`() {
        val expiry = clock.now.plusSeconds(60)
        val first = publish("short", expiresAt = expiry)
        val second = publish("long", expiresAt = clock.now.plusSeconds(600))
        profiles.create(draft(first), "entry")
        profiles.update("entry", 1, "rebind", draft(second))
        val before = profiles.open(query.copy(currentOnly = false))
        assertEquals(second.expiresAt, before.validUntil)
        clock.now = expiry
        assertThrows(StaleSnapshotException::class.java) { profiles.entry(before, "entry", 1) }
        assertEquals(ProfileKnowledgeState.EXPIRED, profiles.entry(profiles.open(query.copy(currentOnly = false)), "entry", 1).knowledgeState)
    }

    @Test fun `reopening after rollback expires at the future adoption or knowledge timestamp`() {
        val publicationTime = clock.now
        val knowledge = publish()
        profiles.create(draft(knowledge), "entry")
        clock.now = clock.now.plusSeconds(60)
        val adoptionTime = clock.now
        profiles.adopt("entry", 1, "adopt")
        clock.now = publicationTime.minusSeconds(60)
        val beforePublication = profiles.open(query)
        assertEquals(0, beforePublication.total)
        assertEquals(publicationTime, beforePublication.validUntil)
        clock.now = publicationTime
        assertThrows(StaleSnapshotException::class.java) { profiles.entries(beforePublication) }
        val beforeAdoption = profiles.open(query)
        assertEquals(0, beforeAdoption.total)
        assertEquals(adoptionTime, beforeAdoption.validUntil)
        clock.now = adoptionTime
        assertThrows(StaleSnapshotException::class.java) { profiles.entries(beforeAdoption) }
        assertEquals(1, profiles.open(query).total)
    }

    @Test fun `raw evidence remains citation bounded and does not inherit the recent viewing cutoff`() {
        val knowledge = publish(scope = AnalysisQuery(today.minusDays(60), today, "UTC"), fragmentSize = 8)
        profiles.create(draft(knowledge).copy(layer = ProfileLayer.RECENT_STATE), "entry")
        profiles.adopt("entry", 1, "adopt")
        val view = profiles.open(query.copy(recentDays = 7))
        val receipt = knowledge.evidence.single()
        assertEquals(body.take(8), profiles.evidence(view, "entry", receipt.id, maxCodePoints = 4_096).text)
        assertEquals(body.substring(4, 8), profiles.evidence(view, "entry", receipt.id, start = 4).text)
        assertThrows(IllegalArgumentException::class.java) { profiles.evidence(view, "entry", receipt.id, start = 8) }
        assertThrows(IllegalArgumentException::class.java) { profiles.evidence(view, "entry", "not-registered") }
        assertThrows(IllegalArgumentException::class.java) { profiles.evidence(view, "entry", receipt.id, maxCodePoints = 4097) }
    }

    @Test fun `metric proof remains typed and exposes only bounded registered source pages`() {
        val knowledge = publish("metric", metric = true)
        profiles.create(draft(knowledge), "entry")
        profiles.adopt("entry", 1, "adopt")
        val view = profiles.open(query)
        val metric = knowledge.metricEvidence.single()
        assertEquals(listOf("source"), profiles.metricSources(view, "entry", metric.id).items.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { profiles.metricSources(view, "entry", metric.id, limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { profiles.evidence(view, "entry", metric.id) }
        assertThrows(IllegalArgumentException::class.java) { profiles.metricSources(view, "entry", "unknown") }
    }

    @Test fun `metadata and history pages are bounded immutable and filter registration is enforced`() {
        val knowledge = publish()
        ProfileDimension.entries.forEachIndexed { index, dimension ->
            profiles.create(draft(knowledge).copy(dimension = dimension), "entry-$index")
            profiles.adopt("entry-$index", 1, "adopt-$index")
        }
        val view = profiles.open(query)
        val page = profiles.entries(view, limit = 3)
        assertEquals(8, page.total)
        assertEquals(3, page.nextOffset)
        assertEquals(3, profiles.entries(view, offset = page.nextOffset!!, limit = 3).items.size)
        assertThrows(UnsupportedOperationException::class.java) { (page.items as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (view.dimensions as MutableList<*>).clear() }
        assertThrows(IllegalArgumentException::class.java) { profiles.entries(view, limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { profiles.history(view, "entry-0", offset = -1) }
        assertEquals(1, profiles.history(view, "entry-0", limit = 1).nextOffset)
        val selected = profiles.open(query.copy(dimension = ProfileDimension.VALUES_AND_DIRECTION))
        assertThrows(IllegalArgumentException::class.java) { profiles.entry(selected, "entry-1") }
        profiles.feedback("entry-0", 2, "new-note", "合成补充")
        assertThrows(StaleSnapshotException::class.java) { profiles.entries(view) }
    }

    @Test fun `clearing latest feedback retains history and whole entry deletion erases the chain`() {
        val knowledge = publish()
        profiles.create(draft(knowledge), "entry", "合成历史独立文字")
        profiles.feedback("entry", 1, "clear-current", null)
        val all = profiles.open(query.copy(currentOnly = false))
        assertNull(profiles.entry(all, "entry").value.userText)
        assertEquals("合成历史独立文字", profiles.entry(all, "entry", 1).value.userText)
        profiles.delete("entry", 2)
        assertNull(profiles.get("entry"))
        assertThrows(IllegalArgumentException::class.java) { profiles.create(draft(knowledge), "entry") }
        assertNotNull(KnowledgeLibrary(repository, clock).get(knowledge.id))
        assertThrows(StaleSnapshotException::class.java) { profiles.history(all, "entry") }
    }

    private fun draft(knowledge: StoredKnowledgeRevision) = ProfileDraft(ProfileKnowledgeRef(knowledge.id, knowledge.revision),
        ProfileDimension.BEHAVIOR_AND_DECISIONS, ProfileLayer.CONTEXTUAL_PATTERN)

    private fun publish(id: String = "run", category: KnowledgeCategory = KnowledgeCategory.OBSERVATION,
        scope: AnalysisQuery = AnalysisQuery(oldDate, today, "UTC"), expiresAt: Instant? = null,
        fragmentSize: Int = 1024, metric: Boolean = false): StoredKnowledgeRevision {
        val analysis = ControlledAnalysis(repository, clock)
        val provider = Provider(metric, category == KnowledgeCategory.INSIGHT || metric)
        analysis.start(AnalysisRequest("合成画像依据整理", scope, limits = AnalysisLimits(fragmentCodePoints = fragmentSize), expiresAt = expiresAt), id)
        if (metric) analysis.analyzeMetrics(id, "extract", provider)
        else analysis.runBatch(id, "extract", listOf(EvidenceSelection("source", "body")), provider)
        val claim = analysis.claimRef(id, "extract", 0)
        if (category == KnowledgeCategory.INSIGHT || metric)
            AnalysisFeedback(repository, clock).respond(id, claim, analysis.task(id).stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim(id, "review", claim, provider = provider)
        return KnowledgeLibrary(repository, clock).publish(id, claim, "review", if (metric) KnowledgeCategory.INSIGHT else category,
            analysis.task(id).stored.version)
    }

    private class Provider(private val metric: Boolean, private val interpretation: Boolean) : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-profile-source"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        private fun receipt(request: ModelRequest) = JSONObject(request.payloadJson)
            .getJSONArray(if (metric) "metricEvidence" else "evidence").getJSONObject(0).getString("id")
        override fun complete(request: ModelRequest) = BatchProposal(listOf(ProposedClaim("合成共享陈述：仅描述记录中的情境。",
            listOf(receipt(request)), if (interpretation) ClaimKind.POSSIBLE_INTERPRETATION else ClaimKind.REPORTED_EXPERIENCE)))
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun reviewPrepared(request: PreparedModelRequest) = ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(receipt(request.original))))
    }
    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T01:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
