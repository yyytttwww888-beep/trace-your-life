package dev.tracelife.providers

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.*
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
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

/** Opt-in synthetic maintenance only. Fixture generation is fabricated; real dispatch is capped at three. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekMaintenanceLiveTest {
    @Test fun `real relationship scan and review narrow a synthetic profile while preserving user writing`() {
        assumeTrue("Live maintenance is explicitly opt-in", System.getProperty("traceLife.deepSeekMaintenance") == "true")
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val directory = File(context.noBackupFilesDir, "synthetic-maintenance-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(directory, context.packageName) { wrappingKey }
        val database = "synthetic-live-maintenance-${UUID.randomUUID()}.db"
        try {
            credentials.configure(key); key.fill('\u0000')
            LifeRepository(context, databaseName = database).use { repository ->
                val date = LocalDate.parse("2026-10-12")
                val scope = AnalysisQuery(date, date.plusDays(2), "Asia/Shanghai")
                val target = ProfileAnalysisTarget(ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.CONTEXTUAL_PATTERN)
                val analysis = ControlledAnalysis(repository)
                val feedback = AnalysisFeedback(repository)
                val profiles = PersonalProfile(repository)
                repository.createRecord(RecordDraft("合成测试资料，不是真实个人经历：10月12日做简单代码练习，时间充足；先列一个小目标，再练习，当天完成了。", Occurrence(date)), "synthetic-0")
                analysis.start(AnalysisRequest("合成旧候选", scope, profileTarget = target), "old")
                val fixture = FixtureProvider("在这些代码练习中，先列一个小目标后通常可以完成练习。")
                analysis.synthesize("old", "old-batch", listOf(EvidenceSelection("synthetic-0", "body")), fixture)
                val oldClaim = analysis.claimRef("old", "old-batch", 0)
                feedback.respond("old", oldClaim, analysis.task("old").stored.version, ClaimFeedbackDecision.CONFIRMED)
                analysis.reviewClaim("old", "old-review", oldClaim, provider = fixture)
                val oldKnowledge = KnowledgeLibrary(repository).publish("old", oldClaim, "old-review", KnowledgeCategory.INSIGHT,
                    analysis.task("old").stored.version)
                val oldProfile = profiles.create(ProfileDraft(ProfileKnowledgeRef(oldKnowledge.id, oldKnowledge.revision), target.dimension, target.layer),
                    id = "synthetic-existing-profile", userText = "合成独立用户文字：请保留我对任务难度的说明。")
                profiles.adopt(oldProfile.value.id, 1, "synthetic-adopt")
                repository.createRecord(RecordDraft("合成测试资料：10月13日做简单代码练习，时间充足；先列小目标后完成，无法判断是否因为任务简单。", Occurrence(date.plusDays(1))), "synthetic-1")
                repository.createRecord(RecordDraft("合成测试资料：10月14日做困难代码练习，可用时间不足；同样先列小目标，仍未完成。这与前两次不同，不能认为方法总有效。", Occurrence(date.plusDays(2))), "synthetic-2")
                analysis.start(AnalysisRequest("合成画像维护：检查新描述是否收窄旧判断，保留困难任务的例外和替代解释。", scope,
                    AnalysisLimits(inputTokenLimit = 30_000), profileTarget = target), "new")
                fixture.text = "2026年10月12至14日的三条代码练习记录中，仅两次时间充足、任务简单的情境里，列出小目标后完成了练习；困难且时间不足的一次未完成。这里仅描述这些记录，无法判断列小目标本身的效果。"
                analysis.synthesize("new", "new-batch", (0..2).map { EvidenceSelection("synthetic-$it", "body") }, fixture)
                val claim = analysis.claimRef("new", "new-batch", 0)
                feedback.respond("new", claim, analysis.task("new").stored.version, ClaimFeedbackDecision.CONFIRMED)
                val maintenance = ProfileMaintenance(repository, analysis)
                val plan = maintenance.begin("new", claim)
                assertEquals(1, plan.totalReferences)
                val real = DeepSeekProvider(credentials)
                var dispatched = 0
                val live = object : SemanticProfileRelationProvider, SemanticReductionScanProvider, SemanticReviewProvider {
                    override val id = real.id
                    override val contextWindowTokens = real.contextWindowTokens
                    override fun prepareRelation(request: ModelRequest) = real.prepareRelation(request)
                    override fun prepareScan(request: ModelRequest) = real.prepareScan(request)
                    override fun prepareReview(request: ModelRequest) = real.prepareReview(request)
                    private fun count() { check(++dispatched <= 3) { "Live maintenance request cap exceeded" } }
                    override fun relatePrepared(request: PreparedModelRequest): ProfileRelationCompletion {
                        count(); return real.relatePrepared(request)
                    }
                    override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
                        count()
                        assertEquals(3, JSONObject(request.original.payloadJson).getJSONArray("evidence").length())
                        return real.scanPrepared(request)
                    }
                    override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
                        count()
                        assertEquals(3, JSONObject(request.original.payloadJson).getJSONArray("evidence").length())
                        return real.reviewPrepared(request).also {
                            println("synthetic-maintenance reviewVerdict=${it.review.verdict} supporting=${it.review.supportingEvidenceIds.size} contradicting=${it.review.contradictingEvidenceIds.size}")
                        }
                    }
                }
                val report = maintenance.process("new", claim, live, live, live, maxModelRequests = 3, fragmentsPerBatch = 3)
                analysis.task("new").state.calls.filter { it.purpose != CallPurpose.SYNTHESIS }.forEach { call ->
                    println("synthetic-maintenance purpose=${call.purpose} status=${call.status} failure=${call.failureCode} relation=${call.profileRelation?.relation} scan=${call.profileScan?.findings?.map { it.classification }} promptTokens=${call.usage?.promptTokens} completionTokens=${call.usage?.completionTokens}")
                }
                assertEquals(3, dispatched)
                assertEquals(ProfileMaintenanceStatus.READY_TO_APPLY, report.status)
                assertTrue(report.progress.allReferencesCompared)
                assertTrue(report.progress.allAvailableTextScanned)
                assertEquals(3, report.progress.finalRereadFragments)
                val relation = requireNotNull(maintenance.relation(report.progress, report.progress.references.single().reference))
                assertTrue(relation.relation in setOf(ProfileRelation.SUPPLEMENT, ProfileRelation.CONFLICT))
                assertEquals(2, repository.profileRevision("synthetic-existing-profile")!!.revision)
                val saved = maintenance.apply("new", claim, analysis.task("new").stored.version, requireNotNull(report.progress.planId),
                    if (relation.relation == ProfileRelation.SUPPLEMENT) ProfileMaintenanceAction.NARROW else ProfileMaintenanceAction.REPLACE,
                    "synthetic-existing-profile", 2, "synthetic-maintain")
                assertEquals("synthetic-existing-profile", saved.profile!!.id)
                assertEquals(ProfileAdoption.CANDIDATE, saved.adoption)
                assertEquals(oldProfile.value.userText, repository.profileRevision(saved.profile.id)!!.userText)
                assertEquals(0, profiles.open().total)
                assertEquals(3, repository.profileHistory(saved.profile.id).size)
                val calls = analysis.task("new").state.calls.filter { it.usage != null }
                assertEquals(3, calls.size)
                calls.forEach { call ->
                    val usage = requireNotNull(call.usage)
                    assertTrue(usage.promptTokens <= call.inputTokens)
                    println("synthetic-maintenance purpose=${call.purpose} promptTokens=${usage.promptTokens} completionTokens=${usage.completionTokens}")
                }
                println("synthetic-maintenance requests=$dispatched relation=${relation.relation} suggested=${relation.recommendation} reread=${report.progress.finalRereadFragments} adoption=${saved.adoption}")
            }
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { directory.deleteRecursively(); context.deleteDatabase(database) }
        }
    }

    private class FixtureProvider(var text: String) : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-maintenance-fixture-v1"
        override val contextWindowTokens = 40_000
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val evidence = JSONObject(request.payloadJson).getJSONArray("evidence")
            return BatchProposal(listOf(ProposedClaim(text, listOf(evidence.getJSONObject(0).getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val evidence = JSONObject(request.original.payloadJson).getJSONArray("evidence")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, supportingEvidenceIds = listOf(evidence.getJSONObject(0).getString("id"))))
        }
    }
}
