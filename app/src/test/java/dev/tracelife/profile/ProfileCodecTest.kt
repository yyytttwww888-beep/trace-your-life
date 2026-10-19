package dev.tracelife.profile

import dev.tracelife.storage.AnalysisQuery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileCodecTest {
    private val now = Instant.parse("2026-10-19T01:00:00Z")
    private val draft = ProfileDraft(ProfileKnowledgeRef("a".repeat(64), 1), ProfileDimension.VALUES_AND_DIRECTION, ProfileLayer.LONG_TERM)
    private val first = StoredProfileRevision("synthetic-profile", 1, draft, ProfileAdoption.CANDIDATE, now, now, userText = "合成独立文字")

    @Test fun `every dimension and layer round trips without any copied knowledge body`() {
        ProfileDimension.entries.forEach { dimension -> ProfileLayer.entries.forEach { layer ->
            val value = first.copy(draft = draft.copy(dimension = dimension, layer = layer))
            assertEquals(value, ProfileCodec.decode(ProfileCodec.encode(value)))
        } }
        assertFalse(ProfileCodec.encode(first).contains("evidence"))
    }

    @Test fun `duplicate unknown missing fractional and unsupported format fields are rejected`() {
        val valid = ProfileCodec.encode(first)
        listOf(
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            valid.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            valid.replace("\"formatVersion\":1", "\"formatVersion\":1.0"),
            valid.replace("\"formatVersion\":1,", ""),
            valid.dropLast(1) + ",\"extra\":null}",
            valid + "{}",
        ).forEach { payload -> assertThrows("$payload", Exception::class.java) { ProfileCodec.decode(payload) } }
    }

    @Test fun `feedback capacity and identity validation reject malformed state`() {
        assertThrows(IllegalArgumentException::class.java) { ProfileCodec.encode(first.copy(userText = "x".repeat(2_001))) }
        assertThrows(IllegalArgumentException::class.java) { ProfileCodec.encode(first.copy(userText = " ")) }
        assertThrows(IllegalArgumentException::class.java) { ProfileCodec.encode(first.copy(adoption = ProfileAdoption.ADOPTED)) }
        assertThrows(IllegalArgumentException::class.java) { ProfileCodec.encode(first.copy(draft = draft.copy(knowledge = ProfileKnowledgeRef("not-a-hash", 1)))) }
        assertThrows(IllegalArgumentException::class.java) { ProfileCodec.decode(" ".repeat(ProfileValidation.MAX_REVISION_BYTES + 1)) }
        assertEquals(first.copy(userText = "\u0000".repeat(2_000)), ProfileCodec.decode(ProfileCodec.encode(first.copy(userText = "\u0000".repeat(2_000)))))
    }

    @Test fun `transition cannot edit independent feedback through organization or keep adoption after scope change`() {
        val adopted = first.copy(revision = 2, operationId = "adopt", change = ProfileChange.ADOPTED, adoption = ProfileAdoption.ADOPTED)
        ProfileValidation.validateTransition(first, adopted)
        assertThrows(IllegalArgumentException::class.java) { ProfileValidation.validateTransition(adopted,
            adopted.copy(revision = 3, operationId = "organize", change = ProfileChange.ORGANIZED, userText = "偷换用户文字")) }
        assertThrows(IllegalArgumentException::class.java) { ProfileValidation.validateTransition(adopted,
            adopted.copy(revision = 3, operationId = "organize", change = ProfileChange.ORGANIZED, draft = draft.copy(layer = ProfileLayer.RECENT_STATE))) }
        val candidate = adopted.copy(revision = 3, operationId = "organize", change = ProfileChange.ORGANIZED,
            draft = draft.copy(layer = ProfileLayer.RECENT_STATE), adoption = ProfileAdoption.CANDIDATE)
        ProfileValidation.validateTransition(adopted, candidate)
        ProfileValidation.validateCollection(listOf(first, adopted, candidate), listOf(ProfileHead(first.id, 3)))
    }

    @Test fun `collection rejects revision gaps reused operations and duplicate organization slots`() {
        val adopted = first.copy(revision = 2, operationId = "same", change = ProfileChange.ADOPTED, adoption = ProfileAdoption.ADOPTED)
        val declined = adopted.copy(revision = 3, change = ProfileChange.DECLINED, adoption = ProfileAdoption.DECLINED)
        assertThrows(IllegalArgumentException::class.java) { ProfileValidation.validateCollection(listOf(first, declined), listOf(ProfileHead(first.id, 3))) }
        assertThrows(IllegalArgumentException::class.java) { ProfileValidation.validateCollection(listOf(first, adopted, declined), listOf(ProfileHead(first.id, 3))) }
        val duplicate = first.copy(id = "different-id", draft = draft.copy(knowledge = draft.knowledge.copy(revision = 2)))
        assertThrows(IllegalArgumentException::class.java) { ProfileValidation.validateCollection(listOf(first, duplicate),
            listOf(ProfileHead(first.id, 1), ProfileHead(duplicate.id, 1))) }
    }

    @Test fun `recent projection preserves date labels and rejects future scopes without scientific scoring`() {
        val day = LocalDate.parse("2026-10-19")
        val old = AnalysisQuery(day.minusDays(29), day.minusDays(29), "UTC")
        assertEquals(ProfileRecentState.IN_WINDOW, ProfileProjection.recentState(old, ProfileLayer.RECENT_STATE, now, "Asia/Shanghai"))
        assertEquals(ProfileRecentState.OUTSIDE_WINDOW, ProfileProjection.recentState(old.copy(through = day.minusDays(30), from = day.minusDays(30)),
            ProfileLayer.RECENT_STATE, now, "Asia/Shanghai"))
        assertEquals(ProfileRecentState.OUTSIDE_WINDOW, ProfileProjection.recentState(old.copy(from = day.plusDays(1), through = day.plusDays(1)),
            ProfileLayer.RECENT_STATE, now, "Asia/Shanghai"))
        assertEquals(ProfileRecentState.NOT_APPLICABLE, ProfileProjection.recentState(old, ProfileLayer.LONG_TERM, now, "UTC"))
        assertFalse(ProfileProjection.isCurrent(ProfileAdoption.CANDIDATE, true, ProfileRecentState.IN_WINDOW))
        assertFalse(ProfileProjection.isCurrent(ProfileAdoption.ADOPTED, false, ProfileRecentState.IN_WINDOW))
        assertFalse(ProfileProjection.isCurrent(ProfileAdoption.ADOPTED, true, ProfileRecentState.UNKNOWN))
        assertThrows(IllegalArgumentException::class.java) { ProfileProjection.recentState(old, ProfileLayer.LONG_TERM, now, "UTC", 0) }
    }

    @Test fun `recent window clamps its lower boundary to supported year one`() {
        val firstDay = LocalDate.of(1, 1, 1)
        assertEquals(ProfileRecentState.IN_WINDOW, ProfileProjection.recentState(AnalysisQuery(firstDay, firstDay, "UTC"),
            ProfileLayer.RECENT_STATE, Instant.parse("0001-01-01T00:00:00Z"), "UTC", 366))
    }
}
