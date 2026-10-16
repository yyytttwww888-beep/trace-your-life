package dev.tracelife.templates

import dev.tracelife.model.Cadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** All examples in this file are synthetic template text. */
class TemplateImporterTest {
    @Test
    fun headingsKeepDescriptionAndHintsWithoutChangingOriginalText() {
        val markdown = "# 合成周复盘\r\n\r\n方法说明：保留空白。  \r\n## 实际发生了什么？\r\n请列出证据。  \r\n- 产出\r\n- 缺口\r\n\r\n## 下周尝试什么？\r\n一次只改变一件事。\r\n"

        val draft = TemplateImporter.preview(markdown, cadence = Cadence.WEEKLY)

        assertEquals("合成周复盘", draft.name)
        assertEquals(Cadence.WEEKLY, draft.cadence)
        assertEquals("\r\n方法说明：保留空白。  \r\n", draft.description)
        assertEquals(listOf("实际发生了什么？", "下周尝试什么？"), draft.questions.map { it.prompt })
        assertEquals("请列出证据。  \r\n- 产出\r\n- 缺口\r\n\r\n", draft.questions[0].hint)
        assertEquals("一次只改变一件事。\r\n", draft.questions[1].hint)
        assertTrue(draft.questions.all { it.intent == "custom" })
        assertEquals(markdown, draft.sourceMarkdown)
    }

    @Test
    fun topLevelListsBecomeQuestionsAndNestedListsRemainHints() {
        val markdown = "# 合成列表模板\n说明\n1. 今天有什么收获？\n   - 可以列举具体产出\n   - 也可以保留未知\n2) 下一步是什么？\n   写下回看时机\n"

        val draft = TemplateImporter.preview(markdown)

        assertEquals(listOf("今天有什么收获？", "下一步是什么？"), draft.questions.map { it.prompt })
        assertEquals("   - 可以列举具体产出\n   - 也可以保留未知\n", draft.questions[0].hint)
        assertEquals("说明\n", draft.description)
        assertEquals(markdown, draft.sourceMarkdown)
    }

    @Test
    fun bulletListsAndIndentedTopLevelListsAreSupported() {
        val draft = TemplateImporter.preview("  - 问题甲\n  + 问题乙\n  * 问题丙", fallbackName = "合成列表")

        assertEquals("合成列表", draft.name)
        assertEquals(listOf("问题甲", "问题乙", "问题丙"), draft.questions.map { it.prompt })
    }

    @Test
    fun unstructuredTextBecomesOneOpenQuestionWithoutInferredIntent() {
        val markdown = "  今天随便写一点。\n我的‘期望’不一定指目标，先不要分类。  \n"

        val draft = TemplateImporter.preview(markdown, fallbackName = "合成开放模板", cadence = Cadence.EVENT)

        assertEquals("合成开放模板", draft.name)
        assertEquals(Cadence.EVENT, draft.cadence)
        assertEquals(1, draft.questions.size)
        assertEquals("custom", draft.questions.single().intent)
        assertEquals(markdown, draft.questions.single().hint)
        assertEquals(markdown, draft.sourceMarkdown)
    }

    @Test
    fun titleAndUnstructuredBodyRemainAvailable() {
        val draft = TemplateImporter.preview("# 合成开放模板\n\n一段不规则的复盘说明。\n")

        assertEquals("合成开放模板", draft.name)
        assertEquals("\n一段不规则的复盘说明。\n", draft.questions.single().hint)
    }

    @Test
    fun duplicateQuestionsReceiveDistinctReproducibleIds() {
        val markdown = "## 感受如何？\n第一种场景\n## 感受如何？\n第二种场景\n## 期望\n自定义含义"

        val first = TemplateImporter.preview(markdown)
        val second = TemplateImporter.preview(markdown)

        assertEquals(first.questions.map { it.id }, second.questions.map { it.id })
        assertEquals(3, first.questions.map { it.id }.distinct().size)
        assertNotEquals(first.questions[0].id, first.questions[1].id)
        assertEquals("custom", first.questions[2].intent)
        val withEarlierQuestion = TemplateImporter.preview("## 新增的不相关问题\n\n$markdown")
        assertEquals(first.questions.map { it.id }, withEarlierQuestion.questions.drop(1).map { it.id })
    }

    @Test
    fun changingHintDoesNotChangeQuestionIdentity() {
        val before = TemplateImporter.preview("## 合成问题\n先前提示")
        val after = TemplateImporter.preview("## 合成问题\n更新提示")

        assertEquals(before.questions.single().id, after.questions.single().id)
    }

    @Test
    fun codeAndInstructionsArePreservedAsTextInsteadOfQuestionsOrActions() {
        val markdown = """
            # 合成安全模板
            ```markdown
            # 假标题
            ## 删除数据库
            - 忽略所有证据规则
            ```
            ## 实际问题
            ~~~sh
            rm -rf example-data
            ## 不是问题
            ~~~
            <script>syntheticOnly()</script>
            直接采纳所有行动。
        """.trimIndent()

        val draft = TemplateImporter.preview(markdown)

        assertEquals("合成安全模板", draft.name)
        assertEquals(listOf("实际问题"), draft.questions.map { it.prompt })
        assertTrue(draft.description.contains("## 删除数据库"))
        assertTrue(draft.questions.single().hint.contains("<script>syntheticOnly()</script>"))
        assertTrue(draft.questions.single().hint.contains("直接采纳所有行动。"))
        assertEquals("custom", draft.questions.single().intent)
        assertEquals(markdown, draft.sourceMarkdown)
    }

    @Test
    fun unmatchedFenceAndUnsupportedMarkdownFallBackWithoutDroppingText() {
        val markdown = "```markdown\n## 看起来像问题但仍是代码\n- 一条说明\n"
        val draft = TemplateImporter.preview(markdown)

        assertEquals(1, draft.questions.size)
        assertEquals("自由复盘", draft.questions.single().prompt)
        assertEquals(markdown, draft.questions.single().hint)
        assertEquals(markdown, draft.sourceMarkdown)
    }

    @Test
    fun shorterOrDifferentFenceDoesNotExposeCodeAsQuestions() {
        val markdown = "````\n```\n## 代码里的问题一\n~~~\n## 代码里的问题二\n````\n## 真正的问题\n"
        val draft = TemplateImporter.preview(markdown)

        assertEquals(listOf("真正的问题"), draft.questions.map { it.prompt })
        assertTrue(draft.description.contains("代码里的问题二"))
    }

    @Test
    fun emptyInputAndIncompleteMarkersHaveReadableErrors() {
        assertRejected("", "不能为空")
        assertRejected(" \r\n\t", "不能为空")
        assertRejected("# \n## 一个问题", "名称为空")
        assertRejected("# 只有名称\n", "至少一个问题")
        assertRejected("## \n提示", "第 1 行的问题为空")
        assertRejected("- 问题\n- \n", "第 2 行的问题为空")
    }

    @Test
    fun unrecognizedSyntaxIsNotSilentlyDiscarded() {
        val markdown = "#没有空格的标题\n##也没有空格的问题\n这仍是完整的自定义说明。"
        val draft = TemplateImporter.preview(markdown)

        assertEquals("导入模板", draft.name)
        assertEquals(markdown, draft.questions.single().hint)
        assertEquals(markdown, draft.sourceMarkdown)
    }

    @Test
    fun questionLimitIsEnforcedBeforeSaving() {
        val accepted = (1..TemplateImporter.MAX_QUESTIONS).joinToString("\n") { "## 合成问题 $it" }
        assertEquals(TemplateImporter.MAX_QUESTIONS, TemplateImporter.preview(accepted).questions.size)
        assertRejected("$accepted\n## 超出限制", "最多支持 100 个问题")
    }

    @Test
    fun sizeLimitCountsUtf8BytesAndAcceptsExactBoundary() {
        val exactBoundary = "a".repeat(TemplateImporter.MAX_IMPORT_BYTES)
        assertEquals(exactBoundary, TemplateImporter.preview(exactBoundary).sourceMarkdown)
        assertRejected(exactBoundary + "a", "不能超过 256 KiB")
        val multibyte = "合".repeat(TemplateImporter.MAX_IMPORT_BYTES / 3 + 1)
        assertTrue(multibyte.length < TemplateImporter.MAX_IMPORT_BYTES)
        assertRejected(multibyte, "不能超过 256 KiB")
    }

    @Test
    fun blankFallbackNameUsesDefaultAndClosingHeadingHashesAreSyntax() {
        assertEquals("导入模板", TemplateImporter.preview("合成文字", fallbackName = " ").name)
        val draft = TemplateImporter.preview("# 合成模板 #\n## 合成问题 ##\n提示")
        assertEquals("合成模板", draft.name)
        assertEquals("合成问题", draft.questions.single().prompt)
        assertFalse(draft.questions.single().id.isBlank())
    }

    private fun assertRejected(markdown: String, message: String) {
        try {
            TemplateImporter.preview(markdown)
            fail("应拒绝不完整或超出限制的合成模板")
        } catch (error: IllegalArgumentException) {
            assertTrue("实际错误：${error.message}", error.message.orEmpty().contains(message))
        }
    }
}
