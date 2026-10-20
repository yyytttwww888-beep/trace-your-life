package dev.tracelife.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import dev.tracelife.actions.ActionDecision
import dev.tracelife.actions.ActionExecution
import dev.tracelife.actions.ActionOriginState
import dev.tracelife.actions.ActionStatus
import dev.tracelife.actions.StoredActionRevision
import dev.tracelife.dashboard.DashboardActionExecution
import dev.tracelife.dashboard.DashboardActionMarkerKind
import dev.tracelife.dashboard.DashboardActionSection
import dev.tracelife.dashboard.DashboardCard
import dev.tracelife.dashboard.DashboardCardState
import dev.tracelife.dashboard.DashboardComparisonState
import dev.tracelife.dashboard.DashboardMetric
import dev.tracelife.dashboard.DashboardMetricSummary
import dev.tracelife.dashboard.DashboardPeriodSelection
import dev.tracelife.dashboard.DashboardSnapshot
import dev.tracelife.dashboard.DashboardSourceCategory
import dev.tracelife.dashboard.DashboardWindow
import dev.tracelife.profile.ProfileDimension
import dev.tracelife.storage.SourceDescriptor
import dev.tracelife.storage.SourceFieldKind
import dev.tracelife.storage.SourceTimeClass
import dev.tracelife.transfer.CompletedExports
import dev.tracelife.transfer.StaleSnapshotException
import dev.tracelife.metrics.ActivityDomain
import dev.tracelife.metrics.ActivityMeasure
import dev.tracelife.templates.ActivityLogTemplate

class DashboardActivity : ScreenActivity() {
    private var snapshot: DashboardSnapshot? = null
    private var sourceCategory = DashboardSourceCategory.ALL
    private var sourcePointIndex: Int? = null
    private var sourceOffset = 0
    private var textRecordId: String? = null
    private var insightPeriod = DashboardPeriodSelection.CURRENT
    private var insightOffset = 0
    private var actionSection = DashboardActionSection.ALL
    private var actionOffset = 0
    private var markerOffset = 0
    private var windowSpinner: Spinner? = null
    private var archivedCheck: CheckBox? = null

    private var selectedWindow: DashboardWindow? = null
    private var activityDomain = ActivityDomain.LEARNING
    private var activityMeasure = ActivityMeasure.MINUTES
    private var activityPoint: Int? = null
    private var activityOffset = 0
    private var activityCorrections = false
    private var activityRecordId: String? = null

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        selectedWindow = savedInstanceState?.getString("trendWindow")?.let(DashboardWindow::valueOf)
        activityDomain = savedInstanceState?.getString("activityDomain")?.let(ActivityDomain::valueOf) ?: ActivityDomain.LEARNING
        activityMeasure = savedInstanceState?.getString("activityMeasure")?.let(ActivityMeasure::valueOf) ?: ActivityMeasure.MINUTES
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedWindow?.let { outState.putString("trendWindow", it.name) }
        outState.putString("activityDomain", activityDomain.name)
        outState.putString("activityMeasure", activityMeasure.name)
        super.onSaveInstanceState(outState)
    }

    override fun showHome() {
        beginScreen("home", "趋势", showBack = false)
        runIo({
            val preferences = app.dashboardPreferences.read().value
            app.dashboard.open(dev.tracelife.dashboard.DashboardRequest(
                window = selectedWindow ?: preferences.window, zoneId = preferences.zoneId,
                cards = preferences.cards, includeArchived = preferences.includeArchived))
        }) { opened ->
            snapshot = opened
            renderHome(opened)
        }
    }

    override fun navigateBack() {
        if (busy) return
        when (screen) {
            "home" -> finish()
            "sourceText" -> activityRecordId?.let(::showActivityEntry) ?: showSourceFields(requireNotNull(textRecordId))
            "activityEntry" -> showActivitySources(activityPoint, activityOffset, activityCorrections)
            "fields" -> showSources(sourceCategory, sourcePointIndex, sourceOffset)
            "actionDetail" -> showActions(actionSection, actionOffset)
            else -> {
                if (activityMeasure == ActivityMeasure.OUTCOMES) activityMeasure = ActivityMeasure.MINUTES
                showHome()
            }
        }
    }

    override fun showError(failure: Throwable) {
        if (failure is StaleSnapshotException) {
            confirm("看板需要更新", "记录或日期已变化，刷新后可继续查看。", "刷新看板") { showHome() }
        } else {
            super.showError(failure)
        }
    }

    private fun chipRow(parent: LinearLayout): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        parent.addView(row)
        return row
    }

    private fun renderHome(board: DashboardSnapshot) {
        activityRecordId = null
        val values = board.current.metric.values
        card().also { overview ->
            Ui.note(overview, "${board.current.metric.scope.from} — ${board.current.metric.scope.through} · 最近 ${windowLabel(board.request.window)}")
            Ui.cardTitle(overview, "${values.recordedDays} 天留下了记录")
            Ui.body(overview, "${values.exactDateRecords} 条日常记录 · 共 ${values.daysInScope} 天")
            Ui.note(overview, "只统计有明确日期的记录，不用记录多少衡量生活好坏。")
            Ui.disclosure(overview, "范围与显示", "dashboardOptions") { options ->
                Ui.note(options, "时区 ${board.request.zoneId}${if (board.request.includeArchived) " · 包含归档" else " · 不含归档"}")
                addButton(options, "调整时间范围", "preferences") { showPreferences() }
                addButton(options, "刷新", "refresh") { showHome() }
                renderMetricRules(options, board.current.metric)
            }
        }

        card().also { trend ->
            Ui.sectionTitle(trend, "记录趋势")
            val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            trend.addView(choices)
            DashboardWindow.entries.forEach { option ->
                Ui.button(choices, (if (board.request.window == option) "✓ " else "") + windowLabel(option), "trendWindow:${option.name}") {
                    if (!busy) { selectedWindow = option; showHome() }
                }.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), dp(8))
                }
            }
            Ui.note(trend, if (board.request.window == DashboardWindow.DAYS_90) "每点为一个自然周，首尾可能不足一周。" else "每点为一天，空心点表示今天尚未结束。")
            trend.addView(RecordTrendView(this, board.current.points) { index ->
                if (!busy) showSources(DashboardSourceCategory.ALL, index, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
            Ui.note(trend, "点按图表查看来源。0 只表示没有保存精确日期记录；日期未知 ${values.unknownTimeRecords} 条、跨多天 ${values.intervalRecords} 条另列。")
        }

        renderActivities(board)

        card().also { actions ->
            Ui.sectionTitle(actions, "行动回看")
            listRow(actions, "我的行动 · ${board.actionCount} 项", "actions") { showActions(DashboardActionSection.ALL, 0) }
            listRow(actions, "本期时间线 · ${board.markerCount} 条", "markers") { showMarkers(0) }
        }

        val healthCards = board.cards.filter { it.metric != DashboardMetric.RECORD_COVERAGE }
        if (healthCards.isNotEmpty()) {
            val label = if (healthCards.all { it.state != DashboardCardState.AVAILABLE }) "健康与饮食 · 暂无数据" else "健康与饮食"
            Ui.disclosure(root, label, "healthAvailability") { health ->
                healthCards.forEach { renderCard(it, health) }
            }
        }

        Ui.disclosure(root, "与前期对照", "comparisonDetails") { comparison ->
            renderComparison(board, comparison)
        }

        Ui.disclosure(root, "按日期查看记录", "dailyRecords") { points ->
            Ui.note(points, if (board.request.window == DashboardWindow.DAYS_90) {
                "按自然周汇总，首尾可能不足一周。"
            } else {
                "点开日期，查看当天参与统计的记录。"
            })
            board.current.points.forEachIndexed { index, point ->
                val range = if (point.from == point.through) point.from.toString() else "${point.from} 至 ${point.through}"
                listRow(points, "$range${if (point.inProgress) " · 进行中" else ""}\n${point.exactDateRecords} 条记录 · ${point.recordedDays}／${point.days} 天有记录", "pointSources:$index") {
                    showSources(DashboardSourceCategory.ALL, index, 0)
                }
            }
        }

        Ui.disclosure(root, "更多记录与说明", "dashboardDetails") { details ->
            listRow(details, "本期全部记录", "sourcesAll") { showSources(DashboardSourceCategory.ALL, null, 0) }
            listRow(details, "未确定日期 · ${values.unknownTimeRecords} 条", "sourcesUnknown") { showSources(DashboardSourceCategory.UNKNOWN_TIME, null, 0) }
            listRow(details, "跨多天记录 · ${values.intervalRecords} 条", "sourcesInterval") { showSources(DashboardSourceCategory.INTERVAL, null, 0) }
            listRow(details, "已保存的趋势说明 · ${board.insightCount} 条", "insights") { showInsights(DashboardPeriodSelection.CURRENT, 0) }
            addButton(details, "导出这份看板", "exportDashboard") { exportDashboard() }
        }
    }

    private fun renderActivities(board: DashboardSnapshot) {
        val metrics = board.activityCurrent
        val series = metrics.series.single { it.domain == activityDomain }
        fun choice(parent: LinearLayout, label: String, tag: String, action: () -> Unit) {
            Ui.button(parent, label, tag) { if (!busy) action() }.layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), dp(8))
                }
        }
        card().also { container ->
            Ui.sectionTitle(container, "工作与学习")
            val domains = chipRow(container)
            ActivityDomain.entries.forEach { domain ->
                choice(domains, (if (domain == activityDomain) "✓ " else "") + domain.label, "activityDomain:${domain.name}") {
                    activityDomain = domain; showHome()
                }
            }
            Ui.body(container, "${series.minutes?.let { "$it 分钟" } ?: "时长未记录"} · ${series.practices?.let { "$it 次实践" } ?: "实践次数未记录"}")
            Ui.note(container, "时长覆盖 ${series.minutesRecordedDays}/${board.request.window.days} 天；实践次数覆盖 ${series.practicesRecordedDays}/${board.request.window.days} 天。只统计明确填写的数值。")
            val measures = chipRow(container)
            listOf(ActivityMeasure.MINUTES, ActivityMeasure.PRACTICES).forEach { measure ->
                choice(measures, (if (measure == activityMeasure) "✓ " else "") + measure.label, "activityMeasure:${measure.name}") {
                    activityMeasure = measure; showHome()
                }
            }
            if (activityMeasure != ActivityMeasure.OUTCOMES) {
                container.addView(ActivityTrendView(this, series.points, activityMeasure) { index ->
                    if (!busy) showActivitySources(index, 0)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
                Ui.note(container, "空白是未记录，只有明确填 0 才显示 0。断线不表示停止活动；空心点仍在进行中。")
            }
            addButton(container, "记一次投入或实践", "newActivity") { openActivityEditor() }
            listRow(container, "成果时间线", "activityOutcomes") {
                activityMeasure = ActivityMeasure.OUTCOMES; showActivitySources(null, 0)
            }
            Ui.disclosure(container, "逐项查看与统计说明", "activityDetails") { details ->
                series.points.forEachIndexed { index, point ->
                    listRow(details, "${point.from} — ${point.through}\n${point.minutes?.let { "$it 分钟" } ?: "时长未记录"} · ${point.practices?.let { "$it 次实践" } ?: "实践次数未记录"}", "activityPoint:$index") {
                        if (activityMeasure == ActivityMeasure.OUTCOMES) activityMeasure = ActivityMeasure.MINUTES
                        showActivitySources(index, 0)
                    }
                }
                listRow(details, "全部投入与实践来源", "activitySourcesAll") { showActivitySources(null, 0) }
                val previous = board.activityPrevious.series.single { it.domain == activityDomain }
                Ui.note(details, "前期同长度窗口：${previous.minutes?.let { "$it 分钟" } ?: "时长未记录"}（${previous.minutesRecordedDays} 天有填写），${previous.practices?.let { "$it 次实践" } ?: "实践次数未记录"}（${previous.practicesRecordedDays} 天有填写）。覆盖不等于全部活动，不能据此断言成长。")
                Ui.note(details, "日期未知 ${metrics.undatedEntries} 条、跨多天 ${metrics.intervalEntries} 条另列，不分摊到图表或总量。同一活动重复录入会重复计数，请编辑原记录修正。")
                if (metrics.entriesNeedingCorrection > 0) listRow(details, "${metrics.entriesNeedingCorrection} 条输入需要修正", "activityCorrections") {
                    showActivitySources(null, 0, corrections = true)
                }
            }
        }
    }

    private fun showActivitySources(point: Int?, offset: Int, corrections: Boolean = false) {
        activityPoint = point; activityOffset = offset; activityCorrections = corrections; activityRecordId = null
        val board = snapshot ?: return showHome()
        beginScreen("activitySources", if (corrections) "需要修正的输入" else "${activityDomain.label} · ${activityMeasure.label}")
        runIo({
            val page = app.dashboard.activitySources(board, domain = if (corrections) null else activityDomain,
                measure = if (corrections || point == null && activityMeasure != ActivityMeasure.OUTCOMES) null else activityMeasure,
                pointIndex = point, correctionsOnly = corrections, offset = offset, limit = PAGE_SIZE)
            val outcomes = if (activityMeasure == ActivityMeasure.OUTCOMES && !corrections) page.items.associate { item ->
                item.recordId to app.dashboard.sourceText(board, item.recordId, "answer:${ActivityLogTemplate.OUTCOME}", 0, 180).text
            } else emptyMap()
            page to outcomes
        }) { (page, outcomes) ->
            note(if (activityMeasure == ActivityMeasure.OUTCOMES) "这里只展示明确填写的成果文字，不用条目数量评估成果大小。" else "未填写的数值保持未知。点开可核对和修改原记录。")
            if (page.items.isEmpty()) emptyState(card(), "这段范围没有符合条件的明确记录。")
            page.items.forEach { entry ->
                val date = entry.exactDate?.toString() ?: entry.occurrence.startDate?.let { "$it 至 ${entry.occurrence.endDate} · 未计入每日总量" } ?: "日期未知 · 未计入每日总量"
                val numbers = "${entry.minutes?.let { "$it 分钟" } ?: "时长未记录"} · ${entry.practices?.let { "$it 次实践" } ?: "实践次数未记录"}"
                listRow(root, "$date · ${entry.domain?.label ?: "领域待修正"}\n$numbers" +
                    outcomes[entry.recordId]?.let { "\n$it" }.orEmpty() + entry.issues.takeIf { it.isNotEmpty() }?.joinToString("；", "\n").orEmpty(), "activitySource:${entry.recordId}") {
                    showActivityEntry(entry.recordId)
                }
            }
            if (offset > 0) button("上一页", "activityPrevious") { showActivitySources(point, (offset - PAGE_SIZE).coerceAtLeast(0), corrections) }
            page.nextOffset?.let { next -> button("下一页", "activityNext") { showActivitySources(point, next, corrections) } }
        }
    }

    private fun showActivityEntry(recordId: String) {
        val board = snapshot ?: return showHome()
        activityRecordId = recordId
        beginScreen("activityEntry", "投入与实践 · 原始记录")
        runIo({ app.dashboard.sourceFields(board, recordId) }) { fields ->
            fields.filter { it.kind == SourceFieldKind.TEXT }.forEach { field ->
                listRow(root, activityFieldLabel(field.key), "activityField:${field.key}") { showSourceText(recordId, field.key, 0) }
            }
            button("修改这条记录", "editActivity") { openActivityEditor(recordId) }
        }
    }

    private fun activityFieldLabel(key: String): String = ActivityLogTemplate.draft.questions
        .firstOrNull { "answer:${it.id}" == key }?.prompt ?: fieldLabel(key)

    @Suppress("DEPRECATION")
    private fun openActivityEditor(recordId: String? = null) {
        startActivityForResult(Intent(this, ActivityEntryActivity::class.java).apply {
            recordId?.let { putExtra(ActivityEntryActivity.RECORD_ID, it) }
        }, ACTIVITY_REQUEST)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ACTIVITY_REQUEST && resultCode == Activity.RESULT_OK) showHome()
    }

    private fun renderCard(metricCard: DashboardCard, parent: LinearLayout = root) {
        Ui.card(parent).also { container ->
            Ui.cardTitle(container, metricLabel(metricCard.metric))
            chip(container, cardStateLabel(metricCard.state), if (metricCard.state == DashboardCardState.AVAILABLE) Ui.Tone.ACCENT else Ui.Tone.WARN)
            val current = metricCard.current
            if (current == null) {
                Ui.note(container, when (metricCard.state) {
                    DashboardCardState.SOURCE_NOT_CONNECTED -> "连接健康数据后，这里会显示趋势。"
                    DashboardCardState.STRUCTURED_INPUT_UNAVAILABLE -> "餐食记录尚未接入，暂时没有数据。"
                    DashboardCardState.AVAILABLE -> "这段时间没有可用数据。"
                })
                return@also
            }
            val values = current.metric.values
            Ui.body(container, "本期候选记录 ${values.totalRecords} 条 · 精确日期 ${values.exactDateRecords} 条 · 区间 ${values.intervalRecords} 条（已含跨边界 ${values.overlappingRecords} 条，二者不可再相加） · 发生日期未知 ${values.unknownTimeRecords} 条")
            Ui.body(container, "期间共 ${values.daysInScope} 天 · 有记录 ${values.recordedDays} 天")
            renderMetricRules(container, current.metric)
        }
    }

    private fun renderMetricRules(parent: LinearLayout, metric: DashboardMetricSummary) {
        Ui.note(parent, "按记录原有日期统计，每条记录只计一次。某天显示 0 条，仅表示没有保存当天的记录。")
        Ui.note(parent, "跨多天或日期未定的记录单独列出，不平均分配到各天。")
        Ui.note(parent, "统计口径：${metric.definitionId} · 第 ${metric.definitionVersion} 版")
    }

    private fun renderComparison(board: DashboardSnapshot, parent: LinearLayout = root) {
        Ui.card(parent).also { comparison ->
            Ui.sectionTitle(comparison, "记录变化")
            val currentValues = board.current.metric.values
            val previousValues = board.previous.metric.values
            Ui.body(comparison, "本期：${board.current.metric.scope.from} 至 ${board.current.metric.scope.through} · 精确日期记录 ${currentValues.exactDateRecords} 条 · 有记录 ${currentValues.recordedDays}／${currentValues.daysInScope} 天")
            Ui.body(comparison, "前期：${board.previous.metric.scope.from} 至 ${board.previous.metric.scope.through} · 精确日期记录 ${previousValues.exactDateRecords} 条 · 有记录 ${previousValues.recordedDays}／${previousValues.daysInScope} 天")
            when (board.comparison.state) {
                DashboardComparisonState.CURRENT_WINDOW_IN_PROGRESS ->
                    Ui.note(comparison, "本期还没结束，先并列看看两段时间。")
                DashboardComparisonState.COMPLETE_RECORDED_WINDOWS ->
                    Ui.body(comparison, "记录 ${signed(board.comparison.exactDateRecordsDelta)} 条 · 有记录的天数 ${signed(board.comparison.recordedDaysDelta)} 天。这里只比较记录数量。")
            }
            Ui.note(comparison, "日期未定的记录没有计入每日数量。")
        }
    }

    private fun showSources(category: DashboardSourceCategory, pointIndex: Int?, offset: Int) {
        activityRecordId = null
        sourceCategory = category
        sourcePointIndex = pointIndex
        sourceOffset = offset
        val board = snapshot ?: return showHome()
        beginScreen("sources", "记录 · ${sourceTitle(category, pointIndex)}")
        runIo({ app.dashboard.sources(board, category = category, pointIndex = pointIndex, offset = offset, limit = PAGE_SIZE) }) { page ->
            note("共 ${page.total} 条记录")
            if (page.items.isEmpty()) emptyState(card(), "这段时间还没有记录。")
            page.items.forEach { source ->
                listRow(root, "${occurrenceLabel(source)}${if (source.archived) " · 已归档" else ""}\n${source.topics.joinToString("、").ifEmpty { "未分类" }} · ${timeClassLabel(source.timeClass)}", "source:${source.id}") {
                    showSourceFields(source.id)
                }
            }
            if (offset > 0) button("上一页", "sourcesPrevious") { showSources(category, pointIndex, (offset - PAGE_SIZE).coerceAtLeast(0)) }
            page.nextOffset?.let { next -> button("下一页", "sourcesNext") { showSources(category, pointIndex, next) } }
        }
    }

    private fun showSourceFields(recordId: String) {
        val board = snapshot ?: return showHome()
        beginScreen("fields", "记录内容")
        runIo({ app.dashboard.sourceFields(board, recordId) }) { fields ->
            note("选择一段内容查看原文。")
            if (fields.isEmpty()) emptyState(card(), "这条记录没有文字内容。")
            fields.forEachIndexed { index, field ->
                when (field.kind) {
                    SourceFieldKind.TEXT -> listRow(root, "${index + 1}. ${fieldLabel(field.key)} · ${field.codePoints} 字", "field:${field.key}") { showSourceText(recordId, field.key, 0) }
                    SourceFieldKind.ATTACHMENT -> note("这条记录附有图片，本页仅展示文字。")
                }
            }
        }
    }

    private fun showSourceText(recordId: String, field: String, start: Int) {
        textRecordId = recordId
        val board = snapshot ?: return showHome()
        beginScreen("sourceText", "来源原文")
        runIo({ app.dashboard.sourceText(board, recordId, field, start = start, maxCodePoints = FRAGMENT_CODE_POINTS) }) { fragment ->
            note("${fieldLabel(fragment.field)} · 已读至 ${fragment.end}／${fragment.totalCodePoints} 字")
            text(fragment.text)
            if (fragment.end < fragment.totalCodePoints) {
                button("继续读取", "readMore") { showSourceText(recordId, field, fragment.end) }
            } else {
                note("已读完这段内容。")
            }
        }
    }

    private fun showInsights(period: DashboardPeriodSelection, offset: Int) {
        insightPeriod = period
        insightOffset = offset
        val board = snapshot ?: return showHome()
        beginScreen("insights", "趋势说明 · ${if (period == DashboardPeriodSelection.CURRENT) "本期" else "前期"}")
        val intro = card()
        Ui.note(intro, "这里保留与本期记录匹配的已保存说明。")
        addButton(
            intro,
            if (period == DashboardPeriodSelection.CURRENT) "查看前期说明（${board.previousInsightCount} 条）" else "查看本期说明（${board.insightCount} 条）",
            "switchInsightPeriod",
        ) { showInsights(if (period == DashboardPeriodSelection.CURRENT) DashboardPeriodSelection.PREVIOUS else DashboardPeriodSelection.CURRENT, 0) }
        runIo({ app.dashboard.insights(board, period = period, offset = offset, limit = PAGE_SIZE) }) { page ->
            Ui.note(intro, "共 ${page.total} 条。")
            if (page.items.isEmpty()) emptyState(intro, "这段时间还没有可用的趋势说明。")
            page.items.forEach { insight ->
                card().also { entry ->
                    Ui.body(entry, insight.text)
                    Ui.note(entry, "已保存 · 第 ${insight.revision} 版")
                }
            }
            if (offset > 0) button("上一页", "insightsPrevious") { showInsights(period, (offset - PAGE_SIZE).coerceAtLeast(0)) }
            page.nextOffset?.let { next -> button("下一页", "insightsNext") { showInsights(period, next) } }
        }
    }

    private fun showActions(section: DashboardActionSection, offset: Int) {
        actionSection = section
        actionOffset = offset
        val board = snapshot ?: return showHome()
        beginScreen("actions", "行动 · ${sectionLabel(section)}")
        val intro = card()
        Ui.note(intro, "这里显示所有行动，包括本期之外需要回看的计划。")
        Ui.disclosure(intro, "筛选行动", "dashboardActionFilters") { filters ->
            listOf(DashboardActionSection.ALL, DashboardActionSection.DUE, DashboardActionSection.UPCOMING, DashboardActionSection.STOPPED)
                .filter { it != section }
                .forEach { option -> addButton(filters, sectionLabel(option), "actionSection:${option.name}") { showActions(option, 0) } }
        }
        runIo({ app.dashboard.actions(board, section = section, offset = offset, limit = PAGE_SIZE) }) { page ->
            Ui.note(intro, "共 ${page.total} 项。")
            if (page.items.isEmpty()) emptyState(intro, "当前分组没有行动。")
            page.items.forEach { item ->
                card().also { entry ->
                    Ui.cardTitle(entry, item.title)
                    val states = chipRow(entry)
                    chip(states, statusLabel(item.status), if (item.status == ActionStatus.OPEN) Ui.Tone.ACCENT else Ui.Tone.NEUTRAL)
                    if (item.due) chip(states, "待回看", Ui.Tone.WARN)
                    Ui.body(entry, "${item.reviewOn} 回看")
                    Ui.body(entry, "${dashboardExecutionLabel(item.latestExecution)} · ${originLabel(item.originState)}")
                    item.latestReview?.let { review ->
                        Ui.note(entry, "${review.reviewedOn} · ${actionExecutionLabel(review.execution)} · ${decisionLabel(review.decision)}")
                    }
                    addButton(entry, "查看计划", "action:${item.id}") { showActionDetail(item.id, null) }
                    item.latestReview?.let { review ->
                        addButton(entry, "最近一次回看", "actionReview:${item.id}") { showActionDetail(item.id, review.revision) }
                    }
                }
            }
            if (offset > 0) button("上一页", "actionsPrevious") { showActions(section, (offset - PAGE_SIZE).coerceAtLeast(0)) }
            page.nextOffset?.let { next -> button("下一页", "actionsNext") { showActions(section, next) } }
        }
    }

    private fun showActionDetail(id: String, revision: Int?) {
        val board = snapshot ?: return showHome()
        beginScreen("actionDetail", if (revision == null) "当前行动计划" else "回看时的计划 · 第 $revision 版")
        runIo({ app.dashboard.action(board, id, revision) }) { value -> renderActionRevision(value) }
    }

    private fun renderActionRevision(value: StoredActionRevision) {
        card().also { plan ->
            Ui.cardTitle(plan, value.draft.title)
            val states = chipRow(plan)
            chip(states, statusLabel(value.status), if (value.status == ActionStatus.OPEN) Ui.Tone.ACCENT else Ui.Tone.NEUTRAL)
            Ui.body(plan, "${value.draft.reviewOn} 回看")
            Ui.body(plan, "什么时候做：${value.draft.trigger}")
            Ui.body(plan, "准备怎么做：${value.draft.behavior}")
            Ui.body(plan, "怎样看出效果：${value.draft.expectedSignal}")
            Ui.disclosure(plan, "来源与版本", "dashboardActionMetadata") { metadata ->
                Ui.note(metadata, "来源：${value.draft.origin?.let { "知识 ${it.knowledgeId} · 第 ${it.revision} 版" } ?: "未关联知识"}")
                Ui.note(metadata, "第 ${value.revision} 版 · 时区 ${value.draft.zoneId}")
                Ui.note(metadata, "创建于 ${value.createdAt}\n更新于 ${value.updatedAt}")
            }
        }
        value.review?.let { review ->
            card().also { entry ->
                Ui.sectionTitle(entry, "这次回看")
                Ui.body(entry, "执行情况：${actionExecutionLabel(review.execution)} · 决定：${decisionLabel(review.decision)} · 回看日期 ${review.reviewedOn}")
                Ui.body(entry, "观察记录：${review.observedText}")
            }
        }
        value.reviewedDraft?.let { previous ->
            card().also { entry ->
                Ui.sectionTitle(entry, "被回看的原计划")
                Ui.body(entry, "标题：${previous.title}\n回看日期 ${previous.reviewOn}（${previous.zoneId}）")
            }
        }
    }

    private fun showMarkers(offset: Int) {
        markerOffset = offset
        val board = snapshot ?: return showHome()
        beginScreen("markers", "本期行动时间线")
        val intro = card()
        Ui.note(intro, "记录何时选定行动、何时回看。选定不代表已经执行。")
        runIo({ app.dashboard.markers(board, offset = offset, limit = PAGE_SIZE) }) { page ->
            Ui.note(intro, "共 ${page.total} 条。")
            if (page.items.isEmpty()) emptyState(intro, "本期还没有选定或回看行动。")
            page.items.forEach { marker ->
                card().also { entry ->
                    val states = chipRow(entry)
                    when (marker.kind) {
                        DashboardActionMarkerKind.ADOPTED -> {
                            chip(states, "采纳", Ui.Tone.ACCENT)
                            Ui.body(entry, marker.title)
                            Ui.note(entry, "${marker.date} · 选定行动")
                        }
                        DashboardActionMarkerKind.REVIEWED -> {
                            chip(states, "回看", Ui.Tone.OK)
                            Ui.body(entry, marker.title)
                            Ui.note(entry, "${marker.date} · ${marker.execution?.let(::actionExecutionLabel) ?: "未记录执行情况"} · ${marker.decision?.let(::decisionLabel) ?: "未记录决定"}")
                        }
                    }
                }
            }
            if (offset > 0) button("上一页", "markersPrevious") { showMarkers((offset - PAGE_SIZE).coerceAtLeast(0)) }
            page.nextOffset?.let { next -> button("下一页", "markersNext") { showMarkers(next) } }
        }
    }

    private fun showPreferences() {
        windowSpinner = null
        archivedCheck = null
        beginScreen("preferences", "看板设置")
        runIo({ app.dashboardPreferences.read() }) { view ->
            card().also { preferences ->
                Ui.fieldLabel(preferences, "显示最近多久")
                windowSpinner = spinnerIn(preferences, listOf("7 天", "30 天", "90 天"), windowIndex(view.value.window), "window")
                archivedCheck = checkIn(preferences, "统计包含已归档记录", view.value.includeArchived)
                Ui.disclosure(preferences, "其他显示信息", "dashboardPreferenceDetails") { details ->
                    Ui.note(details, "时区 ${view.value.zoneId}")
                    Ui.note(details, "关注指标：${view.value.cards.joinToString("、") { metricLabel(it) }}")
                    Ui.note(details, "关注主题：${view.value.focusDimensions.joinToString("、") { dimensionLabel(it) }}")
                    Ui.note(details, "以上信息暂不能在此页修改，主题偏好不筛选统计范围。")
                }

                primaryIn(preferences, "保存设置", "savePreferences") {
                    val selected = windowSpinner?.selectedItemPosition
                    if (selected != null) {
                        val window = when (selected) {
                            0 -> DashboardWindow.DAYS_7
                            1 -> DashboardWindow.DAYS_30
                            else -> DashboardWindow.DAYS_90
                        }
                        val include = archivedCheck?.isChecked ?: view.value.includeArchived
                        runIo({ app.dashboardPreferences.save(view.revision, view.value.copy(window = window, includeArchived = include)) }) {
                            toast("设置已保存")
                            selectedWindow = window
                            showHome()
                        }
                    }
                }
            }
        }
    }

    private fun exportDashboard() {
        val board = snapshot ?: return
        runIo({ CompletedExports(this).create("dashboard") { app.dataTransfer.exportDashboard(it, board) } }) { (_, file) ->
            TransferActivity.saveCompleted(this, file.name)
        }
    }

    private fun fieldLabel(key: String): String = if (key == "body") "正文" else "复盘回答"

    private fun signed(value: Int?): String = value?.let { if (it >= 0) "+$it" else it.toString() } ?: "无"

    private fun sourceTitle(category: DashboardSourceCategory, pointIndex: Int?): String =
        if (pointIndex != null) "所选时段" else when (category) {
            DashboardSourceCategory.ALL -> "本期全部"
            DashboardSourceCategory.EXACT_DATE -> "精确日期"
            DashboardSourceCategory.INTERVAL -> "区间记录"
            DashboardSourceCategory.OVERLAPPING_INTERVAL -> "跨边界区间"
            DashboardSourceCategory.UNKNOWN_TIME -> "发生日期未知"
        }

    private fun occurrenceLabel(source: SourceDescriptor): String = source.occurrence.let {
        when {
            it.startDate == null -> "发生日期未知"
            it.startDate == it.endDate -> it.startDate.toString()
            else -> "${it.startDate} 至 ${it.endDate}"
        }
    }

    private fun windowLabel(window: DashboardWindow) = when (window) {
        DashboardWindow.DAYS_7 -> "7 天"
        DashboardWindow.DAYS_30 -> "30 天"
        DashboardWindow.DAYS_90 -> "90 天"
    }

    private fun windowIndex(window: DashboardWindow) = when (window) {
        DashboardWindow.DAYS_7 -> 0
        DashboardWindow.DAYS_30 -> 1
        DashboardWindow.DAYS_90 -> 2
    }

    private fun metricLabel(metric: DashboardMetric) = when (metric) {
        DashboardMetric.SLEEP_DURATION -> "睡眠时长"
        DashboardMetric.EXERCISE_MINUTES -> "运动时长"
        DashboardMetric.DIET_MEALS -> "饮食餐次"
        DashboardMetric.RECORD_COVERAGE -> "记录覆盖"
    }

    private fun cardStateLabel(state: DashboardCardState) = when (state) {
        DashboardCardState.AVAILABLE -> "可用"
        DashboardCardState.SOURCE_NOT_CONNECTED -> "尚未接入"
        DashboardCardState.STRUCTURED_INPUT_UNAVAILABLE -> "暂无数据"
    }

    private fun timeClassLabel(timeClass: SourceTimeClass) = when (timeClass) {
        SourceTimeClass.WITHIN -> "日期在期内"
        SourceTimeClass.OVERLAP -> "区间跨期边界"
        SourceTimeClass.UNKNOWN -> "发生日期未知"
    }

    private fun statusLabel(status: ActionStatus) = when (status) {
        ActionStatus.OPEN -> "进行中"
        ActionStatus.STOPPED -> "已停止"
    }

    private fun sectionLabel(section: DashboardActionSection) = when (section) {
        DashboardActionSection.ALL -> "全部"
        DashboardActionSection.DUE -> "待回看"
        DashboardActionSection.UPCOMING -> "稍后回看"
        DashboardActionSection.STOPPED -> "已停止"
    }

    private fun originLabel(state: ActionOriginState) = when (state) {
        ActionOriginState.NOT_LINKED -> "未关联知识"
        ActionOriginState.CURRENT -> "关联知识当前有效"
        ActionOriginState.STALE -> "关联知识已失效或被替代"
        ActionOriginState.UNKNOWN -> "关联知识缺失或状态未知"
    }

    private fun dashboardExecutionLabel(execution: DashboardActionExecution) = when (execution) {
        DashboardActionExecution.NOT_REVIEWED -> "未回看"
        DashboardActionExecution.UNKNOWN -> "已回看 · 无法确认执行"
        DashboardActionExecution.NOT_EXECUTED -> "已回看，未执行"
        DashboardActionExecution.EXECUTED -> "已回看，已执行"
    }

    private fun actionExecutionLabel(execution: ActionExecution) = when (execution) {
        ActionExecution.UNKNOWN -> "无法确认"
        ActionExecution.NOT_EXECUTED -> "未执行"
        ActionExecution.EXECUTED -> "已执行"
    }

    private fun decisionLabel(decision: ActionDecision) = when (decision) {
        ActionDecision.CONTINUE -> "继续"
        ActionDecision.ADJUST -> "调整"
        ActionDecision.STOP -> "停止"
        ActionDecision.DEFER -> "推迟"
    }

    private fun dimensionLabel(dimension: ProfileDimension) = when (dimension) {
        ProfileDimension.VALUES_AND_DIRECTION -> "价值与方向"
        ProfileDimension.ROLES_AND_CONTEXT -> "角色与背景"
        ProfileDimension.INTERESTS_AND_PREFERENCES -> "兴趣与偏好"
        ProfileDimension.BEHAVIOR_AND_DECISIONS -> "行为与决策"
        ProfileDimension.ABILITIES_AND_STRATEGIES -> "能力与策略"
        ProfileDimension.ENERGY_AND_RECOVERY -> "精力与恢复"
        ProfileDimension.RELATIONSHIPS_AND_COLLABORATION -> "关系与协作"
        ProfileDimension.NARRATIVE_AND_CHANGES -> "人生叙事与变化"
    }

    companion object {
        private const val ACTIVITY_REQUEST = 91
        private const val PAGE_SIZE = 25
        private const val FRAGMENT_CODE_POINTS = 1_024
    }
}
