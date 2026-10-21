package dev.tracelife.quality

import android.content.Context
import dev.tracelife.analysis.*
import dev.tracelife.conversation.ConversationWorkflow
import dev.tracelife.conversation.ConversationTurn
import dev.tracelife.knowledge.*
import dev.tracelife.model.RecordFilter
import dev.tracelife.providers.*
import dev.tracelife.storage.*
import dev.tracelife.transfer.KnowledgeArchive
import dev.tracelife.transfer.ReadingExportOptions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipFile
import javax.crypto.KeyGenerator

/** Continuous fictional use, with independent operator selection before each save.
 * Requires BOTH quality opt-in and TRACE_LIFE_JOURNEY_REVIEW=true. No automatic retry.
 * TRACE_LIFE_JOURNEY_RESUME_DIR explicitly resumes a validated synthetic FINAL_READY copy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DeepSeekLifeJourneyLiveTest {
    @Test fun `daily weekly and quarterly understanding remains connected to original experiences`() {
        assumeTrue(System.getProperty("traceLife.deepSeek.quality") == "true" &&
            System.getenv("TRACE_LIFE_JOURNEY_REVIEW") == "true")
        val report = SyntheticQualityReport("deepseek-life-journey", "continuous-life-v1")
        val directory = report.directory
        val repositoryRoot = File(requireNotNull(System.getProperty("traceLife.repositoryRoot"))).canonicalFile
        val resume = System.getenv("TRACE_LIFE_JOURNEY_RESUME_DIR")?.let { path ->
            require(path.isNotBlank()) { "An explicit resume directory must not be blank" }
            readResume(File(path), repositoryRoot, directory)
        }
        val priorPaid = resume?.paidRequests ?: 0
        val context: Context = RuntimeEnvironment.getApplication()
        val clock = JourneyClock()
        val database = "synthetic-life-journey-${UUID.randomUUID()}.db"
        val keyDirectory = File(context.noBackupFilesDir, database + "-key")
        val wrapping = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val credentials = DeepSeekCredentials(keyDirectory, context.packageName) { wrapping }
        val keyFile = File(System.getProperty("traceLife.deepSeekKeyFile") ?: File(
            System.getProperty("user.home"), ".config/trace-life/deepseek-api-key").path).canonicalFile
        check(!keyFile.toPath().startsWith(repositoryRoot.toPath()) && keyFile.length() in 8..514)
        val bytes = keyFile.readBytes()
        val secret = try { bytes.toString(Charsets.UTF_8).trim().toCharArray() } finally { bytes.fill(0) }
        var paid = 0
        val transport = HttpsDeepSeekTransport()
        val actualProvider = DeepSeekProvider(credentials, DeepSeekModel.FLASH, transport = DeepSeekTransport { body, key ->
            check(priorPaid + paid < 40) { "Continuous journey reached its cumulative 40-request ceiling" }
            paid++
            File(directory, "request-$paid.json").writeText(body)
            transport.execute(body, key).also { response ->
                File(directory, "response-$paid.json").writeBytes(response.body)
                File(directory, "request-count.txt").writeText(paid.toString())
            }
        })
        var preparation = 0
        fun capture(request: PreparedModelRequest): PreparedModelRequest {
            val number = ++preparation
            File(directory, "prepared-$number-original.json").writeText(request.original.payloadJson)
            File(directory, "prepared-$number-wire.json").writeText(request.payloadJson)
            File(directory, "prepared-$number-metrics.json").writeText(JSONObject()
                .put("budgetInputTokens", request.inputTokens).put("inputBytes", request.payloadJson.toByteArray(Charsets.UTF_8).size)
                .put("wireSha256", AnalysisRunCodec.sha256(request.payloadJson)).toString(2))
            return request
        }
        val provider = object : AnalysisProvider by actualProvider, SemanticReviewProvider by actualProvider,
            SemanticReductionScanProvider by actualProvider, SemanticNavigationProvider by actualProvider {
            override val id = actualProvider.id
            override val contextWindowTokens = actualProvider.contextWindowTokens
            override fun prepare(request: ModelRequest) = capture(actualProvider.prepare(request))
            override fun prepareReview(request: ModelRequest) = capture(actualProvider.prepareReview(request))
            override fun prepareScan(request: ModelRequest) = capture(actualProvider.prepareScan(request))
            override fun prepareNavigation(request: ModelRequest) = capture(actualProvider.prepareNavigation(request))
        }
        val trace = JSONArray()
        val savedIds = mutableListOf<String>()
        val turns = mutableListOf<ConversationTurn>()
        val selectedIndexes = mutableListOf<Int>()
        val seeded = mutableSetOf<String>()
        val experienceId = "synthetic-learning-journey"
        var archiveSyntheticDatabase = resume == null
        var resumedFollowup: ConversationTurn? = null
        try {
            if (resume != null) {
                val target = context.getDatabasePath(database)
                check(!target.exists())
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                resume.database.copyTo(target)
                clock.at(SyntheticLifeJourney.threeMonths.through)
                LifeRepository(context, clock, database).use { repository ->
                    resumedFollowup = validateResumeStore(repository, clock, resume, turns, savedIds, selectedIndexes, experienceId)
                }
                archiveSyntheticDatabase = true
                seeded += SyntheticLifeJourney.all.map { it.id }
                File(directory, "resume.json").writeText(JSONObject().put("synthetic", true)
                    .put("fixtureVersion", "continuous-life-v1").put("sourceFixtureVersion", SyntheticLifeJourney.VERSION)
                    .put("resumeFrom", resume.directory.path).put("priorPaidRequests", priorPaid)
                    .put("sourceDatabaseSha256", resume.databaseSha256).put("originalDatabaseModified", false).toString(2))
                resume.rows.take(resume.completedStages).forEach { prior ->
                    val preserved = JSONObject(prior.toString()).put("resumeFrom", resume.directory.path)
                        .put("priorPaidRequests", cumulativeStageRequests(prior)).put("paidRequests", 0)
                        .put("reusedCompletedStage", true)
                    trace.put(preserved); report.add(preserved)
                }
                File(directory, "journey.json").writeText(trace.toString(2))
            }
            credentials.configure(secret); secret.fill('\u0000')
            SyntheticLifeJourney.stages.forEachIndexed { stageIndex, stage ->
                if (resume != null && stageIndex < resume.completedStages) return@forEachIndexed
                clock.at(stage.through)
                val row = JSONObject().put("id", stage.id).put("question", stage.question)
                    .put("from", stage.from).put("through", stage.through)
                    .put("synthetic", true).put("semanticStatus", "REQUIRES_INDEPENDENT_REVIEW")
                if (resume != null) row.put("resumeFrom", resume.directory.path)
                    .put("priorPaidRequests", cumulativeStageRequests(resume.rows[stageIndex]))
                    .put("priorStageReport", JSONObject(resume.rows[stageIndex].toString()))
                val before = paid
                try {
                    // Reopen the actual repository between stages, as on later visits to the app.
                    LifeRepository(context, clock, database).use { repository ->
                        SyntheticLifeJourney.all.filter { it.date <= stage.through && it.id !in seeded }.forEach { source ->
                            repository.createRecord(source.draft, source.id)
                            seeded += source.id
                        }
                        val analysis = ControlledAnalysis(repository, clock)
                        val knowledge = KnowledgeLibrary(repository, clock)
                        val reflection = ReflectionWorkflow(repository, analysis, AnalysisFeedback(repository, clock), knowledge)
                        val workflow = ConversationWorkflow(repository, analysis, reflection, clock)
                        val scope = AnalysisQuery(stage.from, stage.through, clock.zone.id)
                        val turn = if (resume != null) turns[stageIndex]
                            else workflow.submit(workflow.prepare(stage.question, scopeOverride = scope)).also { turns += it }
                        val run = if (resume != null) reflection.inspect(turn.runId)
                            else workflow.process(turn, provider, maxRequests = 5).also { assertEquals(ReflectionStatus.READY, it.status) }.run
                        row.put("status", run.status.name).put("runId", turn.runId)
                        assertEquals(ReflectionStatus.READY, run.status)
                        val coverage = requireNotNull(run.coverage)
                        val expected = SyntheticLifeJourney.all.filter { it.date in stage.from..stage.through }
                        assertEquals(expected.size, coverage.records)
                        assertEquals(coverage.textCodePoints, coverage.completedTextCodePoints)
                        assertEquals(0L, coverage.remainingTextFragments)
                        assertTrue(run.findings.isNotEmpty())
                        val entries = analysis.task(turn.runId).state.knowledgeContext?.entries.orEmpty()
                        row.put("background", JSONArray(entries.map { JSONObject().put("kind", it.kind.name)
                            .put("id", it.id).put("knowledgeId", it.knowledgeId).put("revision", it.revision) }))
                        if (savedIds.isNotEmpty()) assertTrue("Earlier understanding must remain available", entries.any { it.knowledgeId in savedIds })
                        row.put("coverageRecords", coverage.records).put("coverageCodePoints", coverage.completedTextCodePoints)
                            .put("findings", findings(reflection, turn.runId, run))
                        File(directory, "${stage.id}-candidates.json").writeText(row.toString(2))
                        File(directory, "${stage.id}-ledger-before-save.json").writeText(AnalysisRunCodec.encode(analysis.task(turn.runId).state))
                        if (resume == null) println("JOURNEY_AWAITING_REVIEW ${directory.path}/${stage.id}-candidates.json")
                        val choice = if (resume != null) JSONObject(resume.rows[stageIndex].getJSONObject("independentSelection").toString())
                            else awaitChoice(directory, stage.id)
                        if (resume != null) File(directory, "${stage.id}-choice.json").writeText(choice.toString(2))
                        val index = choice.getInt("findingIndex")
                        val finding = run.findings[index]
                        assertEquals("Decision must bind the exact displayed text", AnalysisRunCodec.sha256(finding.text), choice.getString("textSha256"))
                        assertTrue(choice.getString("reason").isNotBlank())
                        if (resume == null) selectedIndexes += index else assertEquals(selectedIndexes[stageIndex], index)
                        row.put("independentSelection", choice)
                        if (resume == null) reflection.respond(turn.runId, finding.target, analysis.task(turn.runId).stored.version, ClaimFeedbackDecision.CONFIRMED)
                        else {
                            assertEquals(ClaimFeedbackDecision.CONFIRMED, finding.feedback?.feedback?.decision)
                            assertEquals(ReductionReviewStatus.FINAL_READY, finding.reviewProgress?.status)
                            assertTrue(requireNotNull(finding.reviewProgress).allOriginalsScanned)
                        }
                        val scansBefore = analysis.task(turn.runId).state.calls.filter { it.purpose == CallPurpose.REDUCTION_SCAN }
                        val kept = reflection.reviewAndSave(turn.runId, finding.target, provider, provider, maxRequests = 5, topics = setOf("学习"))
                        if (resume != null) assertEquals("A resumed final review must reuse every completed scan", scansBefore,
                            analysis.task(turn.runId).state.calls.filter { it.purpose == CallPurpose.REDUCTION_SCAN })
                        row.put("saveStatus", kept.status.name)
                        val current = kept.run.findings.single { it.target == finding.target }
                        row.put("verdict", current.reviewVerdict?.name ?: JSONObject.NULL)
                            .put("knowledgeId", current.knowledgeId ?: JSONObject.NULL)
                        val saved = requireNotNull(current.knowledgeId) { "Selected useful finding was not saved: ${current.reviewVerdict}, ${current.reviewProgress}" }
                        assertEquals(KnowledgeEvidenceState.CURRENT, knowledge.get(saved)!!.evidenceState)
                        savedIds += saved
                        val continuity = KnowledgeContinuity(repository, clock)
                        if (savedIds.size == 1) continuity.create(saved, 1, "学习方法如何随任务变化", setOf("学习"), choice.getString("reason"), experienceId)
                        else continuity.append(experienceId, repository.experienceThread(experienceId)!!.version, saved, 1,
                            ExperienceRelation.SUPPLEMENT, choice.getString("reason"))
                        val history = requireNotNull(continuity.get(experienceId, limit = 100))
                        assertEquals(savedIds.toSet(), history.changes.map { it.change.knowledgeId }.toSet())
                        assertTrue(history.changes.all { it.knowledge?.evidenceState == KnowledgeEvidenceState.CURRENT })
                        val paidBeforeReading = paid
                        turns.forEach { old ->
                            val reopened = reflection.inspect(old.runId)
                            assertTrue(reopened.findings.any { it.knowledgeId in savedIds })
                            reopened.findings.forEach { evidence(reflection, old.runId, it.target) }
                        }
                        assertEquals(paidBeforeReading, paid)
                        File(directory, "${stage.id}-ledger.json").writeText(AnalysisRunCodec.encode(analysis.task(turn.runId).state))
                        row.put("programmaticStatus", "PASS")
                    }
                } catch (failure: Throwable) {
                    row.put("programmaticStatus", "FAIL").put("failureType", failure.javaClass.simpleName)
                        .put("failureMessage", failure.message)
                    throw failure
                } finally {
                    row.put("paidRequests", paid - before)
                    trace.put(row); File(directory, "journey.json").writeText(trace.toString(2)); report.add(row)
                }
            }
            LifeRepository(context, clock, database).use { repository ->
                val analysis = ControlledAnalysis(repository, clock)
                val reflection = ReflectionWorkflow(repository, analysis, AnalysisFeedback(repository, clock), KnowledgeLibrary(repository, clock))
                val workflow = ConversationWorkflow(repository, analysis, reflection, clock)
                val parent = turns.last()
                val follow = resumedFollowup ?: workflow.submit(workflow.prepare(FOLLOWUP_QUESTION,
                    threadId = parent.threadId, parentTurnId = parent.id, selectedFindingIndex = selectedIndexes.last()))
                val callsBeforeFollowup = analysis.task(follow.runId).state.calls
                val beforeFollowup = paid
                val followRow = JSONObject().put("id", "followup").put("question", follow.question)
                    .put("runId", follow.runId).put("synthetic", true)
                    .put("semanticStatus", "REQUIRES_INDEPENDENT_REVIEW")
                resume?.followup?.let { prior -> followRow.put("resumeFrom", resume.directory.path)
                    .put("priorPaidRequests", cumulativeStageRequests(prior)).put("priorStageReport", JSONObject(prior.toString())) }
                try {
                    val pass = workflow.process(follow, provider, maxRequests = 5)
                    followRow.put("status", pass.status.name)
                        .put("findings", findings(reflection, follow.runId, pass.run))
                    assertEquals(ReflectionStatus.READY, pass.status)
                    if (resumedFollowup != null) {
                        val calls = analysis.task(follow.runId).state.calls
                        assertEquals("Reuse the original navigation and both leaf batches", callsBeforeFollowup, calls.take(callsBeforeFollowup.size))
                        assertEquals(listOf(CallPurpose.REDUCTION), calls.drop(callsBeforeFollowup.size).map { it.purpose })
                    }
                    assertTrue(pass.run.findings.isNotEmpty())
                    assertEquals(parent.runId, analysis.task(follow.runId).state.request.conversationContext!!.parentRunId)
                    followRow.put("programmaticStatus", "PASS")
                } catch (failure: Throwable) {
                    followRow.put("programmaticStatus", "FAIL").put("failureType", failure.javaClass.simpleName)
                        .put("failureMessage", failure.message)
                    throw failure
                } finally {
                    followRow.put("paidRequests", paid - beforeFollowup)
                    File(directory, "followup.json").writeText(followRow.toString(2))
                    File(directory, "followup-ledger.json").writeText(AnalysisRunCodec.encode(analysis.task(follow.runId).state))
                    trace.put(followRow); File(directory, "journey.json").writeText(trace.toString(2)); report.add(followRow)
                }
                val paidBeforeReading = paid
                turns.forEach { old ->
                    val reopened = reflection.inspect(old.runId)
                    assertTrue(reopened.findings.any { it.knowledgeId in savedIds })
                    reopened.findings.forEach { evidence(reflection, old.runId, it.target) }
                }
                assertEquals(paidBeforeReading, paid)
                val attachments = File(directory, "empty-attachments").apply { mkdir() }
                val zipFile = File(directory, "synthetic-reading-package.zip")
                KnowledgeArchive.write(repository.captureSnapshot(attachments), attachments, zipFile, ReadingExportOptions())
                ZipFile(zipFile).use { zip ->
                    val files = zip.entries().asSequence().associate { it.name to zip.getInputStream(it).bufferedReader().readText() }
                    assertTrue(files.containsKey("experiences/index.md"))
                    assertTrue(savedIds.all { id -> files.values.any { id in it } })
                    assertTrue(SyntheticLifeJourney.all.all { source -> files.values.any { source.body in it } })
                }
                File(directory, "completed.json").writeText(JSONObject().put("synthetic", true).put("paidRequests", paid)
                    .put("priorPaidRequests", priorPaid).put("newPaidRequests", paid).put("cumulativePaidRequests", priorPaid + paid)
                    .put("resumeFrom", resume?.directory?.path ?: JSONObject.NULL)
                    .put("savedKnowledgeIds", JSONArray(savedIds)).put("experienceId", experienceId)
                    .put("followupRunId", follow.runId).put("readingPackage", zipFile.name)
                    .put("semanticStatus", "REQUIRES_INDEPENDENT_REVIEW").toString(2))
            }
        } catch (failure: Throwable) {
            File(directory, "failure.json").writeText(JSONObject().put("syntheticRunner", true)
                .put("resumeCopyValidated", archiveSyntheticDatabase).put("resumeFrom", resume?.directory?.path ?: JSONObject.NULL)
                .put("priorPaidRequests", priorPaid).put("newPaidRequests", paid).put("cumulativePaidRequests", priorPaid + paid)
                .put("failureType", failure.javaClass.simpleName).put("failureMessage", failure.message).toString(2))
            throw failure
        } finally {
            secret.fill('\u0000')
            credentials.clear(); keyDirectory.deleteRecursively()
            // Every source is declared synthetic. Retain the closed database for failure diagnosis;
            // credentials live in the separately cleared noBackupFilesDir and are never copied.
            if (archiveSyntheticDatabase) context.getDatabasePath(database).takeIf { it.isFile }?.copyTo(File(directory, "synthetic-journey.db"))
            context.deleteDatabase(database)
            resume?.let { check(databaseSha256(it.database) == it.databaseSha256) { "Original resume database changed" } }
        }
    }

    private data class ResumeJourney(val directory: File, val database: File, val databaseSha256: String,
        val rows: List<JSONObject>, val paidRequests: Int, val followup: JSONObject? = null) {
        val completedStages: Int get() = if (followup == null) 2 else 3
    }

    private fun cumulativeStageRequests(row: JSONObject) = row.optInt("priorPaidRequests", 0) + row.getInt("paidRequests")

    /** Only the runner's closed, fully seeded synthetic checkpoint is accepted. Never open it. */
    private fun readResume(requested: File, repositoryRoot: File, newDirectory: File): ResumeJourney {
        val directory = requested.canonicalFile
        require(directory.isDirectory && !directory.toPath().startsWith(repositoryRoot.toPath()))
        require(directory.name.startsWith("deepseek-life-journey-"))
        UUID.fromString(directory.name.removePrefix("deepseek-life-journey-"))
        require(!newDirectory.canonicalFile.toPath().startsWith(directory.toPath()))
        fun artifact(name: String, maximum: Long): File = File(directory, name).canonicalFile.also {
            require(it.parentFile == directory && it.isFile && it.length() in 1..maximum) { "Invalid synthetic resume artifact: $name" }
        }
        require(!File(directory, "completed.json").exists()) { "Completed journeys cannot be resumed" }
        val manifest = JSONObject(artifact("report.json", 2_097_152).readText())
        require(manifest.getBoolean("synthetic") && manifest.getString("fixtureVersion") == "continuous-life-v1")
        val cases = manifest.getJSONArray("cases")
        require(cases.length() in 3..4)
        val followup = if (cases.length() == 4) cases.getJSONObject(3).also { row ->
            require(row.getBoolean("synthetic") && row.getString("id") == "followup" && row.getString("question") == FOLLOWUP_QUESTION)
            require(row.getString("programmaticStatus") == "FAIL" && row.getString("status") == ReflectionStatus.BUDGET_EXHAUSTED.name)
            artifact("followup-ledger.json", AnalysisRunCodec.MAX_PAYLOAD_BYTES.toLong())
        } else null
        val rows = SyntheticLifeJourney.stages.mapIndexed { index, stage ->
            val row = cases.getJSONObject(index)
            require(row.getBoolean("synthetic") && row.getString("id") == stage.id && row.getString("question") == stage.question)
            require(row.getString("from") == stage.from.toString() && row.getString("through") == stage.through.toString())
            require(row.getString("status") == ReflectionStatus.READY.name)
            require(row.getInt("coverageRecords") == stage.sources.size)
            val recordedChoice = row.getJSONObject("independentSelection")
            if (File(directory, "${stage.id}-choice.json").exists()) {
                val choice = JSONObject(artifact("${stage.id}-choice.json", 32_768).readText())
                listOf("findingIndex", "textSha256", "reason").forEach { key -> require(choice.get(key) == recordedChoice.get(key)) }
            } else require(row.optBoolean("reusedCompletedStage")) { "An original independent selection is required" }
            require(recordedChoice.getString("reason").isNotBlank())
            if (index < 2 || followup != null) {
                require(row.getString("programmaticStatus") == "PASS" && row.getString("verdict") == ReviewVerdict.SUPPORTED.name)
                require(row.getString("knowledgeId").isNotBlank())
            } else {
                require(row.getString("programmaticStatus") == "FAIL" && row.getString("saveStatus") == ReflectionStatus.BUDGET_EXHAUSTED.name)
                require(row.isNull("knowledgeId") && row.isNull("verdict"))
            }
            row
        }
        val localPaid = manifest.getInt("reportedPaidRequests")
        val allRows = rows + listOfNotNull(followup)
        require(localPaid in 0..39 && allRows.all { it.getInt("paidRequests") >= 0 && it.optInt("priorPaidRequests", 0) >= 0 })
        require(allRows.sumOf { it.getInt("paidRequests") } == localPaid)
        val inheritedPaid = if (File(directory, "resume.json").exists()) {
            val priorResume = JSONObject(artifact("resume.json", 32_768).readText())
            require(priorResume.getBoolean("synthetic") && priorResume.getString("fixtureVersion") == "continuous-life-v1" &&
                priorResume.getString("sourceFixtureVersion") == SyntheticLifeJourney.VERSION)
            priorResume.getInt("priorPaidRequests").also { require(it in 1..39) }
        } else 0
        require(allRows.sumOf { it.optInt("priorPaidRequests", 0) } == inheritedPaid)
        val paid = inheritedPaid + localPaid
        require(paid in 1..39)
        if (localPaid > 0) require(artifact("request-count.txt", 16).readText().trim().toInt() == localPaid)
        require(directory.listFiles()!!.count { Regex("request-\\d+\\.json").matches(it.name) } == localPaid)
        (1..localPaid).forEach { number ->
            artifact("request-$number.json", 262_144)
            artifact("response-$number.json", 1_048_576)
        }
        val database = artifact("synthetic-journey.db", 16_777_216)
        listOf("-wal", "-journal").forEach { suffix ->
            require(!File(database.path + suffix).exists()) { "Resume requires a closed standalone synthetic database" }
        }
        if (followup != null) {
            val failure = JSONObject(artifact("failure.json", 32_768).readText())
            require(failure.getBoolean("syntheticRunner") && failure.getBoolean("resumeCopyValidated"))
            require(failure.getInt("priorPaidRequests") == inheritedPaid && failure.getInt("newPaidRequests") == localPaid &&
                failure.getInt("cumulativePaidRequests") == paid)
        }
        return ResumeJourney(directory, database, databaseSha256(database), rows, paid, followup)
    }

    private fun databaseSha256(file: File): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun validateResumeStore(repository: LifeRepository, clock: JourneyClock, resume: ResumeJourney,
        turns: MutableList<ConversationTurn>, savedIds: MutableList<String>, selectedIndexes: MutableList<Int>, experienceId: String,
    ): ConversationTurn? {
        val records = repository.listRecords(RecordFilter(includeArchived = true, includeUnknown = true,
            includeConfiguration = true, limit = 200))
        require(SyntheticLifeJourney.all.size == 18 && records.total == 18 && records.nextOffset == null)
        require(records.items.map { it.id }.toSet() == SyntheticLifeJourney.all.map { it.id }.toSet())
        SyntheticLifeJourney.all.forEach { source ->
            val actual = requireNotNull(repository.record(source.id))
            require(actual.draft == source.draft && actual.revision == 1 && !actual.archived) { "Resume source differs: ${source.id}" }
            require(repository.history(source.id) == listOf(actual)) { "Unexpected source revisions" }
        }
        val analysis = ControlledAnalysis(repository, clock)
        val knowledge = KnowledgeLibrary(repository, clock)
        val reflection = ReflectionWorkflow(repository, analysis, AnalysisFeedback(repository, clock), knowledge)
        val workflow = ConversationWorkflow(repository, analysis, reflection, clock)
        val threads = workflow.history(limit = 20)
        require(threads.size == 3)
        val existingTurns = threads.flatMap { workflow.turns(it.id, limit = 20) }
        val allRows = resume.rows + listOfNotNull(resume.followup)
        require(existingTurns.size == allRows.size && existingTurns.map { it.runId }.toSet() == allRows.map { it.getString("runId") }.toSet())
        SyntheticLifeJourney.stages.forEachIndexed { index, stage ->
            val prior = resume.rows[index]
            val turn = existingTurns.single { it.runId == prior.getString("runId") }
            require(turn.question == stage.question && turn.scope == stage.scope && turn.parentTurnId == null)
            val run = reflection.inspect(turn.runId)
            require(run.status == ReflectionStatus.READY && run.canReviewFindings)
            require(run.coverage?.records == stage.sources.size && run.coverage?.remainingTextFragments == 0L)
            val candidates = prior.getJSONArray("findings")
            require(candidates.length() == run.findings.size)
            run.findings.forEachIndexed { findingIndex, finding ->
                val candidate = candidates.getJSONObject(findingIndex)
                require(candidate.getInt("index") == findingIndex && candidate.getString("text") == finding.text &&
                    candidate.getString("textSha256") == AnalysisRunCodec.sha256(finding.text)) { "Original candidate identity changed" }
            }
            val choice = prior.getJSONObject("independentSelection")
            val selectedIndex = choice.getInt("findingIndex")
            val selected = run.findings[selectedIndex]
            require(choice.getString("textSha256") == AnalysisRunCodec.sha256(selected.text))
            require(selected.feedback?.feedback?.decision == ClaimFeedbackDecision.CONFIRMED)
            if (index < resume.completedStages) {
                val saved = prior.getString("knowledgeId")
                require(selected.knowledgeId == saved && selected.knowledgeEvidenceState == KnowledgeEvidenceState.CURRENT)
                require(knowledge.get(saved)?.evidenceState == KnowledgeEvidenceState.CURRENT)
                savedIds += saved
            } else {
                require(selected.knowledgeId == null && selected.reviewVerdict == null)
                val progress = requireNotNull(selected.reviewProgress)
                require(progress.status == ReductionReviewStatus.FINAL_READY && progress.allOriginalsScanned && progress.originalFragments == 18)
                require(progress.relatedFragments in 1..32 && progress.finalRereadFragments == 0)
                require(analysis.task(turn.runId).state.calls.none { it.purpose == CallPurpose.REDUCTION_REVIEW }) {
                    "Only an unsent final review may resume; failed requests require their original identity"
                }
            }
            turns += turn
            selectedIndexes += selectedIndex
        }
        val history = requireNotNull(KnowledgeContinuity(repository, clock).get(experienceId, limit = 100))
        require(history.changes.size == resume.completedStages && history.changes.map { it.change.knowledgeId }.toSet() == savedIds.toSet())
        require(history.changes.all { it.knowledge?.evidenceState == KnowledgeEvidenceState.CURRENT })
        return resume.followup?.let { prior ->
            val follow = existingTurns.single { it.runId == prior.getString("runId") }
            val parent = turns.last()
            require(follow.question == FOLLOWUP_QUESTION && follow.parentTurnId == parent.id && follow.threadId == parent.threadId &&
                follow.parentFindingIndex == selectedIndexes.last() && follow.scope == parent.scope)
            val task = analysis.task(follow.runId)
            val storedLedger = AnalysisRunCodec.decode(File(resume.directory, "followup-ledger.json").readText())
            require(storedLedger == task.state) { "Original followup ledger changed" }
            require(task.state.calls.size == 3 && task.state.calls.all { it.status == CallStatus.SUCCEEDED } &&
                task.state.calls.map { it.purpose } == listOf(CallPurpose.NAVIGATION, CallPurpose.SYNTHESIS, CallPurpose.SYNTHESIS)) {
                "Resume requires two completed leaves and an unsent reduction"
            }
            val context = requireNotNull(task.state.request.conversationContext)
            val selected = reflection.inspect(parent.runId).findings[selectedIndexes.last()]
            require(context.parentRunId == parent.runId && context.parentScope == parent.scope && context.targets.size == 1 &&
                context.targets.single().target == selected.target && context.targets.single().text == selected.text)
            require(ConversationContextCatalog(repository, clock).current(context))
            val progress = reflection.inspect(follow.runId)
            require(progress.synthesizedBatches == 2 && progress.reducedBatches == 0 && !progress.canReviewFindings &&
                progress.coverage?.records == 18 && progress.coverage?.remainingTextFragments == 0L)
            val previousFindings = prior.getJSONArray("findings")
            require(previousFindings.length() == progress.findings.size)
            progress.findings.forEachIndexed { index, finding ->
                val previous = previousFindings.getJSONObject(index)
                require(previous.getInt("index") == index && previous.getString("text") == finding.text &&
                    previous.getString("textSha256") == AnalysisRunCodec.sha256(finding.text))
            }
            follow
        }
    }

    private fun findings(workflow: ReflectionWorkflow, runId: String, run: ReflectionRun) = JSONArray(run.findings.mapIndexed { index, finding ->
        JSONObject().put("index", index).put("text", finding.text).put("textSha256", AnalysisRunCodec.sha256(finding.text))
            .put("evidence", evidence(workflow, runId, finding.target))
    })

    private fun evidence(workflow: ReflectionWorkflow, runId: String, target: ReflectionTarget): JSONArray {
        val rows = JSONArray()
        var offset: Int? = 0
        while (offset != null) {
            val page = workflow.evidence(runId, target, offset, 4)
            page.items.forEach { receipt ->
                val source = SyntheticLifeJourney.all.single { it.id == receipt.recordId }
                val original = workflow.original(runId, target, receipt.id, maxCodePoints = 4096)
                val expected = source.body.codePoints().toArray().let { String(it, receipt.start, receipt.end - receipt.start) }
                assertEquals(expected, original.text)
                assertEquals(receipt.revision, original.revision)
                rows.put(JSONObject().put("recordId", source.id).put("date", source.date).put("revision", receipt.revision)
                    .put("field", receipt.field).put("start", receipt.start).put("end", receipt.end).put("text", original.text))
            }
            offset = page.nextOffset
        }
        assertTrue(rows.length() > 0)
        return rows
    }

    private fun awaitChoice(directory: File, id: String): JSONObject {
        val file = File(directory, "$id-choice.json")
        val deadline = System.nanoTime() + 600_000_000_000L
        while (!file.isFile && System.nanoTime() < deadline) Thread.sleep(500)
        check(file.isFile) { "No independent synthetic selection supplied within ten minutes: ${file.path}" }
        return JSONObject(file.readText())
    }

    private class JourneyClock : Clock() {
        private var current = Instant.parse("2026-07-22T12:00:00Z")
        override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
        fun at(date: LocalDate) { current = date.atTime(20, 0).atZone(zone).toInstant() }
    }

    companion object {
        private const val FOLLOWUP_QUESTION = "这些变化和例外分别在哪几次经历中出现？按日期说明，哪些原因还只是可能的解释？"
    }
}
