package dev.tracelife.templates

import dev.tracelife.model.Cadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInTemplatesTest {
    @Test
    fun builtInsProvideDistinctReviewCadencesAndStableQuestionIdentities() {
        val templates = listOf(BuiltInTemplates.daily, BuiltInTemplates.weekly, BuiltInTemplates.event)

        assertEquals(listOf(Cadence.DAILY, Cadence.WEEKLY, Cadence.EVENT), templates.map { it.cadence })
        assertEquals(listOf(7, 6, 8), templates.map { it.questions.size })
        val questions = templates.flatMap { it.questions }
        assertEquals(questions.size, questions.map { it.id }.distinct().size)
        assertTrue(questions.all { it.prompt.isNotBlank() && it.hint.isNotBlank() && it.intent != "custom" })
        assertTrue(templates.all { it.description.contains("均可跳过") })
    }

    @Test
    fun sourceTextKeepsMethodBoundariesAndAllQuestionTextForExport() {
        listOf(BuiltInTemplates.daily, BuiltInTemplates.weekly, BuiltInTemplates.event).forEach { template ->
            assertTrue(template.sourceMarkdown.contains("不是各机构的官方个人成长模板"))
            assertTrue(template.sourceMarkdown.contains("https://www.who.int/"))
            assertTrue(template.sourceMarkdown.contains("https://sre.google/"))
            assertTrue(template.sourceMarkdown.contains("https://www.ihi.org/"))
            template.questions.forEach { question ->
                assertTrue(template.sourceMarkdown.contains("## ${question.prompt}\n${question.hint}"))
            }
            assertEquals(template.questions.map { it.prompt }, TemplateImporter.preview(template.sourceMarkdown).questions.map { it.prompt })
        }
    }
}
