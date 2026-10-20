package dev.tracelife.ui

import android.os.Bundle
import dev.tracelife.analysis.AnalysisRunCodec
import dev.tracelife.analysis.CallStatus
import dev.tracelife.transfer.StaleSnapshotException

/** Local lifecycle management for every task type, including background organization. */
class TaskArchiveActivity : ScreenActivity() {
    private var archived = false
    private var offset = 0
    override fun restoreScreenState(savedInstanceState: Bundle?) {
        archived = savedInstanceState?.getBoolean("archived") ?: false
        offset = savedInstanceState?.getInt("offset") ?: 0
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("archived", archived); outState.putInt("offset", offset)
        super.onSaveInstanceState(outState)
    }
    override fun showHome() {
        beginScreen("home", "管理分析记录")
        note("整理、主动分析和其他回看任务都在这里。归档会收起任务，保留知识和依据；恢复不会自动请求服务。")
        button(if (archived) "查看进行中的任务" else "查看已归档的任务", "allTasksToggle") {
            archived = !archived; offset = 0; showHome()
        }
        section(if (archived) "已归档" else "进行中")
        runIo({
            val generation = app.repository.currentAnalysisGeneration()
            val index = app.repository.analysisRunIndex(archived = archived, offset = offset, limit = PAGE_SIZE)
            val values = index.map { requireNotNull(app.repository.analysisRun(it.id)).let { run -> run to AnalysisRunCodec.decode(run.payload) } }
            val next = index.size == PAGE_SIZE && app.repository.analysisRunIndex(archived = archived, offset = offset + index.size, limit = 1).isNotEmpty()
            if (generation != app.repository.currentAnalysisGeneration()) throw StaleSnapshotException("任务已有变化，请刷新后重试")
            values to next
        }) { (runs, next) ->
            if (runs.isEmpty()) emptyState(card(), "这里暂时没有任务。")
            runs.forEach { (run, state) ->
                val container = card()
                Ui.sectionTitle(container, "${state.request.scope.from} — ${state.request.scope.through}")
                Ui.body(container, state.request.question)
                Ui.note(container, "${state.calls.count { it.status == CallStatus.SUCCEEDED }} 次请求已完成。归档状态不代表内容已经核对或判断成立。")
                if (state.calls.any { it.status == CallStatus.IN_FLIGHT }) {
                    Ui.note(container, "这项任务正在请求或等待恢复，暂不能归档。")
                } else if (run.archivedAt == null) {
                    addButton(container, "归档", "archiveAnyTask:${run.id}") {
                        confirm("归档这项任务？", "保留全部处理进度、知识和依据，之后可以恢复继续。", "归档") {
                            runIo({ app.repository.archiveAnalysisRun(run.id, run.version) }) { offset = 0; showHome() }
                        }
                    }
                } else addButton(container, "恢复为进行中", "restoreAnyTask:${run.id}") {
                    runIo({ app.repository.unarchiveAnalysisRun(run.id, run.version) }) { offset = 0; showHome() }
                }
            }
            if (offset > 0) button("上一页", "allTasksPrevious") { offset = maxOf(0, offset - PAGE_SIZE); showHome() }
            if (next) button("下一页", "allTasksNext") { offset += PAGE_SIZE; showHome() }
        }
    }
    companion object { private const val PAGE_SIZE = 20 }
}
