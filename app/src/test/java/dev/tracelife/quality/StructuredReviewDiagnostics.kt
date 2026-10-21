package dev.tracelife.quality

import dev.tracelife.analysis.AnalysisCall
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ClaimReview
import dev.tracelife.analysis.EvidenceFacts
import org.json.JSONArray
import org.json.JSONObject

/** Diagnostic output only; neither fixtures nor assertions depend on these report fields. */
internal fun JSONObject.withReviewDiagnostics(review: ClaimReview?): JSONObject {
    val check = review?.factCheck
    // The adapter may already reject a raw SUPPORTED verdict when individual checks disagree.
    // Preserve that distinction from the separate deterministic fact check below.
    val modelVerdict = if (check == null) review?.verdict else check.modelVerdict
    return put("modelVerdict", modelVerdict?.name ?: JSONObject.NULL)
        .put("modelVerdictOrigin", if (modelVerdict == null) "NO_VALIDATED_MODEL_REVIEW"
            else "PROVIDER_NORMALIZED_BEFORE_PROGRAM_FACT_CHECK")
        .put("finalVerdict", review?.verdict?.name ?: JSONObject.NULL)
        .put("programFactCheck", check?.let { JSONObject(AnalysisRunCodec.json(EvidenceFacts.reviewMap(it))) } ?: JSONObject.NULL)
        .put("programBlocked", check?.blocked ?: JSONObject.NULL)
        .put("programChangedVerdict", if (modelVerdict == null || review == null) JSONObject.NULL else modelVerdict != review.verdict)
        .put("programFactFindings", check?.let { value -> JSONArray(value.findings.map { finding -> JSONObject()
            .put("rule", finding.rule.name).put("targetStart", finding.targetStart).put("targetEnd", finding.targetEnd)
            .put("targetQuote", finding.targetQuote).put("sourceFacts", JSONArray(finding.sourceFacts))
            .put("reason", finding.reason) }) } ?: JSONObject.NULL)
}

internal fun JSONObject.withCallDiagnostics(call: AnalysisCall): JSONObject =
    put("batchKey", call.batchKey).put("attempt", call.attempt)
        .put("providerId", call.providerId).put("requestSha256", call.requestSha256)
        .put("sourceFacts", call.sourceFacts?.let { JSONObject(AnalysisRunCodec.json(EvidenceFacts.packMap(it))) } ?: JSONObject.NULL)
        .withReviewDiagnostics(call.review)

/** Raw wire opinion remains visible even when the adapter later rejects malformed IDs or fields. */
internal fun JSONObject.recordRawReviewResponse(bytes: ByteArray, responseFile: String) {
    val output = runCatching {
        val response = JSONObject(bytes.toString(Charsets.UTF_8))
        JSONObject(response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
    }.getOrNull() ?: return
    if (!output.has("verdict")) return
    val values = optJSONArray("rawModelReviews") ?: JSONArray().also { put("rawModelReviews", it) }
    values.put(JSONObject().put("response", responseFile).put("rawModelVerdict", output.opt("verdict") ?: JSONObject.NULL)
        .put("validated", false).put("interpretation", "RAW_WIRE_NOT_PROGRAM_FINAL_VERDICT"))
}
