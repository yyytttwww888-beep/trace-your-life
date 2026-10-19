package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.actions.*
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
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
import java.io.File
import java.nio.file.Paths
import java.time.*
import java.util.UUID
import java.util.zip.ZipFile

/** Synthetic plans, reports and providers only; no personal material or network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ActionKnowledgeExportTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var actions: ActionExperiments
    private lateinit var analysis: ControlledAnalysis
    private lateinit var workspace: File
    private val clock = MutableClock()
    private val database = "synthetic-action-reading-${UUID.randomUUID()}.db"
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-12"), LocalDate.parse("2026-10-18"), "Asia/Shanghai")
    private val provider = Provider()
    private val outsideReview = "SYNTHETIC_OUTSIDE_REVIEW_MUST_NOT_LEAK"
    private val reviewText = "SYNTHETIC_PERIOD_REVIEW_UNKNOWN"
    private val independentText = "SYNTHETIC_INDEPENDENT_CONFIRMATION_MUST_NOT_LEAK"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        actions = ActionExperiments(repository, clock)
        analysis = ControlledAnalysis(repository, clock)
        workspace = File(context.cacheDir, "synthetic-action-reading-${UUID.randomUUID()}").apply { check(mkdir()) }
    }
    @After fun teardown() { repository.close(); context.deleteDatabase(database); workspace.deleteRecursively() }

    @Test fun `bounded weekly knowledge preserves opening baseline and late review with exact projection hashes`() {
        seed(); val note = publish()
        val entries = export(options = bounded())
        val manifest = JSONObject(entries.getValue("manifest.json"))
        assertEquals(1, manifest.getJSONObject("knowledge").getInt("included"))
        assertEquals(0, manifest.getJSONObject("actions").getInt("included"))
        val actionInfo = manifest.getJSONObject("actionEvidence")
        assertEquals(3, actionInfo.getInt("includedReceipts"))
        assertEquals(1, actionInfo.getInt("openingBaselineReceipts"))
        assertEquals(1, actionInfo.getInt("userReviewReceipts"))
        assertEquals("pre_period_plan_only_no_prior_review", actionInfo.getString("openingBaselineException"))
        assertTrue(manifest.getJSONObject("completeness").getBoolean("actionReviewsIncluded"))
        assertFalse(manifest.getJSONObject("completeness").getBoolean("completeActionHistoryIncluded"))
        assertEquals(1, manifest.getJSONObject("completeness").getInt("omittedHistoricalActionRevisions"))
        val bodies = actionBodies(entries)
        val opening = bodies.single { it.getString("kind") == "OPENING_PLAN" }
        assertEquals(2, opening.getInt("revision")); assertTrue(opening.isNull("review"))
        val changed = bodies.single { it.getString("kind") == "PLAN_CHANGE" }
        assertEquals(3, changed.getInt("revision")); assertTrue(changed.isNull("review"))
        val review = bodies.single { it.getString("kind") == "USER_REVIEW" }
        assertEquals("2026-10-18", review.getJSONObject("review").getString("reviewedOn"))
        assertEquals("2026-10-19T04:00:00Z", review.getString("savedAt"))
        assertEquals("SYNTHETIC_PERIOD_PLAN", review.getJSONObject("reviewedPlan").getString("behavior"))
        assertEquals("SYNTHETIC_NEXT_PLAN", review.getJSONObject("nextPlan").getString("behavior"))
        assertEquals("UNKNOWN", review.getJSONObject("review").getString("execution"))
        assertTrue(entries.values.none { outsideReview in it || independentText in it })
        assertEquals(1, entries.filterKeys { it.endsWith(".md") }.values.count { note.claim.text in it })
        assertEquals(1, entries.filterKeys { it.endsWith(".md") }.values.count { reviewText in it })
        val snapshot = capture()
        KnowledgeValidation.allActionEvidence(note).forEach { receipt ->
            val source = snapshot.actionRevisions.single { it.id == receipt.actionId && it.revision == receipt.revision }
            assertEquals(receipt, ActionEvidenceProtocol.receipt(source, receipt.kind))
            assertEquals(AnalysisRunCodec.json(ActionEvidenceProtocol.modelMap(source, receipt)),
                entries.getValue("data/action-evidence/entries/${receipt.id}.json"))
        }
        assertLinksAndInventory(entries)
    }

    @Test fun `topic and narrowed analysis period omit all action knowledge without broadening request`() {
        seed(); publish()
        val topical = export(options = bounded().copy(scope = ExportScope(topics = setOf("learning"))))
        assertEquals(1, reasons(topical).getInt("action_topics_unavailable"))
        assertTrue(topical.keys.none { it.startsWith("action-evidence/") || it.startsWith("notes/entries/") })
        val narrower = export(options = bounded().copy(scope = ExportScope(scope.from.plusDays(1), scope.through)))
        assertEquals(1, reasons(narrower).getInt("action_scope_not_contained"))
        assertTrue(narrower.values.none { reviewText in it || "SYNTHETIC_SHARED_CONCLUSION" in it })
        assertEquals(1, narrower.keys.count { it.startsWith("records/") })
    }

    @Test fun `evidence package remains original records only`() {
        seed(); publish()
        val entries = export(options = bounded().copy(kind = ReadingPackageKind.EVIDENCE, question = "合成：原文记录"))
        assertEquals(1, reasons(entries).getInt("evidence_package"))
        assertTrue(entries.keys.none { it.startsWith("action-evidence/") || it.startsWith("notes/") || it.startsWith("actions/") })
        assertTrue(entries.values.none { reviewText in it || outsideReview in it })
        assertEquals(0, JSONObject(entries.getValue("manifest.json")).getJSONObject("actionEvidence").getInt("includedReceipts"))
    }

    @Test fun `future current plan does not replace historical evidence or invalidate the past period`() {
        seed(); publish()
        clock.now = Instant.parse("2026-10-20T04:00:00Z")
        actions.update("action", 4, draft().copy(behavior = "SYNTHETIC_FUTURE_SECRET", reviewOn = scope.through.plusDays(20)))
        val entries = export(options = bounded())
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("knowledge").getInt("included"))
        assertTrue(entries.values.none { "SYNTHETIC_FUTURE_SECRET" in it })
        assertEquals(setOf(2, 3, 4), actionBodies(entries).map { it.getInt("revision") }.toSet())
    }

    @Test fun `backfilled review changes fixed directory and suppresses stale publication`() {
        seed(); publish()
        clock.now = Instant.parse("2026-10-20T04:00:00Z")
        actions.review("action", 4, "backfill", ActionReview(ActionExecution.EXECUTED,
            "SYNTHETIC_BACKFILLED_REVIEW", ActionDecision.CONTINUE, scope.through), scope.through.plusDays(20))
        val entries = export(options = bounded())
        assertEquals(1, reasons(entries).getInt("action_evidence_changed"))
        assertTrue(entries.keys.none { it.startsWith("notes/entries/") || it.startsWith("action-evidence/") })
    }

    @Test fun `changed immutable action source fails closed and deletion omits copied knowledge`() {
        seed(); publish(); val snapshot = capture()
        val corrupt = snapshot.copy(actionRevisions = snapshot.actionRevisions.map { value ->
            if (value.revision == 4) value.copy(review = value.review!!.copy(observedText = "SYNTHETIC_TAMPERED")) else value
        })
        val destination = File(workspace, "corrupt.zip")
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeArchive.write(corrupt, workspace, destination, bounded())
        }
        assertFalse(destination.exists())
        val removed = snapshot.copy(actionRevisions = emptyList(), actionHeads = emptyList(),
            deletedActions = listOf(Deletion("action", 4, clock.now)))
        val entries = export(removed, bounded())
        assertEquals(1, reasons(entries).getInt("action_deleted"))
        assertTrue(entries.values.none { reviewText in it || "SYNTHETIC_SHARED_CONCLUSION" in it })
    }

    @Test fun `shared receipts have one body and paginated reverse routes across knowledge entries`() {
        seed(); val first = publish("run-one"); val second = publish("run-two")
        val entries = export(options = bounded().copy(pageSize = 1))
        assertEquals(3, actionBodies(entries).size)
        assertEquals(3, entries.keys.count { it.startsWith("action-evidence/entries/") })
        assertTrue("action-evidence/all/page-002.md" in entries)
        val reviewId = first.actionEvidence.single { it.kind == ActionEvidenceKind.USER_REVIEW }.id
        assertTrue("action-evidence/knowledge/$reviewId/page-002.md" in entries)
        assertEquals(first.actionEvidence, second.actionEvidence)
        entries.filterKeys { it.startsWith("action-evidence/") && !it.startsWith("action-evidence/entries/") }.values.forEach {
            assertTrue(it.lineSequence().count { line -> line.startsWith("- ") } <= 1)
        }
        assertLinksAndInventory(entries)
    }

    @Test fun `knowledge export atomically rejects expiry and clock rollback without requiring an adopted profile`() {
        seed(); publish(expiresAt = clock.now.plusSeconds(60))
        val transfer = DataTransfer(context, repository, clock)
        val expired = File(workspace, "expired.zip")
        clock.duringPublication = clock.now.plusSeconds(60)
        assertThrows(StaleSnapshotException::class.java) { transfer.exportReadingPackage(expired, bounded()) }
        assertFalse(expired.exists())
        val rollback = File(workspace, "rollback.zip")
        clock.duringPublication = clock.now.minusSeconds(1)
        assertThrows(StaleSnapshotException::class.java) { transfer.exportReadingPackage(rollback, bounded()) }
        assertFalse(rollback.exists())
        assertTrue(workspace.listFiles().orEmpty().none { it.name.endsWith(".pending") })
    }

    @Test fun `action deletion after building archive blocks final publication and next export erases traces`() {
        seed(); publish(); val snapshot = capture()
        val staged = File(workspace, "staged.zip")
        KnowledgeArchive.write(snapshot, workspace, staged, bounded())
        actions.delete("action", 4)
        val published = File(workspace, "published.zip")
        assertThrows(StaleSnapshotException::class.java) { repository.publishSnapshot(snapshot, staged, published) }
        assertFalse(published.exists())
        val entries = export(options = bounded())
        assertTrue(entries.values.none { reviewText in it || "SYNTHETIC_SHARED_CONCLUSION" in it || outsideReview in it })
    }

    @Test fun `reduction export retains scan only action sources separately from final review evidence`() {
        seed()
        analysis.start(AnalysisRequest("合成跨批行动归并", scope, actionPeriod = true), "run")
        val refs = analysis.actionPage("run").entries
        refs.forEachIndexed { index, receipt ->
            analysis.synthesizeActions("run", "action-$index", emptyList(), listOf(receipt), provider)
        }
        analysis.synthesizeActions("run", "record", listOf(EvidenceSelection("record", "body")), emptyList(), provider)
        val nodes = analysis.task("run").state.calls.map(ReductionGraph::nodeRef)
        analysis.reduce("run", "root", nodes, provider)
        val target = analysis.reductionStatementRef("run", ReductionGraph.nodeRef(analysis.task("run").state.calls.last()), 0)
        AnalysisFeedback(repository, clock).respondReduction("run", target, analysis.task("run").stored.version,
            ClaimFeedbackDecision.CONFIRMED, independentText)
        val context = analysis.reductionReviewContext("run", target)
        analysis.scanReduction("run", "scan", target,
            context.originalEvidence.map { it.id } + context.originalActionEvidence.map { it.id }, provider)
        analysis.reviewReduction("run", "final", target, provider)
        val note = KnowledgeLibrary(repository, clock).publishReduction("run", target, "final",
            analysis.task("run").stored.version, topics = setOf("learning"))
        val opening = refs.single { it.kind == ActionEvidenceKind.OPENING_PLAN }
        assertTrue(note.actionEvidence.none { it.id == opening.id })
        assertTrue(KnowledgeValidation.allActionEvidence(note).any { it.id == opening.id })
        val entries = export(options = bounded().copy(pageSize = 1))
        assertEquals(3, actionBodies(entries).size)
        assertTrue(entries.values.any { "扫描 IRRELEVANT" in it })
        assertTrue(entries.getValue("action-evidence/entries/${opening.id}.md").contains("周期前计划背景"))
        assertLinksAndInventory(entries)
    }

    private fun seed() {
        clock.now = Instant.parse("2026-10-10T04:00:00Z")
        actions.create(draft(), "action")
        clock.now = Instant.parse("2026-10-11T04:00:00Z")
        actions.review("action", 1, "old-review", ActionReview(ActionExecution.UNKNOWN,
            outsideReview, ActionDecision.DEFER, scope.from.minusDays(1)), scope.through.plusDays(10))
        clock.now = Instant.parse("2026-10-14T04:00:00Z")
        actions.update("action", 2, draft().copy(behavior = "SYNTHETIC_PERIOD_PLAN"))
        clock.now = Instant.parse("2026-10-19T04:00:00Z")
        actions.review("action", 3, "period-review", ActionReview(ActionExecution.UNKNOWN,
            reviewText, ActionDecision.ADJUST, scope.through), adjustedDraft = draft().copy(behavior = "SYNTHETIC_NEXT_PLAN"))
        repository.createRecord(RecordDraft("合成原文：这周尝试小任务，执行信息不完整。", Occurrence(scope.from.plusDays(3)),
            topics = setOf("learning")), "record")
    }
    private fun draft() = ActionDraft("合成行动", "合成触发", "SYNTHETIC_OPENING_PLAN", "合成观察信号", scope.through.plusDays(10), "Asia/Shanghai")
    private fun publish(runId: String = "run", expiresAt: Instant? = null): StoredKnowledgeRevision {
        val period = ActionPeriodReflection(analysis)
        period.start(AnalysisRequest("合成分析行动与记录", scope, expiresAt = expiresAt), runId)
        period.process(runId, provider)
        val claim = period.inspect(runId).batches.single().claims.single()
        AnalysisFeedback(repository, clock).respond(runId, claim, analysis.task(runId).stored.version,
            ClaimFeedbackDecision.CONFIRMED, independentText)
        analysis.reviewClaim(runId, "review", claim, provider = provider)
        return KnowledgeLibrary(repository, clock).publish(runId, claim, "review", KnowledgeCategory.INSIGHT,
            analysis.task(runId).stored.version, topics = setOf("learning"))
    }
    private fun bounded() = ReadingExportOptions(scope = ExportScope(scope.from, scope.through))
    private fun capture() = repository.captureSnapshot(File(workspace, UUID.randomUUID().toString()).apply { check(mkdir()) })
    private fun export(snapshot: RepositorySnapshot = capture(), options: ReadingExportOptions = ReadingExportOptions()): Map<String, String> {
        val file = File(workspace, "reading-${UUID.randomUUID()}.zip")
        KnowledgeArchive.write(snapshot, workspace, file, options)
        return ZipFile(file).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).bufferedReader().use { r -> r.readText() } } }
    }
    private fun actionBodies(entries: Map<String, String>) = entries.filterKeys { it.startsWith("data/action-evidence/entries/") }.values.map(::JSONObject)
    private fun reasons(entries: Map<String, String>) = JSONObject(entries.getValue("manifest.json")).getJSONObject("knowledge").getJSONObject("omittedByReason")
    private fun assertLinksAndInventory(entries: Map<String, String>) {
        entries.filterKeys { it.endsWith(".md") }.forEach { (source, text) ->
            Regex("\\[[^\\]\\n]*]\\(([^)]+)\\)").findAll(text).forEach { match ->
                val path = (Paths.get(source).parent ?: Paths.get("")).resolve(match.groupValues[1]).normalize().toString().replace('\\', '/')
                assertTrue("$source -> $path", path in entries)
            }
        }
        val files = entries.filterKeys { it.startsWith("inventory/") && it.endsWith(".jsonl") }.values
            .flatMap { it.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }
        assertEquals(entries.keys.filter { !it.startsWith("inventory/") && it != "manifest.json" }.toSet(), files.map { it.getString("path") }.toSet())
        files.forEach { file -> assertEquals(file.getString("sha256"), AnalysisRunCodec.sha256(entries.getValue(file.getString("path")))) }
    }
    private class MutableClock : Clock() {
        var now = Instant.parse("2026-10-19T04:00:00Z")
        var duringPublication: Instant? = null
        override fun instant() = duringPublication?.takeIf {
            Thread.currentThread().stackTrace.any { frame -> frame.className.contains("LifeRepository") &&
                (frame.methodName == "publishDerivedFile" || frame.className.contains("publishDerivedFile")) }
        } ?: now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private class Provider : AnalysisProvider, SemanticReviewProvider, SemanticReductionScanProvider {
        override val id = "synthetic-action-export"
        override val contextWindowTokens = 200_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            val json = JSONObject(request.payloadJson)
            val refs = if (json.has("derivedInputs")) ids(json, "derivedInputs") else ids(json, "evidence") + ids(json, "actionEvidence")
            return BatchProposal(listOf(ProposedClaim("SYNTHETIC_SHARED_CONCLUSION 合成：执行信息仍不完整。", refs, ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val json = JSONObject(request.original.payloadJson)
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, ids(json, "evidence") + ids(json, "actionEvidence")))
        }
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            val json = JSONObject(request.original.payloadJson)
            val action = json.optJSONArray("actionEvidence")
            val irrelevant = if (action == null) emptySet() else (0 until action.length()).map { action.getJSONObject(it) }
                .filter { it.getString("kind") == "OPENING_PLAN" }.map { it.getString("id") }.toSet()
            return ReductionScanCompletion(ReductionScanResult((ids(json, "evidence") + ids(json, "actionEvidence")).map {
                ReductionFinding(it, if (it in irrelevant) ReductionRelevance.IRRELEVANT else ReductionRelevance.SUPPORT, "合成分类")
            }))
        }
        private fun ids(json: JSONObject, key: String): List<String> {
            val values = json.optJSONArray(key) ?: return emptyList()
            return (0 until values.length()).map { values.getJSONObject(it).getString("id") }
        }
    }
}
