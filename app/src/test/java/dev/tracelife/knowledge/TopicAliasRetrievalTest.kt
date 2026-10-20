package dev.tracelife.knowledge

import android.app.Application
import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.*
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
import java.time.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TopicAliasRetrievalTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private val clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC)
    private val day = LocalDate.parse("2026-10-20")
    private val scope = AnalysisQuery(day, day, "Asia/Shanghai")
    private val database = "synthetic-alias-retrieval-${UUID.randomUUID()}.db"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `text aliases retrieve raw knowledge and experience and stop after archive`() {
        val saved = publish("source", "专注实践先运行最小例子，条件仍待观察。", setOf("专注实践"))
        KnowledgeContinuity(repository, clock).create(saved.id, saved.revision, "案例线索", setOf("专注实践"), id = "thread")
        val discovery = KnowledgeDiscovery(repository, clock)
        assertTrue(discovery.search(DiscoveryQuery("deep focus")).items.isEmpty())
        val aliases = TopicAliases(repository)
        val rule = aliases.save("专注实践", setOf("deep focus"))
        val results = discovery.search(DiscoveryQuery("deep focus"))
        assertEquals(setOf(DiscoveryKind.RECORD, DiscoveryKind.KNOWLEDGE, DiscoveryKind.EXPERIENCE), results.items.map { it.kind }.toSet())
        assertTrue(results.items.none { it.id == rule.id })
        assertTrue(results.items.all { it.excerpt.contains("专注实践") })
        aliases.archive(rule.id, rule.revision)
        assertTrue(discovery.search(DiscoveryQuery("deep focus")).items.isEmpty())
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(saved.id)!!.evidenceState)
    }

    @Test fun `topic filter expands aliases locally across every record page`() {
        repeat(35) { repository.createRecord(RecordDraft("小范围验收第 $it 条", Occurrence(day), setOf("专注实践")), "record-$it") }
        val aliases = TopicAliases(repository)
        val rule = aliases.save("专注实践", setOf("deep focus"))
        val discovery = KnowledgeDiscovery(repository, clock)
        val query = DiscoveryQuery("小范围验收", topic = "DEEP FOCUS")
        val first = discovery.search(query, limit = 20)
        assertEquals(35, first.total)
        val second = discovery.search(query, limit = 20, previous = first)
        assertEquals(35, (first.items + second.items).map { it.id }.toSet().size)
        aliases.archive(rule.id, rule.revision)
        assertEquals(0, discovery.search(query).total)
    }

    @Test fun `question topic and routed navigation terms can locate existing understanding via aliases`() {
        val saved = publish("source", "专注实践需要明确停止条件。", setOf("专注实践"))
        val catalog = KnowledgeContextCatalog(repository, clock)
        assertTrue(catalog.plan("deep focus", scope, emptySet()).entries.isEmpty())
        val aliases = TopicAliases(repository)
        val rule = aliases.save("专注实践", setOf("deep focus"))
        assertEquals(saved.id, catalog.plan("How can deep focus help?", scope, emptySet()).entries.single().knowledgeId)
        assertEquals(saved.id, catalog.plan("recent changes?", scope, setOf("DEEP FOCUS")).entries.single().knowledgeId)
        assertEquals(saved.id, catalog.plan("recent changes?", scope, emptySet(), setOf("deep focus")).entries.single().knowledgeId)
        aliases.archive(rule.id, rule.revision)
        assertTrue(catalog.plan("deep focus", scope, emptySet()).entries.isEmpty())
    }

    @Test fun `equivalent topic names recommend experience without changing stored tags`() {
        val previous = publish("previous", "蓝色庭院需要定期维护。", setOf("专注实践"))
        KnowledgeContinuity(repository, clock).create(previous.id, previous.revision, "旧线索", setOf("专注实践"), id = "thread")
        val fresh = publish("fresh", "白帆照亮远方港湾。", setOf("deep focus"))
        val discovery = KnowledgeDiscovery(repository, clock)
        assertTrue(discovery.suggestExperiences(fresh.id, fresh.revision).isEmpty())
        val aliases = TopicAliases(repository)
        val rule = aliases.save("专注实践", setOf("deep focus"))
        assertEquals("thread", discovery.suggestExperiences(fresh.id, fresh.revision).single().thread.id)
        aliases.archive(rule.id, rule.revision)
        assertTrue(discovery.suggestExperiences(fresh.id, fresh.revision).isEmpty())
        assertEquals(setOf("deep focus"), repository.knowledgeRevision(fresh.id)!!.topics)
    }

    @Test fun `short Latin aliases do not match inside unrelated words`() {
        TopicAliases(repository).save("人工智能", setOf("AI"))
        assertTrue(TopicAliases(repository).matching("repair a chair").isEmpty())
        assertEquals(setOf("人工智能", "ai"), TopicAliases(repository).matching("try AI today"))
    }

    private fun publish(id: String, text: String, topics: Set<String>): StoredKnowledgeRevision {
        repository.createRecord(RecordDraft(text, Occurrence(day), topics), id)
        val provider = SyntheticProvider(text)
        val run = analysis.start(AnalysisRequest("合成依据", scope.copy(recordIds = setOf(id))))
        analysis.synthesize(run.stored.id, "synthesis", listOf(EvidenceSelection(id, "body")), provider)
        val claim = analysis.claimRef(run.stored.id, "synthesis", 0)
        AnalysisFeedback(repository, clock).respond(run.stored.id, claim, analysis.task(run.stored.id).stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim(run.stored.id, "review", claim, provider = provider)
        return KnowledgeLibrary(repository, clock).publish(run.stored.id, claim, "review", KnowledgeCategory.INSIGHT,
            analysis.task(run.stored.id).stored.version, topics = topics)
    }

    private class SyntheticProvider(private val text: String) : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-alias-retrieval"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest) = BatchProposal(listOf(ProposedClaim(text, ids(request), ClaimKind.POSSIBLE_INTERPRETATION)))
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest) = ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, supportingEvidenceIds = ids(request.original)))
        private fun ids(request: ModelRequest): List<String> = JSONObject(request.payloadJson).getJSONArray("evidence").let { values ->
            (0 until values.length()).map { values.getJSONObject(it).getString("id") }
        }
    }
}
