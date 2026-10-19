package dev.tracelife.analysis

import dev.tracelife.storage.StoredAnalysisRun
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic-only near-capacity v5 history. Its tiny review redaction requires a larger v6 envelope. */
object LegacyRedactionCapacityFixture {
    fun fill(run: StoredAnalysisRun): StoredAnalysisRun {
        val state = AnalysisRunCodec.decode(run.payload)
        require(state.calls.isNotEmpty() && state.calls.size <= 5 && state.request.limits.maxCalls == 100)
        require(state.calls.none { it.purpose in setOf(CallPurpose.REDUCTION, CallPurpose.REDUCTION_SCAN, CallPurpose.REDUCTION_REVIEW) })
        val root = JSONObject(run.payload).put("formatVersion", 5)
        val calls = root.getJSONArray("calls")
        repeat(calls.length()) { stripNew(calls.getJSONObject(it)) }
        val source = state.calls.first { it.purpose.producesClaims && it.status == CallStatus.SUCCEEDED }
        val unknownCount = minOf(19, (state.request.limits.maxOutputBytes - 1_000) / 2_003)
        require(unknownCount >= 14)
        fun filler(index: Int, unknowns: List<String>): JSONObject {
            val call = source.copy(batchKey = "synthetic-capacity-$index", attempt = 1, inputBytes = 1, inputTokens = 1,
                evidence = source.evidence.take(1), proposal = BatchProposal(emptyList(), unknowns), usage = null,
                purpose = CallPurpose.EXTRACTION, reviewTarget = null, review = null, feedbackVersion = 0)
            return JSONObject(AnalysisRunCodec.encode(AnalysisRunState(state.request, listOf(call)))).getJSONArray("calls").getJSONObject(0).also(::stripNew)
        }
        val target = AnalysisRunCodec.MAX_PAYLOAD_BYTES - 64
        fun size() = root.toString().toByteArray(Charsets.UTF_8).size
        while (true) {
            require(calls.length() < 99)
            val full = filler(calls.length(), List(unknownCount) { "x".repeat(2_000) })
            // Reserve one smaller final call; no original source or feedback text is used as filler.
            if (size() + full.toString().toByteArray(Charsets.UTF_8).size + 1 + filler(calls.length() + 1, emptyList()).toString().toByteArray(Charsets.UTF_8).size + 1 > target) break
            calls.put(full)
        }
        val last = filler(calls.length(), emptyList())
        calls.put(last)
        val unknowns = JSONArray()
        last.getJSONObject("proposal").put("unknowns", unknowns)
        repeat(20) {
            val remaining = target - size()
            val overhead = if (unknowns.length() == 0) 2 else 3
            if (remaining > overhead) unknowns.put("x".repeat(minOf(2_000, remaining - overhead)))
        }
        val payload = root.toString()
        require(payload.toByteArray(Charsets.UTF_8).size in (target - 128)..target)
        val result = run.copy(payload = payload)
        AnalysisRunCodec.validateStored(result)
        return result
    }

    private fun stripNew(call: JSONObject) {
        listOf("redactedReductionIdentity", "reductionTarget", "reductionReviewTokens", "reductionScan", "reductionScans").forEach(call::remove)
    }
}
