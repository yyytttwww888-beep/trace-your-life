package dev.tracelife.templates

import dev.tracelife.model.Cadence
import dev.tracelife.model.TemplateDraft
import dev.tracelife.model.TemplateQuestion
import java.security.MessageDigest

/** A bounded, text-only preview parser. The caller must confirm before saving a template. */
object TemplateImporter {
    const val MAX_IMPORT_BYTES = 256 * 1024
    const val MAX_QUESTIONS = 100

    private val heading = Regex("^ {0,3}(#{1,2})(?:[ \\t]+(.*)|[ \\t]*)$")
    private val listItem = Regex("^( {0,3})(?:[-+*]|[0-9]{1,9}[.)])(?:[ \\t]+(.*)|[ \\t]*)$")
    private val fenceStart = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val fenceEnd = Regex("^ {0,3}(`+|~+)[ \\t]*$")

    fun preview(
        markdown: String,
        fallbackName: String = "导入模板",
        cadence: Cadence = Cadence.DAILY,
    ): TemplateDraft {
        require(markdown.isNotBlank()) { "模板内容不能为空，请填写问题或复盘说明" }
        require(markdown.length <= MAX_IMPORT_BYTES && markdown.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) {
            "模板不能超过 256 KiB，请拆分后导入"
        }

        val lines = scanLines(markdown)
        val headings = lines.filter { it.structural }.mapNotNull { line ->
            heading.matchEntire(line.text)?.let { match ->
                Heading(line, match.groupValues[1].length, trimHeading(match.groupValues[2]))
            }
        }
        val questionHeadings = headings.filter { it.level == 2 }
        val listItems = if (questionHeadings.isEmpty()) {
            lines.filter { it.structural }.mapNotNull { line ->
                listItem.matchEntire(line.text)?.let { match ->
                    ListQuestion(line, match.groupValues[1].length, match.groupValues[2].trim())
                }
            }
        } else {
            emptyList()
        }
        val minimumIndent = listItems.minOfOrNull { it.indent }
        val markers = if (questionHeadings.isNotEmpty()) {
            questionHeadings.map { QuestionMarker(it.line, it.prompt) }
        } else {
            listItems.filter { it.indent == minimumIndent }.map { QuestionMarker(it.line, it.prompt) }
        }
        val firstQuestionStart = markers.firstOrNull()?.line?.start ?: markdown.length
        val title = headings.firstOrNull { it.level == 1 && it.line.start < firstQuestionStart }
        require(title == null || title.prompt.isNotBlank()) {
            "第 ${title!!.line.number} 行的模板名称为空，请在 # 后填写名称"
        }
        val name = title?.prompt ?: fallbackName.trim().ifBlank { "导入模板" }

        fun withoutTitle(endExclusive: Int): String {
            if (title == null) return markdown.substring(0, endExclusive)
            return markdown.substring(0, title.line.start) + markdown.substring(title.line.endExclusive, endExclusive)
        }

        if (markers.isEmpty()) {
            val body = withoutTitle(markdown.length)
            require(body.isNotBlank()) { "模板只有名称，请补充至少一个问题或复盘说明" }
            return TemplateDraft(
                name = name,
                cadence = cadence,
                questions = listOf(TemplateQuestion(id = questionId("自由复盘", 1), prompt = "自由复盘", hint = body)),
                sourceMarkdown = markdown,
            )
        }

        require(markers.size <= MAX_QUESTIONS) { "模板最多支持 100 个问题，请拆分后导入" }
        val occurrences = mutableMapOf<String, Int>()
        val questions = markers.mapIndexed { index, marker ->
            require(marker.prompt.isNotBlank()) { "第 ${marker.line.number} 行的问题为空，请补充问题文字" }
            val occurrence = (occurrences[marker.prompt] ?: 0) + 1
            occurrences[marker.prompt] = occurrence
            val hintEnd = markers.getOrNull(index + 1)?.line?.start ?: markdown.length
            TemplateQuestion(
                id = questionId(marker.prompt, occurrence),
                prompt = marker.prompt,
                hint = markdown.substring(marker.line.endExclusive, hintEnd),
                intent = "custom",
            )
        }
        return TemplateDraft(
            name = name,
            cadence = cadence,
            description = withoutTitle(firstQuestionStart),
            questions = questions,
            sourceMarkdown = markdown,
        )
    }

    private fun trimHeading(prompt: String): String =
        prompt.trim().replace(Regex("[ \\t]+#+$"), "").trim()

    private fun questionId(prompt: String, occurrence: Int): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "custom-$hash-$occurrence"
    }

    private fun scanLines(markdown: String): List<SourceLine> {
        val lines = mutableListOf<SourceLine>()
        var cursor = 0
        var fence: String? = null
        while (cursor < markdown.length) {
            val newline = markdown.indexOf('\n', cursor)
            val end = if (newline == -1) markdown.length else newline + 1
            val contentEnd = if (newline == -1) end else newline
            val text = markdown.substring(cursor, contentEnd).removeSuffix("\r")
            val activeFence = fence
            var structural = activeFence == null
            if (activeFence != null) {
                val close = fenceEnd.matchEntire(text)?.groupValues?.get(1)
                if (close != null && close[0] == activeFence[0] && close.length >= activeFence.length) {
                    fence = null
                }
            } else {
                val open = fenceStart.matchEntire(text)
                if (open != null && !(open.groupValues[1][0] == '`' && open.groupValues[2].contains('`'))) {
                    fence = open.groupValues[1]
                    structural = false
                }
            }
            lines += SourceLine(lines.size + 1, text, cursor, end, structural)
            cursor = end
        }
        return lines
    }

    private data class SourceLine(val number: Int, val text: String, val start: Int, val endExclusive: Int, val structural: Boolean)
    private data class Heading(val line: SourceLine, val level: Int, val prompt: String)
    private data class ListQuestion(val line: SourceLine, val indent: Int, val prompt: String)
    private data class QuestionMarker(val line: SourceLine, val prompt: String)
}
