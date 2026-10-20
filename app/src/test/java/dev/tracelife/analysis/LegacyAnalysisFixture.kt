package dev.tracelife.analysis

import org.json.JSONObject

/** Strip fields introduced after v8 when deliberately constructing a historical fixture. */
internal fun JSONObject.withoutProfileMaintenance(): JSONObject = apply {
    remove("feedbackContext")
    remove("historicalFeedbackResolutions")
    getJSONObject("request").remove("inheritHistoricalFeedback")
    remove("profileMaintenancePlans")
    remove("actionPlan")
    getJSONObject("request").remove("actionPeriod")
    val calls = getJSONArray("calls")
    repeat(calls.length()) { index ->
        val call = calls.getJSONObject(index)
        call.remove("actionEvidence")
        call.remove("historicalFeedbackReadIds")
        call.remove("feedbackContextSha256")
        listOf("profileMaintenancePlanSha256", "profileReference", "profileRelation", "profileScan", "profileMaintenanceProof").forEach(call::remove)
    }
}
