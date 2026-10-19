package dev.tracelife.knowledge

import android.content.Context
import dev.tracelife.actions.ActionDraft
import dev.tracelife.actions.ActionExperiments
import dev.tracelife.actions.ActionOrigin
import dev.tracelife.actions.ActionOriginState
import dev.tracelife.analysis.*
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RevisionConflictException
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic records and providers exercise real SQLite publication; no model or network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReductionKnowledgeTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var feedback: AnalysisFeedback
    private lateinit var library: KnowledgeLibrary
    private val database = "synthetic-reduction-knowledge-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-19")
    private val provider = Provider()
    private lateinit var target: ReductionStatementRef

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        feedback = AnalysisFeedback(repository, clock)
        library = KnowledgeLibrary(repository, clock)
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(database) }

    @Test fun `published insight retains exact statement complete scans and independent confirmation`() {
        prepare(); review()
        val saved = publish()
        assertEquals(saved, publish())
        assertEquals(KnowledgeCategory.INSIGHT, saved.category)
        assertEquals(ClaimKind.POSSIBLE_INTERPRETATION, saved.claim.kind)
        assertEquals(saved.reductionProof!!.statement.text, saved.claim.text)
        assertEquals(ReductionGraph.feedbackRef(target), saved.claimRef)
        assertEquals(saved.review.supportingEvidenceIds, saved.claim.evidenceIds)
        assertEquals(1, saved.evidence.size)
        assertEquals(4, KnowledgeValidation.allEvidence(saved).size)
        assertEquals(3, saved.reductionProof.scans.single().result.findings.count { it.classification == ReductionRelevance.IRRELEVANT })
        assertEquals(2, saved.reductionProof.leafRefs.size)
        val dependencies = KnowledgeValidation.feedbackDependencies(saved)
        assertEquals(3, dependencies.size)
        assertEquals(2, dependencies.count { it.version == 0 })
        assertEquals(1, dependencies.single { it.claim == saved.claimRef }.version)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(saved.id)!!.evidenceState)
        assertEquals(1, library.index(KnowledgeFilter(category = KnowledgeCategory.INSIGHT, topics = setOf("learning"))).total)
        assertFalse(KnowledgeCodec.encode(saved).contains("用户独立说明"))
        assertTrue(feedback.forClaim("run", saved.claimRef)!!.feedback.userText!!.contains("用户独立说明"))
    }

    @Test fun `confirmation is mandatory and a later confirmation requires a fresh whole review`() {
        prepare(confirm = false); review()
        assertThrows(IllegalArgumentException::class.java) { publish() }
        confirm()
        assertThrows(IllegalArgumentException::class.java) { publish() }
        review("confirmed-final", "confirmed-scan")
        val saved = publish("confirmed-final")
        assertEquals(1, saved.feedbackVersion)
        assertEquals(1, saved.reductionProof!!.dependencyTokens.single { it.claim == saved.claimRef }.version)
    }

    @Test fun `ordinary publication entry and reported categories cannot adopt a reduction implicitly`() {
        prepare(); review()
        assertThrows(IllegalArgumentException::class.java) {
            library.publish("run", ReductionGraph.feedbackRef(target), "final", KnowledgeCategory.INSIGHT, version())
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeValidation.fromRun(analysis.task("run").stored, ReductionGraph.feedbackRef(target), "final",
                KnowledgeCategory.OBSERVATION, emptySet(), 1, clock.now, clock.now)
        }
        val saved = publish()
        assertThrows(IllegalArgumentException::class.java) {
            repository.publishKnowledge(saved.copy(revision = 2, claim = saved.claim.copy(text = "合成：未经核对的新解释。")), 1, version())
        }
        assertEquals(saved, library.get(saved.id)!!.value)
    }

    @Test fun `later unsupported whole review invalidates old support without modifying saved wording`() {
        prepare(); review()
        val saved = publish()
        provider.verdict = ReviewVerdict.UNSUPPORTED
        analysis.reviewReduction("run", "later-negative", target, provider)
        assertEquals(KnowledgeEvidenceState.REVIEW_CHANGED, library.get(saved.id)!!.evidenceState)
        assertEquals(saved.claim, library.get(saved.id)!!.value.claim)
        assertEquals(0, library.index().total)
        assertThrows(IllegalArgumentException::class.java) { publish(expectedRevision = 1) }
        assertThrows(IllegalArgumentException::class.java) { publish("later-negative", expectedRevision = 1) }
    }

    @Test fun `a newer supported final requires a new revision while topics and old history survive`() {
        prepare(); review()
        val first = publish()
        analysis.reviewReduction("run", "later-support", target, provider)
        val second = publish("later-support", expectedRevision = 1)
        assertEquals(2, second.revision)
        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertEquals(setOf("learning"), second.topics)
        assertEquals(KnowledgeEvidenceState.CURRENT, library.get(first.id)!!.evidenceState)
        assertEquals(KnowledgeEvidenceState.REVIEW_CHANGED, library.get(first.id, 1)!!.evidenceState)
        assertEquals(listOf(2, 1), library.history(first.id).map { it.value.revision })
        assertEquals(second, publish("later-support", expectedRevision = 1))
    }

    @Test fun `parent feedback changes invalidate knowledge and action origin without changing user plans`() {
        prepare(); review()
        val saved = publish()
        val actions = ActionExperiments(repository, clock)
        val draft = ActionDraft("合成用户行动", "学习开始时", "先做一次小练习", "能记录卡住的步骤", day.plusDays(7),
            "Asia/Shanghai", ActionOrigin(saved.id, saved.revision))
        val action = actions.create(draft, "synthetic-action")
        assertEquals(ActionOriginState.CURRENT, action.originState)
        val parent = analysis.claimRef("run", "leaf-0", 0)
        feedback.respond("run", parent, version(), ClaimFeedbackDecision.CONFIRMED, "合成：仅认可局部描述。")
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, library.get(saved.id)!!.evidenceState)
        assertEquals(1, library.index().omittedInvalid)
        assertEquals(ActionOriginState.STALE, actions.get("synthetic-action")!!.originState)
        assertEquals(draft, actions.get("synthetic-action")!!.value.draft)
        assertThrows(RevisionConflictException::class.java) { publish(expectedRevision = 1) }
    }

    @Test fun `intermediate derived statements are feedback dependencies even when not cited by final wording`() {
        prepare(deep = true); review()
        val saved = publish()
        val middleCall = analysis.task("run").state.calls.single { it.batchKey == "middle-1" }
        val middle = ReductionGraph.statementRef(middleCall, 0)
        val ref = ReductionGraph.feedbackRef(middle)
        assertTrue(KnowledgeValidation.feedbackDependencies(saved).any { it.claim == ref && it.version == 0 })
        feedback.respond("run", ref, version(), ClaimFeedbackDecision.REJECTED, "合成用户独立反对。")
        assertEquals(KnowledgeEvidenceState.FEEDBACK_CHANGED, library.get(saved.id)!!.evidenceState)
        assertEquals(0, library.index().total)
    }

    @Test fun `a newly successful synthesis leaf invalidates a previous whole period publication`() {
        prepare(); review()
        val saved = publish()
        analysis.synthesize("run", "additional-leaf", listOf(EvidenceSelection("record-0", "body")), provider)
        assertEquals(KnowledgeEvidenceState.REVIEW_CHANGED, library.get(saved.id)!!.evidenceState)
        assertThrows(IllegalArgumentException::class.java) { publish(expectedRevision = 1) }
        assertEquals(1, repository.knowledgeRevision(saved.id)!!.revision)
    }

    @Test fun `storage reconstructs the full proof and rejects omitted ancestor dependencies`() {
        prepare(); review()
        val saved = publish()
        val proof = saved.reductionProof!!
        val forged = saved.copy(revision = 2, reductionProof = proof.copy(dependencyTokens = proof.dependencyTokens.filter { it.claim == saved.claimRef }))
        // A standalone bounded proof cannot certify the complete graph; retained-task comparison
        // is mandatory both when publishing and when deciding whether a saved entry is current.
        KnowledgeValidation.validate(forged)
        assertFalse(KnowledgeValidation.reviewMatchesState(forged, analysis.task("run").state))
        assertThrows(IllegalArgumentException::class.java) { repository.publishKnowledge(forged, 1, version()) }
        assertEquals(saved, repository.knowledgeRevision(saved.id))
    }

    @Test fun `missing graph retains a historical proof but cannot classify derived insight as current`() {
        prepare(); review()
        val saved = publish()
        assertFalse(KnowledgeValidation.reviewStillCurrent(saved, null))
        assertFalse(KnowledgeValidation.reviewMatchesState(saved, null))
        assertEquals(saved, KnowledgeCodec.decode(KnowledgeCodec.encode(saved)))
        val direct = asDirect(saved)
        assertTrue(KnowledgeValidation.reviewStillCurrent(direct, null))
        assertTrue(KnowledgeValidation.reviewMatchesState(direct, null))
    }

    @Test fun `version two is strict and version one remains supported only for direct proofs`() {
        prepare(); review()
        val saved = publish()
        val payload = KnowledgeCodec.encode(saved)
        assertEquals(2, JSONObject(payload).getInt("formatVersion"))
        assertEquals(saved, KnowledgeCodec.decode(payload))
        val direct = asDirect(saved)
        val legacy = JSONObject(KnowledgeCodec.encode(direct)).apply { put("formatVersion", 1); remove("reductionProof") }
        assertEquals(direct, KnowledgeCodec.decode(legacy.toString()))
        val invalid = listOf(
            JSONObject(payload).apply { remove("reductionProof") }.toString(),
            JSONObject(payload).put("formatVersion", 1).toString(),
            JSONObject(payload).apply { put("formatVersion", 1); remove("reductionProof") }.toString(),
            JSONObject(payload).apply { getJSONObject("reductionProof").put("unregistered", true) }.toString(),
            JSONObject(payload).put("reductionProof", JSONObject.NULL).toString(),
            payload.replace("\"formatVersion\":2", "\"formatVersion\":2,\"formatVersion\":2"),
        )
        invalid.forEach { assertThrows(Exception::class.java) { KnowledgeCodec.decode(it) } }
    }

    @Test fun `complete proof exceeding the publication cap is rejected rather than truncated`() {
        prepare(); review()
        val saved = publish()
        val first = saved.evidence.single()
        val originals = (0 until 256).map { index ->
            if (index == 0) first else {
                val id = "synthetic-long-source-$index-" + "x".repeat(140)
                first.copy(recordId = id, id = AnalysisRunCodec.evidenceId(id, first.revision, first.field, first.start, first.end, first.sha256))
            }
        }
        val scans = originals.chunked(8).mapIndexed { index, refs ->
            val result = ReductionScanResult(refs.map { ref -> ReductionFinding(ref.id,
                if (ref.id == first.id) ReductionRelevance.SUPPORT else ReductionRelevance.IRRELEVANT, "合".repeat(500)) })
            val hash = AnalysisRunCodec.sha256(AnalysisRunCodec.json(ReductionReviewProtocol.scanMap(result)))
            ReductionScanProof(ReductionScanRef("synthetic-large-scan-$index", 1, "a".repeat(64), hash), "synthetic-scan", refs, result)
        }
        val proof = saved.reductionProof!!.copy(originalEvidence = originals, scans = scans)
        val large = saved.copy(reductionProof = proof)
        KnowledgeValidation.validate(large)
        assertTrue(AnalysisRunCodec.json(ReductionReviewProtocol.proofMap(proof)).toByteArray().size > KnowledgeValidation.MAX_REVISION_BYTES)
        assertThrows(IllegalArgumentException::class.java) { KnowledgeCodec.encode(large) }
        assertEquals(saved, repository.knowledgeRevision(saved.id))
    }

    @Test fun `feedback arriving after publication preparation fails CAS without writing knowledge`() {
        prepare(); review()
        val parent = analysis.claimRef("run", "leaf-0", 0)
        val expected = version()
        clock.onNextInstant = { feedback.respond("run", parent, expected, ClaimFeedbackDecision.REJECTED, "合成并发反对。") }
        assertThrows(RevisionConflictException::class.java) {
            library.publishReduction("run", target, "final", expected)
        }
        assertTrue(repository.knowledgeHeads().isEmpty())
    }

    @Test fun `source and expiry changes invalidate derived insight with no automatic reanalysis`() {
        prepare(expiresAt = clock.now.plusSeconds(60)); review()
        val saved = publish()
        val before = library.index()
        clock.now = clock.now.plusSeconds(60)
        assertEquals(KnowledgeEvidenceState.EXPIRED, library.get(saved.id)!!.evidenceState)
        assertEquals(0, library.index().total)
        repository.updateRecord("record-0", 1, RecordDraft("合成来源修订", Occurrence(day)))
        assertEquals(KnowledgeEvidenceState.STALE, library.get(saved.id)!!.evidenceState)
        assertThrows(StaleSnapshotException::class.java) { library.next(before.copy(nextOffset = 1)) }
    }

    private fun prepare(confirm: Boolean = true, deep: Boolean = false, expiresAt: Instant? = null) {
        repeat(4) { repository.createRecord(RecordDraft("合成原文$it：记录练习过程。", Occurrence(day)), "record-$it") }
        analysis.start(AnalysisRequest("合成周期：核对小步骤练习这一解释", AnalysisQuery(day, day, "Asia/Shanghai"), expiresAt = expiresAt), "run")
        val groups = if (deep) (0 until 4).map(::listOf) else listOf(listOf(0, 1), listOf(2, 3))
        val leaves = groups.mapIndexed { index, rows ->
            analysis.synthesize("run", "leaf-$index", rows.map { EvidenceSelection("record-$it", "body") }, provider)
            ReductionGraph.nodeRef(analysis.task("run").state.calls.last())
        }
        val parents = if (deep) leaves.chunked(2).mapIndexed { index, refs ->
            analysis.reduce("run", "middle-$index", refs, provider)
            ReductionGraph.nodeRef(analysis.task("run").state.calls.last())
        } else leaves
        analysis.reduce("run", "root", parents, provider)
        target = analysis.reductionStatementRef("run", ReductionGraph.nodeRef(analysis.task("run").state.calls.last()), 0)
        if (confirm) confirm()
    }
    private fun confirm() = feedback.respond("run", ReductionGraph.feedbackRef(target), version(), ClaimFeedbackDecision.CONFIRMED,
        "合成用户独立说明：我愿意把它作为这一阶段的假设。")
    private fun review(reviewKey: String = "final", scanKey: String = "scan") {
        val context = analysis.reductionReviewContext("run", target)
        analysis.scanReduction("run", scanKey, target, context.originalEvidence.map { it.id }, provider)
        analysis.reviewReduction("run", reviewKey, target, provider)
    }
    private fun publish(reviewKey: String = "final", expectedRevision: Int? = null) =
        library.publishReduction("run", target, reviewKey, version(), expectedRevision, setOf("learning"))
    private fun version() = analysis.task("run").stored.version
    private fun asDirect(value: StoredKnowledgeRevision): StoredKnowledgeRevision {
        val ref = ClaimRef("synthetic-direct", 0, AnalysisRunCodec.claimSha256(value.claim))
        return value.copy(id = KnowledgeValidation.idFor(value.runId, ref), claimRef = ref, reductionProof = null)
    }

    private class Provider : AnalysisProvider, SemanticReductionScanProvider, SemanticReviewProvider {
        override val id = "synthetic-reduction-publication"
        override val contextWindowTokens = 100_000
        var verdict = ReviewVerdict.SUPPORTED
        override fun countInputTokens(request: ModelRequest) = 100
        override fun complete(request: ModelRequest): BatchProposal {
            val body = JSONObject(request.payloadJson)
            val rows = body.getJSONArray(if (body.getString("purpose") == "REDUCTION") "derivedInputs" else "evidence")
            return BatchProposal(listOf(ProposedClaim("合成解释：短练习可能帮助定位下一步问题。",
                listOf(rows.getJSONObject(0).getString("id")), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareScan(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            val rows = JSONObject(request.payloadJson).getJSONArray("evidence")
            return ReductionScanCompletion(ReductionScanResult((0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                ReductionFinding(row.getString("id"), if (row.getString("recordId") == "record-0") ReductionRelevance.SUPPORT else ReductionRelevance.IRRELEVANT,
                    "合成分类理由。")
            }))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, 100)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val row = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0)
            return ReviewCompletion(ClaimReview(verdict,
                supportingEvidenceIds = if (verdict == ReviewVerdict.SUPPORTED) listOf(row.getString("id")) else emptyList(),
                unknowns = listOf("合成：仍不是事实认证。")))
        }
    }

    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T03:00:00Z")) : Clock() {
        var onNextInstant: (() -> Unit)? = null
        override fun instant(): Instant {
            val callback = onNextInstant
            onNextInstant = null
            callback?.invoke()
            return now
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
