package dev.tracelife.transfer

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.*
import dev.tracelife.storage.*
import org.json.JSONArray
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipFile

/** Synthetic knowledge, profile choices, feedback and passphrases; no real model or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ProfileTransferTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var profiles: PersonalProfile
    private lateinit var directory: File
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-19")
    private val name = "synthetic-profile-transfer-${UUID.randomUUID()}.db"
    private val password = "synthetic-profile-backup-password".toCharArray()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, name)
        profiles = PersonalProfile(repository, clock)
        directory = File(context.cacheDir, "synthetic-profile-transfer-${UUID.randomUUID()}").apply { check(mkdir()) }
    }
    @After fun tearDown() { repository.close(); context.deleteDatabase(name); directory.deleteRecursively() }

    @Test fun `logical and encrypted backup preserve full choices notes and orphan references`() {
        val first = candidate("synthetic-entry")
        val adopted = first.copy(revision = 2, adoption = ProfileAdoption.ADOPTED, operationId = "synthetic-adopt", change = ProfileChange.ADOPTED)
        val feedback = adopted.copy(revision = 3, operationId = "synthetic-feedback", change = ProfileChange.FEEDBACK,
            userText = "合成独立背景：来源已经不可用，但我的话仍保留。")
        val source = snapshot(listOf(first, adopted, feedback), listOf(Deletion("synthetic-deleted", 3, clock.now)))
        val encoded = encode(source)
        val json = JSONObject(encoded.toString(Charsets.UTF_8))
        assertEquals(6, json.getInt("formatVersion"))
        assertEquals(7, json.getInt("sourceSchemaVersion"))
        assertEquals(source, SnapshotJsonCodec.read(encoded.inputStream()))
        SnapshotValidation.validate(source)
        val backup = File(directory, "profile.tlb")
        BackupArchive.write(source, attachmentDirectory(), backup, password)
        assertEquals(source, BackupArchive.read(backup, File(directory, "restored"), password))
    }

    @Test fun `five legacy versions omit profile fields and mixed version payloads are rejected`() {
        val source = snapshot(emptyList())
        for (version in 1..5) {
            val old = JSONObject(encode(source).toString(Charsets.UTF_8)).apply {
                put("formatVersion", version); put("sourceSchemaVersion", version + 1)
                remove("profileRevisions"); remove("profileHeads"); remove("deletedProfiles")
                if (version < 5) { remove("actionRevisions"); remove("actionHeads"); remove("deletedActions") }
                if (version < 4) { remove("knowledgeRevisions"); remove("knowledgeHeads"); remove("deletedKnowledge") }
                if (version < 3) { remove("claimFeedback"); remove("deletedClaimFeedback") }
                if (version < 2) { remove("analysisRuns"); remove("analysisGeneration"); remove("deletedAnalysisRuns") }
            }
            assertEquals(source.copy(sourceSchemaVersion = version + 1), SnapshotJsonCodec.read(old.toString().byteInputStream()))
            old.put("profileHeads", JSONArray())
            assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(old.toString().byteInputStream()) }
        }
        val current = JSONObject(encode(snapshot(listOf(candidate("synthetic-entry")))).toString(Charsets.UTF_8))
        listOf("profileRevisions", "profileHeads", "deletedProfiles").forEach { field ->
            val missing = JSONObject(current.toString()).apply { remove(field) }
            assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(missing.toString().byteInputStream()) }
        }
        val extra = JSONObject(current.toString()).apply {
            put("profileRevisions", JSONArray().put(JSONObject(getJSONArray("profileRevisions").getString(0)).put("unknown", true).toString()))
        }
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(extra.toString().byteInputStream()) }
        val oversized = JSONObject(current.toString()).put("profileRevisions", JSONArray().put("x".repeat(ProfileValidation.MAX_REVISION_BYTES + 1)))
        assertThrows(InvalidBackupException::class.java) { SnapshotJsonCodec.read(oversized.toString().byteInputStream()) }
    }

    @Test fun `profile chain head deletion and duplicate slot corruption fail before restore`() {
        val first = candidate("synthetic-entry")
        val valid = snapshot(listOf(first))
        listOf(valid.copy(profileHeads = listOf(ProfileHead(first.id, 2))),
            valid.copy(profileRevisions = listOf(first.copy(revision = 2, operationId = "skip", change = ProfileChange.FEEDBACK))),
            valid.copy(deletedProfiles = listOf(Deletion(first.id, 1, clock.now))),
            valid.copy(deletedProfiles = listOf(Deletion("gone", 1, clock.now), Deletion("gone", 2, clock.now))),
            valid.copy(sourceSchemaVersion = 6),
            snapshot(listOf(first, candidate("duplicate-slot"))),
        ).forEach { invalid -> assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(invalid) } }
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { SnapshotJsonCodec.write(valid.copy(profileHeads = emptyList()), output) }
        assertEquals(0, output.size())
    }

    @Test fun `existing knowledge references must allow profile use while missing revisions stay soft`() {
        val note = publishKnowledge(category = KnowledgeCategory.KNOWLEDGE_NOTE)
        val base = repositorySnapshot()
        val profile = candidate("synthetic-profile", ref = ProfileKnowledgeRef(note.id, note.revision))
        assertThrows(InvalidBackupException::class.java) { SnapshotValidation.validate(base.copy(
            profileRevisions = listOf(profile), profileHeads = listOf(ProfileHead(profile.id, 1)))) }
        val missing = profile.copy(draft = profile.draft.copy(knowledge = profile.draft.knowledge.copy(revision = 99)))
        SnapshotValidation.validate(base.copy(profileRevisions = listOf(missing), profileHeads = listOf(ProfileHead(missing.id, 1))))
    }

    @Test fun `reading package routes eight dimensions to same included knowledge without copied bodies or user text`() {
        val knowledge = publishKnowledge()
        repeat(3) { index ->
            val created = profiles.create(draft(knowledge, ProfileDimension.entries[index]), "profile-$index", "NEVER_EXPORT_PROFILE_BACKGROUND_$index")
            profiles.adopt(created.value.id, 1, "adopt-$index")
        }
        val content = reading(repositorySnapshot(), ReadingExportOptions(pageSize = 1, profileZoneId = "Asia/Shanghai"))
        val manifest = JSONObject(content.getValue("manifest.json"))
        assertEquals(3, manifest.getJSONObject("profile").getInt("included"))
        assertEquals("Asia/Shanghai", manifest.getJSONObject("profile").getString("zoneId"))
        assertEquals(30, manifest.getJSONObject("profile").getInt("recentDays"))
        assertFalse(manifest.getJSONObject("profile").getBoolean("userTextIncluded"))
        assertFalse(content.values.any { "NEVER_EXPORT_PROFILE_BACKGROUND" in it })
        assertFalse(content.filterKeys { it.startsWith("profile/") || it.startsWith("data/profiles/") }.values
            .any { "SYNTHETIC_SHARED_CLAIM" in it || "SYNTHETIC_SOURCE_BODY" in it })
        assertEquals(8, ProfileDimension.entries.count {
            "profile/${it.name.lowercase().replace('_', '-')}/index.md" in content
        })
        assertTrue(content.keys.any { it.startsWith("indexes/profile-ids/") && it.endsWith(".jsonl") })
        val rows = profileRows(content)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.getJSONObject("knowledge").getString("id") == knowledge.id &&
            it.getJSONObject("knowledge").getInt("revision") == knowledge.revision && !it.has("userText") })
        assertEquals(3, content.keys.count { it.startsWith("data/profiles/") && it.endsWith(".jsonl") })
        verifyLinks(content)
        verifyInventory(content)
    }

    @Test fun `candidate declined superseded and missing knowledge entries are never exported as current`() {
        val knowledge = publishKnowledge()
        ProfileDimension.entries.take(4).forEachIndexed { index, dimension ->
            val created = profiles.create(draft(knowledge, dimension), "profile-$index", "NEVER_EXPORT_PRIVATE")
            when (index) {
                0 -> profiles.adopt(created.value.id, 1, "adopt")
                1 -> profiles.decline(created.value.id, 1, "decline")
                2 -> profiles.supersede(created.value.id, 1, "supersede")
            }
        }
        val base = repositorySnapshot()
        val missingFirst = candidate("missing-profile", ref = ProfileKnowledgeRef("b".repeat(64), 1))
        val missingAdopted = missingFirst.copy(revision = 2, operationId = "adopt", change = ProfileChange.ADOPTED, adoption = ProfileAdoption.ADOPTED)
        val source = base.copy(profileRevisions = base.profileRevisions + listOf(missingFirst, missingAdopted),
            profileHeads = base.profileHeads + ProfileHead(missingFirst.id, 2))
        val content = reading(source)
        val profile = JSONObject(content.getValue("manifest.json")).getJSONObject("profile")
        assertEquals(1, profile.getInt("included"))
        assertEquals(3, profile.getJSONObject("omittedByReason").getInt("not_adopted"))
        assertEquals(1, profile.getJSONObject("omittedByReason").getInt("knowledge_not_included"))
        assertEquals(listOf("profile-0"), profileRows(content).map { it.getString("id") })
    }

    @Test fun `evidence and narrower scope packages omit profile rather than widening knowledge sources`() {
        val knowledge = publishKnowledge()
        adopted(knowledge)
        val source = repositorySnapshot()
        listOf(ReadingExportOptions(kind = ReadingPackageKind.EVIDENCE, question = "合成任务"),
            ReadingExportOptions(scope = ExportScope(from = day.minusDays(2), through = day.minusDays(1))),
            ReadingExportOptions(scope = ExportScope(topics = setOf("unrelated-topic"))),
        ).forEach { options ->
            val content = reading(source, options)
            assertFalse(content.keys.any { it.startsWith("profile/") || it.startsWith("data/profiles/") })
            val metadata = JSONObject(content.getValue("manifest.json")).getJSONObject("profile")
            assertEquals(0, metadata.getInt("included"))
            assertEquals(1, metadata.getInt("omitted"))
            assertTrue(metadata.isNull("validUntil"))
        }
    }

    @Test fun `recent window uses explicit export timezone and matches the runtime convention`() {
        val date = LocalDate.parse("2026-09-19")
        val knowledge = publishKnowledge(date = date)
        adopted(knowledge, layer = ProfileLayer.RECENT_STATE)
        val source = repositorySnapshot()
        val utc = reading(source, ReadingExportOptions(profileZoneId = "UTC"))
        val west = reading(source, ReadingExportOptions(profileZoneId = "America/Los_Angeles"))
        assertEquals(0, JSONObject(utc.getValue("manifest.json")).getJSONObject("profile").getInt("included"))
        assertEquals(1, JSONObject(west.getValue("manifest.json")).getJSONObject("profile").getInt("included"))
        assertEquals(0, profiles.open(ProfileQuery(zoneId = "UTC")).total)
        assertEquals(1, profiles.open(ProfileQuery(zoneId = "America/Los_Angeles")).total)
        assertThrows(IllegalArgumentException::class.java) { ReadingExportOptions(profileRecentDays = 0) }
        assertThrows(IllegalArgumentException::class.java) { ReadingExportOptions(profileRecentDays = 367) }
        assertThrows(java.time.DateTimeException::class.java) { ReadingExportOptions(profileZoneId = "not/a-zone") }
    }

    @Test fun `adoption after snapshot time and source invalidation cannot leak into current reading view`() {
        val knowledge = publishKnowledge()
        adopted(knowledge)
        val source = repositorySnapshot()
        val future = source.copy(profileRevisions = source.profileRevisions.map { value ->
            if (value.revision == 2) value.copy(updatedAt = clock.now.plusSeconds(1)) else value
        })
        val futurePackage = reading(future)
        assertEquals(0, JSONObject(futurePackage.getValue("manifest.json")).getJSONObject("profile").getInt("included"))
        repository.updateRecord("synthetic-source", 1, RecordDraft("合成修订", Occurrence(day)))
        val stale = reading(repositorySnapshot())
        assertEquals(0, JSONObject(stale.getValue("manifest.json")).getJSONObject("profile").getInt("included"))
        assertEquals("NEVER_EXPORT_PROFILE_BACKGROUND", profiles.get("synthetic-profile")!!.value.userText)
    }

    @Test fun `profile only target is nonempty and user choice changes invalidate backup preview`() {
        val targetName = "synthetic-profile-target-${UUID.randomUUID()}.db"
        try {
            LifeRepository(context, clock, targetName).use { target ->
                val independent = snapshot(listOf(candidate("local-profile")))
                target.restoreSnapshot(independent, attachmentDirectory(), target.currentGeneration())
                val file = File(directory, "empty-source.tlb")
                DataTransfer(context, repository, clock).exportBackup(file, password)
                val transfer = DataTransfer(context, target, clock)
                val preview = transfer.previewBackup(file, password)
                assertEquals(0, preview.profiles)
                assertTrue(preview.targetHasData)
                assertThrows(IllegalStateException::class.java) { transfer.restoreBackup(file, password, preview) }
                PersonalProfile(target, clock).feedback("local-profile", 1, "new-feedback", "合成本地新文字")
                assertThrows(StaleSnapshotException::class.java) { transfer.restoreBackup(file, password, preview, replaceExisting = true) }
                val localBackup = File(directory, "local-profile.tlb")
                transfer.exportBackup(localBackup, password)
                assertEquals(1, DataTransfer(context, repository, clock).previewBackup(localBackup, password).profiles)
            }
        } finally { context.deleteDatabase(targetName) }
    }

    @Test fun `profile publication checks midnight and backwards clock only at final atomic creation`() {
        adopted(publishKnowledge())
        val transfer = DataTransfer(context, repository, clock)
        listOf(clock.now.atZone(ZoneId.of("UTC")).toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant(),
            clock.now.minusSeconds(1)).forEachIndexed { index, finalTime ->
            clock.duringPublication = finalTime
            val destination = File(directory, "invalid-final-$index.zip")
            assertThrows(StaleSnapshotException::class.java) { transfer.exportReadingPackage(destination) }
            assertFalse(destination.exists())
            assertCleanStaging()
        }
    }

    @Test fun `profile knowledge expiry checked again at final file publication`() {
        val expiry = clock.now.plusSeconds(60)
        adopted(publishKnowledge(expiresAt = expiry))
        clock.duringPublication = expiry
        val destination = File(directory, "expired-final.zip")
        assertThrows(StaleSnapshotException::class.java) { DataTransfer(context, repository, clock).exportReadingPackage(destination) }
        assertFalse(destination.exists())
        assertCleanStaging()
    }

    @Test fun `reading without included profile does not invent a profile midnight deadline`() {
        val knowledge = publishKnowledge()
        profiles.create(draft(knowledge), "candidate")
        clock.duringPublication = clock.now.plusSeconds(172_800)
        val destination = File(directory, "no-profile.zip")
        DataTransfer(context, repository, clock).exportReadingPackage(destination)
        assertTrue(destination.isFile)
        assertTrue(JSONObject(contents(destination).getValue("manifest.json")).getJSONObject("profile").isNull("validUntil"))
    }

    private fun adopted(knowledge: StoredKnowledgeRevision, layer: ProfileLayer = ProfileLayer.LONG_TERM): StoredProfileRevision {
        profiles.create(draft(knowledge).copy(layer = layer), "synthetic-profile", "NEVER_EXPORT_PROFILE_BACKGROUND")
        return profiles.adopt("synthetic-profile", 1, "synthetic-adopt").value
    }
    private fun draft(value: StoredKnowledgeRevision, dimension: ProfileDimension = ProfileDimension.VALUES_AND_DIRECTION) =
        ProfileDraft(ProfileKnowledgeRef(value.id, value.revision), dimension, ProfileLayer.LONG_TERM)
    private fun candidate(id: String, ref: ProfileKnowledgeRef = ProfileKnowledgeRef("a".repeat(64), 1)) =
        StoredProfileRevision(id, 1, ProfileDraft(ref, ProfileDimension.VALUES_AND_DIRECTION, ProfileLayer.LONG_TERM),
            ProfileAdoption.CANDIDATE, clock.now, clock.now, userText = "合成独立文字")
    private fun snapshot(values: List<StoredProfileRevision>, deleted: List<Deletion> = emptyList()) = RepositorySnapshot(
        "synthetic-snapshot", "synthetic-store", 0, clock.now, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        profileRevisions = values, profileHeads = values.groupBy { it.id }.map { (id, chain) -> ProfileHead(id, chain.maxOf { it.revision }) }, deletedProfiles = deleted,
    )
    private fun repositorySnapshot() = repository.captureSnapshot(attachmentDirectory())
    private fun attachmentDirectory() = File(directory, "attachments-${UUID.randomUUID()}").apply { check(mkdir()) }
    private fun encode(source: RepositorySnapshot) = ByteArrayOutputStream().also { SnapshotJsonCodec.write(source, it) }.toByteArray()
    private fun reading(source: RepositorySnapshot, options: ReadingExportOptions = ReadingExportOptions()): Map<String, String> {
        val destination = File(directory, "reading-${UUID.randomUUID()}.zip")
        KnowledgeArchive.write(source, attachmentDirectory(), destination, options)
        return contents(destination)
    }
    private fun contents(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry -> entry.name to zip.getInputStream(entry).bufferedReader().use { it.readText() } }
    }
    private fun profileRows(content: Map<String, String>) = content.filterKeys { it.startsWith("data/profiles/") && it.endsWith(".jsonl") }
        .values.flatMap { it.lineSequence().filter(String::isNotBlank).map(::JSONObject).toList() }
    private fun verifyLinks(content: Map<String, String>) {
        content.filterKeys { it.endsWith(".md") }.forEach { (path, text) ->
            Regex("\\[[^]]*]\\(([^)]+)\\)").findAll(text).forEach { match ->
                val target = (Paths.get(path).parent ?: Paths.get("")).resolve(match.groupValues[1]).normalize().toString().replace('\\', '/')
                assertTrue("Missing $path -> $target", target in content)
            }
        }
        profileRows(content).forEach { row ->
            assertTrue(row.getString("path") in content)
            assertTrue(row.getJSONObject("knowledge").getString("path") in content)
        }
    }
    private fun verifyInventory(content: Map<String, String>) {
        val root = JSONObject(content.getValue("manifest.json")).getJSONObject("inventory")
        var path: String? = root.getString("path")
        var expected: String? = root.getString("sha256")
        val listed = mutableSetOf<String>()
        while (path != null) {
            val text = content.getValue(path)
            assertEquals(expected, hash(text))
            val page = JSONObject(text)
            val entries = page.getJSONArray("entries")
            repeat(entries.length()) { index ->
                val item = entries.getJSONObject(index)
                val leaf = content.getValue(item.getString("path"))
                assertEquals(item.getString("sha256"), hash(leaf))
                leaf.lineSequence().filter(String::isNotBlank).map(::JSONObject).forEach { file ->
                    val filePath = file.getString("path")
                    assertTrue(listed.add(filePath))
                    assertEquals(file.getString("sha256"), hash(content.getValue(filePath)))
                    assertEquals(file.getInt("bytes"), content.getValue(filePath).toByteArray().size)
                }
            }
            path = if (page.isNull("next")) null else page.getString("next")
            expected = if (page.isNull("nextSha256")) null else page.getString("nextSha256")
        }
        assertEquals(content.keys.filter { !it.startsWith("inventory/") && it != "manifest.json" }.toSet(), listed)
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun assertCleanStaging() {
        assertTrue(File(context.noBackupFilesDir, "transfer-staging").listFiles().orEmpty().isEmpty())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".pending") })
    }

    private fun publishKnowledge(date: LocalDate = day, category: KnowledgeCategory = KnowledgeCategory.OBSERVATION,
        expiresAt: Instant? = null): StoredKnowledgeRevision {
        repository.createRecord(RecordDraft("SYNTHETIC_SOURCE_BODY", Occurrence(date), topics = setOf("learning")), "synthetic-source")
        val analysis = ControlledAnalysis(repository, clock)
        val scope = AnalysisQuery(date, date, "UTC")
        analysis.start(AnalysisRequest("合成知识", scope, expiresAt = expiresAt), "synthetic-run")
        val provider = SyntheticProvider()
        analysis.runBatch("synthetic-run", "extract", listOf(EvidenceSelection("synthetic-source", "body")), provider)
        val claim = analysis.claimRef("synthetic-run", "extract", 0)
        analysis.reviewClaim("synthetic-run", "review", claim, provider = provider)
        return KnowledgeLibrary(repository, clock).publish("synthetic-run", claim, "review", category,
            analysis.task("synthetic-run").stored.version, topics = setOf("learning"))
    }
    private class SyntheticProvider : AnalysisProvider, SemanticReviewProvider {
        override val id = "synthetic-profile-export"
        override val contextWindowTokens = 100_000
        override fun countInputTokens(request: ModelRequest) = request.payloadJson.toByteArray().size
        override fun complete(request: ModelRequest): BatchProposal {
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return BatchProposal(listOf(ProposedClaim("SYNTHETIC_SHARED_CLAIM", listOf(id), ClaimKind.REPORTED_EXPERIENCE)))
        }
        override fun prepareReview(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson, countInputTokens(request))
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            val id = JSONObject(request.payloadJson).getJSONArray("evidence").getJSONObject(0).getString("id")
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, listOf(id)))
        }
    }
    private class MutableClock(var now: Instant = Instant.parse("2026-10-19T00:30:00Z")) : Clock() {
        var duringPublication: Instant? = null
        override fun instant(): Instant = duringPublication?.takeIf {
            Thread.currentThread().stackTrace.any { frame -> frame.className.contains("LifeRepository") &&
                (frame.methodName == "publishDerivedFile" || frame.className.contains("publishDerivedFile")) }
        } ?: now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
}
