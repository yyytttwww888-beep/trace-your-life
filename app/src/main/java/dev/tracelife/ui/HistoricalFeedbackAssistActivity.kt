package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.tracelife.analysis.*
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, resumable comparisons. Opening or rotating the screen never starts a model call. */
class HistoricalFeedbackAssistActivity : ScreenActivity() {
    private lateinit var runId: String
    private lateinit var target: ClaimRef
    private var offset = 0
    private var onlyAttention = false
    private val stop = AtomicBoolean(false)
    private var runningDialog: AlertDialog? = null

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        runId = requireNotNull(intent.getStringExtra(EXTRA_RUN_ID))
        target = ClaimRef(requireNotNull(intent.getStringExtra(EXTRA_BATCH_KEY)), intent.getIntExtra(EXTRA_CLAIM_INDEX, -1),
            requireNotNull(intent.getStringExtra(EXTRA_CLAIM_SHA256)))
        offset = savedInstanceState?.getInt("offset") ?: 0
        onlyAttention = savedInstanceState?.getBoolean("onlyAttention") ?: false
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("offset", offset); outState.putBoolean("onlyAttention", onlyAttention)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { stop.set(true); super.onPause() }
    override fun onDestroy() { stop.set(true); runningDialog?.dismiss(); super.onDestroy() }
    override fun showError(failure: Throwable) { runningDialog?.dismiss(); runningDialog = null; super.showError(failure) }

    override fun showHome() {
        beginScreen("historicalAssist", "对照以前的意见")
        runIo({ app.analysis.historicalFeedbackAssistPage(runId, target, offset, onlyAttention) }, ::render)
    }

    private fun render(page: HistoricalFeedbackAssistPage) {
        val editable = !page.archived && page.status != AnalysisStatus.IN_FLIGHT
        val header = card()
        addText(header, page.targetText)
        addText(header, "已辅助比较 ${page.scanned} / ${page.total} 条 · 还需确认 ${page.unresolved} 条", 13f)
        if (page.attention > 0) addText(header, "${page.attention} 条存在冲突或不确定，需要单独处理。", 13f)
        note("先分批比较含义，再由你确认。辅助意见不代表原文支持这条发现；保存知识前还会重新核对原文。")
        if (page.archived) note("这次分析已归档，恢复后可以继续比较。")
        else if (page.status == AnalysisStatus.IN_FLIGHT) {
            button("恢复中断的比较", "historyAssistRecover") {
                confirm("恢复检查？", "仅当之前的请求已停止时使用。已发出的请求仍计入用量，恢复不会自动重新发送。", "恢复进度") {
                    runIo({ app.analysis.recoverInterrupted(runId) }) { showHome() }
                }
            }
        } else if (page.scanned < page.total) {
            primaryButton(if (page.scanned == 0) "开始辅助对照" else "继续辅助对照", "historyAssistStart") {
                confirm("分批对照以前的意见", "将本次发现与选定的历史描述、个人意见发送给 DeepSeek。每次最多检查 3 批，产生请求用量；完成后仍由你确认。", "开始对照") {
                    startScan()
                }
            }
        }
        if (page.scanned == page.total) note("全部相关历史均已比较。冲突、不确定和未确认项仍会阻止保存。")
        button(if (onlyAttention) "查看全部意见" else "只看冲突和不确定项", "historyAssistFilter") {
            onlyAttention = !onlyAttention; offset = 0; showHome()
        }
        page.items.forEach { row ->
            val item = card()
            addText(item, row.suggestion?.assessment?.let(::assessment) ?: "还没有辅助比较", 18f)
            addText(item, "以前的描述：${row.content.previousClaimText ?: "原描述已不可用"}")
            addText(item, "当时的意见：${row.content.decision.name.let { when (it) {
                "CORRECTED" -> "纠正"; "REJECTED" -> "不认同"; else -> "撤回"
            } }}${row.content.userText?.let { "\n$it" }.orEmpty()}")
            row.suggestion?.let { addText(item, "比较理由：${it.reason}") }
            row.resolution?.let { addText(item, "你的选择：${choice(it.choice)}\n${it.reason}", 13f) }
            if (editable) addButton(item, "单独填写我的判断", "historyAssistResolve:${row.content.reference.id}") {
                val form = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
                val options = HistoricalFeedbackChoice.entries
                val select = spinnerIn(form, options.map(::choice), row.resolution?.choice?.ordinal ?: 0, "historyAssistChoice")
                val reason = fieldIn(form, "这条发现如何回应以前的意见", row.resolution?.reason.orEmpty(), true, 1_000)
                val dialog = AlertDialog.Builder(this).setTitle("我的对照判断").setView(form).setNegativeButton("取消", null)
                    .setPositiveButton("保存", null).create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (reason.text.toString().isBlank()) { reason.error = "请填写对照理由"; return@setOnClickListener }
                        dialog.dismiss()
                        runIo({ app.analysis.resolveHistoricalFeedback(runId, target, row.content.reference.id, page.runVersion,
                            options[select.selectedItemPosition], reason.text.toString(), row.resolution?.version) }) { showHome() }
                    }
                }
                dialog.show()
            }
        }
        val safe = page.items.filter { it.resolution == null && it.suggestion?.assessment in
            setOf(HistoricalFeedbackAssessment.ALIGNED, HistoricalFeedbackAssessment.UNRELATED) }.map { it.content.reference.id }.toSet()
        if (editable && safe.isNotEmpty()) primaryButton("确认本页 ${safe.size} 条无冲突建议", "historyAssistAcceptPage") {
            confirm("采用本页的 ${safe.size} 条比较建议？", "这将保存为你已查看并采用这些比较理由。只处理本页尚未作出个人选择的无冲突建议；其他页、冲突和不确定项仍需处理。", "我已查看，采用本页建议") {
                runIo({ app.analysis.acceptHistoricalFeedbackSuggestions(page, safe) }) { toast("已保存你的选择"); showHome() }
            }
        }
        if (offset > 0) button("回到第一批意见", "historyAssistFirst") { offset = 0; showHome() }
        page.nextOffset?.let { next -> button("下一批意见", "historyAssistNext") { offset = next; showHome() } }
        if (page.items.isEmpty()) emptyState(card(), if (onlyAttention) "当前没有待处理的冲突或不确定项。" else "没有相关历史意见。")
        if (page.requests > 0) Ui.disclosure(root, "比较用量", "historyAssistUsage") {
            addText(it, "已登记 ${page.requests} 次请求；成功响应报告输入 ${page.inputTokens}、输出 ${page.outputTokens} token。失败或中断的实际用量可能未返回。", 13f)
        }
    }

    private fun startScan() {
        stop.set(false)
        runningDialog = AlertDialog.Builder(this).setTitle("正在分批对照")
            .setMessage("每批完成都会保存进度。停止后会保留正在请求的这一批，并停止后续请求。")
            .setNegativeButton("停止后续检查") { _, _ -> stop.set(true) }.setCancelable(false).create().also { it.show() }
        runIo({ HistoricalFeedbackAssist(app.analysis).process(runId, target, app.deepSeekProvider, 3, stop::get) }) { result ->
            runningDialog?.dismiss(); runningDialog = null; offset = 0
            toast(when (result.reason) {
                HistoricalFeedbackAssistStop.COMPLETE -> "全部历史意见已比较，请查看后确认"
                HistoricalFeedbackAssistStop.CANCELLED -> "已停止，进度已保存"
                HistoricalFeedbackAssistStop.BUDGET -> "已达到本次预算，剩余意见可逐项处理"
                HistoricalFeedbackAssistStop.REQUEST_LIMIT -> "这一轮比较已完成，可以继续下一批"
                HistoricalFeedbackAssistStop.RETRY_NEEDED -> "请求未成功，已保留进度"
                HistoricalFeedbackAssistStop.NEEDS_RECOVERY -> "之前的请求需要恢复"
                HistoricalFeedbackAssistStop.STALE -> "资料或历史意见已变化，请重新分析"
                HistoricalFeedbackAssistStop.ARCHIVED -> "请先恢复这次分析"
            })
            showHome()
        }
    }

    private fun assessment(value: HistoricalFeedbackAssessment) = when (value) {
        HistoricalFeedbackAssessment.ALIGNED -> "建议：已遵守以前的意见"
        HistoricalFeedbackAssessment.UNRELATED -> "建议：与这次描述无关"
        HistoricalFeedbackAssessment.CONFLICT -> "需要处理：仍存在冲突"
        HistoricalFeedbackAssessment.UNCERTAIN -> "需要处理：暂时无法确定"
    }
    private fun choice(value: HistoricalFeedbackChoice) = when (value) {
        HistoricalFeedbackChoice.RESPECTED -> "已考虑并遵守"
        HistoricalFeedbackChoice.UNRELATED -> "情境不同，不适用"
        HistoricalFeedbackChoice.CONFLICT -> "仍然存在冲突"
    }
    companion object {
        const val EXTRA_RUN_ID = "historicalAssist.runId"
        const val EXTRA_BATCH_KEY = "historicalAssist.batchKey"
        const val EXTRA_CLAIM_INDEX = "historicalAssist.claimIndex"
        const val EXTRA_CLAIM_SHA256 = "historicalAssist.claimSha256"
        fun intent(context: Context, runId: String, target: ReflectionTarget): Intent {
            val ref = when (target) {
                is ReflectionTarget.Claim -> target.ref
                is ReflectionTarget.Reduction -> ReductionGraph.feedbackRef(target.ref)
            }
            return Intent(context, HistoricalFeedbackAssistActivity::class.java).putExtra(EXTRA_RUN_ID, runId)
                .putExtra(EXTRA_BATCH_KEY, ref.batchKey).putExtra(EXTRA_CLAIM_INDEX, ref.claimIndex).putExtra(EXTRA_CLAIM_SHA256, ref.claimSha256)
        }
    }
}
