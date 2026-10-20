package dev.tracelife.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Spinner
import dev.tracelife.metrics.ActivityDomain
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.RecordRevision
import dev.tracelife.templates.ActivityLogTemplate
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Optional structured input; saved as ordinary revisioned record answers. */
class ActivityEntryActivity : ScreenActivity() {
    private var recordId = UUID.randomUUID().toString()
    private var expectedRevision: Int? = null
    private var editing = false
    private var restored = false
    private var original: RecordRevision? = null
    private var domainIndex = 1
    private var zoneId = ZoneId.systemDefault().id
    private val values = linkedMapOf("date" to LocalDate.now().toString(), "through" to "", "body" to "",
        "minutes" to "", "practices" to "", "outcome" to "", "context" to "")
    private val inputs = mutableMapOf<String, EditText>()
    private var domains: Spinner? = null

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        val id = savedInstanceState?.getString("recordId") ?: intent.getStringExtra(RECORD_ID)
        if (id != null) recordId = id
        editing = savedInstanceState?.getBoolean("editing") ?: intent.hasExtra(RECORD_ID)
        expectedRevision = savedInstanceState?.getInt("expectedRevision", 0)?.takeIf { it > 0 }
        if (savedInstanceState != null) {
            restored = true
            domainIndex = savedInstanceState.getInt("domain", 1)
            zoneId = savedInstanceState.getString("zoneId") ?: zoneId
            values.keys.forEach { key -> savedInstanceState.getString(key)?.let { values[key] = it } }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        capture()
        outState.putString("recordId", recordId)
        outState.putInt("expectedRevision", expectedRevision ?: 0)
        outState.putBoolean("editing", editing)
        outState.putInt("domain", domainIndex)
        outState.putString("zoneId", zoneId)
        values.forEach { (key, value) -> outState.putString(key, value) }
        super.onSaveInstanceState(outState)
    }

    override fun showHome() {
        beginScreen("activityEntry", if (editing) "修改投入与实践" else "留下一次实践")
        if (editing) runIo({
            val record = requireNotNull(app.repository.record(recordId, expectedRevision)) { "这条记录已删除" }
            val ref = requireNotNull(record.draft.template)
            require(ActivityLogTemplate.supports(requireNotNull(app.repository.template(ref.id, ref.version)).draft.questions)) { "模板已改变，请从记录页编辑原文" }
            record
        }) { record ->
            original = record
            if (!restored || expectedRevision == null) {
                expectedRevision = record.revision
                zoneId = record.draft.occurrence.zoneId
                values["date"] = record.draft.occurrence.startDate?.toString().orEmpty()
                values["through"] = record.draft.occurrence.endDate?.takeIf { it != record.draft.occurrence.startDate }?.toString().orEmpty()
                values["body"] = record.draft.body
                val answers = record.draft.answers
                values["minutes"] = answers[ActivityLogTemplate.MINUTES].orEmpty()
                values["practices"] = answers[ActivityLogTemplate.PRACTICES].orEmpty()
                values["outcome"] = answers[ActivityLogTemplate.OUTCOME].orEmpty()
                values["context"] = answers[ActivityLogTemplate.CONTEXT].orEmpty()
                domainIndex = ActivityDomain.entries.indexOfFirst { it.label == answers[ActivityLogTemplate.DOMAIN] }.coerceAtLeast(0)
            }
            render()
        } else render()
    }

    private fun render() {
        inputs.clear()
        note("只写确定的内容，其他可以留空。一次活动只记一次，投入和次数不代表成长评分。")
        val form = card()
        Ui.fieldLabel(form, "主要领域")
        domains = spinnerIn(form, ActivityDomain.entries.map { it.label }, domainIndex, "activityDomain")
        fun input(key: String, label: String, hint: String, multiline: Boolean = false, max: Int = 2000) =
            labeledFieldIn(form, label, hint, values.getValue(key), multiline, max).also { it.tag = "activity:$key"; inputs[key] = it }
        input("date", "发生日期", "YYYY-MM-DD；未知可留空", max = 10)
        input("through", "跨多天时的结束日期", "单次活动可留空", max = 10)
        input("body", "做了什么", "可以写下任务或过程", true, 20000)
        input("minutes", "投入分钟 · 可选", "实际分钟，不含计划时间", max = 5).inputType = InputType.TYPE_CLASS_NUMBER
        input("practices", "亲自实践次数 · 可选", "独立尝试次数，阅读和收藏不自动算", max = 5).inputType = InputType.TYPE_CLASS_NUMBER
        Ui.note(form, "留空表示未记录；0 只表示你明确填写为 0。不要把同一段时间分别计入工作和学习。")
        input("outcome", "留下的成果 · 可选", "具体完成了什么，产出在哪里", true, 4000)
        input("context", "方法与条件 · 可选", "怎样做的，哪些条件影响了过程", true, 4000)
        primaryButton("保存记录", "saveActivity") { save() }
        note("仍是一条普通记录，支持修订、归档、删除和完整备份；跨多天或未知日期的数值不分摊到每日图表。")
    }

    private fun capture() {
        inputs.forEach { (key, value) -> values[key] = value.text.toString() }
        domains?.let { domainIndex = it.selectedItemPosition }
    }

    private fun save() {
        capture()
        val parsed = try {
            val minutes = ActivityLogTemplate.number(values.getValue("minutes"), 1440, "投入分钟")
            val practices = ActivityLogTemplate.number(values.getValue("practices"), 10000, "实践次数")
            val from = values.getValue("date").trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
            val through = values.getValue("through").trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse) ?: from
            require(from != null || through == null) { "填写结束日期时也需要开始日期" }
            require(values.getValue("body").isNotBlank() || minutes != null || practices != null ||
                values.getValue("outcome").isNotBlank() || values.getValue("context").isNotBlank()) { "至少留下一项实际内容" }
            Triple(Occurrence(from, through, zoneId), minutes, practices)
        } catch (failure: Exception) {
            showError(if (failure is java.time.format.DateTimeParseException) IllegalArgumentException("日期请使用 YYYY-MM-DD") else failure)
            return
        }
        val captured = values.toMap()
        val domain = ActivityDomain.entries[domainIndex]
        runIo({
            val answers = linkedMapOf(ActivityLogTemplate.DOMAIN to domain.label)
            parsed.second?.let { answers[ActivityLogTemplate.MINUTES] = it.toString() }
            parsed.third?.let { answers[ActivityLogTemplate.PRACTICES] = it.toString() }
            captured.getValue("outcome").takeIf { it.isNotBlank() }?.let { answers[ActivityLogTemplate.OUTCOME] = it }
            captured.getValue("context").takeIf { it.isNotBlank() }?.let { answers[ActivityLogTemplate.CONTEXT] = it }
            val draft = RecordDraft(captured.getValue("body"), parsed.first,
                original?.draft?.topics.orEmpty() + domain.label, original?.draft?.template ?: ActivityLogTemplate.obtain(app.repository),
                answers, original?.draft?.attachmentIds.orEmpty())
            val current = app.repository.record(recordId)
            val expected = expectedRevision
            if (current != null && current.draft == draft && (expected == null || current.revision == expected + 1)) current
            else if (expected == null) app.repository.createRecord(draft, recordId)
            else app.repository.updateRecord(recordId, expected, draft)
        }) { record ->
            app.io.execute { app.backgroundScheduler.wake() }
            setResult(Activity.RESULT_OK, Intent().putExtra(RECORD_ID, record.id))
            toast("记录已保存")
            finish()
        }
    }

    override fun navigateBack() {
        if (busy) return
        capture()
        if (values.filterKeys { it != "date" && it != "through" }.values.any { it.isNotBlank() }) {
            confirm("离开这条记录？", "尚未保存的修改会丢失。", "离开") { finish() }
        } else finish()
    }

    companion object { const val RECORD_ID = "activityRecordId" }
}
