package dev.tracelife.quality

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Synthetic-only diagnostics. No credentials, personal database, or network headers are written. */
internal class SyntheticQualityReport(kind: String) {
    val directory: File
    private val rows = JSONArray()
    private val started = Instant.now().toString()

    init {
        val working = File(System.getProperty("traceLife.repositoryRoot") ?: System.getProperty("user.dir")).canonicalFile
        val repository = generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "AGENTS.md").isFile } ?: working
        val parent = File(System.getProperty("traceLife.qualityReportDir")
            ?: File(System.getProperty("user.home"), "Library/Caches/trace-life-tools/quality").path).canonicalFile
        require(!parent.toPath().startsWith(repository.toPath())) { "合成质量报告必须放在仓库之外" }
        directory = File(parent, "$kind-${UUID.randomUUID()}")
        check(directory.mkdirs())
    }

    fun add(value: JSONObject) { rows.put(value); flush() }

    private fun flush() {
        File(directory, "report.json").writeText(JSONObject()
            .put("synthetic", true).put("fixtureVersion", SyntheticQualityCases.VERSION)
            .put("startedAt", started).put("reportedAt", Instant.now().toString())
            .put("cases", rows).toString(2))
        File(directory, "report.md").writeText(buildString {
            append("# 合成质量与规模运行报告\n\n全部输入为虚构资料。固定案例版本：${SyntheticQualityCases.VERSION}。\n\n")
            append("程序检查仅证明取证、范围和预算等契约；语义项目必须查看实际回答后单独评审，不由关键词命中自动判通过。\n\n")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                append("## ${row.getString("id")}\n\n")
                append("程序结果：${row.optString("programmaticStatus")}；耗时：${row.optLong("elapsedMillis")} ms。\n\n")
                row.optJSONArray("requiredDistinctions")?.let { values ->
                    append("预先规定的语义检查（未自动评分）：\n\n")
                    for (i in 0 until values.length()) append("- 待人工评审：${values.getString(i)}\n")
                    append("\n")
                }
                row.optString("expectedNewValue").takeIf { it.isNotEmpty() }?.let { append("新增价值要求：$it\n\n") }
                row.optJSONArray("findings")?.let { values ->
                    append("实际回答：\n\n")
                    for (i in 0 until values.length()) append("- ${values.getJSONObject(i).getString("text")}\n")
                    append("\n")
                }
                row.optJSONArray("unknowns")?.let { values ->
                    if (values.length() > 0) append("实际未知项：\n\n")
                    for (i in 0 until values.length()) append("- ${values.getString(i)}\n")
                    append("\n")
                }
                append("完整范围、固定输入、禁止结论、请求台账与错误分类见同目录 report.json。\n\n")
            }
        })
        println("synthetic-quality report=${directory.path} completedCases=${rows.length()}")
    }
}
