package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.storage.AnalysisQuery
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

/** Five authored synthetic challenges; only the reviewer is real. Never auto-retries or runs in CI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekReviewLiveTest {
    @Test fun `real reviewer handles bounded synthetic support counterevidence causality and uncertain dates`() {
        assumeTrue("Live review evaluation is explicitly opt-in", System.getProperty("traceLife.deepSeekReview") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514) {
            "Configure the external DeepSeek API key file first"
        }
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val keyDirectory = File(context.noBackupFilesDir, "synthetic-review-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(keyDirectory, context.packageName) { wrappingKey }
        try {
            credentials.configure(key)
            key.fill('\u0000')
            val provider = DeepSeekProvider(credentials)
            cases().forEach { sample -> evaluate(context, provider, sample) }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { keyDirectory.deleteRecursively() }
        }
    }

    private fun evaluate(context: Context, provider: DeepSeekProvider, sample: Sample) {
        val databaseName = "synthetic-review-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, databaseName = databaseName).use { repository ->
                val from = LocalDate.parse("2026-10-12")
                sample.bodies.forEachIndexed { index, body ->
                    repository.createRecord(RecordDraft(body,
                        Occurrence(if (sample.unknownDate) null else from.plusDays(index.toLong()))), "note-$index")
                }
                val analysis = ControlledAnalysis(repository)
                val task = analysis.start(AnalysisRequest("核对合成案例的陈述，区分自述、事实和推断，不生成长期人格。",
                    AnalysisQuery(from, from.plusDays(6), "Asia/Shanghai"),
                    AnalysisLimits(fragmentCodePoints = sample.fragmentSize)))
                // Deliberately authored claims include errors; this evaluates the checker, not extraction quality.
                analysis.runBatch(task.stored.id, "authored-proposal", listOf(EvidenceSelection("note-0", "body")),
                    object : AnalysisProvider {
                        override val id = "synthetic-authored-claim"
                        override val contextWindowTokens = 100_000
                        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
                        override fun complete(request: ModelRequest): BatchProposal {
                            val evidenceId = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
                            return BatchProposal(listOf(ProposedClaim(sample.claim, listOf(evidenceId), sample.kind)))
                        }
                    })
                val claim = analysis.claimRef(task.stored.id, "authored-proposal", 0)
                val contextSelections = sample.bodies.indices.drop(1).map { EvidenceSelection("note-$it", "body") } +
                    listOfNotNull(sample.tailStart?.let { EvidenceSelection("note-0", "body", it) })
                val result = analysis.reviewClaim(task.stored.id, "real-review", claim, contextSelections, provider)
                val call = requireNotNull(analysis.currentReview(task.stored.id, claim))
                val usage = requireNotNull(call.usage)
                // Only fixed case labels, enums and numeric usage are logged, never text or credentials.
                println("synthetic-review case=${sample.id} verdict=${result.verdict} issues=${result.issues.joinToString(",")} " +
                    "promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                assertTrue("Unexpected verdict for synthetic case ${sample.id}", result.verdict in sample.allowed)
                assertTrue("Missing diagnostic for synthetic case ${sample.id}", sample.expectedIssues.isEmpty() ||
                    result.issues.any { it in sample.expectedIssues })
                assertEquals(CallPurpose.REVIEW, call.purpose)
                assertEquals(AnalysisStatus.NEEDS_REVIEW, analysis.task(task.stored.id).status)
                assertTrue((result.supportingEvidenceIds + result.contradictingEvidenceIds).all { id -> call.evidence.any { it.id == id } })
                if (result.verdict == ReviewVerdict.SUPPORTED) assertTrue(result.supportingEvidenceIds.isNotEmpty())
                if (sample.tailStart != null) assertTrue(result.contradictingEvidenceIds.any { id ->
                    call.evidence.any { it.id == id && it.start == sample.tailStart }
                })
            }
        } finally { context.deleteDatabase(databaseName) }
    }

    private data class Sample(
        val id: String, val bodies: List<String>, val claim: String,
        val kind: ClaimKind = ClaimKind.REPORTED_EXPERIENCE,
        val allowed: Set<ReviewVerdict>, val expectedIssues: Set<ReviewIssue> = emptySet(),
        val fragmentSize: Int = 1_024, val tailStart: Int? = null, val unknownDate: Boolean = false,
    )

    private fun cases(): List<Sample> {
        val prefix = "合成测试资料，不是真实个人记录：今天原计划做两次25分钟学习。"
        val padding = "这是无关的合成占位资料。".repeat(30)
        val tail = "复盘更正：实际只完成一次25分钟学习，第二次取消。原计划不代表实际完成。"
        return listOf(
            Sample("reported-support", listOf("合成测试资料，不是真实个人记录：我今天实际做了一次25分钟学习练习。"),
                "用户记录今天实际做了一次25分钟学习练习。", allowed = setOf(ReviewVerdict.SUPPORTED)),
            Sample("report-as-measurement", listOf("合成测试资料，不是真实个人记录：我今天自觉睡得不错，没有设备测量或客观睡眠数据。"),
                "设备测量证明用户睡眠质量已经改善。",
                allowed = setOf(ReviewVerdict.UNSUPPORTED, ReviewVerdict.CONTRADICTED, ReviewVerdict.INSUFFICIENT_CONTEXT),
                expectedIssues = setOf(ReviewIssue.USER_REPORT_AS_FACT)),
            Sample("tail-counterevidence", listOf(prefix + padding + tail), "用户当天实际完成了两次25分钟学习。",
                allowed = setOf(ReviewVerdict.CONTRADICTED), fragmentSize = 96,
                tailStart = (prefix + padding).codePointCount(0, (prefix + padding).length)),
            Sample("causal-overreach", listOf(
                "合成测试资料，不是真实个人记录：周一喝了咖啡，晚上自觉睡得不错。没有测量，也不知道原因。",
                "合成测试资料，不是真实个人记录：周二没有喝咖啡，晚上同样自觉睡得不错。其余五晚没有记录。"),
                "喝咖啡导致用户本周睡眠质量改善。", ClaimKind.POSSIBLE_INTERPRETATION,
                setOf(ReviewVerdict.UNSUPPORTED, ReviewVerdict.INSUFFICIENT_CONTEXT),
                setOf(ReviewIssue.CAUSAL_OVERREACH)),
            Sample("unknown-date-and-scope", listOf("合成测试资料，不是真实个人记录：记不清哪一天，曾散步10分钟，其他日子未记录。"),
                "用户本周每天都锻炼了一小时。",
                allowed = setOf(ReviewVerdict.UNSUPPORTED, ReviewVerdict.INSUFFICIENT_CONTEXT),
                expectedIssues = setOf(ReviewIssue.SCOPE_OVERGENERALIZATION, ReviewIssue.INSUFFICIENT_CONTEXT), unknownDate = true),
        )
    }
}
