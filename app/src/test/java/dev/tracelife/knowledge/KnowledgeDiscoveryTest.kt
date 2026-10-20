package dev.tracelife.knowledge

import android.content.Context
import dev.tracelife.actions.*
import dev.tracelife.analysis.*
import dev.tracelife.model.*
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
import java.time.*
import java.util.UUID

/** Real SQLite, synthetic prose and scripted review verdicts; no network or personal data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KnowledgeDiscoveryTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var discovery: KnowledgeDiscovery
    private val clock = MutableClock()
    private val database = "synthetic-discovery-${UUID.randomUUID()}.db"
    private val day = LocalDate.parse("2026-10-20")

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        discovery = KnowledgeDiscovery(repository, clock)
    }
    @After fun teardown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `search finds long text tail and template answers rather than only body previews`() {
        repository.createRecord(RecordDraft("合成资料：" + "普通段落。".repeat(2_000) + "结尾单独写下月光散步。", Occurrence(day)), "tail")
        val template = repository.saveTemplate(TemplateDraft("合成模板", Cadence.DAILY,
            questions = listOf(TemplateQuestion("actual", "实际发生了什么"))), "synthetic-template")
        repository.createRecord(RecordDraft("合成正文没有关键词", Occurrence(day), template = TemplateRef(template.id, template.version),
            answers = mapOf("actual" to "合成回答：昨夜月光散步之后感到平静。")), "answer")
        val result = discovery.search(DiscoveryQuery("月光散步"))
        assertEquals(setOf("tail", "answer"), result.items.map { it.id }.toSet())
        assertTrue(result.items.all { it.excerpt.contains("月光散步") })
        assertTrue(result.items.all { it.excerpt.length < 250 })
    }

    @Test fun `all bounded source pages remain searchable and stable result pages never duplicate`() {
        repeat(73) { index ->
            repository.createRecord(RecordDraft("合成星光练习第 $index 次记录", Occurrence(day), setOf("学习")), "record-$index")
        }
        val query = DiscoveryQuery("星光练习", topic = "学习")
        val seen = mutableListOf<String>()
        var page = discovery.search(query, limit = 7)
        assertEquals(73, page.total)
        while (true) {
            assertTrue(page.items.size <= 7)
            seen += page.items.map { it.key }
            if (!page.hasNext) break
            page = discovery.search(query, 7, page)
        }
        assertEquals(73, seen.size)
        assertEquals(73, seen.toSet().size)
        assertEquals((0..72).map { "RECORD:record-$it" }.toSet(), seen.toSet())
    }

    @Test fun `deletion invalidates search cursor and deleted source cannot appear in refreshed results`() {
        repeat(3) { repository.createRecord(RecordDraft("合成雨后散步记录", Occurrence(day)), "record-$it") }
        val query = DiscoveryQuery("雨后散步")
        val first = discovery.search(query, limit = 1)
        assertTrue(first.hasNext)
        repository.deleteRecord("record-1", 1)
        assertThrows(StaleSnapshotException::class.java) { discovery.search(query, 1, first) }
        assertEquals(setOf("record-0", "record-2"), discovery.search(query).items.map { it.id }.toSet())
    }

    @Test fun `pending knowledge stays hidden by default and appears with explicit status when requested`() {
        val value = publish(0, "合成月光专注可能帮助启动。")
        reject(value)
        val normal = discovery.search(DiscoveryQuery("月光专注"))
        assertTrue(normal.items.none { it.kind == DiscoveryKind.KNOWLEDGE })
        assertEquals(1, normal.omittedPending)
        val pending = discovery.search(DiscoveryQuery("月光专注", includePending = true)).items.single { it.kind == DiscoveryKind.KNOWLEDGE }
        assertEquals(value.id, pending.id)
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, pending.state)
    }

    @Test fun `past expiry of explicitly included experience cannot make every next page stale`() {
        val value = publish(0, "合成松林专注理解", expiresAt = clock.now.plusSeconds(60))
        val continuity = KnowledgeContinuity(repository, clock)
        continuity.create(value.id, value.revision, "合成松林专注线索", setOf("学习"), id = "thread")
        repository.createRecord(RecordDraft("合成松林专注的另一份材料", Occurrence(day)), "other")
        clock.now = clock.now.plusSeconds(120)
        val query = DiscoveryQuery("松林专注", includePending = true)
        var page = discovery.search(query, limit = 1)
        assertTrue(page.hasNext)
        val seen = page.items.map { it.key }.toMutableSet()
        while (page.hasNext) { page = discovery.search(query, 1, page); seen += page.items.map { it.key } }
        assertTrue("EXPERIENCE:thread" in seen)
        assertTrue("KNOWLEDGE:${value.id}" in seen)
    }

    @Test fun `Unicode lowercase expansion does not corrupt original excerpt offsets`() {
        repository.createRecord(RecordDraft("İ".repeat(100) + "ab", Occurrence(day)), "unicode")
        val result = discovery.search(DiscoveryQuery("ab"))
        assertEquals("unicode", result.items.single().id)
        assertTrue(result.items.single().excerpt.contains("ab"))
    }

    @Test fun `search finds independent exception and replaced notes at their actual history entry`() {
        val first = publish(0, "合成最初的学习理解")
        val exception = publish(1, "合成一次不同情境的例子")
        val replacement = publish(2, "合成后来采用的理解")
        val continuity = KnowledgeContinuity(repository, clock)
        continuity.create(first.id, 1, "合成经验变化", setOf("学习"), "初次说明保留银杏条件", "thread")
        continuity.append("thread", 1, exception.id, 1, ExperienceRelation.EXCEPTION, "独立反例说明：栀子任务存在额外依赖。")
        continuity.append("thread", 2, replacement.id, 1, ExperienceRelation.REPLACE, "改用新的条件范围。")
        val hit = discovery.search(DiscoveryQuery("栀子任务")).items.single { it.kind == DiscoveryKind.EXPERIENCE }
        assertEquals("thread", hit.id)
        assertEquals(2, hit.experienceSequence)
        assertTrue(hit.excerpt.contains("栀子任务"))
        val replaced = discovery.search(DiscoveryQuery("银杏条件")).items.single { it.kind == DiscoveryKind.EXPERIENCE }
        assertEquals(1, replaced.experienceSequence)
        assertEquals(replacement.id, continuity.get("thread")!!.current.change.knowledgeId)
    }

    @Test fun `topic suggestions explain text matches and never write tags automatically`() {
        val record = repository.createRecord(RecordDraft("合成资料", Occurrence(day), setOf("系统设计")), "known-tag")
        val source = repository.analysisSourceStamp()
        val generation = repository.currentAnalysisGeneration()
        val suggestions = discovery.suggestTopics("合成随记：今天学习系统设计，然后跑步。", existing = setOf("学习"))
        assertTrue(suggestions.any { it.topic == "系统设计" && it.reason.contains("使用过") })
        assertTrue(suggestions.any { it.topic == "运动" && it.reason.contains("跑步") })
        assertTrue(suggestions.none { it.topic == "学习" })
        assertEquals(source, repository.analysisSourceStamp())
        assertEquals(generation, repository.currentAnalysisGeneration())
        assertEquals(record, repository.record("known-tag"))
    }

    @Test fun `experience suggestions exclude already linked and invalid current understanding`() {
        val good = publish(0, "合成学习先做小练习，仍需观察任务难度。")
        val pending = publish(1, "合成学习练习经验，原判断需要纠正。")
        val candidate = publish(2, "合成学习新尝试：一个小练习帮助发现问题。")
        val continuity = KnowledgeContinuity(repository, clock)
        continuity.create(good.id, 1, "合成学习练习线索", setOf("学习"), id = "good-thread")
        continuity.create(pending.id, 1, "合成学习练习旧线索", setOf("学习"), id = "pending-thread")
        continuity.create(candidate.id, 1, "合成已关联线索", setOf("学习"), id = "already-linked")
        reject(pending)
        val suggestions = discovery.suggestExperiences(candidate.id, 1)
        assertEquals(listOf("good-thread"), suggestions.map { it.thread.id })
        assertTrue(suggestions.single().reasons.any { it.contains("共同主题：学习") })
        assertEquals(1, repository.experienceThread("good-thread")!!.version)
    }

    @Test fun `topic dossier separates explicit concern pending knowledge and actual adopted actions`() {
        val understanding = publish(0, "值得留下：合成学习练习需要保留任务条件。")
        val concern = publish(1, "值得关注：合成学习中频繁中断仍需要回看。")
        val pending = publish(2, "合成旧理解已不符合新的情况。")
        val actions = ActionExperiments(repository, clock)
        val active = actions.create(action("合成继续尝试", understanding), "active")
        actions.create(action("合成待更新来源尝试", pending), "pending-origin")
        actions.create(action("合成停止尝试", concern), "stopped")
        actions.stop("stopped", 1, "stop", "合成决定先停止")
        actions.create(action("合成未分类行动", understanding).copy(origin = null), "unclassified-action")
        KnowledgeContinuity(repository, clock).create(understanding.id, 1, "合成学习变化", setOf("学习"), id = "thread")
        reject(pending)
        val dossier = TopicDossier(repository, clock).open("学习")
        assertEquals(listOf(understanding.id), dossier.understandings.items.map { it.value.id })
        assertEquals(listOf(concern.id), dossier.concerns.items.map { it.value.id })
        assertEquals(listOf(pending.id), dossier.pending.items.map { it.value.id })
        assertEquals(setOf(active.value.id, "pending-origin"), dossier.actions.items.map { it.value.id }.toSet())
        assertEquals(ActionOriginState.STALE, dossier.actions.items.single { it.value.id == "pending-origin" }.originState)
        assertEquals(1, dossier.experiences.total)
        assertEquals(2, dossier.recent.total)
    }

    @Test fun `topic overview retains bounded newest items while reporting complete section counts`() {
        repeat(7) { publish(it, "值得关注：合成问题 $it 仍需回看。") }
        val dossier = TopicDossier(repository, clock).open("学习", limit = 2)
        assertEquals(7, dossier.concerns.total)
        assertEquals(2, dossier.concerns.items.size)
        assertEquals(7, dossier.recent.total)
        assertEquals(2, dossier.recent.items.size)
        assertEquals(7, dossier.records)
    }

    private fun action(title: String, origin: StoredKnowledgeRevision) = ActionDraft(title, "合成触发", "合成做法", "合成观察信号",
        day.plusDays(7), "UTC", ActionOrigin(origin.id, origin.revision))
    private fun reject(value: StoredKnowledgeRevision) {
        AnalysisFeedback(repository, clock).respond(value.runId, value.claimRef, repository.analysisRun(value.runId)!!.version,
            ClaimFeedbackDecision.CORRECTED, "合成纠正：原描述遗漏条件。")
    }
    private fun publish(index: Int, wording: String, expiresAt: Instant? = null): StoredKnowledgeRevision {
        val recordId = "source-$index"
        val date = day.minusDays(index.toLong())
        repository.createRecord(RecordDraft("合成原始学习材料第 $index 条。", Occurrence(date), setOf("学习")), recordId)
        val analysis = ControlledAnalysis(repository, clock)
        val run = "run-$index"
        analysis.start(AnalysisRequest("合成材料整理", AnalysisQuery(date, date, "UTC", recordIds = setOf(recordId)), expiresAt = expiresAt), run)
        analysis.runBatch(run, "extract", listOf(EvidenceSelection(recordId, "body")), object : AnalysisProvider {
            override val id = "synthetic-discovery-extractor"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = 100
            override fun complete(request: ModelRequest): BatchProposal {
                val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim(wording, listOf(id), ClaimKind.REPORTED_EXPERIENCE)))
            }
        })
        val claim = analysis.claimRef(run, "extract", 0)
        analysis.reviewClaim(run, "review", claim, provider = object : SemanticReviewProvider {
            override val id = "synthetic-discovery-reviewer"
            override val contextWindowTokens = 100_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val id = JSONObject(request.original.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(id)))
            }
        })
        return KnowledgeLibrary(repository, clock).publish(run, claim, "review", KnowledgeCategory.KNOWLEDGE_NOTE,
            analysis.task(run).stored.version, topics = setOf("学习"))
    }
    private class MutableClock : Clock() {
        var now = Instant.parse("2026-10-20T00:00:00Z")
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
