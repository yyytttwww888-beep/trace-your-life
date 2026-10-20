package dev.tracelife.quality

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.conversation.ConversationWorkflow
import dev.tracelife.providers.*
import dev.tracelife.storage.LifeRepository
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.knowledge.KnowledgeLibrary
import org.json.JSONArray
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
import java.util.UUID
import javax.crypto.KeyGenerator

/** Explicit opt-in: unchanged six fictional cases, generation + every claim's review/publication.
 * At most 54 paid calls total (9/case), no failed-call retry. Confirmation is a synthetic fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekKnowledgeQualityLiveTest {
    private val qualityModel = DeepSeekModel.valueOf(System.getProperty("traceLife.qualityModel", "FLASH"))

    @Test fun `fixed quality cases preserve complete evidence and produce a separately reviewable semantic report`() {
        assumeTrue("Live quality checks require explicit opt-in", System.getProperty("traceLife.deepSeek.quality") == "true")
        val root = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile")
            ?: File(System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        check(!keyFile.toPath().startsWith(root.toPath()) && keyFile.isFile && keyFile.length() in 8..514)
        val bytes = Files.readAllBytes(keyFile.toPath())
        val key = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        val context: Context = RuntimeEnvironment.getApplication()
        val keyDirectory = File(context.noBackupFilesDir, "synthetic-quality-key-${UUID.randomUUID()}")
        val wrappingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(keyDirectory, context.packageName) { wrappingKey }
        val report = SyntheticQualityReport("deepseek-fixed-cases")
        val failures = mutableListOf<String>()
        val actualTransport = HttpsDeepSeekTransport()
        var totalPaidRequests = 0
        try {
            credentials.configure(key); key.fill('\u0000')
            SyntheticQualityCases.all.forEach { case ->
                val database = "synthetic-live-quality-${case.id}-${UUID.randomUUID()}.db"
                val started = System.nanoTime()
                var casePaidRequests = 0
                var runId: String? = null
                val row = fixtureRow(case)
                try {
                    LifeRepository(context, clock = SyntheticQualityCases.clock, databaseName = database).use { repository ->
                        val analysis = ControlledAnalysis(repository, SyntheticQualityCases.clock)
                        SyntheticQualityCases.seed(repository, analysis, case)
                        val reflection = ReflectionWorkflow(repository, analysis)
                        val workflow = ConversationWorkflow(repository, analysis, reflection, SyntheticQualityCases.clock)
                        val transport = DeepSeekTransport { body, secret ->
                            check(casePaidRequests < 9 && totalPaidRequests < 54) { "Fixed synthetic benchmark request ceiling reached" }
                            casePaidRequests++; totalPaidRequests++
                            // The wire body contains synthetic inputs only; the secret/header is never recorded.
                            File(report.directory, "${case.id}-request-$casePaidRequests.json").writeText(body)
                            actualTransport.execute(body, secret).also { response ->
                                if (response.status == 200) File(report.directory, "${case.id}-response-$casePaidRequests.json").writeBytes(response.body)
                            }
                        }
                        val provider = DeepSeekProvider(credentials, qualityModel, transport)
                        val turn = workflow.submit(workflow.prepare(case.question, scopeOverride = case.scope))
                        runId = turn.runId
                        val limits = analysis.task(turn.runId).state.request.limits
                        row.put("configuredLimits", JSONObject().put("inputTokenLimit", limits.inputTokenLimit)
                            .put("outputTokenReserve", limits.outputTokenReserve)
                            .put("maxInputBytes", limits.maxInputBytes).put("totalInputBytes", limits.totalInputBytes)
                            .put("maxCalls", limits.maxCalls).put("fragmentCodePoints", limits.fragmentCodePoints)
                            .put("paidRequestsPerCase", 9).put("paidRequestsSuite", 54))
                        try {
                            val result = workflow.process(turn, provider, maxRequests = 3)
                            val state = analysis.task(turn.runId).state
                            val coverage = requireNotNull(result.run.coverage)
                            row.put("reflectionStatus", result.status.name)
                                .put("findings", JSONArray(result.run.findings.map { finding ->
                                    JSONObject().put("text", finding.text).put("evidenceContextRecordIds", JSONArray(
                                        allEvidence(reflection, turn.runId, finding.target).map { it.recordId }.distinct()))
                                        .put("savedKnowledgeId", finding.knowledgeId ?: JSONObject.NULL)
                                })).put("unknowns", JSONArray(result.run.unknowns)).put("published", repository.knowledgeHeads().isNotEmpty())
                                .put("coverage", JSONObject().put("records", coverage.records)
                                    .put("codePoints", coverage.textCodePoints).put("completedCodePoints", coverage.completedTextCodePoints)
                                    .put("remainingFragments", coverage.remainingTextFragments))
                            assertEquals("${case.id}: generation should complete within this fixed case budget", ReflectionStatus.READY, result.status)
                            assertEquals(case.sources.size, coverage.records)
                            assertEquals(coverage.textCodePoints, coverage.completedTextCodePoints)
                            assertEquals(0L, coverage.remainingTextFragments)
                            assertTrue("An empty answer remains a semantic/utility failure for this informative fixture", result.run.findings.isNotEmpty())
                            assertEquals(case.sources.map { it.id }.toSet(), state.calls.flatMap { it.evidence }.map { it.recordId }.toSet())
                            assertTrue(state.calls.all { it.status == CallStatus.SUCCEEDED && it.inputTokens <= state.request.limits.inputTokenLimit &&
                                it.inputBytes <= state.request.limits.maxInputBytes })
                            assertTrue(result.run.findings.all { allEvidence(reflection, turn.runId, it.target).isNotEmpty() })
                            assertTrue("Generation is not user adoption", repository.knowledgeHeads().isEmpty())
                            if (case.prior != null) assertEquals(1, state.calls.flatMap { it.historicalFeedbackReadIds }.distinct().size)
                            assertTrue("The conversation contract asks for at most six findings", result.run.findings.size <= 6)
                            val reviews = JSONArray()
                            row.put("finalOriginalReviews", reviews)
                                .put("syntheticUserConfirmation", true).put("syntheticHistoryResolution", "RESPECTED_FOR_GATE_EXERCISE_NOT_SEMANTIC_PROOF")
                                .put("unknownsReview", "REQUIRES_INDEPENDENT_REVIEW_NOT_A_PUBLICATION_TARGET")
                            result.run.findings.forEachIndexed { index, finding ->
                                val target = finding.target as? ReflectionTarget.Claim
                                    ?: error("Fixed small fixtures unexpectedly required reduction; expand the explicit review budget before rerunning")
                                // Exercise the model and publication fence even if a human made an
                                // incorrect confirmation. It is not a human assessment of this answer.
                                reflection.historicalFeedbackPage(turn.runId, target, limit = 100).items.forEach { old ->
                                    reflection.resolveHistoricalFeedback(turn.runId, target, old.content.reference.id,
                                        analysis.task(turn.runId).stored.version, HistoricalFeedbackChoice.RESPECTED,
                                        "合成门禁验收：模拟人工完成对照；这不是对陈述正确性的证明。")
                                }
                                reflection.respond(turn.runId, target, analysis.task(turn.runId).stored.version, ClaimFeedbackDecision.CONFIRMED)
                                val reviewKey = "quality-final-review-$index"
                                val checked = analysis.reviewClaim(turn.runId, reviewKey, target.ref, provider = provider)
                                val publication = runCatching { KnowledgeLibrary(repository, SyntheticQualityCases.clock).publish(
                                    turn.runId, target.ref, reviewKey, KnowledgeCategory.INSIGHT,
                                    analysis.task(turn.runId).stored.version, topics = setOf("学习")) }
                                reviews.put(JSONObject().put("text", finding.text).put("verdict", checked.verdict.name)
                                    .put("issues", JSONArray(checked.issues.map { it.name }))
                                    .put("unknowns", JSONArray(checked.unknowns))
                                    .put("supportingEvidenceIds", JSONArray(checked.supportingEvidenceIds))
                                    .put("contradictingEvidenceIds", JSONArray(checked.contradictingEvidenceIds))
                                    .put("publicationAttempted", true).put("published", publication.isSuccess)
                                    .put("publicationFailureType", publication.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                                    .put("gateResult", if (checked.verdict == ReviewVerdict.SUPPORTED) "SUPPORTED_REQUIRES_SEMANTIC_REVIEW" else "NON_SUPPORTED_BLOCK_EXPECTED"))
                                assertEquals("A non-supported finding must not pass publication", checked.verdict == ReviewVerdict.SUPPORTED, publication.isSuccess)
                            }
                            row.put("published", repository.knowledgeHeads().isNotEmpty()).put("finalOriginalReview", "EVERY_GENERATED_CLAIM_REVIEWED_AND_PUBLICATION_ATTEMPTED")
                            row.put("programmaticStatus", "PASS").put("semanticStatus", "REQUIRES_INDEPENDENT_REVIEW")
                        } finally {
                            row.put("calls", JSONArray(analysis.task(turn.runId).state.calls.map(::callRow)))
                        }
                    }
                } catch (failure: Throwable) {
                    // Keep failed attempts in the denominator; do not silently rerun until the model agrees.
                    failures += case.id
                    row.put("programmaticStatus", "FAIL").put("failureType", failure.javaClass.simpleName)
                        .put("semanticStatus", "NOT_AUTOMATICALLY_SCORED")
                } finally {
                    row.put("runId", runId ?: JSONObject.NULL).put("paidRequests", casePaidRequests)
                        .put("totalPaidRequestsSoFar", totalPaidRequests).put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
                    report.add(row)
                    context.deleteDatabase(database)
                }
            }
            assertEquals("Programmatic failures are retained in ${report.directory.path}; semantic assessment is separate", emptyList<String>(), failures)
            assertTrue(totalPaidRequests in SyntheticQualityCases.all.size..54)
        } finally {
            key.fill('\u0000')
            try { credentials.clear() } finally { keyDirectory.deleteRecursively() }
        }
    }

    private fun fixtureRow(case: SyntheticQualityCases.Case) = JSONObject()
        .put("id", case.id).put("model", qualityModel.apiId).put("adapter", "deepseek:${qualityModel.apiId}:json-v1:utf8-bound-v1:review-clauses-v4")
        .put("question", case.question).put("from", case.from).put("through", case.through).put("zoneId", "Asia/Shanghai")
        .put("requiredDistinctions", JSONArray(case.requiredDistinctions)).put("forbiddenConclusions", JSONArray(case.forbiddenConclusions))
        .put("expectedNewValue", case.expectedNewValue).put("sources", JSONArray(case.sources.map { source ->
            JSONObject().put("id", source.id).put("date", source.date).put("body", source.body).put("classified", source.classified)
        })).put("priorFeedback", case.prior?.let { prior -> JSONObject()
            .put("sourceId", prior.source.id).put("sourceDate", prior.source.date).put("sourceBody", prior.source.body)
            .put("oldClaim", prior.oldClaim).put("correction", prior.correction) } ?: JSONObject.NULL)
        .put("configuredLimits", JSONObject().put("inputTokenLimit", 32_000).put("maxInputBytes", 65_536)
            .put("totalInputBytes", 1_048_576).put("paidRequestsPerCase", 9).put("paidRequestsSuite", 54))
        .put("published", false).put("finalOriginalReview", "NOT_YET_RUN")

    private fun callRow(call: AnalysisCall) = JSONObject()
        .put("purpose", call.purpose.name).put("status", call.status.name).put("failureCode", call.failureCode ?: JSONObject.NULL)
        .put("inputBytes", call.inputBytes).put("budgetInputTokens", call.inputTokens).put("tokenCountKind", call.tokenCountKind.name)
        .put("promptTokens", call.usage?.promptTokens ?: JSONObject.NULL).put("completionTokens", call.usage?.completionTokens ?: JSONObject.NULL)
        .put("originalFragments", call.evidence.size).put("historyReadCount", call.historicalFeedbackReadIds.size)

    private fun allEvidence(reflection: ReflectionWorkflow, runId: String, target: ReflectionTarget): List<EvidenceReceipt> {
        val receipts = mutableListOf<EvidenceReceipt>()
        var offset: Int? = 0
        while (offset != null) {
            val page = reflection.evidence(runId, target, offset, 100)
            receipts += page.items; offset = page.nextOffset
        }
        return receipts
    }
}
