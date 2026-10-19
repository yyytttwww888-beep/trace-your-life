package dev.tracelife.dashboard

import android.util.JsonReader
import android.util.JsonToken
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.profile.ProfileDimension
import java.io.StringReader
import java.time.Instant

/** Strict, separately versioned settings payload for SQLite and encrypted backups. */
object DashboardPreferencesCodec {
    const val MAX_BYTES = 8_192

    fun encode(value: StoredDashboardPreferences): String {
        require(value.revision in 1 until Int.MAX_VALUE)
        val prefs = value.value.validated()
        return AnalysisRunCodec.json(mapOf("formatVersion" to 1, "revision" to value.revision,
            "updatedAt" to value.updatedAt.toString(), "zoneId" to prefs.zoneId,
            "window" to prefs.window.name, "cards" to prefs.cards.map { it.name },
            "focusDimensions" to prefs.focusDimensions.map { it.name }, "includeArchived" to prefs.includeArchived))
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    fun decode(payload: String): StoredDashboardPreferences {
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val reader = JsonReader(StringReader(payload)).apply { isLenient = false }
        fun string(): String { require(reader.peek() == JsonToken.STRING); return reader.nextString() }
        fun integer(): Int {
            require(reader.peek() == JsonToken.NUMBER)
            val raw = reader.nextString(); require(raw.matches(Regex("[1-9][0-9]*")))
            return raw.toInt()
        }
        fun strings(limit: Int): List<String> {
            reader.beginArray()
            val result = mutableListOf<String>()
            while (reader.hasNext()) { require(result.size < limit); result += string() }
            reader.endArray(); return result
        }
        val seen = mutableSetOf<String>()
        var version: Int? = null; var revision: Int? = null; var updatedAt: Instant? = null
        var zone: String? = null; var window: DashboardWindow? = null
        var cards: List<DashboardMetric>? = null; var dimensions: List<ProfileDimension>? = null
        var archived: Boolean? = null
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName(); require(seen.add(key)) { "看板偏好存在重复字段" }
            when (key) {
                "formatVersion" -> version = integer()
                "revision" -> revision = integer()
                "updatedAt" -> updatedAt = Instant.parse(string())
                "zoneId" -> zone = string()
                "window" -> window = DashboardWindow.valueOf(string())
                "cards" -> cards = strings(3).map(DashboardMetric::valueOf)
                "focusDimensions" -> dimensions = strings(ProfileDimension.entries.size).map(ProfileDimension::valueOf)
                "includeArchived" -> { require(reader.peek() == JsonToken.BOOLEAN); archived = reader.nextBoolean() }
                else -> throw IllegalArgumentException("看板偏好存在未知字段")
            }
        }
        reader.endObject()
        require(reader.peek() == JsonToken.END_DOCUMENT && seen.size == 8 && version == 1)
        return StoredDashboardPreferences(requireNotNull(revision), DashboardPreferences(requireNotNull(zone),
            requireNotNull(window), requireNotNull(cards), requireNotNull(dimensions), requireNotNull(archived)).validated(),
            requireNotNull(updatedAt)).also { encode(it) }
    }
}
