package dev.tracelife.ui

import android.os.Bundle
import android.widget.EditText
import android.content.Intent
import dev.tracelife.MainActivity
import dev.tracelife.knowledge.TopicAliasRule
import dev.tracelife.knowledge.TopicAliasTemplate
import dev.tracelife.knowledge.TopicAliases

/** Explicit local vocabulary management; no model call and no rewriting of original tags. */
class TopicAliasesActivity : ScreenActivity() {
    private var offset = 0
    private var includeArchived = false
    private var editingId: String? = null
    private var editingRevision: Int? = null
    private var canonical = ""
    private var aliasText = ""
    private var canonicalInput: EditText? = null
    private var aliasInput: EditText? = null
    private var restoredForm = false
    private val aliases get() = TopicAliases(app.repository)

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        offset = savedInstanceState?.getInt("offset") ?: 0
        includeArchived = savedInstanceState?.getBoolean("archived") ?: false
        editingId = savedInstanceState?.getString("editingId")
        editingRevision = savedInstanceState?.getInt("editingRevision")?.takeIf { it > 0 }
        canonical = savedInstanceState?.getString("canonical").orEmpty()
        aliasText = savedInstanceState?.getString("aliases").orEmpty()
        restoredForm = savedInstanceState?.getBoolean("form") ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        capture()
        outState.putInt("offset", offset); outState.putBoolean("archived", includeArchived)
        outState.putString("editingId", editingId); outState.putInt("editingRevision", editingRevision ?: 0)
        outState.putString("canonical", canonical); outState.putString("aliases", aliasText)
        outState.putBoolean("form", screen == "form")
        super.onSaveInstanceState(outState)
    }

    override fun showHome() {
        if (restoredForm) { restoredForm = false; showForm(); return }
        canonicalInput = null; aliasInput = null
        beginScreen("list", "主题叫法")
        note("把同一主题的不同叫法连起来，方便查找。例如“学习方法”和“学习策略”。这里只管理导航，不修改原始标签，也不据此判断个人情况。")
        primaryButton("新增叫法", "newTopicAlias") {
            editingId = null; editingRevision = null; canonical = ""; aliasText = ""; showForm()
        }
        val archived = check("包括已归档规则", includeArchived)
        archived.setOnCheckedChangeListener { _, checked -> if (checked != includeArchived) { includeArchived = checked; offset = 0; showHome() } }
        runIo({ aliases.page(offset, PAGE_SIZE, includeArchived) }) { page ->
            val entries = card()
            Ui.sectionTitle(entries, "已保存的叫法")
            Ui.note(entries, "共 ${page.total} 组；最多同时启用 100 组。")
            if (page.items.isEmpty() && page.problems.isEmpty()) Ui.note(entries, "还没有规则。日常记录的原有主题仍可正常查找。")
            page.problems.forEach { problem ->
                Ui.body(entries, "需要修复的叫法" + if (problem.archived) " · 已归档" else "")
                Ui.note(entries, "这组配置未用于查找，其余有效叫法正常使用。${problem.reason}")
                addButton(entries, "查看原文与修订", "sourceAliasProblem:${problem.id}") {
                    startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORD_ID, problem.id))
                }
                addButton(entries, "修复配置", "repairAlias:${problem.id}") {
                    runIo({ aliases.problemRecord(problem.id, problem.revision) }) { record ->
                        editingId = record.id; editingRevision = record.revision
                        canonical = record.draft.answers[TopicAliasTemplate.CANONICAL].orEmpty()
                        aliasText = record.draft.answers[TopicAliasTemplate.ALIASES].orEmpty()
                        showForm()
                    }
                }
                if (!problem.archived) addButton(entries, "归档这组配置", "archiveAliasProblem:${problem.id}") {
                    confirm("归档这组配置？", "原文和历史修订继续保留，可在包括已归档规则中查看。", "归档") {
                        runIo({ aliases.archiveProblem(problem.id, problem.revision) }) { showHome() }
                    }
                }
                Ui.divider(entries)
            }
            page.items.forEachIndexed { index, rule ->
                if (index > 0) Ui.divider(entries)
                Ui.body(entries, rule.canonical + if (rule.archived) " · 已归档" else "")
                Ui.note(entries, rule.aliases.joinToString("、"))
                addButton(entries, "编辑", "editAlias:${rule.id}") { edit(rule) }
                addButton(entries, if (rule.archived) "恢复这组叫法" else "归档这组叫法", "archiveAlias:${rule.id}") {
                    confirm(if (rule.archived) "恢复这组叫法？" else "归档这组叫法？",
                        if (rule.archived) "检查与当前叫法没有冲突后，重新用于导航。" else "之后不再拓展这些叫法。历史修订、原始记录及主题保持可查。",
                        if (rule.archived) "恢复" else "归档") {
                        runIo({ aliases.archive(rule.id, rule.revision, !rule.archived) }) { showHome() }
                    }
                }
            }
            if (offset > 0) addButton(entries, "上一页", "previousAliases") { offset = (offset - PAGE_SIZE).coerceAtLeast(0); showHome() }
            if (page.nextOffset != null) addButton(entries, "下一页", "nextAliases") { offset = requireNotNull(page.nextOffset); showHome() }
        }
    }

    override fun navigateBack() {
        if (busy) return
        if (screen == "form") { capture(); showHome() } else finish()
    }

    private fun edit(rule: TopicAliasRule) {
        editingId = rule.id; editingRevision = rule.revision; canonical = rule.canonical; aliasText = rule.aliases.joinToString("\n")
        showForm()
    }

    private fun showForm() {
        beginScreen("form", if (editingId == null) "新增主题叫法" else "修改主题叫法")
        val form = card()
        canonicalInput = labeledFieldIn(form, "主要叫法", "例如：学习方法", canonical, maxLength = 160).apply { tag = "aliasCanonical" }
        aliasInput = labeledFieldIn(form, "其他叫法", "每行一个，例如：学习策略", aliasText, multiline = true, maxLength = 3400).apply { tag = "aliasTerms" }
        Ui.note(form, "每组 1–20 个别名，每个最多 80 个字符。全角、首尾空格和大小写会统一；同一叫法不能同时属于两组。修改只影响之后的查找。")
        if (editingId != null) Ui.note(form, "保存为新的修订，原配置正文和历史仍保留。")
        primaryIn(form, "保存叫法", "saveTopicAlias") {
            capture()
            val terms = aliasText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            runIo({ aliases.save(canonical, terms, editingId, editingRevision) }) {
                toast("叫法已保存"); editingId = null; editingRevision = null; offset = 0; showHome()
            }
        }
    }

    private fun capture() {
        if (screen == "form") { canonical = canonicalInput?.text?.toString() ?: canonical; aliasText = aliasInput?.text?.toString() ?: aliasText }
    }

    companion object { private const val PAGE_SIZE = 25 }
}
