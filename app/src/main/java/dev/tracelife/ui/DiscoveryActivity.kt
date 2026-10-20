package dev.tracelife.ui

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import dev.tracelife.MainActivity
import dev.tracelife.knowledge.DiscoveryKind
import dev.tracelife.knowledge.DiscoveryPage
import dev.tracelife.knowledge.DiscoveryQuery
import dev.tracelife.knowledge.KnowledgeDiscovery
import dev.tracelife.knowledge.KnowledgeEvidenceState
import java.time.LocalDate

class DiscoveryActivity : ScreenActivity() {
    private var queryText = ""
    private var topic = ""
    private var from = ""
    private var through = ""
    private var includePending = false
    private var submitted = false
    private var pageIndex = 0
    private var queryField: EditText? = null
    private var topicField: EditText? = null
    private var fromField: EditText? = null
    private var throughField: EditText? = null
    private var pendingField: CheckBox? = null
    private var page: DiscoveryPage? = null
    private var refresh = false
    private lateinit var results: LinearLayout

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        queryText = savedInstanceState?.getString("query") ?: intent.getStringExtra(EXTRA_QUERY).orEmpty()
        topic = savedInstanceState?.getString("topic") ?: intent.getStringExtra(EXTRA_TOPIC).orEmpty()
        from = savedInstanceState?.getString("from").orEmpty(); through = savedInstanceState?.getString("through").orEmpty()
        includePending = savedInstanceState?.getBoolean("pending") ?: false
        pageIndex = savedInstanceState?.getInt("pageIndex") ?: 0
        submitted = savedInstanceState?.getBoolean("submitted") ?: queryText.isNotBlank()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        capture()
        outState.putString("query", queryText); outState.putString("topic", topic)
        outState.putString("from", from); outState.putString("through", through)
        outState.putInt("pageIndex", pageIndex)
        outState.putBoolean("pending", includePending); outState.putBoolean("submitted", submitted)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { capture(); refresh = true; super.onPause() }
    override fun onResume() { super.onResume(); if (refresh && !busy) { refresh = false; showHome() } }
    private fun capture() {
        queryText = queryField?.text?.toString() ?: queryText; topic = topicField?.text?.toString() ?: topic
        from = fromField?.text?.toString() ?: from; through = throughField?.text?.toString() ?: through
        includePending = pendingField?.isChecked ?: includePending
    }
    override fun showHome() {
        beginScreen("search", "找回一段经历")
        queryField = labeledField("想找什么", "例如：以前怎么开始复杂任务", queryText, maxLength = 200).apply { tag = "discoveryQuery" }
        note("在这台手机查找记录、复盘答案、知识和经验。可以写几个词，也可以写一句问题。")
        Ui.disclosure(root, "限定查找范围", "discoveryFilters", topic.isNotBlank() || from.isNotBlank() || through.isNotBlank() || includePending) { filters ->
            topicField = labeledFieldIn(filters, "主题", "可留空", topic).apply { tag = "discoveryTopic" }
            fromField = labeledFieldIn(filters, "从哪天", "YYYY-MM-DD，可留空", from).apply { tag = "discoveryFrom" }
            throughField = labeledFieldIn(filters, "到哪天", "YYYY-MM-DD，可留空", through).apply { tag = "discoveryThrough" }
            pendingField = checkIn(filters, "包含待更新的知识与经验", includePending)
        }
        primaryButton("查找", "searchDiscovery") { capture(); submitted = true; pageIndex = 0; search() }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }
        if (submitted && queryText.isNotBlank()) search()
    }
    private fun search(previous: DiscoveryPage? = null) {
        val query = try {
            DiscoveryQuery(queryText.trim(), from.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                through.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse), topic.trim().ifEmpty { null }, includePending)
        } catch (_: java.time.DateTimeException) { showError(IllegalArgumentException("日期请按 YYYY-MM-DD 填写")); return }
        catch (failure: IllegalArgumentException) { showError(failure); return }
        val requestedIndex = pageIndex
        runIo({
            val discovery = KnowledgeDiscovery(app.repository)
            if (previous != null) (requestedIndex + 1) to discovery.search(query, previous = previous)
            else {
                var value = discovery.search(query)
                var reached = 0
                while (reached < requestedIndex && value.hasNext) {
                    value = discovery.search(query, previous = value); reached++
                }
                reached to value
            }
        }) { (reached, value) ->
            pageIndex = reached
            page = value
            results.removeAllViews()
            Ui.sectionTitle(results, "找到 ${value.total} 项 · 第 ${pageIndex + 1} 页")
            Ui.note(results, "按文字匹配排列，结果不代表已回答问题。未命中也不代表相关经历不存在；可以换几个词或放宽日期。")
            if (value.items.isEmpty()) emptyState(results, "这个范围还没有匹配内容。")
            value.items.forEach { hit ->
                val kind = when (hit.kind) { DiscoveryKind.RECORD -> "原始记录"; DiscoveryKind.KNOWLEDGE -> "已保存知识"; DiscoveryKind.EXPERIENCE -> "经验线索" }
                val pending = hit.kind != DiscoveryKind.RECORD && hit.state != KnowledgeEvidenceState.CURRENT
                listRow(results, "${hit.title}\n${hit.excerpt}\n$kind${if (pending) " · 待更新" else ""}", "discoveryHit:${hit.key}") {
                    val target = when (hit.kind) {
                        DiscoveryKind.RECORD -> Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORD_ID, hit.id).putExtra(MainActivity.EXTRA_RECORD_REVISION, hit.revision)
                        DiscoveryKind.KNOWLEDGE -> Intent(this, KnowledgeActivity::class.java).putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID, hit.id).putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_REVISION, hit.revision)
                        DiscoveryKind.EXPERIENCE -> Intent(this, ExperienceActivity::class.java).putExtra(ExperienceActivity.EXTRA_THREAD_ID, hit.id).putExtra(ExperienceActivity.EXTRA_SEQUENCE, hit.experienceSequence ?: -1)
                    }
                    startActivity(target)
                }
            }
            if (pageIndex > 0) addButton(results, "回到最相关的结果", "firstDiscoveryPage") { pageIndex = 0; search() }
            if (value.hasNext) addButton(results, "更多匹配", "nextDiscoveryPage") { search(value) }
            if (value.omittedPending > 0) Ui.note(results, "另有 ${value.omittedPending} 项匹配内容待更新，可在范围中选择查看。")
            Ui.note(results, "检查 ${value.scanned} 个范围内条目；所查原始记录中 ${value.unknownDates} 条日期不确定。仅查当前记录修订，不检索已删除内容或附件图片。")
        }
    }
    companion object { const val EXTRA_TOPIC = "discoveryTopic"; const val EXTRA_QUERY = "discoveryQuery" }
}
