package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.actions.*
import dev.tracelife.conversation.ConversationTurn
import dev.tracelife.conversation.ConversationWorkflow
import dev.tracelife.knowledge.KnowledgeContinuity
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeLibrary
import dev.tracelife.knowledge.ExperienceRelation
import dev.tracelife.knowledge.StoredExperienceChange
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.profile.*
import dev.tracelife.providers.DeepSeekCredentials
import dev.tracelife.providers.DeepSeekProvider
import dev.tracelife.storage.*
import dev.tracelife.transfer.BackupArchive
import dev.tracelife.transfer.RepositorySnapshot
import dev.tracelife.transfer.StaleSnapshotException
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
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Synthetic publications, user decisions and actions. Actual request framing, no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class KnowledgeContextTest {
    private lateinit var android: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private lateinit var reflection: ReflectionWorkflow
    private lateinit var conversation: ConversationWorkflow
    private lateinit var provider: Provider
    private val database = "synthetic-knowledge-context-${UUID.randomUUID()}.db"
    private val clock = MutableClock()
    private val day = LocalDate.parse("2026-10-20")
    private val scope = AnalysisQuery(day, day, "Asia/Shanghai")
    private val directories = mutableListOf<File>()

    @Before fun setup() {
        android = RuntimeEnvironment.getApplication()
        repository = LifeRepository(android, clock, database)
        analysis = ControlledAnalysis(repository, clock)
        reflection = ReflectionWorkflow(repository, analysis, AnalysisFeedback(repository, clock), KnowledgeLibrary(repository, clock))
        conversation = ConversationWorkflow(repository, analysis, reflection, clock)
        provider = Provider(DeepSeekProvider(DeepSeekCredentials(android)))
        repository.createRecord(RecordDraft("合成当前学习记录：先跑小例子，再查资料；效果仍需对照。", Occurrence(day), setOf("学习")), "current")
    }
    @After fun cleanup() { repository.close(); android.deleteDatabase(database); directories.forEach { it.deleteRecursively() } }

    @Test fun `question uses saved understanding experience and adopted profile as context alongside only current originals`() {
        val experience = publishOriginal("experience")
        KnowledgeContinuity(repository, clock).create(experience.knowledgeId, 1, "合成学习经验", setOf("学习"), "合成独立经验说明", "experience")
        val profile = publishOriginal("profile")
        adoptProfile(profile)
        val direct = publishOriginal("direct")
        PersonalProfile(repository, clock).create(profileDraft(direct), "unadopted", "合成未采用说明不得进入背景")
        val plan = conversation.prepare("今天的学习相比以往有什么新变化？", scopeOverride = scope)
        val context = requireNotNull(plan.knowledgeContext)
        assertEquals(setOf(KnowledgeContextKind.EXPERIENCE, KnowledgeContextKind.PROFILE, KnowledgeContextKind.KNOWLEDGE), context.entries.map { it.kind }.toSet())
        assertEquals(setOf(experience.knowledgeId, profile.knowledgeId, direct.knowledgeId), context.entries.map { it.knowledgeId }.toSet())
        assertFalse(context.entries.any { it.id == "unadopted" })
        val callsBefore = provider.requests.size
        val turn = conversation.submit(plan)
        assertEquals(callsBefore, provider.requests.size)
        val result = reflection.process(turn.runId, provider)
        assertEquals(ReflectionStatus.READY, result.status)
        val payload = JSONObject(provider.requests.last().payloadJson)
        val background = payload.getJSONObject("existingUnderstanding")
        assertFalse(background.getBoolean("originalEvidence"))
        assertEquals(3, background.getJSONArray("entries").length())
        assertTrue(background.toString().contains("合成独立经验说明"))
        assertTrue(background.toString().contains("合成画像独立说明"))
        assertFalse(background.toString().contains("未采用说明"))
        assertEquals(1, payload.getJSONArray("evidence").length())
        assertEquals(setOf("current"), analysis.task(turn.runId).state.calls.single().evidence.map { it.recordId }.toSet())
        assertEquals(3, repository.knowledgeHeads().size) // Generation has not published a fourth insight.
        assertEquals(context, AnalysisRunCodec.decode(analysis.task(turn.runId).stored.payload).request.knowledgeContext)
        assertTrue(provider.wires.last().contains("existingUnderstanding"))
    }

    @Test fun `changing a background source outside current date invalidates the answer and saved knowledge`() {
        val previous = publishOriginal("past")
        val turn = ask()
        val saved = save(turn)
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(saved)!!.evidenceState)
        repository.updateRecord(previous.sourceId, 1, RecordDraft("合成更正：先前并未完成练习。", Occurrence(day.minusDays(10)), setOf("学习")))
        assertEquals(AnalysisStatus.STALE, analysis.task(turn.runId).status)
        assertFalse(KnowledgeContextCatalog(repository, clock).current(analysis.task(turn.runId).state.request.knowledgeContext!!))
        assertNotEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(saved)!!.evidenceState)
        assertEquals(0, reflection.process(turn.runId, provider).modelRequestsThisPass)
        assertThrows(StaleSnapshotException::class.java) { analysis.route(turn.runId) }
    }

    @Test fun `adding an answer back to its experience keeps the answer and followup valid until adopted understanding changes`() {
        val initial = publishOriginal("initial-experience")
        val replacement = publishOriginal("replacement-experience")
        val continuity = KnowledgeContinuity(repository, clock)
        continuity.create(initial.knowledgeId, 1, "合成学习经验", setOf("学习"), "合成原有理解", "experience")
        val turn = ask()
        val fixed = analysis.task(turn.runId).state.request.knowledgeContext!!
        val saved = save(turn)

        continuity.append("experience", 1, saved, 1, ExperienceRelation.SUPPLEMENT, "合成新回答补充原有经验")
        assertEquals(2, repository.experienceThread("experience")!!.version)
        assertEquals(1, repository.experienceCurrentChange("experience")!!.sequence)
        assertTrue(KnowledgeContextCatalog(repository, clock).current(fixed))
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(saved)!!.evidenceState)
        val child = followup(turn)
        val childContext = analysis.task(child.runId).state.request.knowledgeContext!!
        assertEquals(1, childContext.entries.single { it.kind == KnowledgeContextKind.EXPERIENCE }.revision)
        val childSaved = save(child)

        continuity.append("experience", 2, childSaved, 1, ExperienceRelation.EXCEPTION, "合成补充适用情境的例外，未替换当前理解")
        assertEquals(3, repository.experienceThread("experience")!!.version)
        assertTrue(KnowledgeContextCatalog(repository, clock).current(fixed))
        assertTrue(KnowledgeContextCatalog(repository, clock).current(childContext))
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(childSaved)!!.evidenceState)
        SnapshotValidation.validate(repository.captureSnapshot(directory()))

        continuity.append("experience", 3, replacement.knowledgeId, 1, ExperienceRelation.NARROW, "合成收窄后的当前理解与说明")
        assertFalse(KnowledgeContextCatalog(repository, clock).current(fixed))
        assertEquals(AnalysisStatus.STALE, analysis.task(turn.runId).status)
        assertEquals(AnalysisStatus.STALE, analysis.task(child.runId).status)
        assertNotEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(saved)!!.evidenceState)
        SnapshotValidation.validate(repository.captureSnapshot(directory())) // Stale fixed identities remain valid history.
    }

    @Test fun `withdrawing an adopted profile invalidates frozen context without deleting original knowledge`() {
        val previous = publishOriginal("profile")
        adoptProfile(previous)
        val turn = ask()
        assertTrue(analysis.task(turn.runId).state.request.knowledgeContext!!.entries.any { it.kind == KnowledgeContextKind.PROFILE })
        PersonalProfile(repository, clock).decline("profile", 2, "withdraw")
        assertEquals(AnalysisStatus.STALE, analysis.task(turn.runId).status)
        assertEquals(KnowledgeEvidenceState.CURRENT, KnowledgeLibrary(repository, clock).get(previous.knowledgeId)!!.evidenceState)
        assertFalse(conversation.prepare("今天学习有哪些变化？", scopeOverride = scope).knowledgeContext!!.entries.any { it.kind == KnowledgeContextKind.PROFILE })
    }

    @Test fun `historical correction during a request rejects its late answer and blocks further dispatch`() {
        val previous = publishOriginal("past")
        val turn = conversation.submit(conversation.prepare("今天学习有哪些变化？", scopeOverride = scope))
        provider.before = {
            AnalysisFeedback(repository, clock).respond(previous.runId, previous.claim, analysis.task(previous.runId).stored.version,
                ClaimFeedbackDecision.CORRECTED, "合成纠正：不能由一次经历判断方法有效。", expectedVersion = 1)
        }
        assertThrows(StaleSnapshotException::class.java) {
            analysis.synthesize(turn.runId, "answer", listOf(EvidenceSelection("current", "body")), provider)
        }
        provider.before = {}
        assertEquals(AnalysisStatus.STALE, analysis.task(turn.runId).status)
        assertTrue(analysis.task(turn.runId).state.calls.none { it.status == CallStatus.SUCCEEDED })
        assertEquals(0, reflection.process(turn.runId, provider).modelRequestsThisPass)
    }

    @Test fun `deleting an experience removes dependent answers and old backup cannot restore them`() = deletionFence(KnowledgeContextKind.EXPERIENCE)
    @Test fun `deleting an adopted profile removes dependent answers and old backup cannot restore them`() = deletionFence(KnowledgeContextKind.PROFILE)
    @Test fun `deleting saved knowledge removes dependent answers and old backup cannot restore them`() = deletionFence(KnowledgeContextKind.KNOWLEDGE)

    @Test fun `encrypted backup retains fixed background references as historical without recertifying them`() {
        val previous = publishOriginal("profile")
        adoptProfile(previous)
        val turn = ask()
        val expected = analysis.task(turn.runId).state.request.knowledgeContext
        val attachments = directory()
        val snapshot = repository.captureSnapshot(attachments)
        val archive = File(directory(), "synthetic-background.tlb")
        val password = "synthetic-quality-backup-only".toCharArray()
        BackupArchive.write(snapshot, attachments, archive, password)
        val decoded = BackupArchive.read(archive, directory(), password)
        password.fill('\u0000')
        assertEquals(expected, AnalysisRunCodec.decode(decoded.analysisRuns.single { it.id == turn.runId }.payload).request.knowledgeContext)
        val targetName = "synthetic-background-target-${UUID.randomUUID()}.db"
        try {
            LifeRepository(android, clock, targetName).use { target ->
                target.restoreSnapshot(decoded, directory(), target.currentGeneration())
                val restored = ControlledAnalysis(target, clock).task(turn.runId)
                assertEquals(expected, restored.state.request.knowledgeContext)
                assertEquals(AnalysisStatus.STALE, restored.status)
                assertEquals(turn, target.conversationTurn(turn.id))
                assertEquals("合成画像独立说明", target.profileRevision("profile")!!.userText)
            }
        } finally { android.deleteDatabase(targetName) }
    }

    @Test fun `local experience branch wins same version restore and removes answers based on the other branch`() {
        val initial = publishOriginal("initial")
        val remoteKnowledge = publishOriginal("remote")
        val localKnowledge = publishOriginal("local")
        val continuity = KnowledgeContinuity(repository, clock)
        continuity.create(initial.knowledgeId, 1, "合成学习经验", setOf("学习"), "合成共同起点", "experience")
        val base = repository.captureSnapshot(directory())
        val local = base.copy(
            experienceThreads = base.experienceThreads.map { it.copy(version = 2, updatedAt = clock.instant()) },
            experienceChanges = base.experienceChanges + StoredExperienceChange("experience", 2, localKnowledge.knowledgeId,
                1, ExperienceRelation.REPLACE, "合成本地分叉说明", clock.instant()),
        )
        continuity.append("experience", 1, remoteKnowledge.knowledgeId, 1, ExperienceRelation.NARROW, "合成远端分叉说明")
        val remoteTurn = ask()
        val entry = analysis.task(remoteTurn.runId).state.request.knowledgeContext!!.entries.single { it.kind == KnowledgeContextKind.EXPERIENCE }
        assertEquals(2, entry.revision)
        assertEquals(remoteKnowledge.knowledgeId, entry.knowledgeId)
        assertEquals("合成远端分叉说明", entry.userNote)
        val saved = save(remoteTurn)
        val child = followup(remoteTurn)
        val remote = repository.captureSnapshot(directory())
        restoredFork(local, remote) { target ->
            val retained = target.experienceCurrentChange("experience")!!
            assertEquals(2, retained.sequence)
            assertEquals(localKnowledge.knowledgeId, retained.knowledgeId)
            assertEquals(ExperienceRelation.REPLACE, retained.relation)
            assertEquals("合成本地分叉说明", retained.userNote)
            assertDiscardedBranch(target, remoteTurn, child, saved, "合成远端分叉说明")
        }
    }

    @Test fun `local adopted profile note wins same revision restore and removes copied remote note from answers`() {
        val previous = publishOriginal("profile")
        adoptProfile(previous)
        val base = repository.captureSnapshot(directory())
        val adopted = base.profileRevisions.single { it.id == "profile" && it.revision == 2 }
        val local = base.copy(
            profileRevisions = base.profileRevisions + adopted.copy(revision = 3, updatedAt = clock.instant(),
                operationId = "local-note", change = ProfileChange.FEEDBACK, userText = "合成本地画像分叉说明"),
            profileHeads = listOf(ProfileHead("profile", 3)),
        )
        PersonalProfile(repository, clock).feedback("profile", 2, "remote-note", "合成远端画像分叉说明")
        val remoteTurn = ask()
        val entry = analysis.task(remoteTurn.runId).state.request.knowledgeContext!!.entries.single { it.kind == KnowledgeContextKind.PROFILE }
        assertEquals(3, entry.revision)
        assertEquals("合成远端画像分叉说明", entry.userNote)
        val saved = save(remoteTurn)
        val child = followup(remoteTurn)
        val remote = repository.captureSnapshot(directory())
        restoredFork(local, remote) { target ->
            val retained = target.profileRevision("profile")!!
            assertEquals(3, retained.revision)
            assertEquals(ProfileAdoption.ADOPTED, retained.adoption)
            assertEquals(previous.knowledgeId, retained.draft.knowledge.knowledgeId)
            assertEquals("合成本地画像分叉说明", retained.userText)
            assertDiscardedBranch(target, remoteTurn, child, saved, "合成远端画像分叉说明")
        }
    }

    @Test fun `unified action answer exposes fixed plans reviews originals and saves before a selected followup`() {
        val prior = publishOriginal("action-history")
        AnalysisFeedback(repository, clock).respond(prior.runId, prior.claim, analysis.task(prior.runId).stored.version,
            ClaimFeedbackDecision.CORRECTED, "合成旧意见：没有执行旧计划不能证明更短的新计划有效。", expectedVersion = 1)
        val actions = ActionExperiments(repository, clock)
        clock.now = clock.now.minusSeconds(86_400)
        val draft = ActionDraft("合成学习计划", "开始练习时", "先练习二十分钟", "能解释一个例子", day, "Asia/Shanghai")
        actions.create(draft, "action")
        clock.now = clock.now.plusSeconds(86_400)
        actions.review("action", 1, "review", ActionReview(ActionExecution.NOT_EXECUTED,
            "合成回看：二十分钟计划没有开始；新五分钟安排还没试过。", ActionDecision.ADJUST, day),
            adjustedDraft = draft.copy(behavior = "下轮先练习五分钟", reviewOn = day.plusDays(7)))
        val plan = conversation.prepare("今天学习尝试哪些有效，下一步怎么调整？", scopeOverride = scope)
        assertTrue(plan.includeActions)
        assertEquals(2, plan.actionEvents)
        val turn = conversation.submit(plan)
        val result = reflection.process(turn.runId, provider)
        assertEquals(ReflectionStatus.READY, result.status)
        val root = JSONObject(provider.requests.last().payloadJson)
        assertEquals("ACTION_SYNTHESIS", root.getString("purpose"))
        assertEquals(1, root.getJSONArray("evidence").length())
        assertEquals(2, root.getJSONArray("actionEvidence").length())
        assertEquals(1, result.run.findings.single().historicalFeedback.usedInGeneration)
        assertEquals(1, result.run.findings.single().historicalFeedback.total)
        assertEquals(0, result.run.findings.single().historicalFeedback.resolved)
        val target = result.run.findings.single().target
        val refs = reflection.actionEvidence(turn.runId, target).entries
        assertEquals(setOf(ActionEvidenceKind.OPENING_PLAN, ActionEvidenceKind.USER_REVIEW), refs.map { it.kind }.toSet())
        val review = refs.single { it.kind == ActionEvidenceKind.USER_REVIEW }
        val original = JSONObject(reflection.actionOriginal(turn.runId, target, review.id))
        assertEquals("NOT_EXECUTED", original.getJSONObject("review").getString("execution"))
        assertEquals("先练习二十分钟", original.getJSONObject("reviewedPlan").getString("behavior"))
        assertEquals("下轮先练习五分钟", original.getJSONObject("nextPlan").getString("behavior"))
        val historical = reflection.historicalFeedbackPage(turn.runId, target).items.single()
        assertEquals("合成旧意见：没有执行旧计划不能证明更短的新计划有效。", historical.content.userText)
        reflection.resolveHistoricalFeedback(turn.runId, target, historical.content.reference.id, result.run.taskVersion,
            HistoricalFeedbackChoice.RESPECTED, "合成人工对照：当前发现只描述旧计划未开始，新五分钟计划效果未知。")
        val saved = save(turn)
        assertEquals(2, repository.knowledgeRevision(saved)!!.actionEvidence.size)
        assertEquals(2, analysis.task(turn.runId).state.calls.last().actionEvidence.size)
        val followup = conversation.submit(conversation.prepare("这条发现有哪些具体依据？", turn.threadId, turn.id, 0))
        assertEquals(ReflectionStatus.READY, reflection.process(followup.runId, provider).status)
        val followupWire = JSONObject(provider.requests.last().payloadJson)
        assertTrue(followupWire.has("conversationContext"))
        assertEquals(2, followupWire.getJSONArray("actionEvidence").length())
        actions.update("action", 2, repository.actionRevision("action")!!.draft.copy(behavior = "合成后来的新计划"))
        assertEquals(AnalysisStatus.STALE, analysis.task(turn.runId).status)
        assertEquals(AnalysisStatus.STALE, analysis.task(followup.runId).status)
    }

    @Test fun `context pseudo citations fail while full request including background is measured before dispatch`() {
        publishOriginal("background")
        val plan = conversation.prepare("今天学习有哪些变化？", scopeOverride = scope)
        val turn = conversation.submit(plan)
        provider.extraCitation = "existing-understanding:1"
        assertThrows(AnalysisValidationException::class.java) {
            analysis.synthesize(turn.runId, "forged", listOf(EvidenceSelection("current", "body")), provider)
        }
        assertEquals(CallStatus.FAILED, analysis.task(turn.runId).state.calls.single().status)
        provider.extraCitation = null
        val background = requireNotNull(plan.knowledgeContext)
        val plain = AnalysisRequest("合成相同问题", scope)
        analysis.start(plain, "plain")
        analysis.synthesize("plain", "answer", listOf(EvidenceSelection("current", "body")), provider)
        val plainBytes = analysis.task("plain").state.calls.single().inputBytes
        val before = provider.requests.size
        analysis.start(plain.copy(limits = AnalysisLimits(maxInputBytes = plainBytes + 32), knowledgeContext = background), "bounded")
        assertThrows(AnalysisBudgetException::class.java) {
            analysis.synthesize("bounded", "answer", listOf(EvidenceSelection("current", "body")), provider)
        }
        assertEquals(before, provider.requests.size)
        assertTrue(analysis.task("bounded").state.calls.isEmpty())
    }

    private fun deletionFence(kind: KnowledgeContextKind) {
        val previous = publishOriginal("origin")
        if (kind == KnowledgeContextKind.EXPERIENCE) KnowledgeContinuity(repository, clock).create(previous.knowledgeId, 1,
            "合成学习经验", setOf("学习"), "合成独立经验说明", "experience")
        if (kind == KnowledgeContextKind.PROFILE) adoptProfile(previous)
        val turn = ask()
        assertTrue(analysis.task(turn.runId).state.request.knowledgeContext!!.entries.any { it.kind == kind })
        val saved = save(turn)
        val child = conversation.submit(conversation.prepare("这条理解有哪些例外？", turn.threadId, turn.id, 0))
        assertEquals(ReflectionStatus.READY, reflection.process(child.runId, provider).status)
        val attachments = directory()
        val snapshot = repository.captureSnapshot(attachments)
        when (kind) {
            KnowledgeContextKind.EXPERIENCE -> repository.deleteExperienceThread("experience", 1)
            KnowledgeContextKind.PROFILE -> PersonalProfile(repository, clock).delete("profile", 2)
            KnowledgeContextKind.KNOWLEDGE -> repository.deleteKnowledge(previous.knowledgeId, 1)
        }
        assertNull(repository.analysisRun(turn.runId)); assertNull(repository.analysisRun(child.runId))
        assertNull(repository.knowledgeRevision(saved))
        assertNotNull(repository.record(previous.sourceId)); assertNotNull(repository.record("current"))
        if (kind != KnowledgeContextKind.KNOWLEDGE) assertNotNull(repository.knowledgeRevision(previous.knowledgeId))
        repository.restoreSnapshot(snapshot, attachments, repository.currentGeneration(), replaceExisting = true)
        assertNull(repository.analysisRun(turn.runId)); assertNull(repository.analysisRun(child.runId))
        assertNull(repository.knowledgeRevision(saved))
        assertEquals(listOf(turn.question, child.question), repository.conversationTurns(turn.threadId).map { it.question })
    }

    private fun restoredFork(local: RepositorySnapshot, remote: RepositorySnapshot, verify: (LifeRepository) -> Unit) {
        val targetName = "synthetic-context-fork-${UUID.randomUUID()}.db"
        try {
            LifeRepository(android, clock, targetName).use { target ->
                // Each branch is independently validated by the real restore path before merge.
                target.restoreSnapshot(local, directory(), target.currentGeneration())
                target.restoreSnapshot(remote, directory(), target.currentGeneration(), replaceExisting = true)
                verify(target)
            }
        } finally { android.deleteDatabase(targetName) }
    }

    private fun assertDiscardedBranch(target: LifeRepository, parent: ConversationTurn, child: ConversationTurn,
        saved: String, discardedText: String) {
        assertNull(target.analysisRun(parent.runId)); assertNull(target.analysisRun(child.runId))
        assertNull(target.knowledgeRevision(saved))
        assertEquals(listOf(parent.question, child.question), target.conversationTurns(parent.threadId).map { it.question })
        val captured = target.captureSnapshot(directory()) // Merging must leave a valid, backupable graph.
        assertTrue(captured.deletedAnalysisRuns.map { it.id }.containsAll(listOf(parent.runId, child.runId)))
        assertFalse(captured.analysisRuns.any { it.payload.contains(discardedText) })
        val archive = File(directory(), "synthetic-merged-fork.tlb")
        val password = "synthetic-fork-backup-only".toCharArray()
        BackupArchive.write(captured, directory(), archive, password)
        assertEquals(captured, BackupArchive.read(archive, directory(), password))
        password.fill('\u0000')
    }

    private data class Published(val runId: String, val sourceId: String, val claim: ClaimRef, val knowledgeId: String)
    private fun publishOriginal(name: String): Published {
        val source = "past-$name"
        val previous = day.minusDays(10)
        repository.createRecord(RecordDraft("合成旧学习原文$name：我试过先写例子，仍需观察条件。", Occurrence(previous), setOf("学习")), source)
        val run = "original-$name"
        analysis.start(AnalysisRequest("合成过去的学习理解$name", AnalysisQuery(previous, previous, "Asia/Shanghai", recordIds = setOf(source))), run)
        analysis.synthesize(run, "answer", listOf(EvidenceSelection(source, "body")), provider)
        val claim = analysis.claimRef(run, "answer", 0)
        AnalysisFeedback(repository, clock).respond(run, claim, analysis.task(run).stored.version, ClaimFeedbackDecision.CONFIRMED)
        analysis.reviewClaim(run, "review", claim, provider = provider)
        val saved = KnowledgeLibrary(repository, clock).publish(run, claim, "review", KnowledgeCategory.INSIGHT,
            analysis.task(run).stored.version, topics = setOf("学习"))
        return Published(run, source, claim, saved.id)
    }
    private fun profileDraft(previous: Published) = ProfileDraft(ProfileKnowledgeRef(previous.knowledgeId, 1),
        ProfileDimension.ABILITIES_AND_STRATEGIES, ProfileLayer.CONTEXTUAL_PATTERN)
    private fun adoptProfile(previous: Published) {
        PersonalProfile(repository, clock).apply {
            create(profileDraft(previous), "profile", "合成画像独立说明")
            adopt("profile", 1, "adopt")
        }
    }
    private fun ask(): ConversationTurn {
        val turn = conversation.submit(conversation.prepare("今天的学习方法有什么变化？", scopeOverride = scope))
        assertEquals(ReflectionStatus.READY, reflection.process(turn.runId, provider).status)
        return turn
    }
    private fun followup(turn: ConversationTurn): ConversationTurn {
        val child = conversation.submit(conversation.prepare("这条理解有哪些例外？", turn.threadId, turn.id, 0))
        assertEquals(ReflectionStatus.READY, reflection.process(child.runId, provider).status)
        return child
    }
    private fun save(turn: ConversationTurn): String {
        val run = reflection.inspect(turn.runId)
        val target = run.findings.single().target
        reflection.respond(turn.runId, target, run.taskVersion, ClaimFeedbackDecision.CONFIRMED)
        val saved = reflection.reviewAndSave(turn.runId, target, provider, provider, topics = setOf("学习"))
        return requireNotNull(saved.run.findings.single().knowledgeId)
    }
    private fun directory() = File(android.cacheDir, "synthetic-context-${UUID.randomUUID()}").apply { mkdirs(); directories += this }
    private class MutableClock(var now: Instant = Instant.parse("2026-10-20T04:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
        override fun instant(): Instant = now
    }
    private class Provider(private val framing: DeepSeekProvider) : AnalysisProvider, SemanticReviewProvider, SemanticReductionScanProvider {
        override val id = framing.id
        override val contextWindowTokens = framing.contextWindowTokens
        val requests = mutableListOf<ModelRequest>()
        val wires = mutableListOf<String>()
        var before: () -> Unit = {}
        var extraCitation: String? = null
        override fun countInputTokens(request: ModelRequest) = framing.countInputTokens(request)
        override fun prepare(request: ModelRequest) = framing.prepare(request)
        override fun completePrepared(request: PreparedModelRequest): ModelCompletion {
            wires += request.payloadJson
            return ModelCompletion(complete(request.original))
        }
        override fun complete(request: ModelRequest): BatchProposal {
            requests += request; before()
            val root = JSONObject(request.payloadJson)
            val fields = if (root.getString("purpose") == "REDUCTION") listOf("derivedInputs") else listOf("evidence", "actionEvidence")
            val ids = ids(root, fields)
            return BatchProposal(listOf(ProposedClaim("回答：合成学习理解，仅讨论已有原文中的一次经历和条件。", ids + listOfNotNull(extraCitation), ClaimKind.POSSIBLE_INTERPRETATION)))
        }
        override fun prepareReview(request: ModelRequest) = framing.prepareReview(request)
        override fun reviewPrepared(request: PreparedModelRequest): ReviewCompletion {
            requests += request.original; wires += request.payloadJson
            return ReviewCompletion(ClaimReview(ReviewVerdict.SUPPORTED, ids(JSONObject(request.original.payloadJson), listOf("evidence", "actionEvidence"))))
        }
        override fun prepareScan(request: ModelRequest) = framing.prepareScan(request)
        override fun scanPrepared(request: PreparedModelRequest): ReductionScanCompletion {
            requests += request.original; wires += request.payloadJson
            return ReductionScanCompletion(ReductionScanResult(ids(JSONObject(request.original.payloadJson), listOf("evidence", "actionEvidence"))
                .map { ReductionFinding(it, ReductionRelevance.SUPPORT, "合成原文支持") }))
        }
        private fun ids(root: JSONObject, fields: List<String>): List<String> = fields.flatMap { field ->
            val rows = root.optJSONArray(field)
            if (rows == null) emptyList() else (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
        }
    }
}
