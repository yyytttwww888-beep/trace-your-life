package dev.tracelife.ui

import android.content.Intent
import android.os.Bundle
import dev.tracelife.background.*
import dev.tracelife.knowledge.KnowledgeEvidenceState
import dev.tracelife.knowledge.KnowledgeFilter
import dev.tracelife.knowledge.KnowledgeIndexPage

/** Read-only inbox. Opening or recreating this screen never schedules a paid model call. */
class MaintenanceInboxActivity : ScreenActivity() {
    private var jobOffset = 0
    private var knowledgeOffset = 0
    private var refreshOnReturn = false
    private var resumed = false

    override fun onPause() {
        resumed = false
        refreshOnReturn = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshIfNeeded()
    }

    /** A return while the prior read is pending must not lose the refresh or reset paging. */
    private fun refreshIfNeeded() {
        if (resumed && refreshOnReturn && !busy) {
            refreshOnReturn = false
            showHome()
        }
    }

    override fun showError(failure: Throwable) {
        if (resumed && refreshOnReturn) refreshIfNeeded() else super.showError(failure)
    }

    override fun restoreScreenState(savedInstanceState: Bundle?) {
        jobOffset = savedInstanceState?.getInt("jobs") ?: 0
        knowledgeOffset = savedInstanceState?.getInt("knowledge") ?: 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("jobs", jobOffset)
        outState.putInt("knowledge", knowledgeOffset)
        super.onSaveInstanceState(outState)
    }

    override fun showHome() {
        beginScreen("inbox", "待查看")
        note("周期回看、待核对描述与需要更新的知识集中在这里。打开只查看；保存、重新整理和采用仍由你决定。")
        runIo({
            val jobs = app.incrementalOrganizer.jobs(jobOffset, PAGE_SIZE)
            var knowledge = app.knowledge.index(KnowledgeFilter(includeInvalid = true), PAGE_SIZE)
            repeat(knowledgeOffset / PAGE_SIZE) { if (knowledge.nextOffset != null) knowledge = app.knowledge.next(knowledge) }
            Triple(app.incrementalOrganizer.settings(), jobs, knowledge)
        }) { (settings, jobs, knowledge) ->
            val status = card()
            Ui.sectionTitle(status, "自动回看")
            Ui.note(status, when {
                settings.requiresEnableAfterRestore -> "恢复后已撤销自动整理授权，请重新选择。"
                !settings.policy.enabled -> "尚未开启自动整理。"
                settings.policy.paused -> "已暂停，保留原有进度。"
                else -> "今日已占用 ${settings.reservedRequestsToday} / ${settings.policy.dailyRequestLimit} 次请求（UTC）。"
            })
            addButton(status, "自动整理设置", "inboxSettings") { startActivity(Intent(this, SettingsActivity::class.java)) }
            renderJobs(jobs)
            renderKnowledge(knowledge)
            refreshIfNeeded()
        }
    }

    private fun renderJobs(jobs: List<BackgroundJob>) {
        val items = card()
        Ui.sectionTitle(items, "整理与回看")
        if (jobs.isEmpty()) Ui.note(items, "这一页还没有自动整理任务。")
        jobs.forEachIndexed { index, job ->
            if (index > 0) Ui.divider(items)
            Ui.body(items, backgroundJobTitle(job))
            Ui.note(items, "${backgroundJobStatus(job.status)} · 已占用 ${job.reservedRequests} 次请求")
            if (job.kind != BackgroundJobKind.RECORD) Ui.note(items, "${job.from} 至 ${job.through} · ${job.zoneId}")
            if (job.kind == BackgroundJobKind.MONTHLY_PROFILE) Ui.note(items, "只形成待核对描述，完整维护核对和正式采用仍需你确认。")
            if (job.status == BackgroundJobStatus.REBUILD_REQUIRED) Ui.note(items, "原范围资料已变化。旧结果保留，明确重新整理后才会建立新的任务。")
            if (job.status in setOf(BackgroundJobStatus.FAILED, BackgroundJobStatus.NEEDS_RECOVERY))
                Ui.note(items, "已停止，未经你明确重试不会再次付费调用。")
            if (job.runId != null && job.status != BackgroundJobStatus.SOURCE_UNAVAILABLE) {
                addButton(items, if (job.kind == BackgroundJobKind.RECORD) "查看逐条整理" else "查看结果与依据", "openJob:${job.id}") {
                    startActivity(backgroundJobIntent(this, job))
                }
            }
            if (job.status in BACKGROUND_RETRYABLE) {
                addButton(items, if (job.status == BackgroundJobStatus.REBUILD_REQUIRED) "重新整理这个范围" else "处理未完成任务", "resumeJob:${job.id}") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
        }
        if (jobOffset > 0) addButton(items, "上一页整理", "previousInboxJobs") { jobOffset = (jobOffset - PAGE_SIZE).coerceAtLeast(0); showHome() }
        if (jobs.size == PAGE_SIZE) addButton(items, "下一页整理", "nextInboxJobs") { jobOffset += PAGE_SIZE; showHome() }
    }

    private fun renderKnowledge(page: KnowledgeIndexPage) {
        val items = card()
        Ui.sectionTitle(items, "需要更新的知识")
        val invalid = page.items.filter { it.evidenceState != KnowledgeEvidenceState.CURRENT }
        if (invalid.isEmpty()) Ui.note(items, "本页知识没有待更新项。${if (page.nextOffset != null) "可继续查看下一页。" else ""}")
        invalid.forEach { item ->
            listRow(items, "${item.topics.joinToString("、").ifBlank { "未指定主题" }} · ${item.from} 至 ${item.through}\n依据已变化，查看后可重新分析", "staleKnowledge:${item.id}") {
                startActivity(Intent(this, KnowledgeActivity::class.java).putExtra(KnowledgeActivity.EXTRA_KNOWLEDGE_ID, item.id))
            }
        }
        if (knowledgeOffset > 0) addButton(items, "上一页知识", "previousInboxKnowledge") { knowledgeOffset = (knowledgeOffset - PAGE_SIZE).coerceAtLeast(0); showHome() }
        if (page.nextOffset != null) addButton(items, "下一页知识", "nextInboxKnowledge") { knowledgeOffset += PAGE_SIZE; showHome() }
    }

    companion object { private const val PAGE_SIZE = 20 }
}

internal val BACKGROUND_RETRYABLE = setOf(BackgroundJobStatus.FAILED, BackgroundJobStatus.NEEDS_RECOVERY,
    BackgroundJobStatus.STORAGE_BLOCKED, BackgroundJobStatus.BUDGET_EXHAUSTED, BackgroundJobStatus.REBUILD_REQUIRED)

internal fun backgroundJobTitle(job: BackgroundJob): String = when (job.kind) {
    BackgroundJobKind.RECORD -> "新记录整理"
    BackgroundJobKind.WEEKLY_REFLECTION -> "一周回看"
    BackgroundJobKind.MONTHLY_REFLECTION -> "一月回看"
    BackgroundJobKind.MONTHLY_PROFILE -> "跨阶段描述 · ${job.profileDimension?.let(::profileDimensionTitle).orEmpty()}"
}

internal fun backgroundJobStatus(status: BackgroundJobStatus): String = when (status) {
    BackgroundJobStatus.QUEUED -> "排队中"
    BackgroundJobStatus.RUNNING -> "处理中"
    BackgroundJobStatus.PROPOSALS_READY -> "等待核对"
    BackgroundJobStatus.NEEDS_ATTENTION -> "需要你查看"
    BackgroundJobStatus.FAILED -> "已停止，等待重试"
    BackgroundJobStatus.NEEDS_RECOVERY -> "中断待恢复"
    BackgroundJobStatus.SUPERSEDED -> "已撤销或被更新的安排取代"
    BackgroundJobStatus.SOURCE_UNAVAILABLE -> "原任务或来源已不可用"
    BackgroundJobStatus.BUDGET_EXHAUSTED -> "任务额度已耗尽"
    BackgroundJobStatus.STORAGE_BLOCKED -> "任务容量已满"
    BackgroundJobStatus.REBUILD_REQUIRED -> "资料变化，待重新整理"
}

internal fun backgroundJobIntent(context: android.content.Context, job: BackgroundJob): Intent = when (job.kind) {
    BackgroundJobKind.RECORD -> Intent(context, ReviewActivity::class.java).putExtra("runId", job.runId)
    BackgroundJobKind.MONTHLY_PROFILE -> Intent(context, LongitudinalProfileActivity::class.java).putExtra("runId", job.runId)
    else -> Intent(context, ReflectionActivity::class.java).putExtra(ReflectionActivity.EXTRA_RUN_ID, job.runId)
}
