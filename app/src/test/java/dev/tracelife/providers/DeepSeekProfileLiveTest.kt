package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.LifeRepository
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID
import javax.crypto.KeyGenerator

/** Opt-in, synthetic-only: one real generation and one independent review; never retries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekProfileLiveTest {
    @Test fun `real contextual candidate remains unadopted after confirmed publication`() {
        assumeTrue("Live profile generation is explicitly opt-in", System.getProperty("traceLife.deepSeekProfile") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514) {
            "Configure the external DeepSeek API key file first"
        }
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-profile-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrappingKey }
        val database = "synthetic-live-profile-${UUID.randomUUID()}.db"
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val day = LocalDate.parse("2026-10-12")
                listOf(
                    "周一做代码练习时，我先把目标缩小成一个可运行的小例子，再查卡住的概念，完成了练习。当天任务简单，时间充足。",
                    "周二做代码练习时，我也先缩小目标，再查卡住的概念，完成了练习。不能确定完成是方法带来的，也可能因为练习容易。",
                    "周三做较难代码练习时，我同样先缩小目标，仍未完成。当天时间不足；这个反例说明记录不能证明方法总有效，更不能推出长期能力或人格。",
                ).forEachIndexed { index, body ->
                    repository.createRecord(RecordDraft("合成测试资料，不是真实个人经历：$body", Occurrence(day.plusDays(index.toLong()))), "synthetic-$index")
                }
                val analysis = ControlledAnalysis(repository)
                val generator = ProfileCandidateGenerator(repository, analysis)
                val target = ProfileAnalysisTarget(ProfileDimension.BEHAVIOR_AND_DECISIONS, ProfileLayer.CONTEXTUAL_PATTERN)
                generator.start(AnalysisRequest("根据三条合成记录，只提出一条代码练习情境下的待核对画像解释，" +
                    "对照重复做法与未完成的反例，保留难度和可用时间的替代解释。不要声称有效或形成长期特征。" +
                    "只输出一条 claims，unknowns 至少保留一项现有资料不能回答的问题。",
                    AnalysisQuery(day, day.plusDays(2), "Asia/Shanghai"), AnalysisLimits(inputTokenLimit = 30_000),
                    profileTarget = target), "synthetic-profile-run")
                val real = DeepSeekProvider(credentials)
                var generated = 0
                var reviewed = 0
                val producer = object : AnalysisProvider by real {
                    override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                        check(++generated <= 1) { "Live profile generation request cap exceeded" }
                        val envelope = JSONObject(request.original.payloadJson)
                        assertEquals("CONTEXTUAL_PATTERN", envelope.getJSONObject("profileTarget").getString("layer"))
                        assertEquals(3, envelope.getJSONArray("evidence").length())
                        return real.completePrepared(request)
                    }
                }
                val reviewer = object : SemanticReviewProvider by real {
                    override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                        check(++reviewed <= 1) { "Live profile review request cap exceeded" }
                        assertEquals(3, JSONObject(request.original.payloadJson).getJSONArray("evidence").length())
                        return real.reviewPrepared(request)
                    }
                }
                val first = generator.process("synthetic-profile-run", producer, reviewer, maxModelRequests = 1, fragmentsPerBatch = 3)
                assertEquals(1, first.modelRequestsThisPass)
                val generation = analysis.task("synthetic-profile-run").state.calls.single()
                check(generation.status == CallStatus.SUCCEEDED) { "Synthetic profile generation failed: ${generation.failureCode}" }
                val proposal = requireNotNull(generation.proposal)
                assertEquals(1, proposal.claims.size)
                assertEquals(ClaimKind.POSSIBLE_INTERPRETATION, proposal.claims.single().kind)
                assertTrue("Expected the generated candidate to retain a concrete context",
                    listOf("练习", "任务", "代码", "学习", "情境").any { proposal.claims.single().text.contains(it) })
                assertTrue("Expected explicit uncertainty for these limited synthetic notes", proposal.unknowns.isNotEmpty())
                assertEquals(setOf("synthetic-0", "synthetic-1", "synthetic-2"), generation.evidence.map { it.recordId }.toSet())
                assertTrue(repository.profileHeads().isEmpty())
                assertTrue(repository.knowledgeHeads().isEmpty())

                val claim = analysis.claimRef("synthetic-profile-run", generation.batchKey, 0)
                AnalysisFeedback(repository).respond("synthetic-profile-run", claim, analysis.task("synthetic-profile-run").stored.version,
                    ClaimFeedbackDecision.CONFIRMED, "测试程序模拟用户对这条有情境限制的合成解释作出明确确认。")
                val second = generator.process("synthetic-profile-run", producer, reviewer, maxModelRequests = 1, fragmentsPerBatch = 3)
                assertEquals(1, second.modelRequestsThisPass)
                val review = requireNotNull(analysis.currentReview("synthetic-profile-run", claim))
                assertEquals(ReviewVerdict.SUPPORTED, requireNotNull(review.review).verdict)
                assertEquals(generation.evidence.toSet(), review.evidence.toSet())
                assertTrue(review.feedbackVersion > 0)
                assertEquals(1, generated); assertEquals(1, reviewed)
                assertTrue(repository.profileHeads().isEmpty())

                val result = generator.materialize("synthetic-profile-run", claim, analysis.task("synthetic-profile-run").stored.version)
                assertEquals(ProfileCandidateMaterializationStatus.CREATED, result.status)
                assertEquals(ProfileAdoption.CANDIDATE, result.adoption)
                val head = requireNotNull(result.profile)
                val profiles = PersonalProfile(repository)
                val candidate = requireNotNull(profiles.get(head.id))
                assertEquals(requireNotNull(result.knowledge), candidate.value.draft.knowledge)
                assertEquals(target.dimension, candidate.value.draft.dimension)
                assertEquals(target.layer, candidate.value.draft.layer)
                assertEquals(0, profiles.open().total)
                assertNull(candidate.value.userText)
                profiles.adopt(head.id, head.revision, "synthetic-explicit-adoption")
                assertEquals(1, profiles.open().total)
                listOf(generation, review).forEach { call ->
                    val usage = requireNotNull(call.usage)
                    assertTrue(usage.promptTokens <= call.inputTokens)
                    println("synthetic-profile purpose=${call.purpose} promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                }
                println("synthetic-profile requests=${generated + reviewed} originals=${review.evidence.size} materialization=${result.status} review=${review.review!!.verdict}")
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
}
