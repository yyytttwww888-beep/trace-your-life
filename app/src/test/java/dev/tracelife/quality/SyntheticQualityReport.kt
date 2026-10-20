package dev.tracelife.quality

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Synthetic-only diagnostics. No credentials, personal database, or network headers are written. */
internal class SyntheticQualityReport(kind: String, private val fixtureVersion: String = SyntheticQualityCases.VERSION) {
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
            .put("synthetic", true).put("fixtureVersion", fixtureVersion)
            .put("startedAt", started).put("reportedAt", Instant.now().toString())
            .put("cases", rows).toString(2))
        File(directory, "report.md").writeText(buildString {
            append("# 合成质量与规模运行报告\n\n全部输入为虚构资料。固定案例版本：$fixtureVersion。\n\n")
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
                row.optJSONArray("finalOriginalReviews")?.let { values ->
                    append("原文核对与发布门禁（用户确认／历史对照为合成操作，不是正确性证明）：\n\n")
                    for (i in 0 until values.length()) {
                        val review = values.getJSONObject(i)
                        append("- ${review.getString("verdict")}；实际发布 ${review.getBoolean("published")}：${review.getString("text")}\n")
                        append("  问题：${review.getJSONArray("issues")}；未知项：${review.getJSONArray("unknowns")}\n")
                    }
                    append("\n不支持意见成功阻止发布是门禁正常，不当作产品失败；SUPPORTED 及已发布结果仍须独立评审。生成 unknowns 不是知识发布目标，另行检查与原文／正文是否冲突。\n\n")
                }
                if (row.has("target")) {
                    append("固定原文：${row.optString("source")}\n\n固定目标：${row.getString("target")}\n\n")
                    append("预期支持：${row.optBoolean("expectedSupported")}；实际意见：${row.optString("verdict", "未完成")}；实际发布：${row.optBoolean("published")}。\n\n")
                }
                append("完整范围、固定输入、禁止结论、请求台账与错误分类见同目录 report.json。\n\n")
            }
        })
        println("synthetic-quality report=${directory.path} completedCases=${rows.length()}")
    }
}
