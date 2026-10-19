package dev.tracelife.analysis

import org.json.JSONObject

/** Strip fields introduced after v8 when deliberately constructing a historical fixture. */
internal fun JSONObject.withoutProfileMaintenance(): JSONObject = apply {
    remove("profileMaintenancePlans")
    val calls = getJSONArray("calls")
    repeat(calls.length()) { index ->
        val call = calls.getJSONObject(index)
        listOf("profileMaintenancePlanSha256", "profileReference", "profileRelation", "profileScan", "profileMaintenanceProof").forEach(call::remove)
    }
}
