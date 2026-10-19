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

/** Two paid reductions at most. Leaf proposals are synthetic fixtures, never personal information. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekReductionLiveTest {
    @Test fun `real two-level reduction retains derived references and remains an unpublished draft`() {
        assumeTrue("Live reduction is explicitly opt-in", System.getProperty("traceLife.deepSeekReduction") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514) {
            "Configure the external DeepSeek API key file first"
        }
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-reduction-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrappingKey }
        val database = "synthetic-live-reduction-${UUID.randomUUID()}.db"
        try {
            credentials.configure(key)
            key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val from = LocalDate.parse("2026-10-12")
                val descriptions = listOf(
                    "合成周一：计划做练习，实际查资料到结束，没有动手。",
                    "合成周二：先做最小练习，卡住后查资料，完成一个练习，但当天更简单。",
                    "合成周三：先动手仍未完成，当天可用时间较少，不能认定顺序就能保证完成。",
                    "合成周四：先做最小练习再查资料，完成练习，但可用时间充足。",
                    "合成周五：资料只记录阅读方法，没有记录是否应用，不能当作已经掌握。",
                )
                descriptions.forEachIndexed { index, text ->
                    repository.createRecord(RecordDraft("明确标注的合成测试资料，不是真实个人经历。$text", Occurrence(from.plusDays(index.toLong()))), "note-$index")
                }
                val analysis = ControlledAnalysis(repository)
                val task = analysis.start(AnalysisRequest(
                    "将本次提供的合成局部草稿归并为最多两条周期判断，比较练习与查资料的安排，同时保留难度、时间与未记录应用的局限。不得断言因果或用户已采纳行动。",
                    AnalysisQuery(from, from.plusDays(6), "Asia/Shanghai"),
                    limits = AnalysisLimits(inputTokenLimit = 30_000),
                ))
                val leafProvider = object : AnalysisProvider {
                    override val id = "synthetic-live-reduction-leaves"
                    override val contextWindowTokens = 100_000
                    override fun countInputTokens(request: ModelRequest) = 100
                    override fun complete(request: ModelRequest): BatchProposal {
                        val ref = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0)
                        val index = ref.getString("recordId").substringAfter("note-").toInt()
                        return BatchProposal(listOf(ProposedClaim(descriptions[index], listOf(ref.getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)),
                            listOf("合成局限：局部材料尚未原文核对，难度与可用时间可能不同。"))
                    }
                }
                repeat(descriptions.size) { index ->
                    analysis.synthesize(task.stored.id, "leaf-$index", listOf(EvidenceSelection("note-$index", "body")), leafProvider)
                }
                val real = DeepSeekProvider(credentials)
                var requests = 0
                val counted = object : AnalysisProvider by real {
                    override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
                        check(++requests <= 2) { "Live reduction request cap exceeded" }
                        val json = JSONObject(request.original.payloadJson)
                        assertEquals("REDUCTION", json.getString("purpose"))
                        assertFalse(json.has("evidence"))
                        assertTrue(json.getJSONArray("derivedInputs").length() in 2..4)
                        return real.completePrepared(request)
                    }
                }
                val result = PeriodReductionProcessor(analysis).process(task.stored.id, counted, maxRequests = 2, fanIn = 4)
                val reductions = analysis.task(task.stored.id).state.calls.filter { it.purpose == CallPurpose.REDUCTION }
                check(result.status == ReductionProcessingStatus.TREE_INTEGRATED) {
                    "Synthetic reduction did not finish: ${reductions.lastOrNull()?.failureCode ?: result.status.name}"
                }
                assertEquals(2, requests)
                val rootRef = requireNotNull(result.progress.root)
                val view = analysis.reductionNode(task.stored.id, rootRef.batchKey)
                assertEquals(5, view.leafRefs.size)
                assertEquals(5, view.originalEvidence.size)
                assertTrue(view.digest.statements.size in 1..2)
                assertTrue("Expected comparison of both direct branches", view.digest.statements.any { it.inputIds.size == 2 })
                assertTrue(view.digest.unknowns.isNotEmpty())
                reductions.forEach { call ->
                    assertEquals(CallStatus.SUCCEEDED, call.status)
                    assertTrue(call.evidence.isEmpty())
                    assertNull(call.proposal)
                    val usage = requireNotNull(call.usage)
                    println("synthetic-reduction promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                }
                println("synthetic-reduction requests=$requests leaves=${view.leafRefs.size} statements=${view.digest.statements.size} status=${result.status}")
                assertThrows(IllegalArgumentException::class.java) { analysis.claimRef(task.stored.id, rootRef.batchKey, 0) }
                assertTrue(repository.knowledgeHeads().isEmpty())
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }
}
