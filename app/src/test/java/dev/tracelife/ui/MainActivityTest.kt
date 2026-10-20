package dev.tracelife.ui

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import dev.tracelife.MainActivity
import dev.tracelife.TraceLifeApplication
import dev.tracelife.model.Cadence
import dev.tracelife.model.Occurrence
import dev.tracelife.model.RecordDraft
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import dev.tracelife.templates.BuiltInTemplates
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Synthetic user journeys; no real personal data or external service is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MainActivityTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var app: TraceLifeApplication
    private val activity get() = controller.get()

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        drain()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        app.io.submit { app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `draft recreation preserves occurrence topics and body before saving once`() {
        click("newRecord")
        input("body", "合成资料：周末实践了新的学习方法")
        click("recordMetadata")
        input("topics", "学习，工作")
        view<Spinner>("dateMode").setSelection(1)
        input("start", "2026-10-01")
        input("end", "2026-10-03")
        click("recordMetadata")
        assertEquals(View.GONE, view<View>("recordMetadata:content").visibility)
        controller.recreate()
        drain()
        assertEquals("合成资料：周末实践了新的学习方法", view<EditText>("body").text.toString())
        assertEquals("2026-10-01", view<EditText>("start").text.toString())
        assertEquals(1, view<Spinner>("dateMode").selectedItemPosition)
        click("saveRecord")
        val records = app.repository.listRecords().items
        assertEquals(1, records.size)
        assertEquals(LocalDate.parse("2026-10-01"), records.single().draft.occurrence.startDate)
        assertEquals(LocalDate.parse("2026-10-03"), records.single().draft.occurrence.endDate)
        assertEquals(setOf("学习", "工作"), records.single().draft.topics)
    }

    @Test fun `accepting two topic suggestions saves separate tags and preserves existing spelling`() {
        click("newRecord")
        input("body", "合成资料：今天学习了一个方法，并在工作中实践。")
        click("recordMetadata")
        // A pre-existing single tag may contain a Chinese enumeration comma.
        input("topics", "个人、项目")
        click("suggestRecordTopics")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val list = dialog.listView
        for (topic in listOf("工作", "学习")) {
            val index = (0 until list.adapter.count).single { list.adapter.getItem(it).toString().startsWith("$topic · ") }
            list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        controller.recreate()
        drain()
        click("saveRecord")
        val saved = app.repository.listRecords().items.single()
        assertEquals(setOf("个人、项目", "工作", "学习"), saved.draft.topics)
        assertFalse("工作、学习" in saved.draft.topics)
    }

    @Test fun `recreation during save retries stable identity without a duplicate record`() {
        click("newRecord")
        input("body", "合成资料：保存中的草稿")
        // Pause the worker so rotation certainly happens before the save callback.
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        app.io.execute { entered.countDown(); release.await(20, TimeUnit.SECONDS) }
        assertTrue(entered.await(20, TimeUnit.SECONDS))
        view<View>("saveRecord").performClick()
        controller.recreate()
        release.countDown()
        drain()
        click("saveRecord")
        assertEquals(1, app.repository.listRecords().total)
        assertEquals(1, app.repository.history(app.repository.listRecords().items.single().id).size)
    }

    @Test fun `old review continues to edit its bound question after template changes`() {
        val original = app.repository.saveTemplate(TemplateDraft("合成模板", Cadence.DAILY, questions = listOf(TemplateQuestion("q1", "原问题"))))
        click("templates")
        click("useTemplate:${original.id}")
        input("answer:q1", "合成资料：保留旧问题的回答")
        controller.recreate()
        drain()
        assertEquals("合成资料：保留旧问题的回答", view<EditText>("answer:q1").text.toString())
        click("saveRecord")
        val record = app.repository.listRecords().items.single()
        app.repository.saveTemplate(original.draft.copy(questions = listOf(TemplateQuestion("q2", "新问题"))), original.id, original.version)
        click("editRecord")
        assertEquals("合成资料：保留旧问题的回答", view<EditText>("answer:q1").text.toString())
        assertNull(activity.window.decorView.findViewWithTag<View>("answer:q2"))
        input("answer:q1", "合成资料：修订旧问题的回答")
        click("saveRecord")
        assertEquals(original.version, app.repository.record(record.id)!!.draft.template!!.version)
        assertEquals(2, app.repository.history(record.id).size)
    }

    @Test fun `template import survives recreation requires preview and preserves optional questions`() {
        click("templates")
        click("importTemplate")
        input("templateName", "合成模板")
        input("markdown", "# 合成模板\n\n## 发生了什么\n可选提示\n\n## 有什么经验\n")
        controller.recreate()
        drain()
        assertTrue(view<EditText>("markdown").text.toString().contains("## 发生了什么"))
        assertFalse(app.repository.listTemplates().any { it.draft.name == "合成模板" })
        click("previewTemplate")
        val preview = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(preview.isShowing)
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        val saved = app.repository.listTemplates().single { it.draft.name == "合成模板" }
        assertEquals(listOf("发生了什么", "有什么经验"), saved.draft.questions.map { it.prompt })
        click("useTemplate:${saved.id}")
        input("answer:${saved.draft.questions.first().id}", "合成资料：只填写一个问题")
        click("saveRecord")
        assertEquals(1, app.repository.listRecords().items.single().draft.answers.size)
    }

    @Test fun `stale editor retains text and does not overwrite another revision`() {
        val original = app.repository.createRecord(RecordDraft(body = "合成资料：初始内容"))
        controller.recreate()
        drain()
        click("record:${original.id}")
        click("editRecord")
        input("body", "合成资料：当前未提交的输入")
        app.repository.updateRecord(original.id, original.revision, original.draft.copy(body = "合成资料：另一处已保存内容"))
        click("saveRecord")
        assertEquals("合成资料：当前未提交的输入", view<EditText>("body").text.toString())
        assertEquals("合成资料：另一处已保存内容", app.repository.record(original.id)!!.draft.body)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.findViewById<TextView>(android.R.id.message).text.contains("资料已经发生修改"))
    }

    @Test fun `back navigation asks before discarding unsaved input`() {
        click("newRecord")
        input("body", "合成资料：尚未保存")
        click("back")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals("合成资料：尚未保存", view<EditText>("body").text.toString())
        click("saveRecord")
        assertEquals("合成资料：尚未保存", app.repository.listRecords().items.single().draft.body)
    }

    @Test fun `removing an existing image changes the draft while preserving history`() {
        val attachment = app.repository.importAttachment("synthetic image".byteInputStream(), "synthetic.png", "image/png")
        val original = app.repository.createRecord(RecordDraft(body = "合成资料：含图记录", attachmentIds = setOf(attachment.id)))
        controller.recreate()
        drain()
        click("record:${original.id}")
        click("editRecord")
        click("removeAttachment:${attachment.id}")
        assertNull(activity.window.decorView.findViewWithTag<View>("removeAttachment:${attachment.id}"))
        click("saveRecord")
        assertTrue(app.repository.record(original.id)!!.draft.attachmentIds.isEmpty())
        assertEquals(setOf(attachment.id), app.repository.record(original.id, 1)!!.draft.attachmentIds)
        assertNotNull(app.repository.attachment(attachment.id))
    }

    @Test fun `renaming a structured template preserves its question identity and intent`() {
        val original = app.repository.saveTemplate(BuiltInTemplates.daily, "synthetic-daily")
        click("templates")
        click("editTemplate:${original.id}")
        input("templateName", "合成资料：重新命名的每日模板")
        click("previewTemplate")
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        drain()
        val updated = app.repository.template(original.id)!!
        assertEquals(2, updated.version)
        assertEquals("合成资料：重新命名的每日模板", updated.draft.name)
        assertEquals(original.draft.questions, updated.draft.questions)
    }

    @Test fun `date and topic filters include interval overlap and disclose unknown time`() {
        val overlap = app.repository.createRecord(RecordDraft("合成资料：区间记录", Occurrence(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03")), setOf("学习")))
        val unknown = app.repository.createRecord(RecordDraft("合成资料：未知日期", topics = setOf("学习")))
        val excluded = app.repository.createRecord(RecordDraft("合成资料：其他主题", Occurrence(LocalDate.parse("2026-10-02")), setOf("运动")))
        click("recordFilters")
        input("filterFrom", "2026-10-02")
        input("filterThrough", "2026-10-02")
        input("filterTopic", "学习")
        click("applyFilter")
        assertNotNull(activity.window.decorView.findViewWithTag<View>("record:${overlap.id}"))
        assertNotNull(activity.window.decorView.findViewWithTag<View>("record:${unknown.id}"))
        assertNull(activity.window.decorView.findViewWithTag<View>("record:${excluded.id}"))
        val results = activity.window.decorView.findViewWithTag<android.view.ViewGroup>("recordResults")
        assertTrue((results.getChildAt(0) as TextView).text.contains("发生日期未知 1 条"))
    }

    @Test fun `home keeps records visible and sidebar reaches settings without a long navigation list`() {
        val record = app.repository.createRecord(RecordDraft("合成资料：记录应该直接可见"))
        controller.recreate()
        drain()
        assertEquals(View.GONE, view<View>("recordFilters:content").visibility)
        assertTrue(view<View>("record:${record.id}").isShown)
        assertNull(activity.window.decorView.findViewWithTag<View>("navSettings"))
        click("navigation")
        val menu = ShadowDialog.getLatestDialog()
        assertTrue(menu.isShowing)
        listOf("navReflection", "navDashboard", "navReview", "navKnowledge", "navActions", "navPeriod", "navProfile", "navTransfer", "navSettings")
            .forEach { assertNotNull(menu.findViewById<View>(android.R.id.content).findViewWithTag<View>(it)) }
        menu.findViewById<View>(android.R.id.content).findViewWithTag<View>("navSettings").performClick()
        assertFalse(menu.isShowing)
        assertEquals(SettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    }

    @Test fun `clearing collapsed filters restores other records without changing data`() {
        val learning = app.repository.createRecord(RecordDraft("合成资料：学习记录", topics = setOf("学习")))
        val work = app.repository.createRecord(RecordDraft("合成资料：工作记录", topics = setOf("工作")))
        click("recordFilters")
        input("filterTopic", "学习")
        click("applyFilter")
        assertNotNull(activity.window.decorView.findViewWithTag<View>("record:${learning.id}"))
        assertNull(activity.window.decorView.findViewWithTag<View>("record:${work.id}"))
        click("recordFilters")
        click("recordFilters")
        assertEquals("学习", view<EditText>("filterTopic").text.toString())
        click("clearFilters")
        assertNotNull(activity.window.decorView.findViewWithTag<View>("record:${work.id}"))
        assertEquals(View.GONE, view<View>("recordFilters:content").visibility)
        assertEquals(2, app.repository.listRecords().total)
    }

    @Test fun `analysis is reachable from home and a saved records actual period`() {
        click("analyzeRecords")
        assertEquals(ReflectionActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertTrue(app.reflectionWorkflow.history().isEmpty())
        val date = LocalDate.parse("2026-09-12")
        val record = app.repository.createRecord(RecordDraft("合成资料：先动手再查资料，更容易找到具体问题", Occurrence(date)))
        controller.recreate()
        drain()
        click("record:${record.id}")
        click("analyzeRecordPeriod")
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(ReflectionActivity::class.java.name, intent.component!!.className)
        assertEquals(date.toString(), intent.getStringExtra(ReflectionActivity.EXTRA_FROM))
        assertEquals(date.toString(), intent.getStringExtra(ReflectionActivity.EXTRA_THROUGH))
        assertTrue(app.reflectionWorkflow.history().isEmpty())
    }

    @Test fun `returning home refreshes persisted analysis without dispatching a model`() {
        controller.pause().stop()
        val date = LocalDate.now()
        val started = app.reflectionWorkflow.start(dev.tracelife.storage.AnalysisQuery(date, date, "Asia/Shanghai"))
        controller.start().resume()
        drain()
        click("latestReflection")
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(started.id, intent.getStringExtra(ReflectionActivity.EXTRA_RUN_ID))
        assertEquals(0, app.reflectionWorkflow.inspect(started.id).totalRequests)
    }

    private fun input(tag: String, value: String) { view<EditText>(tag).setText(value) }
    private fun click(tag: String) { assertTrue("Missing/enabled action $tag", view<View>(tag).performClick()); drain() }
    private fun <T : View> view(tag: String): T = requireNotNull(activity.window.decorView.findViewWithTag<T>(tag)) { "Missing view $tag" }
    private fun drain() {
        repeat(4) {
            app.io.submit {}.get(20, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
