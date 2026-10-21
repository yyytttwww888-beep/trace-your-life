package dev.tracelife.providers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals

/** Test-side decoder compares complete semantic objects with the original request. */
internal object DeepSeekWireProjectionAssertions {
    fun restoreInput(value: JSONObject): JSONObject = JSONObject(value.toString()).also { input ->
        val ids = input.optJSONArray("sourceIds")
        input.optJSONArray("derivedFactConstraints")?.let { constraints ->
            repeat(constraints.length()) { index ->
                val pack = constraints.getJSONObject(index).getJSONObject("sourceFacts")
                listOf("sources" to "sourceColumns", "facts" to "factColumns").forEach { (field, columnField) ->
                    val columns = pack.getJSONArray(columnField)
                    val rows = pack.getJSONArray(field)
                    val restored = JSONArray()
                    repeat(rows.length()) { rowIndex ->
                        val row = rows.getJSONArray(rowIndex)
                        assertEquals(columns.length(), row.length())
                        restored.put(JSONObject().also { result -> repeat(columns.length()) { column ->
                            val name = columns.getString(column)
                            if (name == "sourceIdIndex") result.put("id", requireNotNull(ids).getString(row.getInt(column)))
                            else result.put(name, row.get(column))
                        } })
                    }
                    pack.put(field, restored)
                    pack.remove(columnField)
                }
            }
        }
        if (ids != null) {
            val claims = input.getJSONObject("content").getJSONArray("claims")
            repeat(claims.length()) { index ->
                val claim = claims.getJSONObject(index)
                val indexes = claim.getJSONArray("sourceIdIndexes")
                claim.put("evidenceIds", JSONArray((0 until indexes.length()).map { ids.getString(indexes.getInt(it)) }))
                claim.remove("sourceIdIndexes")
            }
            input.remove("sourceIds")
        }
    }

    fun semanticInput(value: JSONObject): JSONObject = JSONObject(value.toString()).also { input ->
        val constraints = input.optJSONArray("derivedFactConstraints")
        if (constraints != null && constraints.length() > 0) {
            listOf("requestSha256", "feedbackManifestSha256").forEach { key ->
                assertEquals(64, input.getString(key).length); input.remove(key)
            }
            input.getJSONObject("node").remove("outputSha256")
            input.getJSONObject("provenance").remove("manifestSha256")
            repeat(constraints.length()) { index ->
                val constraint = constraints.getJSONObject(index)
                constraint.getJSONObject("node").remove("outputSha256")
                val pack = constraint.getJSONObject("sourceFacts")
                assertEquals(64, pack.getString("sourcePackSha256").length)
                pack.remove("sourcePackSha256")
                val sources = pack.getJSONArray("sources")
                repeat(sources.length()) { sourceIndex ->
                    val source = sources.getJSONObject(sourceIndex)
                    listOf("sha256", "textSha256").forEach { key ->
                        assertEquals(64, source.getString(key).length); source.remove(key)
                    }
                }
            }
        }
    }

    fun assertJsonEquals(expected: JSONObject, actual: JSONObject) {
        assertEquals(StrictJson.parse(expected.toString().toByteArray(Charsets.UTF_8), 262_144),
            StrictJson.parse(actual.toString().toByteArray(Charsets.UTF_8), 262_144))
    }
}
