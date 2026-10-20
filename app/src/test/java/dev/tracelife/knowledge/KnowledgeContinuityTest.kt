package dev.tracelife.knowledge

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
import dev.tracelife.storage.*
import dev.tracelife.transfer.*
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KnowledgeContinuityTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var continuity: KnowledgeContinuity
    private val name = "synthetic-continuity-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC)
    private val date = LocalDate.parse("2026-10-05")
    private lateinit var directory: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        continuity = KnowledgeContinuity(repository, clock)
        directory = File(context.cacheDir, "synthetic-continuity-${UUID.randomUUID()}").apply { mkdirs() }
        repeat(4) { index -> repository.createRecord(RecordDraft("合成第 $index 周经历：简单任务可直接试做，复杂任务需要先确认接口。", Occurrence(date.plusWeeks(index.toLong())), setOf("学习")), "source-$index") }
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name); directory.deleteRecursively() }

    @Test fun `several weeks refine one identity while original publications remain unchanged`() {
        val first = publish(0, "合成经验：先动手可能帮助开始。")
        val second = publish(1, "合成经验：复杂任务先确认接口再动手。")
        val start = continuity.create(first.id, 1, "合成：怎么开始任务", setOf("学习"), "合成独立说明：先从一次尝试开始", "thread")
        val refined = continuity.append(start.id, 1, second.id, 1, ExperienceRelation.NARROW, "合成独立说明：复杂任务需要先规划边界")
        val view = requireNotNull(continuity.get(start.id))
        assertEquals(start.id, refined.id)
        assertEquals(second.id, view.current.change.knowledgeId)
        assertEquals(listOf(2, 1), view.changes.map { it.change.sequence })
        assertEquals(first, KnowledgeLibrary(repository, clock).get(first.id)!!.value)
        assertEquals(second, KnowledgeLibrary(repository, clock).get(second.id)!!.value)
        assertEquals(listOf(start.id), continuity.page(topic = "学习").items.map { it.thread.id })
        assertEquals(listOf(start.id), continuity.page(knowledgeId = first.id).items.map { it.thread.id })
    }

    @Test fun `retrying creation with same user operation does not create a second experience`() {
        val first = publish(0)
        val created = continuity.create(first.id, 1, "合成经验", emptySet(), "合成独立说明", "stable-operation")
        assertEquals(created, continuity.create(first.id, 1, "合成经验", emptySet(), "合成独立说明", "stable-operation"))
        assertEquals(1, continuity.page().items.size)
        assertThrows(IllegalArgumentException::class.java) {
            continuity.create(first.id, 1, "不同内容不能复用身份", emptySet(), id = "stable-operation")
        }
    }

    @Test fun `exceptions and supplements stay attached without replacing selected understanding`() {
        val first = publish(0); val exception = publish(1); val supplement = publish(2)
        val thread = continuity.create(first.id, 1, "合成经验", emptySet())
        continuity.append(thread.id, 1, exception.id, 1, ExperienceRelation.EXCEPTION, "合成反例：任务复杂度不同")
        continuity.append(thread.id, 2, supplement.id, 1, ExperienceRelation.SUPPLEMENT)
        assertEquals(first.id, continuity.get(thread.id)!!.current.change.knowledgeId)
        assertEquals(3, continuity.get(thread.id)!!.thread.version)
        assertThrows(IllegalArgumentException::class.java) {
            continuity.append(thread.id, 3, exception.id, 1, ExperienceRelation.REPLACE, "重复引用不能制造新的形成过程")
        }
    }

    @Test fun `current selection becomes pending without falling back when corrected`() {
        val first = publish(0); val second = publish(1)
        val thread = continuity.create(first.id, 1, "合成经验", emptySet())
        continuity.append(thread.id, 1, second.id, 1, ExperienceRelation.REPLACE, "合成新的说明")
        AnalysisFeedback(repository, clock).respond(second.runId, second.claimRef,
            repository.analysisRun(second.runId)!!.version, ClaimFeedbackDecision.CORRECTED, "合成独立纠正：还需要更多情境")
        val selected = continuity.get(thread.id)!!.current
        assertEquals(second.id, selected.change.knowledgeId)
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, selected.knowledge!!.evidenceState)
        assertEquals("合成独立纠正：还需要更多情境", selected.feedback!!.feedback.userText)
        assertThrows(IllegalArgumentException::class.java) { continuity.create(second.id, 1, "禁止复活", emptySet()) }
    }

    @Test fun `deleting source erases machine body but keeps independently written history`() {
        val first = publish(0)
        val thread = continuity.create(first.id, 1, "合成经验", setOf("学习"), "合成独立说明应保留")
        repository.deleteRecord("source-0", 1)
        val view = requireNotNull(continuity.get(thread.id))
        assertNull(view.current.knowledge)
        assertEquals(1, KnowledgeJourney(repository, clock).topics().single { it.name == "学习" }.experienceThreads)
        assertEquals("合成独立说明应保留", view.current.change.userNote)
        val snapshot = capture()
        assertTrue(snapshot.knowledgeRevisions.none { it.id == first.id })
        assertEquals("合成独立说明应保留", snapshot.experienceChanges.single().userNote)
        SnapshotValidation.validate(snapshot)
    }

    @Test fun `optimistic changes and page snapshots reject concurrent user decisions`() {
        val first = publish(0); val second = publish(1); val third = publish(2)
        val thread = continuity.create(first.id, 1, "合成经验", emptySet())
        val page = continuity.page(limit = 1)
        val detail = continuity.get(thread.id, limit = 1)!!
        continuity.append(thread.id, 1, second.id, 1, ExperienceRelation.SUPPLEMENT)
        assertThrows(RevisionConflictException::class.java) {
            continuity.append(thread.id, 1, third.id, 1, ExperienceRelation.REPLACE, "不能覆盖已保存变化")
        }
        assertThrows(StaleSnapshotException::class.java) { continuity.page(limit = 1, previous = page) }
        assertThrows(StaleSnapshotException::class.java) { continuity.get(thread.id, limit = 1, previous = detail) }
        assertThrows(RevisionConflictException::class.java) { continuity.delete(thread.id, 1) }
    }

    @Test fun `backup round trip keeps history and restore cannot roll back independent text`() {
        val first = publish(0); val second = publish(1)
        val thread = continuity.create(first.id, 1, "合成经验", setOf("学习"), "合成初始说明", "thread")
        val old = capture()
        val bytes = ByteArrayOutputStream().also { SnapshotJsonCodec.write(old, it) }.toByteArray()
        assertEquals(old, SnapshotJsonCodec.read(bytes.inputStream()))
        val encrypted = File(directory, "synthetic.tlb")
        BackupArchive.write(old, File(directory, "attachment-source"), encrypted, "synthetic-test-passphrase".toCharArray())
        val restored = BackupArchive.read(encrypted, File(directory, "unpacked"), "synthetic-test-passphrase".toCharArray())
        assertEquals(old, restored)
        continuity.append(thread.id, 1, second.id, 1, ExperienceRelation.NARROW, "合成较新说明不能回滚")
        repository.restoreSnapshot(restored, File(directory, "unpacked"), repository.analysisSourceStamp().generation,
            replaceExisting = true, expectedAnalysisGeneration = repository.currentAnalysisGeneration())
        assertEquals(2, continuity.get(thread.id)!!.thread.version)
        assertEquals("合成较新说明不能回滚", continuity.get(thread.id)!!.current.change.userNote)
        assertNotEquals(KnowledgeEvidenceState.CURRENT, continuity.get(thread.id)!!.current.knowledge?.evidenceState)
        continuity.delete(thread.id, 2)
        repository.restoreSnapshot(restored, File(directory, "unpacked"), repository.analysisSourceStamp().generation,
            replaceExisting = true, expectedAnalysisGeneration = repository.currentAnalysisGeneration())
        assertNull(continuity.get(thread.id))
        assertEquals(thread.id, capture().deletedExperienceThreads.single().id)
    }

    @Test fun `malformed backup histories and duplicate JSON fields fail validation`() {
        val first = publish(0)
        continuity.create(first.id, 1, "合成经验", emptySet(), id = "thread")
        val snapshot = capture()
        val thread = snapshot.experienceThreads.single()
        val event = snapshot.experienceChanges.single()
        listOf(snapshot.copy(experienceChanges = emptyList()),
            snapshot.copy(experienceThreads = listOf(thread.copy(version = 2))),
            snapshot.copy(experienceChanges = listOf(event.copy(sequence = 2, relation = ExperienceRelation.SUPPLEMENT))),
            snapshot.copy(deletedExperienceThreads = listOf(Deletion(thread.id, 1, clock.instant()))),
        ).forEach { assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(it) } }
        val encoded = ExperienceCodec.encodeThread(thread)
        assertThrows(IllegalArgumentException::class.java) { ExperienceCodec.decodeThread(encoded.replaceFirst("{", "{\"id\":\"duplicate\",")) }
    }

    @Test fun `historical revisions cannot be newly attached and user explanations are required for changes of meaning`() {
        val saved = publish(0)
        val library = KnowledgeLibrary(repository, clock)
        val updated = library.publish(saved.runId, saved.claimRef, "review", KnowledgeCategory.KNOWLEDGE_NOTE,
            repository.analysisRun(saved.runId)!!.version, 1, setOf("工作"))
        assertThrows(IllegalArgumentException::class.java) { continuity.create(saved.id, 1, "旧修订", emptySet()) }
        val thread = continuity.create(updated.id, updated.revision, "合成经验", emptySet())
        val next = publish(1)
        listOf(ExperienceRelation.EXCEPTION, ExperienceRelation.NARROW, ExperienceRelation.REPLACE).forEach { relation ->
            assertThrows(IllegalArgumentException::class.java) { continuity.append(thread.id, 1, next.id, 1, relation) }
        }
        assertEquals(1, continuity.get(thread.id)!!.thread.version)
    }

    private fun capture() = repository.captureSnapshot(File(directory, "attachment-source").apply { mkdirs() })

    private fun publish(index: Int, wording: String = "合成第 $index 周的经验"): StoredKnowledgeRevision {
        val analysis = ControlledAnalysis(repository, clock)
        val run = "run-$index"
        val day = date.plusWeeks(index.toLong())
        analysis.start(AnalysisRequest("整理合成经验", AnalysisQuery(day, day, "Asia/Shanghai")), run)
        analysis.runBatch(run, "extract", listOf(EvidenceSelection("source-$index", "body")), object : AnalysisProvider {
            override val id = "synthetic-extractor"
            override val contextWindowTokens = 100_000
            override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
            override fun complete(request: ModelRequest): BatchProposal {
                val evidence = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return BatchProposal(listOf(ProposedClaim(wording, listOf(evidence), ClaimKind.REPORTED_EXPERIENCE)))
            }
        })
        val claim = analysis.claimRef(run, "extract", 0)
        analysis.reviewClaim(run, "review", claim, provider = object : SemanticReviewProvider {
            override val id = "synthetic-reviewer"
            override val contextWindowTokens = 100_000
            override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, request.payloadJson.toByteArray().size)
            override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                val evidence = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(evidence)))
            }
        })
        return KnowledgeLibrary(repository, clock).publish(run, claim, "review", KnowledgeCategory.KNOWLEDGE_NOTE,
            repository.analysisRun(run)!!.version, topics = setOf("学习"))
    }
}
