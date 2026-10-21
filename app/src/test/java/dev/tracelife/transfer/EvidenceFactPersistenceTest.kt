package dev.tracelife.transfer

import dev.tracelife.analysis.*
import dev.tracelife.actions.*
import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordRevision
import dev.tracelife.storage.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

/** Synthetic records and password only. No transport or credentials are used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EvidenceFactPersistenceTest {
    private val now = Instant.parse("2026-10-21T01:00:00Z")
    private val date = LocalDate.parse("2026-10-18")
    private val body = "😀合成资料：计划下周每次练习五分钟，还没有尝试。"
    private val record = RecordRevision("synthetic-fact-record", 1, RecordDraft(body, Occurrence(date)), now, now)
    private val textHash = AnalysisRunCodec.sha256(body)
    private val length = body.codePointCount(0, body.length)
    private val receipt = EvidenceReceipt(AnalysisRunCodec.evidenceId(record.id, 1, "body", 0, length, textHash),
        record.id, 1, "body", 0, length, length, textHash)
    private val scope = AnalysisQuery(date, date, "UTC")

    @Test fun `all prior task formats remain readable without adding factual proofs`() {
        val base = state(withFacts = false).copy(calls = state(withFacts = false).calls.take(1))
        (1..17).forEach { version ->
            val payload = legacyPayload(base, version)
            val decoded = AnalysisRunCodec.decode(payload)
            assertNull("v$version", decoded.calls.single().sourceFacts)
            assertNull("v$version", decoded.calls.single().review?.factCheck)
            assertEquals(2_000, decoded.request.limits.outputTokenReserve)
            val row = run(decoded).copy(payload = payload)
            AnalysisRunCodec.validateStored(row, listOf(record))
            assertEquals("Historical validation does not rewrite its payload", payload, row.payload)
        }
    }

    @Test fun `version eighteen keeps source facts and review proof while legacy formats reject new fields`() {
        val expected = state()
        val encoded = AnalysisRunCodec.encode(expected)
        assertEquals(18, JSONObject(encoded).getInt("formatVersion"))
        assertEquals(expected, AnalysisRunCodec.decode(encoded))
        AnalysisRunCodec.validateStored(run(expected), listOf(record))
        val downgraded = JSONObject(encoded).put("formatVersion", 17)
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(downgraded.toString()) }
        val hiddenProof = JSONObject(encoded).put("formatVersion", 17)
        (0 until hiddenProof.getJSONArray("calls").length()).forEach {
            hiddenProof.getJSONArray("calls").getJSONObject(it).remove("sourceFacts")
        }
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(hiddenProof.toString()) }
    }

    @Test fun `fact input omissions foreign receipts wrong dates and detached target proofs are rejected`() {
        val expected = state()
        val call = expected.calls.last()
        val proof = requireNotNull(call.review?.factCheck)
        val foreign = pack(receipt.copy(recordId = "synthetic-other", id = AnalysisRunCodec.evidenceId(
            "synthetic-other", 1, "body", 0, length, textHash)))
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.encode(expected.copy(calls = listOf(expected.calls.first().copy(sourceFacts = foreign), call)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.encode(expected.copy(calls = listOf(expected.calls.first(), call.copy(review = call.review!!.copy(factCheck = null)))))
        }
        val other = EvidenceFacts.check("合成另一个待核对目标。", proof.pack)
        assertThrows(IllegalArgumentException::class.java) {
            AnalysisRunCodec.encode(expected.copy(calls = listOf(expected.calls.first(), call.copy(review = call.review!!.copy(factCheck = other)))))
        }
        // Dates are not part of an EvidenceReceipt's text hash. A structurally valid pack must
        // still be checked against the actual original revision when snapshots are accepted.
        val wrongDates = pack(from = date.minusDays(1))
        val altered = expected.copy(calls = expected.calls.map {
            it.copy(sourceFacts = wrongDates, review = it.review?.let { review ->
                EvidenceFacts.apply(review.copy(factCheck = null), EvidenceFacts.check(body, wrongDates))
            })
        })
        val row = run(altered)
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.validateStored(row, listOf(record)) }
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(snapshot(row)) }
    }

    @Test fun `published proof survives absence of its task and old knowledge never acquires one`() {
        val row = run(state())
        val value = knowledge(row)
        val payload = KnowledgeCodec.encode(value)
        assertEquals(8, JSONObject(payload).getInt("formatVersion"))
        assertEquals(value, KnowledgeCodec.decode(payload))
        assertTrue(KnowledgeValidation.reviewStillCurrent(value, null))
        KnowledgeValidation.validateEvidence(value, listOf(record))
        val old = value.copy(review = value.review.copy(factCheck = null))
        val legacy = JSONObject(KnowledgeCodec.encode(old)).put("formatVersion", 7).toString()
        assertNull(KnowledgeCodec.decode(legacy).review.factCheck)
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeCodec.decode(JSONObject(payload).put("formatVersion", 7).toString())
        }
        val detached = value.copy(claim = value.claim.copy(text = "合成被改写的知识正文。"))
        assertThrows(IllegalArgumentException::class.java) { KnowledgeCodec.encode(detached) }
        val copied = snapshot(row).copy(analysisRuns = emptyList(), knowledgeRevisions = listOf(value),
            knowledgeHeads = listOf(KnowledgeHead(value.id, value.revision)))
        val output = ByteArrayOutputStream().also { SnapshotJsonCodec.write(copied, it) }
        assertEquals(copied, SnapshotJsonCodec.read(output.toByteArray().inputStream()))
    }

    @Test fun `encrypted backup carries both factual task input and self contained publication proof`() {
        val row = run(state())
        val value = knowledge(row)
        val original = snapshot(row).copy(knowledgeRevisions = listOf(value), knowledgeHeads = listOf(KnowledgeHead(value.id, value.revision)))
        val folder = Files.createTempDirectory("synthetic-fact-backup-").toFile()
        val password = "synthetic factual proof 合成".toCharArray()
        try {
            val attachments = File(folder, "attachments").apply { mkdir() }
            val archive = File(folder, "facts.tlb")
            BackupArchive.write(original, attachments, archive, password)
            val restored = BackupArchive.read(archive, File(folder, "restored"), password)
            assertEquals(original, restored)
            assertNotNull(AnalysisRunCodec.decode(restored.analysisRuns.single().payload).calls.last().review?.factCheck)
            assertEquals(value.review.factCheck, restored.knowledgeRevisions.single().review.factCheck)
            assertEquals(10, JSONObject(ByteArrayOutputStream().also { SnapshotJsonCodec.write(restored, it) }
                .toString(Charsets.UTF_8.name())).getInt("formatVersion"))
        } finally {
            password.fill('\u0000'); folder.deleteRecursively()
        }
    }

    @Test fun `pending reviews reserve space for the independent program proof`() {
        val full = state()
        val pending = full.calls.last().copy(status = CallStatus.IN_FLIGHT, finishedAt = null, review = null)
        val withProof = run(full.copy(calls = listOf(full.calls.first(), pending)))
        val old = run(full.copy(calls = listOf(full.calls.first().copy(sourceFacts = null), pending.copy(sourceFacts = null))))
        assertTrue(AnalysisRunCodec.reservedResponseBytes(withProof) > AnalysisRunCodec.reservedResponseBytes(old))
        assertEquals(full.request.limits.maxOutputBytes + 256, AnalysisRunCodec.reservedResponseBytes(old))
    }

    @Test fun `pending review reserves proof nodes before dispatch and smaller history completes within the same limit`() {
        val denseText = "练习五分钟。".repeat(256)
        fun input(text: String): Pair<EvidenceReceipt, EvidenceFactPack> {
            val end = text.codePointCount(0, text.length)
            val hash = AnalysisRunCodec.sha256(text)
            val ref = EvidenceReceipt(AnalysisRunCodec.evidenceId("synthetic-dense", 1, "body", 0, end, hash),
                "synthetic-dense", 1, "body", 0, end, denseText.codePointCount(0, denseText.length), hash)
            val facts = requireNotNull(EvidenceFacts.build(mapOf("evidence" to listOf(
                AnalysisRunCodec.receiptMap(ref) + mapOf("text" to text, "startDate" to date.toString(), "endDate" to date.toString())))))
            return ref to facts
        }
        val (ref, facts) = input(denseText)
        assertEquals(256, facts.facts.size)
        val claim = ProposedClaim("合成：练习五分钟。", listOf(ref.id), ClaimKind.REPORTED_EXPERIENCE)
        val generation = state().calls.first().copy(batchKey = "dense-source", evidence = listOf(ref),
            proposal = BatchProposal(listOf(claim)), sourceFacts = facts)
        val pending = state().calls.last().copy(batchKey = "dense-review", evidence = listOf(ref), status = CallStatus.IN_FLIGHT,
            finishedAt = null, review = null, reviewTarget = ClaimRef(generation.batchKey, 0, AnalysisRunCodec.claimSha256(claim)), sourceFacts = facts)
        val base = state().let { it.copy(request = it.request.copy(limits = it.request.limits.copy(fragmentCodePoints = 4_096)),
            calls = listOf(generation, pending)) }
        val (partial, partialFacts) = input("练习五分钟。".repeat(128))
        val filler = generation.copy(batchKey = "filler", evidence = listOf(partial), sourceFacts = partialFacts,
            proposal = BatchProposal(listOf(claim.copy(evidenceIds = listOf(partial.id)))))
        val fillerJson = JSONObject(AnalysisRunCodec.encode(base.copy(calls = listOf(filler)))).getJSONArray("calls").getJSONObject(0)
        val root = JSONObject(AnalysisRunCodec.encode(base))
        val originalCalls = root.getJSONArray("calls")
        val count = (59_000 - jsonNodes(root)) / jsonNodes(fillerJson)
        assertTrue(count in 1..98)
        val calls = JSONArray()
        repeat(count) { calls.put(JSONObject(fillerJson.toString()).put("batchKey", "filler-$it")) }
        calls.put(originalCalls.getJSONObject(0)).put(originalCalls.getJSONObject(1))
        root.put("calls", calls)
        val proof = EvidenceFacts.apply(ClaimReview(ReviewVerdict.SUPPORTED, listOf(ref.id)), EvidenceFacts.check(claim.text, facts))
        assertTrue("The old 1,000-node allowance admitted this pending request", jsonNodes(root) <= 59_000)
        assertTrue("Its independent proof alone would exceed the final ceiling",
            jsonNodes(root) + jsonNodes(JSONObject(AnalysisRunCodec.json(EvidenceFacts.reviewMap(requireNotNull(proof.factCheck))))) > 60_000)
        val unsafe = AnalysisRunCodec.decode(root.toString())
        assertTrue(root.toString().toByteArray().size < AnalysisRunCodec.MAX_PAYLOAD_BYTES - 150_000)
        assertThrows(AnalysisPayloadCapacityException::class.java) { AnalysisRunCodec.encode(unsafe) }
        // Rejection happens at the same encode used to persist IN_FLIGHT, before dispatch.
        val smaller = unsafe.copy(calls = unsafe.calls.drop(2))
        assertEquals(smaller, AnalysisRunCodec.decode(AnalysisRunCodec.encode(smaller)))
        val complete = smaller.copy(calls = smaller.calls.dropLast(1) + smaller.calls.last().copy(
            status = CallStatus.SUCCEEDED, finishedAt = now, review = proof))
        assertEquals(complete, AnalysisRunCodec.decode(AnalysisRunCodec.encode(complete)))
    }

    @Test fun `mixed action facts round trip with exact scalar fields and reject invented source dates`() {
        val first = StoredActionRevision("synthetic-action", 1, ActionDraft("合成行动", "午饭后",
            "练习五分钟", "是否完成", date.plusDays(10), "UTC"), ActionStatus.OPEN, now, now)
        val action = first.copy(revision = 2, operationId = "synthetic-review", reviewedDraft = first.draft,
            review = ActionReview(ActionExecution.EXECUTED, "合成：按计划练习五分钟。", ActionDecision.CONTINUE, date))
        val ref = ActionEvidenceProtocol.receipt(action, ActionEvidenceKind.USER_REVIEW)
        val actionBody = ActionEvidenceProtocol.modelMap(action, ref)
        val recordBody = AnalysisRunCodec.receiptMap(receipt) + mapOf("text" to body,
            "startDate" to date.toString(), "endDate" to date.toString())
        fun pending(input: Map<String, Any?>): RepositorySnapshot {
            val facts = requireNotNull(EvidenceFacts.build(mapOf("evidence" to listOf(recordBody), "actionEvidence" to listOf(input))))
            val call = AnalysisCall("synthetic-action-pending", 1, "synthetic-facts-provider", "3".repeat(64), 2_000, 2_000,
                listOf(receipt), now, CallStatus.IN_FLIGHT, purpose = CallPurpose.ACTION_SYNTHESIS,
                actionEvidence = listOf(ref), sourceFacts = facts)
            val mixed = AnalysisRunState(AnalysisRequest("合成行动资料", scope.copy(through = date.plusDays(3)), actionPeriod = true),
                listOf(call), actionPlan = ActionEvidencePlan(listOf(ref)))
            return snapshot(run(mixed)).copy(actionRevisions = listOf(first, action), actionHeads = listOf(ActionHead(action.id, 2)))
        }
        val original = pending(actionBody)
        SnapshotValidation.validate(original)
        val bytes = ByteArrayOutputStream().also { SnapshotJsonCodec.write(original, it) }.toByteArray()
        assertEquals(original, SnapshotJsonCodec.read(bytes.inputStream()))
        val reviewBody = requireNotNull(actionBody["review"] as? Map<*, *>).entries.associate { it.key.toString() to it.value }
        val wrongDate = pending(actionBody + ("review" to (reviewBody + ("reviewedOn" to date.minusDays(1).toString()))))
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(wrongDate) }
    }

    @Test fun `independent reading snapshot rebuilds action proof even with valid receipts and recomputed hashes`() {
        val action = StoredActionRevision("synthetic-export-action", 1, ActionDraft("合成行动", "午饭后",
            "练习五分钟", "是否完成", date.plusDays(10), "UTC"), ActionStatus.OPEN, now, now)
        val ref = ActionEvidenceProtocol.receipt(action, ActionEvidenceKind.PLAN_CHANGE)
        val input = ActionEvidenceProtocol.modelMap(action, ref)
        val claim = ProposedClaim("合成：该行动尚需结合后续回看观察。", listOf(ref.id), ClaimKind.POSSIBLE_INTERPRETATION)
        val target = ClaimRef("synthetic-action-claim", 0, AnalysisRunCodec.claimSha256(claim))
        val runId = "synthetic-action-export"
        fun note(actionInput: Map<String, Any?>): StoredKnowledgeRevision {
            val pack = requireNotNull(EvidenceFacts.build(mapOf("actionEvidence" to listOf(actionInput))))
            val review = EvidenceFacts.apply(ClaimReview(ReviewVerdict.SUPPORTED, listOf(ref.id)), EvidenceFacts.check(claim.text, pack))
            return knowledge(run(state())).copy(id = KnowledgeValidation.idFor(runId, target), runId = runId,
                claimRef = target, scope = scope.copy(through = date.plusDays(3)), category = KnowledgeCategory.INSIGHT,
                claim = claim, review = review, evidence = emptyList(), feedbackVersion = 1,
                actionPlan = ActionEvidencePlan(listOf(ref)), actionEvidence = listOf(ref))
        }
        val original = note(input)
        val feedback = StoredClaimFeedback(AnalysisRunCodec.feedbackId(runId, target), 1, runId, target.batchKey,
            target.claimIndex, target.claimSha256, original.source, ClaimFeedbackDecision.CONFIRMED, null, now, now)
        val originalSnapshot = snapshot(run(state())).copy(records = emptyList(), analysisRuns = emptyList(),
            knowledgeRevisions = listOf(original), knowledgeHeads = listOf(KnowledgeHead(original.id, 1)),
            claimFeedback = listOf(feedback), actionRevisions = listOf(action), actionHeads = listOf(ActionHead(action.id, 1)))
        assertEquals(1, PublishedKnowledgeExport.select(originalSnapshot, emptyList(), emptyList(), ReadingExportOptions()).entries.size)
        val originalPlan = requireNotNull(input["plan"] as? Map<*, *>).entries.associate { it.key.toString() to it.value }
        val forged = note(input + ("plan" to (originalPlan + ("behavior" to "练习六分钟"))))
        // Both the unchanged source receipt and the independently recomputed pack/proof hashes
        // are valid. Only comparison with the actual retained scalar reveals the forged quote.
        assertEquals(original.actionEvidence, forged.actionEvidence)
        assertEquals(forged, KnowledgeCodec.decode(KnowledgeCodec.encode(forged)))
        assertNotEquals(original.review.factCheck!!.pack.sha256, forged.review.factCheck!!.pack.sha256)
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceFactStorage.validateActions(forged.review.factCheck!!.pack, forged.actionEvidence, listOf(action))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PublishedKnowledgeExport.select(originalSnapshot.copy(knowledgeRevisions = listOf(forged)),
                emptyList(), emptyList(), ReadingExportOptions())
        }
    }

    private fun pack(ref: EvidenceReceipt = receipt, from: LocalDate = date): EvidenceFactPack = requireNotNull(EvidenceFacts.build(mapOf(
        "evidence" to listOf(AnalysisRunCodec.receiptMap(ref) + mapOf("text" to body,
            "startDate" to from.toString(), "endDate" to from.toString())),
    )))

    private fun jsonNodes(value: Any?): Int = 1 + when (value) {
        is JSONObject -> value.keys().asSequence().sumOf { jsonNodes(value.get(it)) }
        is JSONArray -> (0 until value.length()).sumOf { jsonNodes(value.get(it)) }
        else -> 0
    }

    private fun state(withFacts: Boolean = true): AnalysisRunState {
        val facts = if (withFacts) pack() else null
        val claim = ProposedClaim(body, listOf(receipt.id), ClaimKind.REPORTED_EXPERIENCE)
        val generated = AnalysisCall("synthetic-generation", 1, "synthetic-facts-provider", "1".repeat(64), 1_000, 1_000,
            listOf(receipt), now, CallStatus.SUCCEEDED, now, proposal = BatchProposal(listOf(claim)), sourceFacts = facts)
        val target = ClaimRef(generated.batchKey, 0, AnalysisRunCodec.claimSha256(claim))
        val modelReview = ClaimReview(ReviewVerdict.SUPPORTED, listOf(receipt.id))
        val review = if (facts == null) modelReview else EvidenceFacts.apply(modelReview, EvidenceFacts.check(body, facts))
        assertEquals("Exact original text is the positive control", ReviewVerdict.SUPPORTED, review.verdict)
        val checked = AnalysisCall("synthetic-review", 1, generated.providerId, "2".repeat(64), 1_000, 1_000,
            listOf(receipt), now, CallStatus.SUCCEEDED, now, purpose = CallPurpose.REVIEW, reviewTarget = target,
            review = review, sourceFacts = facts)
        return AnalysisRunState(AnalysisRequest("合成资料核验", scope, AnalysisLimits(outputTokenReserve = 2_000)), listOf(generated, checked))
    }

    private fun run(state: AnalysisRunState) = StoredAnalysisRun("synthetic-fact-run", 3,
        SourceStamp("synthetic-fact-store", 1), now, now, null, AnalysisRunCodec.encode(state))
    private fun knowledge(row: StoredAnalysisRun): StoredKnowledgeRevision {
        val checked = AnalysisRunCodec.decode(row.payload).calls.last()
        return KnowledgeValidation.fromRun(row, requireNotNull(checked.reviewTarget), checked.batchKey,
            KnowledgeCategory.OBSERVATION, emptySet(), 1, now, now)
    }
    private fun snapshot(row: StoredAnalysisRun) = RepositorySnapshot("synthetic-fact-snapshot", row.source.storeId,
        row.source.generation, now, listOf(record), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        analysisRuns = listOf(row), analysisGeneration = 1)

    private fun legacyPayload(state: AnalysisRunState, version: Int): String {
        val root = JSONObject(AnalysisRunCodec.encode(state)).put("formatVersion", version)
        val request = root.getJSONObject("request")
        if (version < 8) request.remove("profileTarget")
        if (version < 10) request.remove("actionPeriod")
        if (version < 12) request.remove("inheritHistoricalFeedback")
        if (version >= 14) request.put("conversationContext", JSONObject.NULL) else request.remove("conversationContext")
        if (version >= 15) request.put("knowledgeContext", JSONObject.NULL) else request.remove("knowledgeContext")
        if (version < 9) root.remove("profileMaintenancePlans")
        if (version < 10) root.remove("actionPlan")
        if (version < 12) { root.remove("feedbackContext"); root.remove("historicalFeedbackResolutions") }
        val call = root.getJSONArray("calls").getJSONObject(0)
        if (version == 1) listOf("tokenCountKind", "usage").forEach(call::remove)
        if (version < 3) listOf("purpose", "reviewTarget", "review", "feedbackVersion").forEach(call::remove)
        if (version < 5) listOf("reductionInputs", "reductionOutput").forEach(call::remove)
        if (version < 6) listOf("redactedReductionIdentity", "reductionTarget", "reductionReviewTokens", "reductionScan", "reductionScans").forEach(call::remove)
        if (version < 7) call.remove("metricEvidence")
        if (version < 9) listOf("profileMaintenancePlanSha256", "profileReference", "profileRelation", "profileScan", "profileMaintenanceProof").forEach(call::remove)
        if (version < 10) call.remove("actionEvidence")
        if (version < 12) listOf("historicalFeedbackReadIds", "feedbackContextSha256").forEach(call::remove)
        if (version >= 13) call.put("historicalFeedbackScan", JSONObject.NULL)
        if (version >= 17) { call.put("navigation", JSONObject.NULL); call.put("navigationContext", JSONObject.NULL) }
        call.remove("sourceFacts")
        return root.toString()
    }
}
