package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Spinner
import android.widget.LinearLayout
import dev.tracelife.analysis.AnalysisProviderException
import dev.tracelife.analysis.AnalysisRequest
import dev.tracelife.analysis.ClaimKind
import dev.tracelife.analysis.ReviewIssue
import dev.tracelife.analysis.ReviewVerdict
import dev.tracelife.analysis.SynthesisIntegration
import dev.tracelife.profile.ProfileAdoption
import dev.tracelife.profile.ProfileAnalysisTarget
import dev.tracelife.profile.ProfileCandidateDisposition
import dev.tracelife.profile.ProfileCandidateItem
import dev.tracelife.profile.ProfileCandidateMaterializationStatus
import dev.tracelife.profile.ProfileCandidateProgress
import dev.tracelife.profile.ProfileCandidateStatus
import dev.tracelife.profile.ProfileChange
import dev.tracelife.profile.ProfileChangedField
import dev.tracelife.profile.ProfileChangesQuery
import dev.tracelife.profile.ProfileChangesSnapshot
import dev.tracelife.profile.ProfileDimension
import dev.tracelife.profile.ProfileEntryView
import dev.tracelife.profile.ProfileKnowledgeState
import dev.tracelife.profile.ProfileLayer
import dev.tracelife.profile.ProfileQuery
import dev.tracelife.profile.ProfileRecentState
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.ClaimFeedbackDecision
import dev.tracelife.storage.KnowledgeCategory
import dev.tracelife.storage.KnowledgeValidation
import dev.tracelife.transfer.StaleSnapshotException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ProfileActivity : ScreenActivity() {
    private val zoneId get() = ZoneId.systemDefault().id
    private var dimension: ProfileDimension? = null
    private var dimensionOffset = 0
    private var entryId: String? = null
    private var entryRevision: Int? = null
    private var entryOrigin = "home"
    private var historyOffset = 0
    private var evidenceId: String? = null
    private var evidenceStart = 0
    private var runId: String? = null
    private var candidateOffset = 0
    private var candidateProgress: ProfileCandidateProgress? = null
    private var changesFrom = ""
    private var changesThrough = ""
    private var changesOffset = 0
    private var changesSnapshot: ProfileChangesSnapshot? = null

    private var restoredPage: String? = null
    private var generateDimension = 0
    private var generateLayer = 0
    private var generateFrom = ""
    private var generateThrough = ""
    private var generateQuestion = ""
    private var generateInputs: List<EditText> = emptyList()
    private var generateSelectors: List<Spinner> = emptyList()

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        restoredPage = savedInstanceState?.getString("profileScreen")
        runId = savedInstanceState?.getString("profileRunId")
        candidateOffset = savedInstanceState?.getInt("candidateOffset") ?: 0
        entryId = savedInstanceState?.getString("profileEntryId") ?: intent.getStringExtra("entryId")
        if (restoredPage == null && entryId != null) restoredPage = "entry"
        generateDimension = savedInstanceState?.getInt("generateDimension") ?: 0
        generateLayer = savedInstanceState?.getInt("generateLayer") ?: 0
        generateFrom = savedInstanceState?.getString("generateFrom").orEmpty()
        generateThrough = savedInstanceState?.getString("generateThrough").orEmpty()
        generateQuestion = savedInstanceState?.getString("generateQuestion").orEmpty()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (screen == "generate") captureGenerateForm()
        outState.putString("profileScreen", screen)
        outState.putString("profileRunId", runId)
        outState.putString("profileEntryId", entryId)
        outState.putInt("candidateOffset", candidateOffset)
        outState.putInt("generateDimension", generateDimension)
        outState.putInt("generateLayer", generateLayer)
        outState.putString("generateFrom", generateFrom)
        outState.putString("generateThrough", generateThrough)
        outState.putString("generateQuestion", generateQuestion)
        super.onSaveInstanceState(outState)
    }

    private fun captureGenerateForm() {
        generateInputs.getOrNull(0)?.let { generateFrom = it.text.toString() }
        generateInputs.getOrNull(1)?.let { generateThrough = it.text.toString() }
        generateInputs.getOrNull(2)?.let { generateQuestion = it.text.toString() }
        generateSelectors.getOrNull(0)?.let { generateDimension = it.selectedItemPosition }
        generateSelectors.getOrNull(1)?.let { generateLayer = it.selectedItemPosition }
    }

    override fun showHome() {
        val restore = restoredPage
        restoredPage = null
        if (restore == "generate") { showGenerateForm(); return }
        if (restore in setOf("candidates", "candidateDetail") && runId != null) { showCandidateProgress(requireNotNull(runId), candidateOffset); return }
        if (restore == "entry" && entryId != null) { showEntry(requireNotNull(entryId)); return }
        entryRevision = null
        beginScreen("home", "关于我", showBack = false)
        note("从经历中慢慢认识自己。这里留下你选择采用的描述，也允许随时修正。")
        runIo({ app.personalProfile.open(ProfileQuery(zoneId = zoneId)) }) { snapshot ->
            if (snapshot.total == 0) Ui.card(root).also { card ->
                Ui.sectionTitle(card, "从最近的经历开始")
                Ui.note(card, "暂时没有当前可用的描述。先写一些记录，再来看看自己的习惯与变化。")
                primaryIn(card, "了解近期的自己", "generate") { showGenerateForm() }
            }
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "我的不同侧面")
                Ui.note(card, "当前有 ${snapshot.total} 条描述")
                snapshot.dimensions.forEachIndexed { index, summary ->
                    if (index > 0) Ui.divider(card)
                    listRow(card, "${dimensionLabel(summary.dimension)}\n" +
                        (if (summary.unknown) "还没有留下描述" else "${summary.currentEntries} 条当前描述"), "dimension:${summary.dimension}") {
                        showDimension(summary.dimension, 0)
                    }
                }
            }
            Ui.card(root).also { card ->
                if (snapshot.total > 0) primaryIn(card, "了解近期的自己", "generate") { showGenerateForm() }
                addButton(card, "从不同阶段认识自己", "longitudinalProfile") { startActivity(Intent(this, LongitudinalProfileActivity::class.java)) }
                addButton(card, "整理历史与进度", "profileTaskHistory") { startActivity(Intent(this, LongitudinalProfileActivity::class.java).putExtra("history", true)) }
                addButton(card, "待采用与历史描述", "manage") { showManagement() }
                addButton(card, "变化记录", "changes") { showChangesForm() }
            }
            Ui.disclosure(root, "这些描述从哪里来", "profileAbout") { details ->
                Ui.note(details, "描述来自已有记录与知识，由你明确采用，不是人格测验或固定标签。近期描述默认查看最近 30 天；没有描述只代表资料不足。")
                Ui.note(details, "${snapshot.omittedNotCurrent} 条描述当前未纳入。新描述可继续与历史判断对照、检查原文与反例；长期整理按多个完整月份比较。")
            }
        }
    }

    override fun navigateBack() {
        if (busy) return
        if (screen == "generate") captureGenerateForm()
        when (screen) {
            "dimension", "manage", "generate", "candidates", "changesForm" -> showHome()
            "changes" -> showChangesForm()
            "candidateDetail" -> runId?.let { showCandidateProgress(it, candidateOffset) } ?: showHome()
            "entry" -> when {
                entryRevision != null -> entryId?.let { showHistory(it, historyOffset) } ?: showHome()
                entryOrigin == "manage" -> showManagement()
                entryOrigin == "dimension" -> dimension?.let { showDimension(it, dimensionOffset) } ?: showHome()
                else -> showHome()
            }
            "history" -> entryId?.let { showEntry(it) } ?: showHome()
            "evidence" -> entryId?.let { showEntry(it, entryRevision) } ?: showHome()
            else -> finish()
        }
    }

    private fun showDimension(value: ProfileDimension, offset: Int) {
        dimension = value
        dimensionOffset = offset
        beginScreen("dimension", dimensionLabel(value))
        runIo({
            val snapshot = app.personalProfile.open(ProfileQuery(zoneId = zoneId, dimension = value))
            app.personalProfile.entries(snapshot, offset, PAGE_SIZE)
        }) { page ->
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "条目")
                Ui.note(card, "点开查看描述与原文依据。")
                Ui.note(card, "共 ${page.total} 条")
                if (page.items.isEmpty()) emptyState(card, "当前范围没有条目。")
                page.items.forEach { item ->
                    listRow(card,
                        "${layerLabel(item.draft.layer)} · ${adoptionLabel(item.adoption)}\n" +
                            "知识：${knowledgeStateLabel(item.knowledgeState)} · 近期：${recentStateLabel(item.recentState)}" +
                            (if (item.isCurrent) " · 当前有效" else "") + "\n更新于 ${item.updatedAt}",
                        "entry:${item.id}") {
                        entryOrigin = "dimension"
                        showEntry(item.id)
                    }
                }
                if (offset > 0) addButton(card, "上一页", "previousPage") { showDimension(value, (offset - PAGE_SIZE).coerceAtLeast(0)) }
                page.nextOffset?.let { next -> addButton(card, "下一页", "nextPage") { showDimension(value, next) } }
            }
        }
    }

    private fun showManagement() {
        beginScreen("manage", "待采用与历史描述")
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "说明")
            Ui.note(card, "查看待采用、未采用和已被替代的描述。不采用只会移出「关于我」；如果内容有误，请到「整理」中纠正。")
        }
        runIo({
            val snapshot = app.personalProfile.open(ProfileQuery(zoneId = zoneId, currentOnly = false))
            val all = mutableListOf<dev.tracelife.profile.ProfileIndexItem>()
            var offset = 0
            while (true) {
                val page = app.personalProfile.entries(snapshot, offset, 100)
                all += page.items
                offset = page.nextOffset ?: break
            }
            all
        }) { items ->
            listOf(ProfileAdoption.CANDIDATE, ProfileAdoption.DECLINED, ProfileAdoption.SUPERSEDED).forEach { adoption ->
                val group = items.filter { it.adoption == adoption }
                Ui.card(root).also { card ->
                    Ui.cardTitle(card, "${adoptionLabel(adoption)}（${group.size} 条）")
                    if (group.isEmpty()) emptyState(card, "暂无。")
                    group.forEach { item ->
                        listRow(card,
                            "${dimensionLabel(item.draft.dimension)} · ${layerLabel(item.draft.layer)} · 知识：${knowledgeStateLabel(item.knowledgeState)}\n更新于 ${item.updatedAt}",
                            "manage:${item.id}") {
                            entryOrigin = "manage"
                            showEntry(item.id)
                        }
                    }
                }
            }
        }
    }

    private fun showEntry(id: String, revision: Int? = null) {
        entryId = id
        entryRevision = revision
        beginScreen("entry", if (revision == null) "关于我的描述" else "历史版本 · $revision")
        runIo({
            val snapshot = app.personalProfile.open(ProfileQuery(zoneId = zoneId, currentOnly = false))
            app.personalProfile.entry(snapshot, id, revision)
        }) { detail -> renderEntry(detail) }
    }

    private fun renderEntry(detail: ProfileEntryView) {
        val value = detail.value
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, dimensionLabel(value.draft.dimension))
            Ui.note(card, layerLabel(value.draft.layer))
            chipRow(card).also { row ->
                chip(row, adoptionLabel(value.adoption), adoptionTone(value.adoption))
                chip(row, knowledgeStateLabel(detail.knowledgeState), knowledgeStateTone(detail.knowledgeState))
                chip(row, recentStateLabel(detail.recentState), recentStateTone(detail.recentState))
            }
            if (!detail.isCurrentRevision) Ui.note(card, "正在查看历史版本，当前状态以最新版本为准。")
            value.userText?.let { Ui.body(card, "我的补充：$it") }
            Ui.disclosure(card, "版本信息", "profileVersion") { details ->
                Ui.note(details, "版本 ${value.revision}\n创建于 ${value.createdAt}\n更新于 ${value.updatedAt}\n检查于 ${detail.evaluatedAt}")
            }
        }
        val knowledge = detail.knowledge
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "描述与依据")
            if (knowledge == null) {
                emptyState(card, "关联知识不可用（${knowledgeStateLabel(detail.knowledgeState)}）。")
            } else {
                Ui.body(card, knowledge.value.claim.text)
                Ui.note(card, "性质：${claimKindLabel(knowledge.value.claim.kind)} · 类别：${categoryLabel(knowledge.value.category)} · 范围：${knowledge.value.scope.from} 至 ${knowledge.value.scope.through}")
                if (!knowledge.isCurrentRevision) Ui.note(card, "注意：知识已有更新的修订，此处显示画像绑定的旧修订。")
            }
        }
        if (knowledge != null) {
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "原文引用")
                val receipts = KnowledgeValidation.allEvidence(knowledge.value)
                if (receipts.isEmpty()) {
                    emptyState(card, "该知识没有登记原文引用。")
                } else {
                    Ui.note(card, "${receipts.size} 段原文，点开查看：")
                    receipts.forEachIndexed { index, receipt ->
                        listRow(card, "原文 ${index + 1} · ${receipt.end - receipt.start} 字", "evidence:${receipt.id}") {
                            showEvidence(value.id, receipt.id, 0)
                        }
                    }
                }
                if (detail.knowledgeState != ProfileKnowledgeState.CURRENT) {
                    Ui.note(card, "关联知识当前状态为「${knowledgeStateLabel(detail.knowledgeState)}」，不能从旧引用取证。")
                }
            }
        }
        button("查看修订历史", "history") { showHistory(value.id, 0) }
        if (entryRevision != null || !detail.isCurrentRevision) return
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "操作")
            Ui.note(card, "不采用会保留原来的知识。若内容有误，请到「整理」中纠正或否定。")
            if (value.adoption != ProfileAdoption.ADOPTED) primaryIn(card, "采用这条描述", "adopt") {
                confirm("采用这条描述？", "它会进入「关于我」，以后仍可修正。采用表示你的选择，不会把这段描述变成固定的人格结论。", "采用") {
                    val operationId = UUID.randomUUID().toString()
                    runIo({ app.personalProfile.adopt(value.id, value.revision, operationId) }) { toast("已采用"); showEntry(value.id) }
                }
            }
            if (value.adoption != ProfileAdoption.DECLINED) addButton(card, "不用于画像", "decline") {
                confirm("标记为不用于画像？", "条目退出当前画像组织并保留历史；共享知识不受影响。", "不用于画像") {
                    val operationId = UUID.randomUUID().toString()
                    runIo({ app.personalProfile.decline(value.id, value.revision, operationId) }) { toast("已标记为不用于画像"); showEntry(value.id) }
                }
            }
            if (value.adoption != ProfileAdoption.SUPERSEDED) addButton(card, "标记已被替代", "supersede") {
                confirm("标记为已被替代？", "表示这条目退出当前采用并保留历史；不会自动选择替代条目。", "标记已被替代") {
                    val operationId = UUID.randomUUID().toString()
                    runIo({ app.personalProfile.supersede(value.id, value.revision, operationId) }) { toast("已标记为已被替代"); showEntry(value.id) }
                }
            }
            val feedbackField = labeledFieldIn(card, "补充文字（可选）", value = value.userText.orEmpty(), multiline = true, maxLength = 2_000)
            Ui.note(card, "最多 2000 字；清空后保存将清除本次补充，历史仍保留。")
            addButton(card, "保存补充文字", "saveFeedback") {
                val operationId = UUID.randomUUID().toString()
                val note = feedbackField.text.toString()
                runIo({ app.personalProfile.feedback(value.id, value.revision, operationId, note.ifBlank { null }) }) {
                    toast("补充文字已保存")
                    showEntry(value.id)
                }
            }
            dangerIn(card, "永久删除这条画像", "delete") {
                confirm("永久删除这条画像？", "全部修订与独立补充文字将被永久删除并保留删除标记；关联的共享知识保留。此操作不能撤销。", "永久删除") {
                    runIo({ app.personalProfile.delete(value.id, value.revision) }) {
                        toast("画像已永久删除")
                        if (entryOrigin == "manage") showManagement() else showHome()
                    }
                }
            }
        }
    }

    private fun showEvidence(id: String, evidence: String, start: Int) {
        entryId = id
        evidenceId = evidence
        evidenceStart = start
        beginScreen("evidence", "原文引用")
        runIo({
            val snapshot = app.personalProfile.open(ProfileQuery(zoneId = zoneId, currentOnly = false))
            val entry = app.personalProfile.entry(snapshot, id)
            if (entry.knowledgeState != ProfileKnowledgeState.CURRENT) {
                throw IllegalStateException("关联知识当前不可取证（${knowledgeStateLabel(entry.knowledgeState)}），不能从旧引用读取原文。")
            }
            val receipt = KnowledgeValidation.allEvidence(requireNotNull(entry.knowledge).value).single { it.id == evidence }
            val fragment = app.personalProfile.evidence(snapshot, id, evidence, start, EVIDENCE_PAGE)
            receipt to fragment
        }) { (receipt, fragment) ->
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "原文片段")
                Ui.note(card, "原文片段 · 版本 ${fragment.revision}")
                Ui.note(card, "正在查看引用内第 ${fragment.start - receipt.start}–${fragment.end - receipt.start} 字，共 ${receipt.end - receipt.start} 字。")
                Ui.body(card, fragment.text)
                if (fragment.start > receipt.start) {
                    addButton(card, "上一段", "evidencePrevious") { showEvidence(id, evidence, (start - EVIDENCE_PAGE).coerceAtLeast(0)) }
                }
                if (fragment.end < receipt.end) {
                    addButton(card, "下一段", "evidenceNext") { showEvidence(id, evidence, fragment.end - receipt.start) }
                }
            }
        }
    }

    private fun showHistory(id: String, offset: Int) {
        entryId = id
        historyOffset = offset
        beginScreen("history", "修订历史")
        runIo({
            val snapshot = app.personalProfile.open(ProfileQuery(zoneId = zoneId, currentOnly = false))
            app.personalProfile.history(snapshot, id, offset, PAGE_SIZE)
        }) { page ->
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "修订")
                Ui.note(card, "共 ${page.total} 个修订")
                if (page.items.isEmpty()) emptyState(card, "暂无修订。")
                page.items.forEach { revision ->
                    listRow(card,
                        "修订 ${revision.revision} · ${changeLabel(revision.change)} · ${adoptionLabel(revision.adoption)}\n" +
                            "${revision.updatedAt}${if (revision.userText != null) " · 含补充文字" else ""}",
                        "revision:${revision.revision}") {
                        showEntry(id, revision.revision)
                    }
                }
                if (offset > 0) addButton(card, "上一页", "previousPage") { showHistory(id, (offset - PAGE_SIZE).coerceAtLeast(0)) }
                page.nextOffset?.let { next -> addButton(card, "下一页", "nextPage") { showHistory(id, next) } }
            }
        }
    }

    private fun showGenerateForm() {
        beginScreen("generate", "了解近期的自己")
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "想了解哪个方面？")
            Ui.note(card, "从这段时间的记录中整理可能的描述。核对、确认后可以保存，你再决定是否采用。跨阶段描述可从「从不同阶段认识自己」进入。")
            Ui.fieldLabel(card, "画像维度")
            val dimensionSpinner = spinnerIn(card, ProfileDimension.entries.map(::dimensionLabel), generateDimension, "dimension")
            Ui.fieldLabel(card, "层次")
            val layerSpinner = spinnerIn(card, listOf("近期状态", "情境模式"), generateLayer, "layer")
            val fromField = labeledFieldIn(card, "范围起始日期", hint = "YYYY-MM-DD", value = generateFrom.ifEmpty { LocalDate.now().minusDays(30).toString() })
            val throughField = labeledFieldIn(card, "范围结束日期", hint = "YYYY-MM-DD", value = generateThrough.ifEmpty { LocalDate.now().toString() })
            val questionField = labeledFieldIn(card, "这次想了解什么？", value = generateQuestion, multiline = true, maxLength = 2_000)
            generateInputs = listOf(fromField, throughField, questionField)
            generateSelectors = listOf(dimensionSpinner, layerSpinner)
            primaryIn(card, "开始整理", "startGenerate") {
                captureGenerateForm()
                val request = try {
                    val from = parseDate(fromField.text.toString(), "起始日期")
                    val through = parseDate(throughField.text.toString(), "结束日期")
                    require(!through.isBefore(from)) { "结束日期不能早于起始日期" }
                    val question = questionField.text.toString().trim()
                    require(question.isNotEmpty()) { "请填写问题" }
                    val target = ProfileAnalysisTarget(
                        ProfileDimension.entries[dimensionSpinner.selectedItemPosition],
                        if (layerSpinner.selectedItemPosition == 0) ProfileLayer.RECENT_STATE else ProfileLayer.CONTEXTUAL_PATTERN,
                    )
                    AnalysisRequest(question, AnalysisQuery(from, through, zoneId), profileTarget = target)
                } catch (failure: IllegalArgumentException) {
                    showError(failure)
                    return@primaryIn
                }
                runIo({ app.deepSeekCredentials.isConfigured() }) { configured ->
                    if (!configured) {
                        showError(IllegalStateException("尚未配置 DeepSeek 密钥。请先在「设置」中保存密钥后再生成。"))
                        return@runIo
                    }
                    confirm("开始在线整理？", "将把所选记录片段发送给 DeepSeek，可能产生费用，每轮最多请求 5 次。整理不会自动替你确认、收录或采用。", "开始整理") {
                        runIo({ app.profileCandidates.start(request).stored.id }) { started -> runCandidatePass(started) }
                    }
                }
            }
        }
    }

    private fun runCandidatePass(id: String) {
        runId = id
        beginScreen("candidates", "正在整理，进度会保留")
        note("本轮最多 5 次请求。关闭后重新打开只查看已保存的进度。")
        runIo({
            val failure = try {
                app.profileCandidates.process(id, app.deepSeekProvider, app.deepSeekProvider, maxModelRequests = 5)
                null
            } catch (provider: AnalysisProviderException) {
                provider.message
            }
            id to failure
        }) { (started, failure) ->
            failure?.let { toast("在线整理未完成：$it") }
            showCandidateProgress(started, 0)
        }
    }

    private fun showCandidateProgress(id: String, offset: Int) {
        runId = id
        candidateOffset = offset
        beginScreen("candidates", "待确认的描述")
        runIo({ app.profileCandidates.inspect(id, offset, PAGE_SIZE) }) { progress ->
            candidateProgress = progress
            renderCandidateProgress(progress)
        }
    }

    private fun renderCandidateProgress(progress: ProfileCandidateProgress) {
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "整理进度")
            chipRow(card).also { row -> chip(row, statusLabel(progress.status), statusTone(progress.status)) }
            progress.target?.let { Ui.note(card, "目标：${dimensionLabel(it.dimension)} · ${layerLabel(it.layer)}") }
            when (progress.status) {
                ProfileCandidateStatus.BUDGET_EXHAUSTED ->
                    Ui.note(card, "本次整理额度已用完，已有内容会保留。如需继续，请建立新的整理。")
                ProfileCandidateStatus.CLOCK_ROLLBACK -> Ui.note(card, "手机时间发生回拨，已暂停整理。请检查系统时间。")
                ProfileCandidateStatus.NEEDS_RECOVERY -> Ui.note(card, "上次整理中断了，需要先恢复。")
                else -> Unit
            }
            Ui.note(card, "整合状态：${integrationLabel(progress.integration)}")
            if (progress.integration == SynthesisIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED) {
                Ui.body(card, "这些描述分次整理，尚未合并，不能代表完整的周期或长期倾向。")
            }
            if (progress.status == ProfileCandidateStatus.READY || progress.status == ProfileCandidateStatus.PASS_LIMIT_REACHED) {
                primaryIn(card, "继续整理", "continueProcessing") {
                    confirm("继续在线整理？", "将把所选记录片段发送给 DeepSeek，可能产生费用，本轮最多请求 5 次。", "继续整理") {
                        runCandidatePass(progress.runId)
                    }
                }
            }
        }
        Ui.disclosure(root, "本次整理范围与进度", "candidateCoverage") { card ->
            val coverage = progress.coverage
            if (coverage == null) {
                emptyState(card, "暂时无法读取整理进度，可能已过期或被移除。")
            } else {
                Ui.note(card, "${coverage.records} 条记录 · 已整理 ${coverage.completedTextCodePoints} / ${coverage.textCodePoints} 字 · 还剩 ${coverage.remainingTextFragments} 段。")
                if (coverage.hasGaps) {
                    Ui.note(card, "覆盖缺口：未读取附件 ${coverage.unreadAttachments} 个，不可读记录 ${coverage.unreadableRecords} 条，日期未知记录 ${coverage.unknownTimeRecords} 条，跨期记录 ${coverage.overlappingRecords} 条，缺失指定 ID ${coverage.missingExactIds.size} 个。")
                } else {
                    Ui.note(card, "无已知覆盖缺口。")
                }
            }
            Ui.note(card, "这些描述只依据本次所选记录，仍可能有遗漏或反例。本步骤尚未与其他整理结果去重，也不会找回你已永久删除的手工归类。")
        }
        Ui.card(root).also { card ->
            Ui.cardTitle(card, "候选 ${progress.total} 条（本页 ${progress.candidates.size} 条）")
            progress.candidates.forEach { item ->
                chipRow(card).also { row -> chip(row, dispositionLabel(item.disposition), dispositionTone(item.disposition)) }
                listRow(card,
                    "候选 ${item.claim.claimIndex + 1}\n" +
                        "核对：${item.reviewVerdict?.let(::verdictLabel) ?: "未核对"}" +
                        (item.adoption?.let { " · 画像：${adoptionLabel(it)}" } ?: ""),
                    "candidate:${item.claim.claimSha256.take(12)}") {
                    showCandidateDetail(progress, item)
                }
            }
            if (candidateOffset > 0) addButton(card, "上一页", "previousPage") { showCandidateProgress(progress.runId, (candidateOffset - PAGE_SIZE).coerceAtLeast(0)) }
            progress.nextOffset?.let { next -> addButton(card, "下一页", "nextPage") { showCandidateProgress(progress.runId, next) } }
        }
    }

    private fun showCandidateDetail(progress: ProfileCandidateProgress, item: ProfileCandidateItem) {
        beginScreen("candidateDetail", "待确认的描述")
        runIo({
            try {
                app.profileCandidates.candidate(progress, item.claim)
            } catch (_: StaleSnapshotException) {
                val fresh = app.profileCandidates.inspect(progress.runId, candidateOffset, progress.pageSize)
                candidateProgress = fresh
                app.profileCandidates.candidate(fresh, item.claim)
            }
        }) { detail -> renderCandidateDetail(detail) }
    }

    private fun renderCandidateDetail(detail: dev.tracelife.profile.ProfileCandidateDetail) {
        val id = detail.item.claim
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "描述")
            chipRow(card).also { row -> chip(row, dispositionLabel(detail.item.disposition), dispositionTone(detail.item.disposition)) }
            Ui.body(card, detail.proposal.text)
            Ui.note(card, "${claimKindLabel(detail.proposal.kind)} · 尚未收录或采用")
            Ui.note(card, "本次整理参考了 ${detail.evidence.size} 段原文与上下文。")
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "核对")
            val review = detail.review
            if (review == null) {
                Ui.note(card, "尚未核对。")
            } else {
                chipRow(card).also { row -> chip(row, verdictLabel(review.verdict), verdictTone(review.verdict)) }
                if (review.issues.isNotEmpty()) Ui.note(card, "问题：${review.issues.joinToString("、", transform = ::issueLabel)}")
                if (review.unknowns.isNotEmpty()) Ui.note(card, "未知：${review.unknowns.joinToString("；")}")
                Ui.note(card, "支持原文 ${review.supportingEvidenceIds.size} 段 · 存在矛盾 ${review.contradictingEvidenceIds.size} 段。核对只依据这些记录，意见仍需你判断。")
            }
            when (detail.item.disposition) {
                ProfileCandidateDisposition.MAINTENANCE_REQUIRED ->
                    Ui.note(card, "这条描述需要与历史判断及完整范围原文对照。请继续历史对照与反例检查。")
                ProfileCandidateDisposition.READY_TO_MATERIALIZE ->
                    Ui.note(card, "已完成核对和你的确认，可以保存为待采用描述。")
                ProfileCandidateDisposition.AWAITING_CONFIRMATION ->
                    Ui.note(card, "等待你确认。确认后请返回列表「继续整理」，重新核对后即可保存。")
                else -> Unit
            }
        }
        val currentRunId = runId ?: return
        primaryButton("历史对照与反例检查", "openProfileMaintenance") {
            startActivity(ProfileMaintenanceActivity.intent(this, currentRunId, id))
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "操作")
            addButton(card, "确认这条描述", "confirmClaim") {
                confirm("确认这条描述？", "确认表示你认可这段文字。之后需要重新核对，再决定是否保存；不会自动采用。", "确认") {
                    runIo({
                        val previous = app.analysisFeedback.forClaim(currentRunId, id)
                        app.analysisFeedback.respond(currentRunId, id, app.analysis.task(currentRunId).stored.version, ClaimFeedbackDecision.CONFIRMED,
                            previous?.feedback?.userText, previous?.feedback?.version)
                    }) {
                        toast("已确认，请返回列表继续整理，完成重新核对")
                        showCandidateProgress(currentRunId, candidateOffset)
                    }
                }
            }
            if (detail.item.disposition == ProfileCandidateDisposition.READY_TO_MATERIALIZE) {
                primaryIn(card, "保存为待采用描述", "materialize") {
                    confirm("保存这条描述？", "它会作为洞察收进知识库，并保存为待采用描述。是否加入「关于我」，仍由你另行决定。", "保存") {
                        runIo({
                            app.profileCandidates.materialize(currentRunId, id, app.analysis.task(currentRunId).stored.version)
                        }) { result ->
                            val message = when (result.status) {
                                ProfileCandidateMaterializationStatus.CREATED ->
                                    "已保存。请到「待采用与历史描述」查看，再决定是否采用。"
                                ProfileCandidateMaterializationStatus.EXISTING ->
                                    "已有对应的描述，本次没有重复保存。原来的采用决定与补充文字保留。"
                                ProfileCandidateMaterializationStatus.DELETED ->
                                    "这条描述曾被永久删除，本次没有重新保存。"
                                ProfileCandidateMaterializationStatus.CONFLICT ->
                                    "这条描述与已有画像需要进一步比较，本次没有保存。对应的比较页面尚未接入。"
                            }
                            AlertDialog.Builder(this).setTitle("保存结果").setMessage(message)
                                .setPositiveButton("知道了") { _, _ -> showCandidateProgress(currentRunId, candidateOffset) }.show()
                        }
                    }
                }
            }
        }
    }

    private fun showChangesForm() {
        beginScreen("changesForm", "变化记录")
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "统计范围")
            Ui.note(card, "回看你采用、修正或补充过的描述。这些是保存记录，不代表成长评分。")
            val fromField = labeledFieldIn(card, "起始日期", hint = "YYYY-MM-DD", value = changesFrom.ifEmpty { LocalDate.now().minusDays(30).toString() })
            val throughField = labeledFieldIn(card, "结束日期", hint = "YYYY-MM-DD", value = changesThrough.ifEmpty { LocalDate.now().toString() })
            primaryIn(card, "查看变化", "openChanges") {
                val query = try {
                    changesFrom = fromField.text.toString()
                    changesThrough = throughField.text.toString()
                    ProfileChangesQuery(parseDate(changesFrom, "起始日期"), parseDate(changesThrough, "结束日期"), zoneId)
                } catch (failure: IllegalArgumentException) {
                    showError(failure)
                    return@primaryIn
                }
                runIo({ app.profileChanges.open(query) }) { snapshot ->
                    changesSnapshot = snapshot
                    showChanges(snapshot, 0)
                }
            }
        }
    }

    private fun showChanges(snapshot: ProfileChangesSnapshot, offset: Int) {
        changesSnapshot = snapshot
        changesOffset = offset
        beginScreen("changes", "变化记录")
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "这段时间")
            Ui.body(card, "${snapshot.query.from} 至 ${snapshot.query.through} · ${snapshot.total} 次保存")
            Ui.disclosure(card, "变化统计说明", "profileChangeDetails") { details ->
                Ui.note(details, ProfileChangedField.entries.joinToString("；") { "${changedFieldLabel(it)} ${snapshot.counts[it] ?: 0}" })
                Ui.note(details, "一次保存可能改变多个项目，计数不能直接相加。这里不含已永久删除的历史，也不衡量个人成长。")
            }
        }
        runIo({
            try {
                app.profileChanges.changes(snapshot, offset, PAGE_SIZE)
            } catch (_: StaleSnapshotException) {
                val fresh = app.profileChanges.open(snapshot.query)
                changesSnapshot = fresh
                app.profileChanges.changes(fresh, offset, PAGE_SIZE)
            }
        }) { page ->
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "变化明细")
                if (page.items.isEmpty()) emptyState(card, "该范围没有画像变化。")
                page.items.forEachIndexed { index, item ->
                    if (index > 0) Ui.divider(card)
                    Ui.body(card, "${item.changedAt} · ${changeLabel(item.change)} · ${dimensionLabel(item.dimension)} · ${layerLabel(item.layer)}\n" +
                        "修订 ${item.previousRevision ?: 0} → ${item.revision} · ${adoptionLabel(item.adoption)} · 字段：${item.fields.joinToString("、", transform = ::changedFieldLabel)}" +
                        (item.maintenanceAction?.let { " · 维护操作：${maintenanceActionLabel(it)}" } ?: ""))
                }
                if (offset > 0) addButton(card, "上一页", "previousPage") { showChanges(snapshot, (offset - PAGE_SIZE).coerceAtLeast(0)) }
                page.nextOffset?.let { next -> addButton(card, "下一页", "nextPage") { showChanges(snapshot, next) } }
            }
        }
    }

    private fun chipRow(card: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        card.addView(this)
    }

    private fun adoptionTone(value: ProfileAdoption) = when (value) {
        ProfileAdoption.ADOPTED -> Ui.Tone.OK
        ProfileAdoption.CANDIDATE -> Ui.Tone.WARN
        ProfileAdoption.DECLINED, ProfileAdoption.SUPERSEDED -> Ui.Tone.NEUTRAL
    }

    private fun knowledgeStateTone(value: ProfileKnowledgeState) = when (value) {
        ProfileKnowledgeState.CURRENT -> Ui.Tone.OK
        ProfileKnowledgeState.NOT_YET_PUBLISHED -> Ui.Tone.NEUTRAL
        else -> Ui.Tone.WARN
    }

    private fun recentStateTone(value: ProfileRecentState) = when (value) {
        ProfileRecentState.IN_WINDOW -> Ui.Tone.ACCENT
        ProfileRecentState.OUTSIDE_WINDOW -> Ui.Tone.WARN
        ProfileRecentState.NOT_APPLICABLE, ProfileRecentState.UNKNOWN -> Ui.Tone.NEUTRAL
    }

    private fun statusTone(value: ProfileCandidateStatus) = when (value) {
        ProfileCandidateStatus.PASS_FINISHED -> Ui.Tone.OK
        ProfileCandidateStatus.READY -> Ui.Tone.ACCENT
        ProfileCandidateStatus.NO_TEXT, ProfileCandidateStatus.CANCELLED -> Ui.Tone.NEUTRAL
        ProfileCandidateStatus.CONFLICT -> Ui.Tone.ERROR
        else -> Ui.Tone.WARN
    }

    private fun dispositionTone(value: ProfileCandidateDisposition) = when (value) {
        ProfileCandidateDisposition.READY_TO_MATERIALIZE, ProfileCandidateDisposition.EXISTING_PROFILE -> Ui.Tone.OK
        ProfileCandidateDisposition.REVIEW_CONTRADICTED, ProfileCandidateDisposition.FEEDBACK_BLOCKED,
        ProfileCandidateDisposition.FEEDBACK_DELETED, ProfileCandidateDisposition.KNOWLEDGE_DELETED,
        ProfileCandidateDisposition.PROFILE_DELETED, ProfileCandidateDisposition.PROFILE_CONFLICT -> Ui.Tone.ERROR
        else -> Ui.Tone.WARN
    }

    private fun verdictTone(value: ReviewVerdict) = when (value) {
        ReviewVerdict.SUPPORTED -> Ui.Tone.OK
        ReviewVerdict.CONTRADICTED -> Ui.Tone.ERROR
        ReviewVerdict.UNSUPPORTED, ReviewVerdict.INSUFFICIENT_CONTEXT -> Ui.Tone.WARN
    }

    private fun dimensionLabel(value: ProfileDimension) = when (value) {
        ProfileDimension.VALUES_AND_DIRECTION -> "价值与方向"
        ProfileDimension.ROLES_AND_CONTEXT -> "角色与环境"
        ProfileDimension.INTERESTS_AND_PREFERENCES -> "兴趣与偏好"
        ProfileDimension.BEHAVIOR_AND_DECISIONS -> "行为与决策"
        ProfileDimension.ABILITIES_AND_STRATEGIES -> "能力与策略"
        ProfileDimension.ENERGY_AND_RECOVERY -> "精力与恢复"
        ProfileDimension.RELATIONSHIPS_AND_COLLABORATION -> "关系与协作"
        ProfileDimension.NARRATIVE_AND_CHANGES -> "叙事与变化"
    }

    private fun layerLabel(value: ProfileLayer) = when (value) {
        ProfileLayer.LONG_TERM -> "长期"
        ProfileLayer.CONTEXTUAL_PATTERN -> "情境模式"
        ProfileLayer.RECENT_STATE -> "近期状态"
    }

    private fun adoptionLabel(value: ProfileAdoption) = when (value) {
        ProfileAdoption.CANDIDATE -> "待采用"
        ProfileAdoption.ADOPTED -> "已采用"
        ProfileAdoption.DECLINED -> "不用于画像"
        ProfileAdoption.SUPERSEDED -> "已被替代"
    }

    private fun knowledgeStateLabel(value: ProfileKnowledgeState) = when (value) {
        ProfileKnowledgeState.CURRENT -> "当前有效"
        ProfileKnowledgeState.MISSING -> "关联知识缺失"
        ProfileKnowledgeState.SUPERSEDED -> "知识已有新修订"
        ProfileKnowledgeState.NOT_YET_PUBLISHED -> "尚未发布"
        ProfileKnowledgeState.STALE -> "来源已变化"
        ProfileKnowledgeState.EXPIRED -> "已过期"
        ProfileKnowledgeState.FEEDBACK_CHANGED -> "反馈已变化"
        ProfileKnowledgeState.REVIEW_CHANGED -> "核对已变化"
    }

    private fun recentStateLabel(value: ProfileRecentState) = when (value) {
        ProfileRecentState.NOT_APPLICABLE -> "不限近期"
        ProfileRecentState.IN_WINDOW -> "近期描述"
        ProfileRecentState.OUTSIDE_WINDOW -> "已超出近期范围"
        ProfileRecentState.UNKNOWN -> "近期状态未知"
    }

    private fun changeLabel(value: ProfileChange) = when (value) {
        ProfileChange.CREATED -> "创建"
        ProfileChange.ORGANIZED -> "组织调整"
        ProfileChange.ADOPTED -> "采用"
        ProfileChange.DECLINED -> "不采用"
        ProfileChange.SUPERSEDED -> "替代"
        ProfileChange.FEEDBACK -> "补充文字"
    }

    private fun changedFieldLabel(value: ProfileChangedField) = when (value) {
        ProfileChangedField.CREATED -> "创建"
        ProfileChangedField.KNOWLEDGE_REFERENCE -> "知识引用"
        ProfileChangedField.DIMENSION -> "维度"
        ProfileChangedField.LAYER -> "层次"
        ProfileChangedField.ADOPTION -> "采用决定"
        ProfileChangedField.USER_TEXT -> "用户文字"
    }

    private fun statusLabel(value: ProfileCandidateStatus) = when (value) {
        ProfileCandidateStatus.READY -> "可继续处理"
        ProfileCandidateStatus.PASS_FINISHED -> "本轮处理完成"
        ProfileCandidateStatus.NEEDS_ATTENTION -> "需要留意"
        ProfileCandidateStatus.NO_TEXT -> "范围内没有可用文字"
        ProfileCandidateStatus.PASS_LIMIT_REACHED -> "达到本轮请求上限"
        ProfileCandidateStatus.CANCELLED -> "已取消"
        ProfileCandidateStatus.RETRY_NEEDED -> "需要重试"
        ProfileCandidateStatus.BUDGET_EXHAUSTED -> "预算已用完"
        ProfileCandidateStatus.NEEDS_RECOVERY -> "上次整理中断"
        ProfileCandidateStatus.STALE -> "任务来源已失效"
        ProfileCandidateStatus.EXPIRED -> "任务已过期"
        ProfileCandidateStatus.CLOCK_ROLLBACK -> "时钟回拨"
        ProfileCandidateStatus.TASK_UNAVAILABLE -> "任务不可用"
        ProfileCandidateStatus.CONFLICT -> "任务状态冲突"
    }

    private fun dispositionLabel(value: ProfileCandidateDisposition) = when (value) {
        ProfileCandidateDisposition.PENDING_REVIEW -> "待核对"
        ProfileCandidateDisposition.AWAITING_CONFIRMATION -> "待用户确认"
        ProfileCandidateDisposition.READY_TO_MATERIALIZE -> "可以保存"
        ProfileCandidateDisposition.REVIEW_UNSUPPORTED -> "核对未支持"
        ProfileCandidateDisposition.REVIEW_CONTRADICTED -> "核对发现矛盾"
        ProfileCandidateDisposition.REVIEW_INSUFFICIENT_CONTEXT -> "核对上下文不足"
        ProfileCandidateDisposition.FEEDBACK_BLOCKED -> "用户已否定／纠正／撤回"
        ProfileCandidateDisposition.FEEDBACK_DELETED -> "反馈已删除"
        ProfileCandidateDisposition.KNOWLEDGE_DELETED -> "知识已删除"
        ProfileCandidateDisposition.PROFILE_DELETED -> "画像已删除"
        ProfileCandidateDisposition.EXISTING_PROFILE -> "已有画像条目"
        ProfileCandidateDisposition.PROFILE_CONFLICT -> "与既有画像冲突"
        ProfileCandidateDisposition.MAINTENANCE_REQUIRED -> "需要与已有描述比较"
    }

    private fun verdictLabel(value: ReviewVerdict) = when (value) {
        ReviewVerdict.SUPPORTED -> "现有记录支持"
        ReviewVerdict.UNSUPPORTED -> "未支持"
        ReviewVerdict.CONTRADICTED -> "存在矛盾"
        ReviewVerdict.INSUFFICIENT_CONTEXT -> "上下文不足"
    }

    private fun issueLabel(value: ReviewIssue) = when (value) {
        ReviewIssue.USER_REPORT_AS_FACT -> "自述被当作事实"
        ReviewIssue.SCOPE_OVERGENERALIZATION -> "局部扩大为整体"
        ReviewIssue.CAUSAL_OVERREACH -> "相关扩大为因果"
        ReviewIssue.COUNTEREVIDENCE_OMISSION -> "遗漏反例"
        ReviewIssue.INSUFFICIENT_CONTEXT -> "上下文不足"
        ReviewIssue.OTHER -> "其他"
    }

    private fun integrationLabel(value: SynthesisIntegration) = when (value) {
        SynthesisIntegration.INCOMPLETE -> "尚未完成整合"
        SynthesisIntegration.SINGLE_CALL_ALL_AVAILABLE_TEXT -> "本次已查看全部可用文字"
        SynthesisIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED -> "分次结果尚未合并"
        SynthesisIntegration.NO_TEXT -> "没有可用文字"
    }

    private fun claimKindLabel(value: ClaimKind) = when (value) {
        ClaimKind.REPORTED_EXPERIENCE -> "自述经历"
        ClaimKind.POSSIBLE_INTERPRETATION -> "可能解释"
    }

    private fun categoryLabel(value: KnowledgeCategory) = when (value) {
        KnowledgeCategory.OBSERVATION -> "观察"
        KnowledgeCategory.KNOWLEDGE_NOTE -> "知识笔记"
        KnowledgeCategory.INSIGHT -> "洞察"
    }

    private fun maintenanceActionLabel(value: dev.tracelife.profile.ProfileMaintenanceAction) = when (value) {
        dev.tracelife.profile.ProfileMaintenanceAction.KEEP -> "保留"
        dev.tracelife.profile.ProfileMaintenanceAction.NEW -> "新增"
        dev.tracelife.profile.ProfileMaintenanceAction.NARROW -> "收窄"
        dev.tracelife.profile.ProfileMaintenanceAction.REPLACE -> "替换"
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val EVIDENCE_PAGE = 1_024
    }
}
