package dev.tracelife.ui

import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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

    override fun showHome() {
        entryRevision = null
        beginScreen("home", "个人画像", showBack = false)
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "画像说明")
            Ui.note(card, "画像是你明确采用的知识条目的组织结果，不生成或验证人格结论。近期窗口默认 30 天只是查看约定，不是心理测量或长期人格判定阈值；长期与情境条目不受该窗口筛选。")
            Ui.note(card, "跨任务画像维护（历史比较、反例扫描与明确应用）暂未接入界面。")
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "操作")
            cardPrimary(card, "生成画像候选", "generate") { showGenerateForm() }
            cardButton(card, "候选与历史条目管理", "manage") { showManagement() }
            cardButton(card, "画像变化摘要", "changes") { showChangesForm() }
        }
        runIo({ app.personalProfile.open(ProfileQuery(zoneId = zoneId)) }) { snapshot ->
            Ui.card(root).also { card ->
                Ui.sectionTitle(card, "八维汇总")
                Ui.note(card, "当前区条目 ${snapshot.total} 条；未进入当前区的条目 ${snapshot.omittedNotCurrent} 条。评估时间：${snapshot.evaluatedAt}")
                snapshot.dimensions.forEachIndexed { index, summary ->
                    if (index > 0) Ui.divider(card)
                    Ui.body(card, dimensionLabel(summary.dimension))
                    Ui.note(card, "当前 ${summary.currentEntries} 条／共 ${summary.totalEntries} 条" +
                        (summary.updatedAt?.let { "，最近修改 $it" } ?: ""))
                    if (summary.unknown) {
                        chip(card, "暂无", Ui.Tone.NEUTRAL)
                        Ui.note(card, "暂无当前可用条目。这仅表示该维度没有当前可用条目，不代表任何个人判断，也不表示已检查全部资料。")
                    }
                    listRow(card, "查看「${dimensionLabel(summary.dimension)}」条目", "dimension:${summary.dimension}") {
                        showDimension(summary.dimension, 0)
                    }
                }
            }
        }
    }

    override fun navigateBack() {
        if (busy) return
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
                Ui.note(card, "只列出条目元数据；正文在条目详情中查看。")
                Ui.note(card, "共 ${page.total} 条")
                if (page.items.isEmpty()) Ui.note(card, "当前范围没有条目。")
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
                if (offset > 0) cardButton(card, "上一页", "previousPage") { showDimension(value, (offset - PAGE_SIZE).coerceAtLeast(0)) }
                page.nextOffset?.let { next -> cardButton(card, "下一页", "nextPage") { showDimension(value, next) } }
            }
        }
    }

    private fun showManagement() {
        beginScreen("manage", "候选与历史条目管理")
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "说明")
            Ui.note(card, "这里列出候选、不用于画像与已被替代的条目。「不用于画像」只表示不纳入画像组织，不否定或删除共享知识；认为原判断错误请使用核对与反馈中的纠正／否定。")
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
                    Ui.sectionTitle(card, "${adoptionLabel(adoption)}（${group.size} 条）")
                    if (group.isEmpty()) Ui.note(card, "暂无。")
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
        beginScreen("entry", if (revision == null) "画像条目" else "画像条目 · 历史修订 $revision")
        runIo({
            val snapshot = app.personalProfile.open(ProfileQuery(zoneId = zoneId, currentOnly = false))
            app.personalProfile.entry(snapshot, id, revision)
        }) { detail -> renderEntry(detail) }
    }

    private fun renderEntry(detail: ProfileEntryView) {
        val value = detail.value
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "条目信息")
            Ui.body(card, "维度：${dimensionLabel(value.draft.dimension)} · 层次：${layerLabel(value.draft.layer)}")
            chipRow(card).also { row ->
                chip(row, adoptionLabel(value.adoption), adoptionTone(value.adoption))
                chip(row, knowledgeStateLabel(detail.knowledgeState), knowledgeStateTone(detail.knowledgeState))
                chip(row, recentStateLabel(detail.recentState), recentStateTone(detail.recentState))
            }
            Ui.note(card, "当前有效：${if (detail.isCurrent) "是" else "否"} · 是否为当前修订：${if (detail.isCurrentRevision) "是" else "否"}（修订 ${value.revision}）")
            Ui.note(card, "评估时间：${detail.evaluatedAt}\n创建：${value.createdAt} · 更新：${value.updatedAt}")
            Ui.body(card, "我的补充：${value.userText ?: "（无）"}")
        }
        val knowledge = detail.knowledge
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "关联知识")
            if (knowledge == null) {
                Ui.note(card, "关联知识不可用（${knowledgeStateLabel(detail.knowledgeState)}）。")
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
                    Ui.note(card, "该知识没有登记原文引用。")
                } else {
                    Ui.note(card, "已登记原文引用 ${receipts.size} 段（点按读取原文片段）：")
                    receipts.forEachIndexed { index, receipt ->
                        listRow(card, "引用 ${index + 1}：记录 ${receipt.recordId.take(8)} · 第 ${receipt.start}–${receipt.end} 码点", "evidence:${receipt.id}") {
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
            Ui.note(card, "「不用于画像」与「原判断错误」不同：前者只是不纳入画像组织，后者请通过核对与反馈纠正／否定原判断。")
            if (value.adoption != ProfileAdoption.ADOPTED) cardPrimary(card, "采用这条画像", "adopt") {
                confirm("采用这条画像？", "采用是你明确的组织决定，关联知识的当前有效性会再次检查；这不是人格评分或身份推断。", "采用") {
                    val operationId = UUID.randomUUID().toString()
                    runIo({ app.personalProfile.adopt(value.id, value.revision, operationId) }) { toast("已采用"); showEntry(value.id) }
                }
            }
            if (value.adoption != ProfileAdoption.DECLINED) cardButton(card, "不用于画像", "decline") {
                confirm("标记为不用于画像？", "条目退出当前画像组织并保留历史；共享知识不受影响。", "不用于画像") {
                    val operationId = UUID.randomUUID().toString()
                    runIo({ app.personalProfile.decline(value.id, value.revision, operationId) }) { toast("已标记为不用于画像"); showEntry(value.id) }
                }
            }
            if (value.adoption != ProfileAdoption.SUPERSEDED) cardButton(card, "标记已被替代", "supersede") {
                confirm("标记为已被替代？", "表示这条目退出当前采用并保留历史；不会自动选择替代条目。", "标记已被替代") {
                    val operationId = UUID.randomUUID().toString()
                    runIo({ app.personalProfile.supersede(value.id, value.revision, operationId) }) { toast("已标记为已被替代"); showEntry(value.id) }
                }
            }
            val feedbackField = cardField(card, "补充文字（可选，最多 2000 字；清空后保存将清除本次补充，历史仍保留）", value.userText.orEmpty(), multiline = true, maxLength = 2_000)
            cardButton(card, "保存补充文字", "saveFeedback") {
                val operationId = UUID.randomUUID().toString()
                val note = feedbackField.text.toString()
                runIo({ app.personalProfile.feedback(value.id, value.revision, operationId, note.ifBlank { null }) }) {
                    toast("补充文字已保存")
                    showEntry(value.id)
                }
            }
            cardDanger(card, "永久删除这条画像", "delete") {
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
                Ui.note(card, "记录 ${fragment.recordId.take(8)} · 修订 ${fragment.revision} · 字段 ${fragment.field}")
                Ui.note(card, "引用内第 ${fragment.start - receipt.start}–${fragment.end - receipt.start} 码点，共 ${receipt.end - receipt.start} 码点。起点相对于该引用，不能扩展成整条原文。")
                Ui.body(card, fragment.text)
                if (fragment.start > receipt.start) {
                    cardButton(card, "上一段", "evidencePrevious") { showEvidence(id, evidence, (start - EVIDENCE_PAGE).coerceAtLeast(0)) }
                }
                if (fragment.end < receipt.end) {
                    cardButton(card, "下一段", "evidenceNext") { showEvidence(id, evidence, fragment.end - receipt.start) }
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
                if (page.items.isEmpty()) Ui.note(card, "暂无修订。")
                page.items.forEach { revision ->
                    listRow(card,
                        "修订 ${revision.revision} · ${changeLabel(revision.change)} · ${adoptionLabel(revision.adoption)}\n" +
                            "${revision.updatedAt}${if (revision.userText != null) " · 含补充文字" else ""}",
                        "revision:${revision.revision}") {
                        showEntry(id, revision.revision)
                    }
                }
                if (offset > 0) cardButton(card, "上一页", "previousPage") { showHistory(id, (offset - PAGE_SIZE).coerceAtLeast(0)) }
                page.nextOffset?.let { next -> cardButton(card, "下一页", "nextPage") { showHistory(id, next) } }
            }
        }
    }

    private fun showGenerateForm() {
        beginScreen("generate", "生成画像候选")
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "生成条件")
            Ui.note(card, "从固定范围的原始记录生成「近期状态」或「情境模式」提案，经独立核对后由你明确确认、保存与采用；生成不自动确认、发布或采用。长期层次须经纵向画像流程，本页不提供。")
            val dimensionSpinner = cardSpinner(card, ProfileDimension.entries.map(::dimensionLabel), 0, "dimension")
            val layerSpinner = cardSpinner(card, listOf("近期状态", "情境模式"), 0, "layer")
            val fromField = cardField(card, "范围起始日期（YYYY-MM-DD）", LocalDate.now().minusDays(30).toString())
            val throughField = cardField(card, "范围结束日期（YYYY-MM-DD）", LocalDate.now().toString())
            val questionField = cardField(card, "问题（向模型提出的分析请求）", "", multiline = true, maxLength = 2_000)
            cardPrimary(card, "开始生成", "startGenerate") {
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
                    return@cardPrimary
                }
                runIo({ app.deepSeekCredentials.isConfigured() }) { configured ->
                    if (!configured) {
                        showError(IllegalStateException("尚未配置 DeepSeek 密钥。请先在「设置」中保存密钥后再生成。"))
                        return@runIo
                    }
                    confirm("将向 DeepSeek 发送范围内原文", "生成与核对会将所选范围内的记录原文片段发送给 DeepSeek，产生付费模型请求（每轮最多 5 次）。处理不自动确认、发布或采用。是否继续？", "继续生成") {
                        runIo({ app.profileCandidates.start(request).stored.id }) { started -> runCandidatePass(started) }
                    }
                }
            }
        }
    }

    private fun runCandidatePass(id: String) {
        runIo({
            val failure = try {
                app.profileCandidates.process(id, app.deepSeekProvider, app.deepSeekProvider, maxModelRequests = 5)
                null
            } catch (provider: AnalysisProviderException) {
                provider.message
            }
            id to failure
        }) { (started, failure) ->
            failure?.let { toast("模型请求未完成：$it") }
            showCandidateProgress(started, 0)
        }
    }

    private fun showCandidateProgress(id: String, offset: Int) {
        runId = id
        candidateOffset = offset
        beginScreen("candidates", "画像候选任务")
        runIo({ app.profileCandidates.inspect(id, offset, PAGE_SIZE) }) { progress ->
            candidateProgress = progress
            renderCandidateProgress(progress)
        }
    }

    private fun renderCandidateProgress(progress: ProfileCandidateProgress) {
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "任务状态")
            chipRow(card).also { row -> chip(row, statusLabel(progress.status), statusTone(progress.status)) }
            progress.target?.let { Ui.note(card, "目标：${dimensionLabel(it.dimension)} · ${layerLabel(it.layer)}") }
            when (progress.status) {
                ProfileCandidateStatus.BUDGET_EXHAUSTED ->
                    Ui.note(card, "预算已用完：已有进度保留；任务预算不能原地扩大，须明确建立预算更充足的新任务。")
                ProfileCandidateStatus.CLOCK_ROLLBACK -> Ui.note(card, "设备时钟早于任务更新时间，已暂停派发与保存。")
                ProfileCandidateStatus.NEEDS_RECOVERY -> Ui.note(card, "存在在途模型调用遗留，需要先恢复任务。")
                else -> Unit
            }
            Ui.note(card, "整合状态：${integrationLabel(progress.integration)}")
            if (progress.integration == SynthesisIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED) {
                Ui.body(card, "多批尚未整合：各批提案分别处理，尚未跨批统一整合，不能当作完整周期或长期画像。")
            }
            if (progress.status == ProfileCandidateStatus.READY || progress.status == ProfileCandidateStatus.PASS_LIMIT_REACHED) {
                cardPrimary(card, "继续处理（本轮最多 5 次付费请求）", "continueProcessing") {
                    confirm("继续付费处理？", "将向 DeepSeek 发送范围内原文片段，本轮最多 5 次模型请求。", "继续处理") {
                        runCandidatePass(progress.runId)
                    }
                }
            }
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "范围覆盖")
            val coverage = progress.coverage
            if (coverage == null) {
                Ui.note(card, "当前没有范围覆盖信息（任务可能不可用或已过期）。")
            } else {
                Ui.note(card, "范围覆盖：记录 ${coverage.records} 条，文字 ${coverage.textCodePoints} 码点，已处理 ${coverage.completedTextCodePoints} 码点，剩余片段 ${coverage.remainingTextFragments}。")
                if (coverage.hasGaps) {
                    Ui.note(card, "覆盖缺口：未读取附件 ${coverage.unreadAttachments} 个，不可读记录 ${coverage.unreadableRecords} 条，日期未知记录 ${coverage.unknownTimeRecords} 条，跨期记录 ${coverage.overlappingRecords} 条，缺失指定 ID ${coverage.missingExactIds.size} 个。")
                } else {
                    Ui.note(card, "无已知覆盖缺口。")
                }
            }
            Ui.note(card, "如实说明：反例搜索未穷尽（counterevidenceSearchExhaustive=false）；未完成跨任务语义去重（crossTaskSemanticDeduplication=false）；未跟踪已永久删除的手工组织位置（manualDeletedSlotTracking=false）。")
        }
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "候选 ${progress.total} 条（本页 ${progress.candidates.size} 条）")
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
            if (candidateOffset > 0) cardButton(card, "上一页", "previousPage") { showCandidateProgress(progress.runId, (candidateOffset - PAGE_SIZE).coerceAtLeast(0)) }
            progress.nextOffset?.let { next -> cardButton(card, "下一页", "nextPage") { showCandidateProgress(progress.runId, next) } }
        }
    }

    private fun showCandidateDetail(progress: ProfileCandidateProgress, item: ProfileCandidateItem) {
        beginScreen("candidateDetail", "候选详情")
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
            Ui.sectionTitle(card, "提案")
            chipRow(card).also { row -> chip(row, dispositionLabel(detail.item.disposition), dispositionTone(detail.item.disposition)) }
            Ui.body(card, detail.proposal.text)
            Ui.note(card, "性质：${claimKindLabel(detail.proposal.kind)}（提案均为可能解释，不是已发布知识或已采用画像）")
            Ui.note(card, "生成输入共 ${detail.evidence.size} 段原文引用（含提案未引用的上下文）。")
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
                Ui.note(card, "支持引用 ${review.supportingEvidenceIds.size} 段 · 矛盾引用 ${review.contradictingEvidenceIds.size} 段。模型的「支持」只是有限证据下的意见，不是事实认证。")
            }
            when (detail.item.disposition) {
                ProfileCandidateDisposition.MAINTENANCE_REQUIRED ->
                    Ui.note(card, "该候选存在适用历史画像或属长期层次，必须经跨任务维护流程比较并明确应用；普通核对通过也不能直接保存。维护流程暂未接入界面。")
                ProfileCandidateDisposition.READY_TO_MATERIALIZE ->
                    Ui.note(card, "核对与用户确认均已完成，可以保存为画像候选。")
                ProfileCandidateDisposition.AWAITING_CONFIRMATION ->
                    Ui.note(card, "核对已完成，等待你明确确认。确认后先前核对失效，需返回列表「继续处理」完成重新核对，之后才可保存。")
                else -> Unit
            }
        }
        val currentRunId = runId ?: return
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "操作")
            cardButton(card, "确认这条陈述", "confirmClaim") {
                confirm("确认这条陈述？", "确认表示你明确认可这条候选文字；确认会产生新的反馈版本，先前核对因此失效，需要重新核对后才能保存。", "确认") {
                    runIo({
                        app.analysisFeedback.respond(currentRunId, id, app.analysis.task(currentRunId).stored.version, ClaimFeedbackDecision.CONFIRMED)
                    }) {
                        toast("已确认；请返回候选列表继续处理以完成重新核对")
                        showCandidateProgress(currentRunId, candidateOffset)
                    }
                }
            }
            if (detail.item.disposition == ProfileCandidateDisposition.READY_TO_MATERIALIZE) {
                cardPrimary(card, "保存为画像候选", "materialize") {
                    confirm("保存为画像候选？", "将把该陈述发布为共享洞察并保存为画像候选；保存后仍需在画像页明确采用，采用是独立操作。", "保存") {
                        runIo({
                            app.profileCandidates.materialize(currentRunId, id, app.analysis.task(currentRunId).stored.version)
                        }) { result ->
                            val message = when (result.status) {
                                ProfileCandidateMaterializationStatus.CREATED ->
                                    "已保存为画像候选。保存不等于采用：请在「候选与历史条目管理」中查看并明确采用。"
                                ProfileCandidateMaterializationStatus.EXISTING ->
                                    "已存在对应的画像条目，本次未新建；这不表示新建成功或已采用，已有条目的采用决定与文字保留。"
                                ProfileCandidateMaterializationStatus.DELETED ->
                                    "对应画像条目已被永久删除，其稳定身份不能再次使用，本次未保存。"
                                ProfileCandidateMaterializationStatus.CONFLICT ->
                                    "与既有画像条目冲突，须通过跨任务维护流程明确处理，不能用其他身份重试；维护流程暂未接入界面，本次未保存。"
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
        beginScreen("changesForm", "画像变化摘要")
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "统计范围")
            Ui.note(card, "按修订时间列出画像的组织变化。计数是操作事实统计，不衡量个人成长、画像准确度或行为改变；同一修订可同时修改多个字段，分类计数不能相加当作总修订数。")
            val fromField = cardField(card, "起始日期（YYYY-MM-DD）", changesFrom.ifEmpty { LocalDate.now().minusDays(30).toString() })
            val throughField = cardField(card, "结束日期（YYYY-MM-DD）", changesThrough.ifEmpty { LocalDate.now().toString() })
            cardButton(card, "查看变化", "openChanges") {
                val query = try {
                    changesFrom = fromField.text.toString()
                    changesThrough = throughField.text.toString()
                    ProfileChangesQuery(parseDate(changesFrom, "起始日期"), parseDate(changesThrough, "结束日期"), zoneId)
                } catch (failure: IllegalArgumentException) {
                    showError(failure)
                    return@cardButton
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
        beginScreen("changes", "画像变化摘要")
        Ui.card(root).also { card ->
            Ui.sectionTitle(card, "统计口径")
            Ui.body(card, "${snapshot.query.from} 至 ${snapshot.query.through} · 共 ${snapshot.total} 个修订")
            Ui.note(card, "字段计数（操作事实统计）：" + ProfileChangedField.entries.joinToString("；") { "${changedFieldLabel(it)} ${snapshot.counts[it] ?: 0}" })
            Ui.note(card, "如实说明：不含已永久删除的历史（includesPermanentlyDeletedHistory=false）；不衡量个人成长（measuresPersonalGrowth=false）。")
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
                if (page.items.isEmpty()) Ui.note(card, "该范围没有画像变化。")
                page.items.forEachIndexed { index, item ->
                    if (index > 0) Ui.divider(card)
                    Ui.body(card, "${item.changedAt} · ${changeLabel(item.change)} · ${dimensionLabel(item.dimension)} · ${layerLabel(item.layer)}\n" +
                        "修订 ${item.previousRevision ?: 0} → ${item.revision} · ${adoptionLabel(item.adoption)} · 字段：${item.fields.joinToString("、", transform = ::changedFieldLabel)}" +
                        (item.maintenanceAction?.let { " · 维护操作：${maintenanceActionLabel(it)}" } ?: ""))
                }
                if (offset > 0) cardButton(card, "上一页", "previousPage") { showChanges(snapshot, (offset - PAGE_SIZE).coerceAtLeast(0)) }
                page.nextOffset?.let { next -> cardButton(card, "下一页", "nextPage") { showChanges(snapshot, next) } }
            }
        }
    }

    private fun chipRow(card: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        card.addView(this)
    }

    private fun cardButton(card: LinearLayout, label: String, tag: String, action: () -> Unit) =
        Ui.button(card, label, tag) { if (!busy) action() }

    private fun cardPrimary(card: LinearLayout, label: String, tag: String, action: () -> Unit) =
        Ui.primary(card, label, tag) { if (!busy) action() }

    private fun cardDanger(card: LinearLayout, label: String, tag: String, action: () -> Unit) =
        Ui.dangerButton(card, label, tag) { if (!busy) action() }

    private fun cardField(card: LinearLayout, hint: String, value: String = "", multiline: Boolean = false, maxLength: Int = 2_000): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0
            minLines = if (multiline) 3 else 1
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setText(value)
            setFreezesText(false)
            isSaveEnabled = false
        }
        Ui.styleField(this, input)
        card.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
        input.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        return input
    }

    private fun cardSpinner(card: LinearLayout, items: List<String>, selected: Int, viewTag: String) = Spinner(this).apply {
        tag = viewTag
        adapter = ArrayAdapter(this@ProfileActivity, android.R.layout.simple_spinner_item, items).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(selected)
        Ui.styleSpinner(this@ProfileActivity, this)
        card.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
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
        ProfileAdoption.CANDIDATE -> "候选"
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
        ProfileRecentState.NOT_APPLICABLE -> "不受近期窗口筛选"
        ProfileRecentState.IN_WINDOW -> "在近期窗口内"
        ProfileRecentState.OUTSIDE_WINDOW -> "已超出近期窗口"
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
        ProfileCandidateStatus.NEEDS_RECOVERY -> "有在途调用待恢复"
        ProfileCandidateStatus.STALE -> "任务来源已失效"
        ProfileCandidateStatus.EXPIRED -> "任务已过期"
        ProfileCandidateStatus.CLOCK_ROLLBACK -> "时钟回拨"
        ProfileCandidateStatus.TASK_UNAVAILABLE -> "任务不可用"
        ProfileCandidateStatus.CONFLICT -> "任务状态冲突"
    }

    private fun dispositionLabel(value: ProfileCandidateDisposition) = when (value) {
        ProfileCandidateDisposition.PENDING_REVIEW -> "待核对"
        ProfileCandidateDisposition.AWAITING_CONFIRMATION -> "待用户确认"
        ProfileCandidateDisposition.READY_TO_MATERIALIZE -> "可保存为画像候选"
        ProfileCandidateDisposition.REVIEW_UNSUPPORTED -> "核对未支持"
        ProfileCandidateDisposition.REVIEW_CONTRADICTED -> "核对发现矛盾"
        ProfileCandidateDisposition.REVIEW_INSUFFICIENT_CONTEXT -> "核对上下文不足"
        ProfileCandidateDisposition.FEEDBACK_BLOCKED -> "用户已否定／纠正／撤回"
        ProfileCandidateDisposition.FEEDBACK_DELETED -> "反馈已删除"
        ProfileCandidateDisposition.KNOWLEDGE_DELETED -> "知识已删除"
        ProfileCandidateDisposition.PROFILE_DELETED -> "画像已删除"
        ProfileCandidateDisposition.EXISTING_PROFILE -> "已有画像条目"
        ProfileCandidateDisposition.PROFILE_CONFLICT -> "与既有画像冲突"
        ProfileCandidateDisposition.MAINTENANCE_REQUIRED -> "须经跨任务维护"
    }

    private fun verdictLabel(value: ReviewVerdict) = when (value) {
        ReviewVerdict.SUPPORTED -> "有限证据下支持（不是事实认证）"
        ReviewVerdict.UNSUPPORTED -> "未支持"
        ReviewVerdict.CONTRADICTED -> "存在矛盾"
        ReviewVerdict.INSUFFICIENT_CONTEXT -> "上下文不足"
    }

    private fun issueLabel(value: ReviewIssue) = when (value) {
        ReviewIssue.USER_REPORT_AS_FACT -> "自述被当作事实"
        ReviewIssue.SCOPE_OVERGENERALIZATION -> "范围过度泛化"
        ReviewIssue.CAUSAL_OVERREACH -> "因果过度推断"
        ReviewIssue.COUNTEREVIDENCE_OMISSION -> "遗漏反例"
        ReviewIssue.INSUFFICIENT_CONTEXT -> "上下文不足"
        ReviewIssue.OTHER -> "其他"
    }

    private fun integrationLabel(value: SynthesisIntegration) = when (value) {
        SynthesisIntegration.INCOMPLETE -> "尚未完成整合"
        SynthesisIntegration.SINGLE_CALL_ALL_AVAILABLE_TEXT -> "单次调用覆盖全部可用文字"
        SynthesisIntegration.MULTIPLE_BATCHES_NOT_INTEGRATED -> "多批尚未整合"
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
