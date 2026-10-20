package dev.tracelife.ui

import android.content.Intent
import android.os.Bundle
import dev.tracelife.analysis.HistoricalFeedbackChoice
import dev.tracelife.transfer.StaleSnapshotException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Independent human comparison notes remain readable after their machine task is removed. */
class FeedbackNotesActivity : ScreenActivity() {
    private var selectedRun: String? = null
    private var refreshOnResume = false

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        selectedRun = savedInstanceState?.getString("selectedRun")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedRun", selectedRun)
        super.onSaveInstanceState(outState)
    }
    override fun showHome() { selectedRun?.let { showNotes(it) } ?: showGroups() }
    override fun onPause() { super.onPause(); refreshOnResume = true }
    override fun onResume() {
        super.onResume()
        if (refreshOnResume && !busy) { refreshOnResume = false; showHome() }
    }
    override fun navigateBack() {
        if (busy) return
        if (selectedRun != null) { selectedRun = null; showGroups() } else finish()
    }

    private fun showGroups(offset: Int = 0, previousGeneration: Long? = null) {
        selectedRun = null
        beginScreen("groups", "我的对照说明")
        note("这些是你在对照以前的意见时，亲自写下的理由。相关分析或原始资料删除后，说明仍留在这里。")
        runIo({
            val generation = app.repository.currentAnalysisGeneration()
            if (previousGeneration != null && generation != previousGeneration) throw StaleSnapshotException("对照说明已有变化，请重新打开")
            val groups = app.repository.historicalComparisonRuns(offset, PAGE_SIZE)
            val hasNext = groups.size == PAGE_SIZE && app.repository.historicalComparisonRuns(offset + groups.size, 1).isNotEmpty()
            if (generation != app.repository.currentAnalysisGeneration()) throw StaleSnapshotException("对照说明在读取时有变化，请重新打开")
            Triple(groups, hasNext, generation)
        }) { (groups, hasNext, generation) ->
            if (groups.isEmpty()) emptyState(card(), "还没有独立对照说明。\n分析遇到以前的纠正或否定时，你可以逐项写下本次如何看待它们。")
            groups.forEach { group ->
                listRow(root, "${date(group.latestAt)}\n这一轮留下 ${group.noteCount} 条对照说明", "comparisonRun:${group.runId}") { showNotes(group.runId) }
            }
            if (offset > 0) button("回到最近记录", "comparisonGroupsFirst") { showGroups() }
            if (hasNext) button("更早的对照说明", "comparisonGroupsNext") { showGroups(offset + groups.size, generation) }
        }
    }

    private fun showNotes(runId: String, offset: Int = 0, previousGeneration: Long? = null) {
        selectedRun = runId
        beginScreen("notes", "这一轮的对照说明")
        note("按最近写入的时间回看。每次选择与理由分别保留，较早的选择不代表你现在仍然认同；这些说明不是新的原始证据。")
        runIo({
            val generation = app.repository.currentAnalysisGeneration()
            if (previousGeneration != null && generation != previousGeneration) throw StaleSnapshotException("对照说明已有变化，请重新打开")
            val notes = app.repository.historicalComparisons(runId, offset, PAGE_SIZE)
            val hasNext = notes.size == PAGE_SIZE && app.repository.historicalComparisons(runId, offset + notes.size, 1).isNotEmpty()
            val task = app.repository.analysisRun(runId)
            if (generation != app.repository.currentAnalysisGeneration()) throw StaleSnapshotException("对照说明在读取时有变化，请重新打开")
            NotesPage(notes, hasNext, generation, task != null)
        }) { page ->
            if (page.notes.isEmpty()) emptyState(card(), "本轮没有保留的对照说明。")
            page.notes.forEach { note ->
                val resolution = note.resolution
                card().also { item ->
                    Ui.sectionTitle(item, choice(resolution.choice))
                    Ui.body(item, resolution.reason)
                    Ui.note(item, "${date(resolution.createdAt)} · 这条意见的第 ${resolution.version} 次对照")
                }
            }
            if (offset > 0) button("回到最近说明", "comparisonNotesFirst") { showNotes(runId) }
            if (page.hasNext) button("更早的说明", "comparisonNotesNext") { showNotes(runId, offset + page.notes.size, page.generation) }
            if (page.taskExists && runId.startsWith("reflection-v1-")) button("回看当时的分析", "comparisonOriginalAnalysis") {
                startActivity(Intent(this, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, runId))
            } else if (!page.taskExists) note("当时的分析已删除。你的说明独立保留，原机器正文不会从这里恢复。")
            if (page.notes.isNotEmpty()) Ui.disclosure(root, "管理本轮说明", "comparisonNotesManagement") { management ->
                dangerIn(management, "永久删除本轮说明", "deleteComparisonNotes") {
                    confirm("永久删除本轮对照说明？", "本轮全部独立对照说明都会删除。关联的分析及其派生知识也会清理，无法撤销。原始记录和单独保存的用户反馈仍保留。", "永久删除") {
                        runIo({ app.repository.deleteHistoricalComparisons(runId, page.generation) }) {
                            toast("本轮对照说明已删除"); selectedRun = null; showGroups()
                        }
                    }
                }
            }
        }
    }

    private data class NotesPage(
        val notes: List<dev.tracelife.storage.HistoricalComparisonNote>, val hasNext: Boolean,
        val generation: Long, val taskExists: Boolean,
    )
    private fun choice(value: HistoricalFeedbackChoice): String = when (value) {
        HistoricalFeedbackChoice.RESPECTED -> "当时认为：已经尊重之前的意见"
        HistoricalFeedbackChoice.UNRELATED -> "当时认为：情境不同，不适用这条意见"
        HistoricalFeedbackChoice.CONFLICT -> "当时认为：仍然存在冲突"
    }
    private fun date(value: Instant) = value.atZone(ZoneId.systemDefault()).format(DISPLAY_TIME)
    companion object {
        private const val PAGE_SIZE = 20
        private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
