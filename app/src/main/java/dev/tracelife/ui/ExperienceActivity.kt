package dev.tracelife.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import dev.tracelife.knowledge.ExperienceMemberView
import dev.tracelife.knowledge.ExperiencePage
import dev.tracelife.knowledge.ExperienceRelation
import dev.tracelife.knowledge.ExperienceView
import dev.tracelife.knowledge.KnowledgeContinuity
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.storage.ClaimFeedbackDecision

/** Reading and explicit user linking only. No model request runs from this screen. */
class ExperienceActivity : ScreenActivity() {
    private val continuity get() = KnowledgeContinuity(app.repository)
    private var mode = "catalog"
    private var threadId: String? = null
    private var knowledgeId: String? = null
    private var knowledgeRevision = -1
    private var relatedKnowledgeId: String? = null
    private var topic: String? = null
    private var titleDraft = ""
    private var noteDraft = ""
    private var relationDraft = ExperienceRelation.SUPPLEMENT
    private var expectedVersion: Int? = null
    private var draftThreadId = java.util.UUID.randomUUID().toString()
    private var titleField: EditText? = null
    private var noteField: EditText? = null
    private var page: ExperiencePage? = null
    private var detail: ExperienceView? = null
    private var refreshOnResume = false

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            mode = savedInstanceState.getString("mode", "catalog")
            threadId = savedInstanceState.getString("threadId")
            knowledgeId = savedInstanceState.getString("knowledgeId")
            knowledgeRevision = savedInstanceState.getInt("knowledgeRevision", -1)
            relatedKnowledgeId = savedInstanceState.getString("relatedKnowledgeId")
            topic = savedInstanceState.getString("topic")
            titleDraft = savedInstanceState.getString("title", "")
            noteDraft = savedInstanceState.getString("note", "")
            relationDraft = ExperienceRelation.valueOf(savedInstanceState.getString("relation", ExperienceRelation.SUPPLEMENT.name))
            expectedVersion = savedInstanceState.getInt("version", -1).takeIf { it > 0 }
            draftThreadId = savedInstanceState.getString("draftThreadId") ?: draftThreadId
        } else {
            threadId = intent.getStringExtra(EXTRA_THREAD_ID)
            knowledgeId = intent.getStringExtra(EXTRA_KNOWLEDGE_ID)
            knowledgeRevision = intent.getIntExtra(EXTRA_KNOWLEDGE_REVISION, -1)
            relatedKnowledgeId = intent.getStringExtra(EXTRA_RELATED_KNOWLEDGE_ID)
            topic = intent.getStringExtra(EXTRA_TOPIC)?.ifBlank { null }
            if (threadId != null) mode = "detail"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureDraft()
        outState.putString("mode", mode); outState.putString("threadId", threadId)
        outState.putString("knowledgeId", knowledgeId); outState.putInt("knowledgeRevision", knowledgeRevision)
        outState.putString("relatedKnowledgeId", relatedKnowledgeId); outState.putString("topic", topic)
        outState.putString("title", titleDraft); outState.putString("note", noteDraft)
        outState.putString("relation", relationDraft.name); outState.putInt("version", expectedVersion ?: -1)
        outState.putString("draftThreadId", draftThreadId)
        super.onSaveInstanceState(outState)
    }
    override fun showHome() {
        when (mode) {
            "detail" -> showThread(requireNotNull(threadId))
            "create", "append" -> showForm()
            else -> showCatalog()
        }
    }
    override fun onPause() { super.onPause(); refreshOnResume = true }
    override fun onResume() {
        super.onResume()
        if (refreshOnResume && !busy) {
            refreshOnResume = false
            if (mode == "detail") threadId?.let { showThread(it) }
            else if (mode == "catalog") showCatalog()
        }
    }
    override fun navigateBack() {
        if (busy) return
        when (mode) {
            "create", "append" -> { captureDraft(); expectedVersion = null; showCatalog() }
            "detail" -> { threadId = null; showCatalog() }
            else -> finish()
        }
    }
    private fun captureDraft() {
        if (mode in setOf("create", "append")) {
            titleDraft = titleField?.text?.toString() ?: titleDraft
            noteDraft = noteField?.text?.toString() ?: noteDraft
        }
    }

    private fun showCatalog(offset: Int = 0, previous: ExperiencePage? = null) {
        mode = "catalog"
        titleField = null; noteField = null
        beginScreen("catalog", if (knowledgeId == null) "经验线索" else "把新理解接到已有经验")
        Ui.note(root, "同一条经验可以随着不同周的经历补充、出现例外，或者改变。这里保存你选择的关联，不把关联当作新证据。")
        if (knowledgeId != null) {
            primaryButton("建立一条新的经验线索", "newExperience") {
                threadId = null; expectedVersion = null; mode = "create"; showForm()
            }
            section("或加入一条已有经验")
        }
        topic?.let { note("主题：$it") }
        runIo({ continuity.page(topic, relatedKnowledgeId, offset, 20, previous) }) { result ->
            page = result
            if (result.items.isEmpty()) note(if (knowledgeId == null) "还没有经验线索。打开一条已保存的知识，从它开始积累。" else "这里还没有经验线索，可以先为这条理解起一个名字。")
            result.items.forEach { item ->
                val wording = item.current.knowledge?.value?.claim?.text?.take(100) ?: "相关知识已删除，独立说明仍保留"
                listRow(root, "${item.thread.title}\n${item.thread.version} 次积累 · ${memberState(item.current)}\n$wording", "experienceChoice:${item.thread.id}") {
                    threadId = item.thread.id
                    if (knowledgeId != null) {
                        expectedVersion = item.thread.version; mode = "append"; showForm()
                    } else showThread(item.thread.id)
                }
            }
            if (offset > 0) button("回到最近更新", "experienceFirstPage") { showCatalog() }
            result.nextOffset?.let { next -> button("更早的经验", "experienceNextPage") { showCatalog(next, result) } }
            if (topic != null) button("查看所有主题的经验", "allExperienceTopics") { topic = null; showCatalog() }
        }
    }

    private fun showForm() {
        val create = mode == "create"
        titleField = null; noteField = null
        beginScreen(mode, if (create) "建立经验线索" else "接上这次新的理解")
        val sourceId = requireNotNull(knowledgeId)
        runIo({
            val source = requireNotNull(app.knowledge.get(sourceId, knowledgeRevision)) { "这条知识不存在或已删除" }
            require(source.isCurrentRevision && source.evidenceState == KnowledgeEvidenceState.CURRENT) { "这条知识已变化，请重新核对后再关联" }
            val thread = if (create) null else requireNotNull(continuity.get(requireNotNull(threadId))) { "经验线索已删除" }
            source to thread
        }) { (source, thread) ->
            card().also {
                Ui.sectionTitle(it, "这次准备加入的内容")
                Ui.body(it, source.value.claim.text)
                Ui.note(it, "涉及 ${source.value.scope.from} — ${source.value.scope.through} 的记录")
            }
            if (create) {
                titleField = labeledField("给这条经验起个名字", "例如：开始复杂任务前的准备", titleDraft, maxLength = 160).apply { tag = "experienceTitle" }
                note("主题沿用这条知识：${source.value.topics.joinToString("、").ifBlank { "未设置" }}")
            } else {
                val selected = requireNotNull(thread)
                section(selected.thread.title)
                note("目前采用的理解：${selected.current.knowledge?.value?.claim?.text ?: "相关知识已删除"}")
                note("${memberState(selected.current)}。补充与例外保留原理解；收窄与替代把这次知识设为目前采用的理解。")
                val relations = ExperienceRelation.entries.filter { it != ExperienceRelation.INITIAL }
                spinner(relations.map(::relationLabel), relations.indexOf(relationDraft), "experienceRelation").apply {
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                            relationDraft = relations[position]
                        }
                    }
                }
            }
            noteField = labeledField("我的说明", if (create) "可留空；写下你自己的理解" else "补充可留空；例外、收窄和替代请写明原因", noteDraft, multiline = true).apply { tag = "experienceNote" }
            note("这些文字由你独立保存。旧理解与依据保留各自的版本，不会被新关联覆盖。")
            primaryButton(if (create) "建立经验线索" else "保存这次变化", "saveExperience") {
                captureDraft()
                runIo({
                    if (create) continuity.create(sourceId, source.value.revision, titleDraft, source.value.topics, noteDraft, draftThreadId)
                    else continuity.append(requireNotNull(threadId), requireNotNull(expectedVersion), sourceId, source.value.revision, relationDraft, noteDraft)
                }) { saved ->
                    knowledgeId = null; knowledgeRevision = -1
                    titleDraft = ""; noteDraft = ""; expectedVersion = null
                    draftThreadId = java.util.UUID.randomUUID().toString()
                    toast("已保存到这条经验")
                    showThread(saved.id)
                }
            }
        }
    }

    private fun showThread(id: String, offset: Int = 0, previous: ExperienceView? = null) {
        mode = "detail"; threadId = id
        beginScreen("detail", "这条经验的变化")
        runIo({ requireNotNull(continuity.get(id, offset, 20, previous)) { "经验线索已删除" } }) { view ->
            detail = view
            section(view.thread.title)
            note("${view.thread.version} 次积累 · ${view.thread.topics.joinToString("、").ifBlank { "未设置主题" }}")
            card().also { current ->
                Ui.sectionTitle(current, "目前采用的理解")
                renderMember(current, view.current, "current")
                if (view.current.knowledge?.evidenceState != KnowledgeEvidenceState.CURRENT)
                    Ui.note(current, "这次采用的内容需要更新。不会自动把更早的理解恢复为当前结论。")
            }
            note("新发现可从知识详情的「建立或加入经验线索」继续接到这里。")
            section("形成过程 · 从最近一次变化回看")
            note("补充与例外保留在过程中；每次收窄或替代都有原来的理解可以回看。")
            view.changes.forEach { item ->
                card().also { change ->
                    Ui.sectionTitle(change, "第 ${item.change.sequence} 次 · ${relationLabel(item.change.relation)}")
                    if (item.change.sequence == view.current.change.sequence) {
                        Ui.note(change, "目前采用这一项。内容、说明与依据见上方。")
                    } else renderMember(change, item, "change:${item.change.sequence}")
                }
            }
            if (offset > 0) button("回到最近变化", "experienceHistoryFirst") { showThread(id) }
            view.nextOffset?.let { next -> button("更早的形成过程", "experienceHistoryNext") { showThread(id, next, view) } }
            Ui.disclosure(root, "管理这条经验", "experienceManagement") { management ->
                dangerIn(management, "永久删除经验线索", "deleteExperience") {
                    confirm("永久删除这条经验线索？", "线索和其中独立写下的说明都会删除，无法撤销。原有知识、经历和行动仍保留。", "永久删除") {
                        runIo({ continuity.delete(id, view.thread.version) }) { toast("经验线索已删除"); threadId = null; showCatalog() }
                    }
                }
            }
        }
    }

    private fun renderMember(parent: android.widget.LinearLayout, item: ExperienceMemberView, prefix: String) {
        val source = item.knowledge
        Ui.body(parent, source?.value?.claim?.text ?: "关联知识已被删除，原正文不再展示。")
        Ui.note(parent, memberState(item))
        source?.value?.let { Ui.note(parent, "分析范围 ${it.scope.from} — ${it.scope.through} · 固定第 ${it.revision} 版") }
        item.change.userNote?.let { Ui.note(parent, "我当时写下的说明"); Ui.body(parent, it) }
        item.feedback?.feedback?.takeIf { it.decision != ClaimFeedbackDecision.CONFIRMED }?.let { feedback ->
            Ui.note(parent, when (feedback.decision) {
                ClaimFeedbackDecision.CORRECTED -> "后来纠正了这条内容"
                ClaimFeedbackDecision.REJECTED -> "后来否定了这条内容"
                ClaimFeedbackDecision.WITHDRAWN -> "后来撤回了确认"
                else -> "我的反馈"
            })
            feedback.userText?.let { Ui.body(parent, it) }
        }
        Ui.note(parent, "加入于 ${item.change.createdAt.atZone(java.time.ZoneId.systemDefault()).format(DISPLAY_TIME)}")
        if (source != null) addButton(parent, "查看这次理解与依据", "$prefix:knowledge") {
            startActivity(Intent(this, KnowledgeActivity::class.java)
                .putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID, item.change.knowledgeId)
                .putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_REVISION, item.change.knowledgeRevision))
        }
    }
    private fun memberState(item: ExperienceMemberView): String = when (item.knowledge?.evidenceState) {
        KnowledgeEvidenceState.CURRENT -> if (item.knowledge.isCurrentRevision) "依据当前有效" else "历史知识修订"
        KnowledgeEvidenceState.STALE -> "来源变化 · 待更新"
        KnowledgeEvidenceState.EXPIRED -> "已过期"
        KnowledgeEvidenceState.FEEDBACK_CHANGED -> "反馈变化 · 待更新"
        KnowledgeEvidenceState.REVIEW_CHANGED -> "核对变化 · 待更新"
        null -> "关联知识已删除"
    }
    private fun relationLabel(relation: ExperienceRelation): String = when (relation) {
        ExperienceRelation.INITIAL -> "起点"
        ExperienceRelation.SUPPLEMENT -> "补充经历或理解"
        ExperienceRelation.EXCEPTION -> "出现例外"
        ExperienceRelation.NARROW -> "收窄适用条件"
        ExperienceRelation.REPLACE -> "用新理解替代"
    }
    companion object {
        private val DISPLAY_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        const val EXTRA_THREAD_ID = "experienceThreadId"
        const val EXTRA_KNOWLEDGE_ID = "experienceSourceKnowledgeId"
        const val EXTRA_KNOWLEDGE_REVISION = "experienceSourceRevision"
        const val EXTRA_RELATED_KNOWLEDGE_ID = "experienceRelatedKnowledgeId"
        const val EXTRA_TOPIC = "experienceTopic"
    }
}
