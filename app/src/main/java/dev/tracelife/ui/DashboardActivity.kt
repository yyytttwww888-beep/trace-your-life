package dev.tracelife.ui

import android.app.AlertDialog
import android.widget.ArrayAdapter
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
import dev.tracelife.transfer.StaleSnapshotException
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    override fun showHome() {
        beginScreen("home", "看板", showBack = false)
        runIo({ app.dashboardPreferences.open() }) { opened ->
            snapshot = opened
            renderHome(opened)
        }
    }

    override fun navigateBack() {
        if (busy) return
        when (screen) {
            "home" -> finish()
            "sourceText" -> showSourceFields(requireNotNull(textRecordId))
            "fields" -> showSources(sourceCategory, sourcePointIndex, sourceOffset)
            "actionDetail" -> showActions(actionSection, actionOffset)
            else -> showHome()
        }
    }

    override fun showError(failure: Throwable) {
        if (failure is StaleSnapshotException) {
            confirm("看板已失效", failure.message ?: "看板的资料、行动、说明或日期已变化，请重新读取。", "重新打开看板") { showHome() }
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
        card().also { overview ->
            Ui.sectionTitle(overview, "概览")
            Ui.body(overview, "窗口：${windowLabel(board.request.window)} · 时区：${board.request.zoneId}${if (board.request.includeArchived) " · 包含已归档记录" else " · 不含已归档记录"}")
            Ui.body(overview, "评估时间：${board.evaluatedAt}\n有效期至：${board.validUntil}")
            Ui.note(overview, "任何写入、跨日或到期都会使本页失效，届时需重新打开。")
            addButton(overview, "刷新看板", "refresh") { showHome() }
            addButton(overview, "看板偏好", "preferences") { showPreferences() }
            Ui.primary(overview, "导出当前看板", "exportDashboard") { if (!busy) exportDashboard() }
        }

        board.cards.forEach { renderCard(it) }
        note("看板数值只描述已保存的记录覆盖，不代表餐次、运动天数、健康或成长评价；记录覆盖是辅助区，不能当作未接通健康指标的数值。")

        renderComparison(board)

        card().also { points ->
            Ui.sectionTitle(points, "本期记录点")
            Ui.note(points, if (board.request.window == DashboardWindow.DAYS_90) {
                "90 天窗口按周一至周日的自然周汇总，首尾可能不足整周；不能按点序号与前期逐周相减。"
            } else {
                "7／30 天窗口为每日一点；包含当天的点标记为进行中。"
            })
            board.current.points.forEachIndexed { index, point ->
                val range = if (point.from == point.through) point.from.toString() else "${point.from} 至 ${point.through}"
                listRow(points, "$range（${point.days} 天）· 精确日期记录 ${point.exactDateRecords} 条 · 有记录 ${point.recordedDays} 天${if (point.inProgress) " · 进行中" else ""}", "pointSources:$index") {
                    showSources(DashboardSourceCategory.ALL, index, 0)
                }
            }
        }

        card().also { sources ->
            Ui.sectionTitle(sources, "本期来源下钻")
            listRow(sources, "本期全部来源", "sourcesAll") { showSources(DashboardSourceCategory.ALL, null, 0) }
            listRow(sources, "发生日期未知的来源", "sourcesUnknown") { showSources(DashboardSourceCategory.UNKNOWN_TIME, null, 0) }
            listRow(sources, "区间记录来源", "sourcesInterval") { showSources(DashboardSourceCategory.INTERVAL, null, 0) }
        }

        card().also { insights ->
            Ui.sectionTitle(insights, "说明")
            addButton(insights, "已发布指标说明（本期 ${board.insightCount} 条）", "insights") { showInsights(DashboardPeriodSelection.CURRENT, 0) }
        }

        card().also { actions ->
            Ui.sectionTitle(actions, "行动")
            addButton(actions, "全局行动（${board.actionCount} 项）", "actions") { showActions(DashboardActionSection.ALL, 0) }
            addButton(actions, "本期行动时间线（${board.markerCount} 条）", "markers") { showMarkers(0) }
        }
    }

    private fun renderCard(metricCard: DashboardCard) {
        card().also { container ->
            Ui.sectionTitle(container, metricLabel(metricCard.metric))
            chip(container, cardStateLabel(metricCard.state), if (metricCard.state == DashboardCardState.AVAILABLE) Ui.Tone.ACCENT else Ui.Tone.WARN)
            val current = metricCard.current
            if (current == null) {
                Ui.note(container, when (metricCard.state) {
                    DashboardCardState.SOURCE_NOT_CONNECTED -> "不可用：尚未接通数据来源，当前期没有数值。"
                    DashboardCardState.STRUCTURED_INPUT_UNAVAILABLE -> "不可用：尚无可用结构化餐次输入，当前期没有数值。"
                    DashboardCardState.AVAILABLE -> "当前期没有数值。"
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
        Ui.note(parent, "口径：${metric.definitionId} v${metric.definitionVersion} · 单位 ${metric.recordUnit}／${metric.coverageUnit}")
        Ui.note(parent, "缺失规则 ${metric.missingRule}：每日零值只表示没有已保存的精确日期记录，不表示当天没有活动。")
        Ui.note(parent, "区间规则 ${metric.intervalRule}：区间与未知日期不分摊到每日值。")
        Ui.note(parent, "时间规则 ${metric.timeRule}：记录按原始日期标签归属，不做时区换算。")
        Ui.note(parent, "去重规则 ${metric.deduplicationRule}。")
    }

    private fun renderComparison(board: DashboardSnapshot) {
        card().also { comparison ->
            Ui.sectionTitle(comparison, "两期比较")
            val currentValues = board.current.metric.values
            val previousValues = board.previous.metric.values
            Ui.body(comparison, "本期：${board.current.metric.scope.from} 至 ${board.current.metric.scope.through} · 精确日期记录 ${currentValues.exactDateRecords} 条 · 有记录 ${currentValues.recordedDays}／${currentValues.daysInScope} 天")
            Ui.body(comparison, "前期：${board.previous.metric.scope.from} 至 ${board.previous.metric.scope.through} · 精确日期记录 ${previousValues.exactDateRecords} 条 · 有记录 ${previousValues.recordedDays}／${previousValues.daysInScope} 天")
            when (board.comparison.state) {
                DashboardComparisonState.CURRENT_WINDOW_IN_PROGRESS ->
                    Ui.note(comparison, "本期窗口仍在进行，不提供差值，仅并列展示两期范围与覆盖。")
                DashboardComparisonState.COMPLETE_RECORDED_WINDOWS ->
                    Ui.body(comparison, "精确日期记录差值 ${signed(board.comparison.exactDateRecordsDelta)} 条 · 有记录天数差值 ${signed(board.comparison.recordedDaysDelta)} 天；差值仅描述已保存记录。")
            }
            Ui.note(comparison, "发生日期未知的记录可能同时属于两期候选，两期总量不能相加当作独立事件数。")
        }
    }

    private fun showSources(category: DashboardSourceCategory, pointIndex: Int?, offset: Int) {
        sourceCategory = category
        sourcePointIndex = pointIndex
        sourceOffset = offset
        val board = snapshot ?: return showHome()
        beginScreen("sources", "来源 · ${sourceTitle(category, pointIndex)}")
        runIo({ app.dashboard.sources(board, category = category, pointIndex = pointIndex, offset = offset, limit = PAGE_SIZE) }) { page ->
            note("共 ${page.total} 条候选来源。")
            if (page.items.isEmpty()) note("当前范围没有来源。")
            page.items.forEach { source ->
                listRow(root, "${occurrenceLabel(source)} · 修订 ${source.revision}${if (source.archived) " · 已归档" else ""}\n主题：${source.topics.joinToString("、").ifEmpty { "未分类" }} · ${timeClassLabel(source.timeClass)}", "source:${source.id}") {
                    showSourceFields(source.id)
                }
            }
            if (offset > 0) button("上一页", "sourcesPrevious") { showSources(category, pointIndex, (offset - PAGE_SIZE).coerceAtLeast(0)) }
            page.nextOffset?.let { next -> button("下一页", "sourcesNext") { showSources(category, pointIndex, next) } }
        }
    }

    private fun showSourceFields(recordId: String) {
        val board = snapshot ?: return showHome()
        beginScreen("fields", "来源字段")
        runIo({ app.dashboard.sourceFields(board, recordId) }) { fields ->
            note("记录 ${recordId.take(8)}… 的已登记字段。附件只有元数据，本模块不读取或分析图片。")
            if (fields.isEmpty()) note("没有可展示字段。")
            fields.forEach { field ->
                when (field.kind) {
                    SourceFieldKind.TEXT -> listRow(root, "文本字段 ${field.key}（${field.codePoints} 码点）", "field:${field.key}") { showSourceText(recordId, field.key, 0) }
                    SourceFieldKind.ATTACHMENT -> note("附件字段 ${field.key}：图片内容不在此读取。")
                }
            }
        }
    }

    private fun showSourceText(recordId: String, field: String, start: Int) {
        textRecordId = recordId
        val board = snapshot ?: return showHome()
        beginScreen("sourceText", "来源原文")
        runIo({ app.dashboard.sourceText(board, recordId, field, start = start, maxCodePoints = FRAGMENT_CODE_POINTS) }) { fragment ->
            note("字段 ${fragment.field} · 码点 ${fragment.start}–${fragment.end}（左闭右开）· 共 ${fragment.totalCodePoints}")
            text(fragment.text)
            if (fragment.end < fragment.totalCodePoints) {
                button("继续读取", "readMore") { showSourceText(recordId, field, fragment.end) }
            } else {
                note("已到字段末尾。")
            }
        }
    }

    private fun showInsights(period: DashboardPeriodSelection, offset: Int) {
        insightPeriod = period
        insightOffset = offset
        val board = snapshot ?: return showHome()
        beginScreen("insights", "已发布指标说明 · ${if (period == DashboardPeriodSelection.CURRENT) "本期" else "前期"}")
        val intro = card()
        Ui.note(intro, "只列出已发布、当前有效且凭据与整期来源、范围、口径及计算值完全一致的说明；没有匹配就是空，不触发任何模型请求。")
        addButton(
            intro,
            if (period == DashboardPeriodSelection.CURRENT) "查看前期说明（${board.previousInsightCount} 条）" else "查看本期说明（${board.insightCount} 条）",
            "switchInsightPeriod",
        ) { showInsights(if (period == DashboardPeriodSelection.CURRENT) DashboardPeriodSelection.PREVIOUS else DashboardPeriodSelection.CURRENT, 0) }
        runIo({ app.dashboard.insights(board, period = period, offset = offset, limit = PAGE_SIZE) }) { page ->
            Ui.note(intro, "共 ${page.total} 条。")
            if (page.items.isEmpty()) Ui.note(intro, "该期没有已发布且凭据完全匹配的指标说明。")
            page.items.forEach { insight ->
                card().also { entry ->
                    Ui.body(entry, insight.text)
                    Ui.note(entry, "知识 ${insight.id.take(8)}… · 修订 ${insight.revision} · 指标 ${insight.metricId}")
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
        beginScreen("actions", "全局行动 · ${sectionLabel(section)}")
        val intro = card()
        Ui.note(intro, "行动区是全局的，不随图表窗口隐藏过期计划。「未回看」与用户回看后填写的「无法确认」含义不同；采纳与回看标记不构成开始、成功或因果证据。")
        listOf(DashboardActionSection.ALL, DashboardActionSection.DUE, DashboardActionSection.UPCOMING, DashboardActionSection.STOPPED)
            .filter { it != section }
            .forEach { option -> addButton(intro, "查看：${sectionLabel(option)}", "actionSection:${option.name}") { showActions(option, 0) } }
        runIo({ app.dashboard.actions(board, section = section, offset = offset, limit = PAGE_SIZE) }) { page ->
            Ui.note(intro, "共 ${page.total} 项。")
            if (page.items.isEmpty()) Ui.note(intro, "当前分组没有行动。")
            page.items.forEach { item ->
                card().also { entry ->
                    Ui.sectionTitle(entry, item.title)
                    val states = chipRow(entry)
                    chip(states, statusLabel(item.status), if (item.status == ActionStatus.OPEN) Ui.Tone.ACCENT else Ui.Tone.NEUTRAL)
                    if (item.due) chip(states, "已到期", Ui.Tone.WARN)
                    Ui.body(entry, "回看日期 ${item.reviewOn}（${item.zoneId}）")
                    Ui.body(entry, "来源状态：${originLabel(item.originState)} · 最近执行：${dashboardExecutionLabel(item.latestExecution)}")
                    item.latestReview?.let { review ->
                        Ui.note(entry, "最近回看：修订 ${review.revision} · ${review.reviewedOn}（${review.zoneId}）· 执行 ${actionExecutionLabel(review.execution)} · 决定 ${decisionLabel(review.decision)}")
                    }
                    addButton(entry, "查看当前计划（修订 ${item.revision}）", "action:${item.id}") { showActionDetail(item.id, null) }
                    item.latestReview?.let { review ->
                        addButton(entry, "查看最近回看（修订 ${review.revision}）", "actionReview:${item.id}") { showActionDetail(item.id, review.revision) }
                    }
                }
            }
            if (offset > 0) button("上一页", "actionsPrevious") { showActions(section, (offset - PAGE_SIZE).coerceAtLeast(0)) }
            page.nextOffset?.let { next -> button("下一页", "actionsNext") { showActions(section, next) } }
        }
    }

    private fun showActionDetail(id: String, revision: Int?) {
        val board = snapshot ?: return showHome()
        beginScreen("actionDetail", if (revision == null) "当前行动计划" else "回看时的计划 · 修订 $revision")
        runIo({ app.dashboard.action(board, id, revision) }) { value -> renderActionRevision(value) }
    }

    private fun renderActionRevision(value: StoredActionRevision) {
        card().also { plan ->
            Ui.sectionTitle(plan, value.draft.title)
            val states = chipRow(plan)
            chip(states, statusLabel(value.status), if (value.status == ActionStatus.OPEN) Ui.Tone.ACCENT else Ui.Tone.NEUTRAL)
            Ui.body(plan, "修订 ${value.revision} · 回看日期 ${value.draft.reviewOn}（${value.draft.zoneId}）")
            Ui.body(plan, "触发情境：${value.draft.trigger}")
            Ui.body(plan, "具体做法：${value.draft.behavior}")
            Ui.body(plan, "预期信号：${value.draft.expectedSignal}")
            Ui.note(plan, "来源：${value.draft.origin?.let { "知识 ${it.knowledgeId.take(8)}… · 修订 ${it.revision}" } ?: "未关联知识"}")
            Ui.note(plan, "创建于 ${value.createdAt} · 更新于 ${value.updatedAt}")
        }
        value.review?.let { review ->
            card().also { entry ->
                Ui.sectionTitle(entry, "本次修订的回看")
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
        Ui.note(intro, "只包含本期日期内的采纳与回看标记。采纳不表示实际开始或执行；回看标记保留被回看计划的日期与时区，不构成行动造成变化的因果证据。")
        runIo({ app.dashboard.markers(board, offset = offset, limit = PAGE_SIZE) }) { page ->
            Ui.note(intro, "共 ${page.total} 条。")
            if (page.items.isEmpty()) Ui.note(intro, "本期没有采纳或回看标记。")
            page.items.forEach { marker ->
                card().also { entry ->
                    val states = chipRow(entry)
                    when (marker.kind) {
                        DashboardActionMarkerKind.ADOPTED -> {
                            chip(states, "采纳", Ui.Tone.ACCENT)
                            Ui.body(entry, marker.title)
                            Ui.note(entry, "采纳时间 ${marker.occurredAt} · 按看板时区 ${marker.zoneId} 归属 ${marker.date} · 修订 ${marker.revision}")
                        }
                        DashboardActionMarkerKind.REVIEWED -> {
                            chip(states, "回看", Ui.Tone.OK)
                            Ui.body(entry, marker.title)
                            Ui.note(entry, "回看日期 ${marker.date}（${marker.zoneId}）· 执行 ${marker.execution?.let(::actionExecutionLabel) ?: "未记录"} · 决定 ${marker.decision?.let(::decisionLabel) ?: "未记录"} · 修订 ${marker.revision}")
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
        beginScreen("preferences", "看板偏好")
        runIo({ app.dashboardPreferences.read() }) { view ->
            card().also { preferences ->
                Ui.sectionTitle(preferences, "统计窗口")
                windowSpinner = Spinner(this).apply {
                    tag = "window"
                    adapter = ArrayAdapter(this@DashboardActivity, android.R.layout.simple_spinner_item, listOf("7 天", "30 天", "90 天")).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    setSelection(windowIndex(view.value.window))
                    Ui.styleSpinner(this@DashboardActivity, this)
                    preferences.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(4), 0, dp(8))
                    })
                }
                archivedCheck = CheckBox(this).apply {
                    text = "统计包含已归档记录"
                    isChecked = view.value.includeArchived
                    Ui.styleCheck(this@DashboardActivity, this)
                    preferences.addView(this)
                }
                Ui.note(preferences, "时区：${view.value.zoneId}（本页不修改）")
                Ui.note(preferences, "指标卡片：${view.value.cards.joinToString("、") { metricLabel(it) }}（本页不修改）")
                Ui.note(preferences, "关注维度：${view.value.focusDimensions.joinToString("、") { dimensionLabel(it) }}（仅用于导航偏好，不筛选全局统计）")
                Ui.note(preferences, if (view.updatedAt == null) "尚未保存过设置，当前显示默认值。" else "设置版本 ${view.revision} · 保存于 ${view.updatedAt}")
                Ui.note(preferences, "保存设置会使当前看板失效，保存成功后自动重新打开看板。")
                Ui.primary(preferences, "保存偏好并重开看板", "savePreferences") {
                    if (!busy) {
                        val selected = windowSpinner?.selectedItemPosition
                        if (selected != null) {
                            val window = when (selected) {
                                0 -> DashboardWindow.DAYS_7
                                1 -> DashboardWindow.DAYS_30
                                else -> DashboardWindow.DAYS_90
                            }
                            val include = archivedCheck?.isChecked ?: view.value.includeArchived
                            runIo({ app.dashboardPreferences.save(view.revision, view.value.copy(window = window, includeArchived = include)) }) {
                                toast("看板偏好已保存")
                                showHome()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun exportDashboard() {
        val board = snapshot ?: return
        val directory = getExternalFilesDir(null)
        if (directory == null) {
            showError(IllegalStateException("外部存储目录不可用"))
            return
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val target = File(directory, "dashboard-$stamp.zip")
        runIo({ app.dataTransfer.exportDashboard(target, board) }) { result ->
            AlertDialog.Builder(this).setTitle("看板已导出")
                .setMessage("路径：${target.absolutePath}\n文件 ${result.files} 个 · 记录 ${result.records} 条 · 共 ${result.bytes} 字节\n\n导出是评估时刻的固定历史视图，不随以后修改更新；不可用于恢复，也不可作为模型引用凭据。包内来源属于记录覆盖辅助区，不作为未接通健康指标的依据。")
                .setPositiveButton("知道了", null).show()
        }
    }

    private fun signed(value: Int?): String = value?.let { if (it >= 0) "+$it" else it.toString() } ?: "无"

    private fun sourceTitle(category: DashboardSourceCategory, pointIndex: Int?): String =
        if (pointIndex != null) "记录点 ${pointIndex + 1} 的贡献记录" else when (category) {
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
        DashboardCardState.SOURCE_NOT_CONNECTED -> "未接通来源"
        DashboardCardState.STRUCTURED_INPUT_UNAVAILABLE -> "无可用结构化输入"
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
        DashboardActionSection.DUE -> "已到期"
        DashboardActionSection.UPCOMING -> "未到期"
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
        DashboardActionExecution.UNKNOWN -> "已回看，用户填写无法确认执行"
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
        private const val PAGE_SIZE = 25
        private const val FRAGMENT_CODE_POINTS = 1_024
    }
}
