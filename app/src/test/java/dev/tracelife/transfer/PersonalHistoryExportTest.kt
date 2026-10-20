package dev.tracelife.transfer

import dev.tracelife.actions.*
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.ClaimRef
import dev.tracelife.analysis.HistoricalFeedbackChoice
import dev.tracelife.analysis.HistoricalFeedbackResolution
import dev.tracelife.profile.*
import dev.tracelife.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipFile

/** Explicit synthetic historical identities and original user notes; no model or private content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PersonalHistoryExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = Instant.parse("2026-10-20T04:00:00Z")
    private val day = LocalDate.parse("2026-10-20")
    private val knowledgeId = "a".repeat(64)
    private val target = ClaimRef("synthetic-batch", 0, "b".repeat(64))
    private val options = ReadingExportOptions(includePersonalHistory = true, pageSize = 2, profileZoneId = "Asia/Shanghai")

    @Test fun `explicit reading history preserves independent notes and profile changes despite missing original judgment`() {
        val feedback = feedback("synthetic-run", "合成纠正原话：只能限于当时项目。")
        val comparison = HistoricalComparisonNote("synthetic-current-run", HistoricalFeedbackResolution(target, feedback.id, 1,
            HistoricalFeedbackChoice.CONFLICT, "合成对照说明：原意见仍需要保留。", now))
        val entries = export(snapshot().copy(profileRevisions = profileHistory(), profileHeads = listOf(ProfileHead("profile", 3)),
            claimFeedback = listOf(feedback), historicalComparisons = listOf(comparison)))
        val manifest = JSONObject(entries.getValue("manifest.json"))
        assertEquals(5, manifest.getJSONObject("personalHistory").getInt("included"))
        assertTrue(manifest.getJSONObject("completeness").getBoolean("profileUserTextIncluded"))
        assertTrue(manifest.getJSONObject("completeness").getBoolean("profileReleaseHistoryIncluded"))
        assertTrue(entries.keys.any { it == "profile/history/index.md" })
        val rows = data(entries)
        assertEquals(setOf("PROFILE_REVISION", "CLAIM_FEEDBACK", "HISTORICAL_COMPARISON"), rows.map { it.getString("kind") }.toSet())
        assertTrue(rows.any { it.optString("userText") == feedback.userText })
        assertTrue(rows.any { it.optString("userText") == "合成更新的独立说明" })
        assertTrue(rows.filter { it.getString("kind") == "PROFILE_REVISION" }.all { !it.getJSONObject("details").getBoolean("isCurrentUnderstanding") })
        assertTrue(rows.all { !it.getBoolean("machineClaimIncluded") })
        assertFalse(entries.containsKey("profile/index.md"))
        assertTrue(entries.getValue("README.md").contains("personal-history/index.md"))
    }

    @Test fun `history is explicit and an evidence package never acquires independent commentary`() {
        val source = snapshot().copy(claimFeedback = listOf(feedback("run", "合成独立文字只在选择后导出")))
        val unselected = export(source, ReadingExportOptions())
        assertFalse(unselected.keys.any { it.startsWith("personal-history/") })
        assertTrue(unselected.values.none { it.contains("合成独立文字只在选择后导出") })
        val evidence = export(source, options.copy(kind = ReadingPackageKind.EVIDENCE, question = "合成任务"))
        assertFalse(evidence.keys.any { it.startsWith("personal-history/") })
        assertFalse(JSONObject(evidence.getValue("manifest.json")).getJSONObject("personalHistory").getBoolean("selected"))
    }

    @Test fun `date filtering uses declared note save dates and never leaks an outside note through latest profile`() {
        val old = feedback("old-run", "合成范围外说明不能泄漏", now.minusSeconds(2 * 86_400))
        val current = feedback("new-run", "合成范围内独立说明")
        val source = snapshot().copy(claimFeedback = listOf(old, current), profileRevisions = profileHistory(), profileHeads = listOf(ProfileHead("profile", 3)))
        val entries = export(source, options.copy(scope = ExportScope(day, day)))
        assertTrue(entries.values.none { it.contains(old.userText!!) })
        assertTrue(entries.values.any { it.contains(current.userText!!) })
        assertEquals(setOf(3), data(entries).filter { it.getString("kind") == "PROFILE_REVISION" }.map { it.getInt("revision") }.toSet())
        assertEquals(3, JSONObject(entries.getValue("manifest.json")).getJSONObject("personalHistory").getJSONObject("omittedByReason").getInt("outside_date"))
    }

    @Test fun `unknown deleted target cannot prove a requested topic but independent text remains in all-topic history`() {
        val feedback = feedback("deleted-run", "合成删除来源后保留的独立原话")
        val source = snapshot().copy(claimFeedback = listOf(feedback), deletedAnalysisRuns = listOf(Deletion("deleted-run", 1, now)))
        val all = export(source)
        val row = data(all).single()
        assertEquals("deleted", row.getJSONObject("details").getString("targetState"))
        assertEquals(feedback.userText, row.getString("userText"))
        val scoped = export(source, options.copy(scope = ExportScope(topics = setOf("学习"))))
        assertTrue(scoped.values.none { it.contains(feedback.userText!!) })
        assertEquals(1, JSONObject(scoped.getValue("manifest.json")).getJSONObject("personalHistory").getJSONObject("omittedByReason").getInt("topic_not_provable"))
    }

    @Test fun `unreferenced action review is selected by review date without leaking subsequent plan`() {
        val plan = ActionDraft("合成未来计划标题不应泄漏", "合成场景", "合成执行方式", "合成观察信号", day.plusDays(3), "Asia/Shanghai")
        val first = StoredActionRevision("action", 1, plan, ActionStatus.OPEN, now.minusSeconds(10 * 86_400), now.minusSeconds(10 * 86_400))
        val reviewDate = day.minusDays(5)
        val reviewed = first.copy(revision = 2, updatedAt = now, operationId = "review", reviewedDraft = plan,
            draft = plan.copy(reviewOn = day.plusDays(7)), review = ActionReview(ActionExecution.NOT_EXECUTED,
                "合成补记回看：那天没有练习，原因未确定。", ActionDecision.CONTINUE, reviewDate))
        val source = snapshot().copy(actionRevisions = listOf(first, reviewed), actionHeads = listOf(ActionHead("action", 2)))
        val entries = export(source, options.copy(scope = ExportScope(reviewDate, reviewDate)))
        val row = data(entries).single()
        assertEquals("ACTION_REVIEW", row.getString("kind")); assertEquals(reviewDate.toString(), row.getString("occurredOn"))
        assertEquals("NOT_EXECUTED", row.getJSONObject("details").getString("execution"))
        assertEquals(1, row.getJSONObject("details").getInt("reviewedPlanRevision"))
        assertTrue(entries.values.none { it.contains(plan.title) || it.contains(plan.behavior) })
        assertTrue(JSONObject(entries.getValue("manifest.json")).getJSONObject("completeness").getBoolean("actionReviewsIncluded"))
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("completeness").getInt("actionRevisionsIncluded"))
        assertFalse(entries.containsKey("actions/index.md"))
    }

    @Test fun `permanent note deletion removes its original text from every new reading file`() {
        val feedback = feedback("run", "合成明确删除的独立原话")
        val source = snapshot().copy(claimFeedback = listOf(feedback), deletedClaimFeedback = listOf(Deletion(feedback.id, 1, now)))
        val entries = export(source)
        assertTrue(entries.values.none { it.contains(feedback.userText!!) })
        assertEquals(0, JSONObject(entries.getValue("manifest.json")).getJSONObject("personalHistory").getInt("included"))
    }

    @Test fun `experience explanation remains readable after knowledge deletion with its own topic and event date`() {
        val thread = dev.tracelife.knowledge.StoredExperienceThread("experience", 1, "合成经验", setOf("学习"), now, now)
        val change = dev.tracelife.knowledge.StoredExperienceChange(thread.id, 1, knowledgeId, 1,
            dev.tracelife.knowledge.ExperienceRelation.INITIAL, "合成独立原话：后来保留了不同解释。", now)
        val source = snapshot().copy(experienceThreads = listOf(thread), experienceChanges = listOf(change),
            deletedKnowledge = listOf(Deletion(knowledgeId, 1, now)))
        val entries = export(source, options.copy(scope = ExportScope(day, day, setOf("学习"))))
        val row = data(entries).single()
        assertEquals("EXPERIENCE_CHANGE", row.getString("kind"))
        assertEquals(change.userNote, row.getString("userText"))
        assertEquals("deleted", row.getJSONObject("details").getString("targetState"))
        assertFalse(row.getJSONObject("details").getBoolean("isCurrentUnderstanding"))
        assertFalse(row.getBoolean("machineClaimIncluded"))
        assertEquals(1, JSONObject(entries.getValue("manifest.json")).getJSONObject("completeness").getInt("experienceChangesIncluded"))
        val outside = export(source, options.copy(scope = ExportScope(day.minusDays(1), day.minusDays(1))))
        assertTrue(outside.values.none { it.contains(change.userNote!!) })
        val erased = export(snapshot().copy(deletedExperienceThreads = listOf(Deletion(thread.id, 1, now))))
        assertTrue(erased.values.none { it.contains(change.userNote!!) })
    }

    @Test fun `large history keeps every note reachable through bounded pages with explicit counts`() {
        val notes = (1..65).map { feedback("run-$it", "合成说明 $it") }
        val entries = export(snapshot().copy(claimFeedback = notes), options.copy(pageSize = 5))
        assertEquals(65, data(entries).size)
        assertEquals(65, entries.keys.count { it.startsWith("personal-history/entries/") })
        entries.filterKeys { it.startsWith("data/personal-history/") && it.endsWith(".jsonl") }.values.forEach {
            assertTrue(it.trim().lines().size <= 5)
            assertTrue(it.toByteArray().size <= 1024 * 1024)
        }
        assertTrue(entries.values.filter { it.startsWith("# ") && "personal-history" in it }.isNotEmpty())
        assertEquals(65, JSONObject(entries.getValue("manifest.json")).getJSONObject("personalHistory").getInt("included"))
    }

    private fun profileHistory(): List<StoredProfileRevision> {
        val created = StoredProfileRevision("profile", 1,
            ProfileDraft(ProfileKnowledgeRef(knowledgeId, 1), ProfileDimension.ROLES_AND_CONTEXT, ProfileLayer.CONTEXTUAL_PATTERN),
            ProfileAdoption.CANDIDATE, now.minusSeconds(3 * 86_400), now.minusSeconds(3 * 86_400), userText = "合成最初的独立说明")
        val adopted = created.copy(revision = 2, updatedAt = now.minusSeconds(2 * 86_400), operationId = "adopt",
            change = ProfileChange.ADOPTED, adoption = ProfileAdoption.ADOPTED)
        return listOf(created, adopted, adopted.copy(revision = 3, updatedAt = now, operationId = "feedback",
            change = ProfileChange.FEEDBACK, userText = "合成更新的独立说明"))
    }
    private fun feedback(run: String, text: String, at: Instant = now) = StoredClaimFeedback(
        AnalysisRunCodec.feedbackId(run, target), 1, run, target.batchKey, target.claimIndex, target.claimSha256,
        SourceStamp("synthetic-store", 1), ClaimFeedbackDecision.CORRECTED, text, at, at)
    private fun snapshot() = RepositorySnapshot("synthetic-snapshot", "synthetic-store", 1, now,
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun export(snapshot: RepositorySnapshot, options: ReadingExportOptions = this.options): Map<String, String> {
        val destination = File(temporary.root, "${UUID.randomUUID()}.zip")
        KnowledgeArchive.write(snapshot, temporary.root, destination, options)
        return ZipFile(destination).use { zip -> zip.entries().asSequence().filter { !it.isDirectory }
            .associate { it.name to zip.getInputStream(it).bufferedReader().readText() } }
    }
    private fun data(entries: Map<String, String>) = entries.filterKeys { it.startsWith("data/personal-history/") && it.endsWith(".jsonl") }
        .values.flatMap { text -> text.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList() }
}
