package dev.tracelife.transfer

import dev.tracelife.knowledge.KnowledgeCodec
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.metrics.RecordMetricEvidence
import dev.tracelife.metrics.RecordMetricProtocol
import dev.tracelife.model.Occurrence
import dev.tracelife.reports.PeriodReport
import dev.tracelife.reports.PeriodReports
import dev.tracelife.reports.ReportAnswerRef
import dev.tracelife.reports.ReportSection
import dev.tracelife.storage.SourceFragment
import dev.tracelife.storage.KnowledgeValidation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A bounded, historical materials view. It neither creates a synthesis nor copies the store. */
object PeriodReportArchive {
    const val MAX_FILES = 100_000
    const val MAX_UNCOMPRESSED_BYTES = 256L * 1024 * 1024
    private const val PAGE_SIZE = 25
    private const val FRAGMENT_SIZE = 1024

    fun write(reports: PeriodReports, report: PeriodReport, metricEvidence: RecordMetricEvidence, destination: File): ExportResult {
        require(!destination.exists()) { "导出暂存文件已存在，不能覆盖" }
        require(metricEvidence.report == report.metrics) { "指标证据与报告不一致" }
        reports.assertCurrent(report)
        val snapshotId = hash(JSONArray(listOf(report.source.storeId, report.source.generation,
            report.analysisGeneration, report.evaluatedAt.toString(), metricEvidence.id)).toString())
        var completed = false
        try {
            val writer = Writer(destination)
            writer.use { archive ->
                val sourcePaths = writeSources(archive, reports, report)
                writeAnswers(archive, reports, report)
                writeKnowledge(archive, reports, report, sourcePaths)
                writeMetrics(archive, report, metricEvidence)
                archive.text("report.md", buildString {
                    append("# 周期复盘材料报告\n\n")
                    append("期间：${report.scope.from} 至 ${report.scope.through}；时区：${escape(report.scope.zoneId)}。\n\n")
                    append("这是已有材料的组织视图，综合复盘尚未生成（NOT_GENERATED）。未推断增长、问题、原因或下一步建议。空缺表示没有对应材料，不表示该行为没有发生。\n\n")
                    ReportSection.entries.forEach { section ->
                        append("- ").append(archive.link("report.md", "sections/${section.name.lowercase()}/index.md", sectionLabel(section)))
                            .append("：${report.sectionCounts.getValue(section)} 份原始回答\n")
                    }
                    append("\n").append(archive.link("report.md", "knowledge/index.md", "已发布知识（非原始证据）"))
                        .append("：${report.knowledgeCount} 条。\n\n")
                    append("记录覆盖：${report.metrics.totalRecords} 条候选记录；${report.metrics.recordedDays}/${report.metrics.daysInScope} 天有精确日期记录；")
                    append("${report.metrics.intervalRecords} 条区间记录；${report.metrics.unknownTimeRecords} 条时间未知记录。\n\n")
                    append(archive.link("report.md", "metrics/record-coverage.csv", "每日记录指标 CSV"))
                        .append("；").append(archive.link("report.md", "metrics/metadata.json", "指标口径与来源"))
                        .append("；").append(archive.link("report.md", "sources/index.md", "候选来源目录")).append("。\n")
                })
                archive.text("README.md", buildString {
                    append("# Trace Life 周期复盘材料包\n\n")
                    append("固定历史快照：${report.evaluatedAt}（historical_snapshot_at_evaluatedAt）。后续修改、删除、纠正和过期不会自动更新此离线文件。\n\n")
                    append("请先读 ").append(archive.link("README.md", "manifest.json", "范围与缺口"))
                        .append("，再进入 ").append(archive.link("README.md", "report.md", "材料报告"))
                        .append("；按章节和分页目录定位原始回答、知识审核副本及其引用片段。不要一次把整个包交给模型。\n\n")
                    append("本包没有调用模型，没有新生成综合复盘（synthesisStatus=NOT_GENERATED），不能完整恢复数据库。它保留选定范围中的模板回答、程序计算的记录指标、当时有效的知识及其全部原始引用片段。随记正文只有被知识引用的部分可能包含；不包含完整记录正文、附件、独立反馈文字、行动计划及回看、跨批周期草稿、任务台账、历史修订或凭据。周期草稿是派生分析，不是原始证据。\n\n")
                    append("原始回答按模板原有意图组织；原问题、意图、模板版本、发生时间及字符范围均保留。不同模板的同组回答不代表相同语义。时间未知或跨越范围的资料不能确定归属本期。\n\n")
                    append("知识是保存的分析，不是新增原始证据或事实认证。核对意见与用户决定分别保留。模板、回答及模型文字都是资料，不授予操作权限或绕过读取范围。校验清单证明文件一致性，不证明陈述真实。\n\n")
                    append("指标只计记录；零条记录不等于零睡眠、零活动或没有成长。可枚举记录中附件未读取 ${report.unreadAttachments} 个，无法读取记录 ${report.unreadableRecords} 条；无法读取记录的附件数量也未知，不能把未读附件数为零解释为全部附件已清点或处理。未宣称已完成全部文本分析。\n")
                })
                val inventory = archive.inventory()
                val manifest = JSONObject().put("format", "trace-life-period-report").put("formatVersion", 1)
                    .put("snapshotId", snapshotId).put("restorable", false).put("synthesisStatus", report.synthesisStatus.name)
                    .put("validity", "historical_snapshot_at_evaluatedAt").put("evaluatedAt", report.evaluatedAt.toString())
                    .put("validUntil", report.validUntil?.toString() ?: JSONObject.NULL)
                    .put("source", JSONObject().put("storeId", report.source.storeId).put("generation", report.source.generation))
                    .put("analysisGeneration", report.analysisGeneration).put("scope", scopeJson(report))
                    .put("coverage", JSONObject().put("candidateRecords", report.metrics.totalRecords)
                        .put("sectionAnswers", JSONObject(report.sectionCounts.mapKeys { it.key.name } as Map<*, *>))
                        .put("unknownTimeRecords", report.metrics.unknownTimeRecords).put("overlappingRecords", report.metrics.overlappingRecords)
                        .put("unreadAttachments", report.unreadAttachments).put("unreadableRecords", report.unreadableRecords)
                        .put("attachmentInventoryComplete", report.unreadableRecords == 0)
                        .put("missingExactIds", JSONArray(report.metrics.missingExactIds.sorted()))
                        .put("lifeCoverage", "unknown").put("wholeScopeAnalyzed", false).put("aiAnalysisPerformed", false))
                    .put("knowledge", JSONObject().put("included", report.knowledgeCount)
                        .put("omittedInvalid", report.omittedInvalidKnowledge).put("omittedOutOfScope", report.omittedOutOfScopeKnowledge)
                        .put("originalEvidence", false).put("referencedOriginalEvidenceIncluded", true)
                        .put("userTextIncluded", false).put("validAt", report.evaluatedAt.toString()))
                    .put("completeness", JSONObject().put("sourceBodiesIncluded", false).put("attachmentsIncluded", false)
                        .put("actionsIncluded", false)
                        .put("analysisRunsIncluded", false).put("reductionDraftsIncluded", false)
                        .put("historicalRevisionsIncluded", false).put("claimFeedbackIncluded", false))
                    .put("metricEvidenceId", metricEvidence.id)
                    .put("navigation", JSONObject().put("report", "report.md").put("sources", "sources/index.md")
                        .put("knowledge", "knowledge/index.md").put("metrics", "metrics/metadata.json"))
                    .put("inventory", JSONObject().put("path", inventory.path).put("sha256", inventory.sha256)
                        .put("excludes", JSONArray(listOf("manifest.json", "inventory/"))))
                archive.text("manifest.json", manifest.toString(2), index = true)
                archive.validateReferences()
                reports.assertCurrent(report)
            }
            completed = true
            return ExportResult(snapshotId, report.metrics.totalRecords, 0, writer.fileCount, destination.length())
        } finally { if (!completed) destination.delete() }
    }

    private fun writeSources(archive: Writer, reports: PeriodReports, report: PeriodReport): Map<Pair<String, Int>, String> {
        val pages = mutableListOf<Nav>()
        val paths = mutableMapOf<Pair<String, Int>, String>()
        var offset = 0
        do {
            val page = reports.records(report, offset, PAGE_SIZE)
            val path = "sources/page-${number(pages.size)}.json"
            page.items.forEach { paths[it.id to it.revision] = path }
            val entries = page.items.map { source -> JSONObject().put("id", source.id).put("revision", source.revision)
                .put("occurrence", occurrenceJson(source.occurrence)).put("timeClass", source.timeClass.name)
                .put("archived", source.archived).put("topics", JSONArray(source.topics.sorted())) }
            val next = page.nextOffset?.let { "sources/page-${number(pages.size + 1)}.json" }
            archive.jsonPage(path, "source-metadata", entries, page.total, next)
            pages += Nav("候选来源 ${offset + 1}–${offset + page.items.size}", path)
            offset = page.nextOffset ?: break
        } while (true)
        archive.pages("sources/index.md", "候选来源元数据", pages,
            "目录不含整条记录正文。UNKNOWN 和 OVERLAP 只能作为时间不确定的材料；区间不展开为每天均有记录。")
        return paths
    }

    private fun writeAnswers(archive: Writer, reports: PeriodReports, report: PeriodReport) {
        ReportSection.entries.forEach { section ->
            val entries = mutableListOf<Nav>()
            var offset = 0
            do {
                val page = reports.answers(report, section, offset, PAGE_SIZE)
                page.items.forEach { answer ->
                    val base = "answers/${hash(JSONArray(listOf(answer.recordId, answer.revision, answer.field)).toString())}"
                    archive.text("$base/metadata.json", answerJson(answer).toString(2))
                    val fragments = mutableListOf<Nav>()
                    var start = 0
                    while (start < answer.codePoints) {
                        val fragment = reports.answer(report, answer, start, FRAGMENT_SIZE)
                        require(fragment.end > start) { "回答片段未推进" }
                        val path = "$base/fragments/${number(fragments.size)}.json"
                        archive.text(path, fragmentJson(fragment).toString(2))
                        fragments += Nav("字符 ${fragment.start}–${fragment.end}", path)
                        start = fragment.end
                    }
                    archive.pages("$base/index.md", "原始模板回答", listOf(Nav("原问题、意图和模板版本", "$base/metadata.json")) + fragments,
                        "用户原始资料；字符位置为 Unicode 码点，结束位置不包含在内。时间分类：${answer.timeClass}。")
                    entries += Nav("${answer.recordId} · ${answer.question.id} · ${answer.timeClass}", "$base/index.md")
                }
                offset = page.nextOffset ?: break
            } while (true)
            archive.pages("sections/${section.name.lowercase()}/index.md", sectionLabel(section), entries,
                if (entries.isEmpty()) "尚无对应模板回答；未生成或推断本节内容。" else "按原问题意图分组；各回答保留自身语义与时间，不自动合成结论。")
        }
    }

    private fun writeKnowledge(archive: Writer, reports: PeriodReports, report: PeriodReport,
        sourcePaths: Map<Pair<String, Int>, String>) {
        val entries = mutableListOf<Nav>()
        var offset = 0
        do {
            val page = reports.knowledge(report, offset, PAGE_SIZE)
            page.items.forEach { item ->
                val value = item.view.value
                val base = "knowledge/entries/${hash(value.id)}-r${value.revision}"
                archive.text("$base/publication.json", KnowledgeCodec.encode(value))
                archive.text("$base/status.json", JSONObject().put("evidenceState", item.view.evidenceState.name)
                    .put("userDecision", item.view.userDecision?.name ?: JSONObject.NULL)
                    .put("evidenceTimeClasses", JSONArray(item.evidenceTimeClasses.map { it.name }.sorted()))
                    .put("originalEvidence", false).put("evaluatedAt", report.evaluatedAt.toString()).toString(2))
                val scanFindings = value.reductionProof?.scans?.flatMap { it.result.findings }?.associateBy { it.receiptId }.orEmpty()
                val evidenceEntries = KnowledgeValidation.allEvidence(value).map { receipt ->
                    val evidenceBase = "$base/evidence/${hash(receipt.id)}"
                    val fragments = mutableListOf<Nav>()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var relativeStart = 0
                    while (relativeStart < receipt.end - receipt.start) {
                        val fragment = reports.evidence(report, value.id, receipt.id, relativeStart, FRAGMENT_SIZE)
                        require(fragment.start == receipt.start + relativeStart && fragment.end > fragment.start && fragment.end <= receipt.end)
                        digest.update(fragment.text.toByteArray(Charsets.UTF_8))
                        val path = "$evidenceBase/fragments/${number(fragments.size)}.json"
                        archive.text(path, fragmentJson(fragment).toString(2))
                        fragments += Nav("字符 ${fragment.start}–${fragment.end}", path)
                        relativeStart = fragment.end - receipt.start
                    }
                    require(digest.digest().hex() == receipt.sha256) { "知识引用原文校验失败" }
                    val usage = if (value.evidence.any { it.id == receipt.id }) "最终核对原文"
                        else "分批原文筛查 ${scanFindings[receipt.id]?.classification?.name ?: "未标注"}；未直接提供给最终核对"
                    archive.pages("$evidenceBase/index.md", "原始引用片段", fragments,
                        "引用 SHA-256：${receipt.sha256}。$usage。仅包含审核引用的字符区间，不代表整份原文已读取。")
                    Nav("${receipt.recordId} · ${receipt.field} · ${receipt.start}–${receipt.end}", "$evidenceBase/index.md")
                }
                val metricEntries = value.metricEvidence.map { metric ->
                    val metricBase = "$base/metrics/${hash(metric.id)}"
                    archive.text("$metricBase/result.json", AnalysisRunCodec.json(RecordMetricProtocol.map(metric)))
                    archive.text("$metricBase/definition.md", "# 记录覆盖指标口径\n\nrecord-coverage v1；单位为记录数与日期天数。" +
                        "每个记录 ID 只计当前修订；精确日期计入每日序列，区间与未知日期单列，不摊入每天。\n\n" +
                        "零条记录不表示零活动、零睡眠或没有成长。模型取得程序计算结果，未逐条读过记录正文。" +
                        "完整范围、归档与精确 ID 限定、缺失项及来源修订保存在结果 JSON。\n")
                    archive.pages("$metricBase/sources/index.md", "指标完整来源清单", metric.sources.map { source ->
                        Nav("${source.recordId} · 修订 ${source.revision}", sourcePaths.getValue(source.recordId to source.revision))
                    }, "链接定位参与计算的原始记录元数据；不包含或声称已读取记录正文。空清单仍保留完整查询范围。")
                    archive.pages("$metricBase/index.md", "程序计算依据", listOf(
                        Nav("完整数值与凭据", "$metricBase/result.json"), Nav("口径与限制", "$metricBase/definition.md"),
                        Nav("分页来源清单", "$metricBase/sources/index.md")), "指标引用：${metric.id}。")
                    Nav("程序计算指标 ${metric.id}", "$metricBase/index.md")
                }
                val reviewMethod = value.reductionProof?.let { proof ->
                    "分批原文筛查：${proof.scans.size} 批、${proof.originalEvidence.size} 个登记片段；最终核对：${value.evidence.size} 个筛选片段。筛查与最终支持判断分别保存，不表示最终模型逐条读过所有原文。"
                } ?: if (value.metricEvidence.isNotEmpty()) "通过重新计算的记录覆盖指标核对；模型未读取记录正文或附件。" else "通过直接原文核对。"
                archive.text("$base/statement.md", "# 已发布知识（非原始证据）\n\n${fenced(value.claim.text)}\n类别：${value.category}；证据时间分类：${item.evidenceTimeClasses.joinToString()}。\n\n$reviewMethod\n")
                archive.pages("$base/index.md", "已发布知识及审核依据", listOf(
                    Nav("陈述", "$base/statement.md"), Nav("完整发布审核副本", "$base/publication.json"), Nav("用户决定与时间状态", "$base/status.json"),
                ) + evidenceEntries + metricEntries, "保存的分析，不是新增原始证据、事实认证或新的周期判断。")
                entries += Nav("${value.id} · ${value.category}", "$base/index.md")
            }
            offset = page.nextOffset ?: break
        } while (true)
        archive.pages("knowledge/index.md", "当时有效且证据在范围内的知识", entries,
            "分析范围与本期相交的候选中，省略失效知识 ${report.omittedInvalidKnowledge} 条、证据范围不符知识 ${report.omittedOutOfScopeKnowledge} 条；不含分析范围本就不相交的知识。")
    }

    private fun writeMetrics(archive: Writer, report: PeriodReport, evidence: RecordMetricEvidence) {
        val metric = report.metrics
        // CSV contains only program-owned identifiers, ISO dates and numbers, never free-form cells.
        archive.text("metrics/record-coverage.csv", buildString {
            append("evidence_id,definition_id,definition_version,unit,date,records\r\n")
            metric.daily.forEach { append("${evidence.id},${metric.definitionId},${metric.definitionVersion},records,${it.date},${it.records}\r\n") }
        })
        archive.text("metrics/metadata.json", JSONObject().put("evidenceId", evidence.id)
            .put("definitionId", metric.definitionId).put("definitionVersion", metric.definitionVersion)
            .put("source", JSONObject().put("storeId", metric.source.storeId).put("generation", metric.source.generation))
            .put("scope", scopeJson(report)).put("calculatedAt", metric.calculatedAt.toString())
            .put("units", JSONObject().put("records", "count").put("recordedDays", "days"))
            .put("totalRecords", metric.totalRecords).put("exactDateRecords", metric.exactDateRecords)
            .put("intervalRecords", metric.intervalRecords).put("overlappingRecords", metric.overlappingRecords)
            .put("unknownTimeRecords", metric.unknownTimeRecords).put("daysInScope", metric.daysInScope).put("recordedDays", metric.recordedDays)
            .put("missingExactIds", JSONArray(metric.missingExactIds.sorted()))
            .put("deduplication", "one_current_revision_per_record_id")
            .put("dailyInclusion", "only_records_whose_original_start_and_end_dates_are_equal")
            .put("intervalHandling", "included_in_totals_not_expanded_into_daily_counts")
            .put("timeZoneHandling", "preserve_original_date_labels_no_invented_time_conversion")
            .put("missingRule", "zero_records_does_not_mean_zero_behavior_or_growth")
            .put("modelCitableReceipt", false).put("sources", "sources/index.md").toString(2))
    }

    private fun scopeJson(report: PeriodReport) = JSONObject().put("from", report.scope.from.toString())
        .put("through", report.scope.through.toString()).put("zoneId", report.scope.zoneId)
        .put("includeArchived", report.scope.includeArchived)
        .put("recordIds", report.scope.recordIds?.sorted()?.let(::JSONArray) ?: JSONObject.NULL)
        .put("timeMatch", "interval-overlap-with-explicit-unknown-candidates")
    private fun occurrenceJson(value: Occurrence) = JSONObject().put("startDate", value.startDate?.toString() ?: JSONObject.NULL)
        .put("endDate", value.endDate?.toString() ?: JSONObject.NULL).put("zoneId", value.zoneId)
    private fun answerJson(value: ReportAnswerRef) = JSONObject().put("recordId", value.recordId).put("revision", value.revision)
        .put("field", value.field).put("codePoints", value.codePoints).put("section", value.section.name)
        .put("template", JSONObject().put("id", value.template.id).put("version", value.template.version))
        .put("question", JSONObject().put("id", value.question.id).put("prompt", value.question.prompt)
            .put("hint", value.question.hint).put("intent", value.question.intent))
        .put("occurrence", occurrenceJson(value.occurrence)).put("timeClass", value.timeClass.name)
    private fun fragmentJson(value: SourceFragment) = JSONObject().put("recordId", value.recordId).put("revision", value.revision)
        .put("field", value.field).put("start", value.start).put("end", value.end).put("totalCodePoints", value.totalCodePoints)
        .put("offsetUnit", "unicode-code-points").put("endExclusive", true).put("text", value.text)
        .put("sha256", hash(value.text)).put("question", value.question ?: JSONObject.NULL)
    private fun sectionLabel(section: ReportSection) = when (section) {
        ReportSection.EXPECTATION -> "期望与方向"; ReportSection.ACTUAL -> "实际与证据"
        ReportSection.FACTORS -> "影响因素与情境"; ReportSection.LEARNING -> "经验与学习"
        ReportSection.ACTION -> "下一步行动实验"; ReportSection.FOLLOW_UP -> "行动回看"
        ReportSection.STATE -> "个人状态"; ReportSection.OTHER -> "其他原始意图"
    }
    private fun number(index: Int) = (index + 1).toString().padStart(5, '0')
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun escape(value: String) = buildString { value.forEach { char -> when {
        char.isISOControl() -> append(' ')
        char in "&<>[]()\\#*_`|!" -> append("&#${char.code};")
        else -> append(char)
    } } }
    private fun display(value: String) = escape(value.take(160))
    private fun fenced(value: String): String {
        val longest = Regex("`+").findAll(value).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        return "$fence\n$value${if (value.endsWith('\n')) "" else "\n"}$fence\n"
    }
    private data class Nav(val label: String, val path: String)
    private data class FileInfo(val path: String, val sha256: String, val bytes: Int)

    /** All paths are generated; index pages and leaves have separate byte and count ceilings. */
    private class Writer(destination: File) : AutoCloseable {
        private val zip = ZipOutputStream(FileOutputStream(destination))
        private val files = linkedMapOf<String, FileInfo>()
        private val references = mutableListOf<Pair<String, String>>()
        private var bytes = 0L
        val fileCount get() = files.size
        fun text(path: String, value: String, index: Boolean = false) {
            require(path.matches(Regex("[A-Za-z0-9][A-Za-z0-9/._-]*")) && path.split('/').none { it == ".." || it.isEmpty() })
            require(path !in files && files.size < MAX_FILES) { "报告路径冲突或文件超过 100000 个，请缩小范围" }
            val content = value.toByteArray(Charsets.UTF_8)
            require(content.size <= if (index) 128 * 1024 else 1024 * 1024) { "报告文档超过大小上限，请缩小范围" }
            require(bytes + content.size <= MAX_UNCOMPRESSED_BYTES) { "报告超过 256 MiB，请缩小范围" }
            zip.putNextEntry(ZipEntry(path).apply { time = 0 })
            zip.write(content)
            zip.closeEntry()
            files[path] = FileInfo(path, hash(value), content.size)
            bytes += content.size
        }
        fun link(from: String, target: String, label: String): String {
            references += from to target
            return "[${display(label)}](${(Paths.get(from).parent ?: Paths.get("")).relativize(Paths.get(target)).toString().replace('\\', '/')})"
        }
        fun pages(path: String, title: String, entries: List<Nav>, note: String = "") {
            val chunks = entries.chunked(PAGE_SIZE).ifEmpty { listOf(emptyList()) }
            val paths = chunks.indices.map { if (it == 0) path else "${path.substringBeforeLast('/')}/page-${number(it)}.md" }
            chunks.forEachIndexed { index, page ->
                text(paths[index], buildString {
                    append("# ${display(title)}\n\n第 ${index + 1}/${chunks.size} 页；共 ${entries.size} 项；每页最多 $PAGE_SIZE 项。\n\n$note\n\n")
                    page.forEach { append("- ").append(link(paths[index], it.path, it.label)).append('\n') }
                    append('\n')
                    if (index > 0) append(link(paths[index], paths[index - 1], "上一页")).append(' ')
                    if (index + 1 < paths.size) append(link(paths[index], paths[index + 1], "下一页"))
                    append('\n')
                }, index = true)
            }
        }
        fun jsonPage(path: String, type: String, entries: List<JSONObject>, total: Int, next: String?) {
            if (next != null) references += path to next
            text(path, JSONObject().put("type", type).put("pageSize", PAGE_SIZE).put("totalEntries", total)
                .put("entries", JSONArray(entries)).put("next", next ?: JSONObject.NULL).toString(2), index = true)
        }
        fun inventory(): FileInfo {
            val pages = files.values.toList().chunked(PAGE_SIZE).mapIndexed { index, page ->
                val path = "inventory/files-${number(index)}.jsonl"
                text(path, page.joinToString("\n", postfix = "\n") { file -> JSONObject().put("path", file.path)
                    .put("sha256", file.sha256).put("bytes", file.bytes).toString() }, index = true)
                JSONObject().put("path", path).put("sha256", files.getValue(path).sha256).put("entries", page.size)
            }
            val chunks = pages.chunked(PAGE_SIZE)
            val paths = chunks.indices.map { if (it == 0) "inventory/index.json" else "inventory/page-${number(it)}.json" }
            chunks.indices.reversed().forEach { index ->
                val next = paths.getOrNull(index + 1)
                chunks[index].forEach { references += paths[index] to it.getString("path") }
                if (next != null) references += paths[index] to next
                text(paths[index], JSONObject().put("type", "sha256-file-inventory").put("pageSize", PAGE_SIZE)
                    .put("entries", JSONArray(chunks[index])).put("totalEntries", pages.size)
                    .put("next", next ?: JSONObject.NULL).put("nextSha256", next?.let { files.getValue(it).sha256 } ?: JSONObject.NULL).toString(2), index = true)
            }
            return files.getValue("inventory/index.json")
        }
        fun validateReferences() { references.forEach { (from, to) -> require(from in files && to in files) { "报告引用缺失：$from → $to" } } }
        override fun close() = zip.close()
    }
}
