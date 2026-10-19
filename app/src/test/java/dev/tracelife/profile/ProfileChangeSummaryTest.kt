package dev.tracelife.profile

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.StoredKnowledgeRevision
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

/** Synthetic organization decisions. The change summary itself never calls any model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileChangeSummaryTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var profiles: PersonalProfile
    private lateinit var summary: ProfileChangeSummary
    private val database = "synthetic-profile-changes-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val date = LocalDate.parse("2026-10-19")
    private val query = ProfileChangesQuery(date, date, "UTC")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication(); open()
        repository.createRecord(RecordDraft("合成来源：在练习中列小步骤后完成任务。", Occurrence(date)), "source")
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `deterministic paginated differences separate adoption organization notes and shared knowledge`() {
        val original = publish("original")
        profiles.create(draft(original), "entry", "合成独立文字一")
        tick(); profiles.adopt("entry", 1, "adopt")
        tick(); profiles.feedback("entry", 2, "note", "合成独立文字二")
        val other = publish("other")
        tick(); profiles.update("entry", 3, "organize", draft(other).copy(layer = ProfileLayer.LONG_TERM))
        val snapshot = summary.open(query)
        assertEquals(4, snapshot.total)
        assertEquals(1, snapshot.counts[ProfileChangedField.CREATED])
        assertEquals(1, snapshot.counts[ProfileChangedField.KNOWLEDGE_REFERENCE])
        assertEquals(2, snapshot.counts[ProfileChangedField.ADOPTION])
        assertEquals(1, snapshot.counts[ProfileChangedField.USER_TEXT])
        assertFalse(snapshot.measuresPersonalGrowth)
        val first = summary.changes(snapshot, limit = 2)
        assertEquals(listOf(4, 3), first.items.map { it.revision })
        assertEquals(listOf(2, 1), summary.next(first).items.map { it.revision })
        val organized = summary.detail(first, "entry", 4)
        assertEquals(original.id, organized.before!!.knowledge!!.value.id)
        assertEquals(other.id, organized.after.knowledge!!.value.id)
        assertEquals("合成独立文字二", organized.after.value.userText)
        assertThrows(IllegalArgumentException::class.java) { summary.detail(first, "entry", 1) }
        assertThrows(UnsupportedOperationException::class.java) { (first.items as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (first.items.first().fields as MutableSet<*>).clear() }
        assertThrows(IllegalArgumentException::class.java) { summary.changes(snapshot, limit = 101) }
    }

    @Test fun `moving dimension remains visible in both dimension filters`() {
        val source = publish("original")
        profiles.create(draft(source), "entry")
        tick(); profiles.update("entry", 1, "move", draft(source).copy(dimension = ProfileDimension.VALUES_AND_DIRECTION))
        val oldDimension = summary.changes(summary.open(query.copy(dimension = ProfileDimension.BEHAVIOR_AND_DECISIONS)))
        val newDimension = summary.changes(summary.open(query.copy(dimension = ProfileDimension.VALUES_AND_DIRECTION)))
        assertEquals(listOf(2, 1), oldDimension.items.map { it.revision })
        assertEquals(listOf(2), newDimension.items.map { it.revision })
        assertTrue(ProfileChangedField.DIMENSION in newDimension.items.single().fields)
    }

    @Test fun `source edits keep retained history labelled stale and delete clears pages and text`() {
        val knowledge = publish("original")
        profiles.create(draft(knowledge), "entry", "合成独立文字")
        val before = summary.changes(summary.open(query))
        repository.updateRecord("source", 1, RecordDraft("合成已纠正的来源。", Occurrence(date)))
        assertThrows(StaleSnapshotException::class.java) { summary.detail(before, "entry", 1) }
        val current = summary.changes(summary.open(query))
        assertEquals(ProfileKnowledgeState.STALE, summary.detail(current, "entry", 1).after.knowledgeState)
        profiles.delete("entry", 1)
        assertThrows(StaleSnapshotException::class.java) { summary.detail(current, "entry", 1) }
        assertEquals(0, summary.open(query).total)
        assertFalse(summary.open(query).includesPermanentlyDeletedHistory)
        assertNull(repository.profileRevision("entry"))
    }

    @Test fun `date filtering uses requested zone and reopening keeps the same revision facts`() {
        clock.now = Instant.parse("2026-10-19T16:30:00Z")
        profiles.create(draft(publish("original")), "entry")
        val tomorrow = query.copy(from = date.plusDays(1), through = date.plusDays(1))
        assertEquals(0, summary.open(tomorrow).total)
        val china = tomorrow.copy(zoneId = "Asia/Shanghai")
        val first = summary.changes(summary.open(china)).items
        assertEquals(1, first.size)
        repository.close(); open()
        assertEquals(first, summary.changes(summary.open(china)).items)
    }

    @Test fun `clock rollback midnight and later commands invalidate opened summaries`() {
        profiles.create(draft(publish("original")), "entry")
        val snapshot = summary.open(query)
        clock.now = clock.now.minusSeconds(1)
        assertThrows(StaleSnapshotException::class.java) { summary.changes(snapshot) }
        clock.now = snapshot.evaluatedAt
        val day = summary.open(query)
        clock.now = day.validUntil
        assertThrows(StaleSnapshotException::class.java) { summary.changes(day) }
        val refreshed = summary.open(query)
        profiles.feedback("entry", 1, "new-note", "合成新补充")
        assertThrows(StaleSnapshotException::class.java) { summary.changes(refreshed) }
    }

    @Test fun `opening after rollback expires at the earliest future historical revision`() {
        profiles.create(draft(publish("original")), "entry")
        val createdAt = clock.now
        tick(); profiles.adopt("entry", 1, "adopt")
        val adoptionAt = clock.now
        tick(); profiles.feedback("entry", 2, "later-note", "合成较晚说明")
        clock.now = createdAt
        val snapshot = summary.open(query)
        assertEquals(1, snapshot.total)
        assertEquals(adoptionAt, snapshot.validUntil)
        clock.now = adoptionAt
        assertThrows(StaleSnapshotException::class.java) { summary.changes(snapshot) }
    }

    private fun open() {
        repository = LifeRepository(context, clock, database)
        profiles = PersonalProfile(repository, clock); summary = ProfileChangeSummary(repository, clock)
    }
    private fun tick() { clock.now = clock.now.plusSeconds(1) }
    private fun draft(value: StoredKnowledgeRevision) = ProfileDraft(ProfileKnowledgeRef(value.id, value.revision),
        ProfileDimension.BEHAVIOR_AND_DECISIONS, ProfileLayer.CONTEXTUAL_PATTERN)
    private fun publish(id: String): StoredKnowledgeRevision {
        val analysis = ControlledAnalysis(repository, clock)
        val model = object : AnalysisProvider, SemanticReviewProvider {
            override val id = "synthetic-profile-history"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = 100
            private fun receipt(request: ModelRequest) = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            override fun complete(request: ModelRequest) = BatchProposal(listOf(ProposedClaim("合成情境自述。", listOf(receipt(request)), ClaimKind.REPORTED_EXPERIENCE)))
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun reviewPrepared(request: PreparedModelRequest) = ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(receipt(request.original))))
        }
        analysis.start(AnalysisRequest("合成画像依据", AnalysisQuery(date, date, "UTC")), id)
        analysis.runBatch(id, "extract", listOf(EvidenceSelection("source", "body")), model)
        val claim = analysis.claimRef(id, "extract", 0)
        analysis.reviewClaim(id, "review", claim, provider = model)
        return KnowledgeLibrary(repository, clock).publish(id, claim, "review", KnowledgeCategory.OBSERVATION, analysis.task(id).stored.version)
    }
    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T03:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
