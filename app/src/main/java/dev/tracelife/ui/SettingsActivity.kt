package dev.tracelife.ui

import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.EditText
import dev.tracelife.background.BackgroundConflictException
import dev.tracelife.background.BackgroundJob
import dev.tracelife.background.BackgroundJobStatus
import dev.tracelife.background.BackgroundPolicy
import dev.tracelife.background.BackgroundSettings
import dev.tracelife.providers.CredentialAccessException
import dev.tracelife.providers.CredentialStatus

class SettingsActivity : ScreenActivity() {
    private var jobOffset = 0

    override fun showHome() {
        beginScreen("home", "设置")
        runIo({ app.deepSeekCredentials.status() to app.incrementalOrganizer.settings() }) { (status, settings) ->
            renderDeepSeek(status)
            renderBackground(settings)
            loadJobs(settings)
        }
    }

    override fun showError(failure: Throwable) {
        if (failure is CredentialAccessException) {
            AlertDialog.Builder(this).setTitle("操作未完成")
                .setMessage("无法读取已保存的密钥，请重新填写。")
                .setPositiveButton("知道了", null).show()
        } else super.showError(failure)
    }

    private fun renderDeepSeek(status: CredentialStatus) {
        card().also { card ->
            Ui.sectionTitle(card, "DeepSeek")
            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                card.addView(this)
            }
            Ui.body(statusRow, "状态：")
            when (status) {
                CredentialStatus.MISSING -> chip(statusRow, "未配置", Ui.Tone.NEUTRAL)
                CredentialStatus.CONFIGURED -> chip(statusRow, "已配置", Ui.Tone.OK)
                CredentialStatus.UNREADABLE -> chip(statusRow, "配置损坏", Ui.Tone.WARN)
            }
            if (status == CredentialStatus.UNREADABLE) {
                Ui.note(card, "这台手机无法读取原来的密钥，请重新填写。")
            }
            val key = labeledFieldIn(card, "访问密钥", "粘贴你的 DeepSeek 密钥", maxLength = 512)
            key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            primaryIn(card, "保存密钥", "saveKey") {
                val value = key.text.toString()
                if (value.length !in 8..512 || value.any { it.code !in 0x21..0x7e }) {
                    showError(IllegalArgumentException("密钥格式不正确，请完整粘贴，不要包含空格或换行"))
                    return@primaryIn
                }
                val chars = value.toCharArray()
                runIo({
                    try { app.deepSeekCredentials.configure(chars) } finally { chars.fill('\u0000') }
                }) {
                    toast("密钥已保存")
                    showHome()
                }
            }
            if (status != CredentialStatus.MISSING) {
                dangerIn(card, "移除密钥", "clearKey") {
                    confirm("移除密钥？", "之后需要重新填写才能在线整理。离线记录不受影响。", "移除") {
                        runIo({ app.deepSeekCredentials.clear() }) {
                            toast("密钥已移除")
                            showHome()
                        }
                    }
                }
            }
            Ui.note(card, "密钥加密保存在这台手机，不会随备份导出。换机后需重新填写。")
            Ui.disclosure(card, "在线整理说明", "connectionDetails") { details ->
                Ui.note(details, "在线整理会将所选记录片段发送给 DeepSeek，可能产生费用；已发出的请求无法撤回。")
                Ui.note(details, "使用 DeepSeek 官方服务和 deepseek-flash。密钥由 Android Keystore 加密保存。")
            }
        }
    }

    private fun renderBackground(settings: BackgroundSettings) {
        val policy = settings.policy
        card().also { card ->
            Ui.sectionTitle(card, "自动整理")
            if (settings.requiresEnableAfterRestore) {
                chip(card, "恢复后须重新启用", Ui.Tone.WARN)
                Ui.note(card, "恢复备份后自动整理已关闭。重新开启，只会整理之后新增或修改的记录。")
            }
            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                card.addView(this)
            }
            Ui.body(statusRow, "状态：")
            chip(statusRow, if (policy.enabled) "已启用" else "已停用", if (policy.enabled) Ui.Tone.OK else Ui.Tone.NEUTRAL)
            if (policy.paused) chip(statusRow, "已暂停", Ui.Tone.WARN)
            Ui.note(card, "今日已占用 ${settings.reservedRequestsToday} / ${policy.dailyRequestLimit} 次请求额度（UTC）。")
            val enabled = checkIn(card, "自动整理新记录", policy.enabled)
            Ui.note(card, "开启后会将之后新增或修改的记录片段发送给 DeepSeek，可能产生费用。整理结果仍需你核对。")
            val unmetered = checkIn(card, "仅在非计费网络下整理（如 Wi-Fi）", policy.unmeteredOnly)
            lateinit var perPass: EditText
            lateinit var daily: EditText
            lateinit var perJob: EditText
            Ui.disclosure(card, "用量限制", "usageLimits") { limits ->
                perPass = labeledFieldIn(limits, "每轮最多请求数", "1–10", policy.maxRequestsPerPass.toString(), maxLength = 4)
                perPass.inputType = InputType.TYPE_CLASS_NUMBER
                daily = labeledFieldIn(limits, "每日请求上限（UTC）", "1–1000", policy.dailyRequestLimit.toString(), maxLength = 5)
                daily.inputType = InputType.TYPE_CLASS_NUMBER
                perJob = labeledFieldIn(limits, "每条记录的请求上限", "1–100", policy.maxRequestsPerJob.toString(), maxLength = 4)
                perJob.inputType = InputType.TYPE_CLASS_NUMBER
                Ui.note(limits, "这里限制请求次数，不是费用金额。失败和未收到结果的请求也占用额度。")
            }
            primaryIn(card, "保存设置", "applyPolicy") {
                applyPolicy(settings, enabled.isChecked, perPass.text.toString().trim(),
                    daily.text.toString().trim(), perJob.text.toString().trim(), unmetered.isChecked)
            }
            if (policy.enabled && !policy.paused) {
                addButton(card, "暂停后台整理", "pause") {
                    runIo({ app.incrementalOrganizer.pause() }) { toast("已暂停"); showHome() }
                }
            }
            if (policy.enabled && policy.paused) {
                addButton(card, "继续后台整理", "resume") {
                    runIo({ app.incrementalOrganizer.resume() }) { toast("已继续"); showHome() }
                }
            }
            Ui.disclosure(card, "整理何时进行", "backgroundDetails") { details ->
                Ui.note(details, "由手机在网络和电量允许时安排，保存后不一定马上整理。")
                Ui.note(details, "暂停会保留进度，继续时会补上暂停期间的变更。关闭后再开启不会补发关闭期间的记录，也不会重置今日额度。已发出的请求无法撤回。")
            }
        }
    }

    private fun applyPolicy(current: BackgroundSettings, enabled: Boolean, perPassText: String,
                            dailyText: String, perJobText: String, unmeteredOnly: Boolean) {
        val perPass = perPassText.toIntOrNull()?.takeIf { it in 1..10 }
            ?: run { showError(IllegalArgumentException("每轮最多请求数须为 1 至 10")); return }
        val daily = dailyText.toIntOrNull()?.takeIf { it in 1..1000 }
            ?: run { showError(IllegalArgumentException("每日请求上限须为 1 至 1000")); return }
        val perJob = perJobText.toIntOrNull()?.takeIf { it in 1..100 }
            ?: run { showError(IllegalArgumentException("每条记录的请求上限须为 1 至 100")); return }
        val policy = BackgroundPolicy(
            enabled = enabled,
            paused = enabled && current.policy.paused,
            zoneId = current.policy.zoneId,
            maxRequestsPerPass = perPass,
            dailyRequestLimit = daily,
            maxRequestsPerJob = perJob,
            unmeteredOnly = unmeteredOnly,
        )
        val save = {
            runIo({
                try {
                    app.incrementalOrganizer.configure(policy, expectedVersion = current.version)
                } catch (_: BackgroundConflictException) {
                    null
                }
            }) { saved ->
                toast(if (saved == null) "设置已变化，请核对最新状态后重试" else "设置已保存")
                showHome()
            }
        }
        if (enabled && (!current.policy.enabled || current.requiresEnableAfterRestore)) {
            confirm("开启自动整理？", "之后新增或修改的记录片段会发送给 DeepSeek，可能产生费用。每天最多 $daily 次请求。整理不会自动替你确认或采用结果。", "开启", save)
        } else save()
    }

    private fun loadJobs(settings: BackgroundSettings) {
        runIo({ app.incrementalOrganizer.jobs(jobOffset, JOB_PAGE_SIZE) }) { jobs -> renderJobs(settings, jobs) }
    }

    private fun renderJobs(settings: BackgroundSettings, jobs: List<BackgroundJob>) {
        card().also { card ->
            Ui.sectionTitle(card, "整理进度")
            Ui.note(card, "整理后的内容可在「整理」中核对，尚未收进知识库。")
            if (jobs.isEmpty()) emptyState(card, "还没有自动整理记录。")
            jobs.forEachIndexed { index, job ->
                if (index > 0) Ui.divider(card)
                Ui.body(card, "记录整理 · ${job.updatedAt.toString().take(10)}")
                chip(card, statusLabel(job.status), statusTone(job.status))
                Ui.note(card, "已占用 ${job.reservedRequests} 次请求额度")
                Ui.disclosure(card, "查看详情", "jobDetails:${job.id}") { details ->
                    Ui.note(details, "记录编号：${job.recordId}\n记录版本：${job.recordRevision}\n整理编号：${job.runId ?: "尚未开始"}\n更新于 ${job.updatedAt}")
                }
                if (job.status in RETRYABLE) {
                    if (job.status == BackgroundJobStatus.BUDGET_EXHAUSTED &&
                        job.reservedRequests >= settings.policy.maxRequestsPerJob) {
                        Ui.note(card, "这条记录的请求额度已用完。可在「用量限制」中提高上限，保存后再重试。")
                    } else {
                        addButton(card, "重新整理", "retry:${job.id}") {
                            runIo({
                                app.incrementalOrganizer.retry(job.id, job.version).also { app.backgroundScheduler.wake() }
                            }) {
                                toast("已重新排队，等待下一轮后台处理")
                                showHome()
                            }
                        }
                    }
                }
                if (job.status != BackgroundJobStatus.QUEUED && job.status != BackgroundJobStatus.RUNNING) {
                    dangerIn(card, "移除进度记录", "forget:${job.id}") {
                        confirm("移除这条进度记录？", "只移除这里的进度记录。原始记录、已保存的整理结果和历史都会保留。", "移除") {
                            runIo({ app.incrementalOrganizer.forget(job.id, job.version) }) {
                                toast("已移除")
                                showHome()
                            }
                        }
                    }
                }
            }
            if (jobOffset > 0) {
                addButton(card, "上一页", "jobsPrevious") {
                    jobOffset = (jobOffset - JOB_PAGE_SIZE).coerceAtLeast(0)
                    showHome()
                }
            }
            if (jobs.size == JOB_PAGE_SIZE) {
                addButton(card, "下一页", "jobsNext") {
                    jobOffset += JOB_PAGE_SIZE
                    showHome()
                }
            }
        }
    }

    private fun statusLabel(status: BackgroundJobStatus) = when (status) {
        BackgroundJobStatus.QUEUED -> "排队中"
        BackgroundJobStatus.RUNNING -> "处理中"
        BackgroundJobStatus.PROPOSALS_READY -> "等待核对"
        BackgroundJobStatus.NEEDS_ATTENTION -> "需要你查看"
        BackgroundJobStatus.FAILED -> "失败"
        BackgroundJobStatus.NEEDS_RECOVERY -> "待恢复"
        BackgroundJobStatus.SUPERSEDED -> "已被更新的修订取代"
        BackgroundJobStatus.SOURCE_UNAVAILABLE -> "来源记录不可用"
        BackgroundJobStatus.BUDGET_EXHAUSTED -> "额度已耗尽"
        BackgroundJobStatus.STORAGE_BLOCKED -> "任务容量已满"
    }

    private fun statusTone(status: BackgroundJobStatus) = when (status) {
        BackgroundJobStatus.QUEUED -> Ui.Tone.NEUTRAL
        BackgroundJobStatus.RUNNING -> Ui.Tone.ACCENT
        BackgroundJobStatus.PROPOSALS_READY -> Ui.Tone.OK
        BackgroundJobStatus.NEEDS_ATTENTION -> Ui.Tone.WARN
        BackgroundJobStatus.FAILED -> Ui.Tone.ERROR
        BackgroundJobStatus.NEEDS_RECOVERY -> Ui.Tone.WARN
        BackgroundJobStatus.SUPERSEDED -> Ui.Tone.NEUTRAL
        BackgroundJobStatus.SOURCE_UNAVAILABLE -> Ui.Tone.WARN
        BackgroundJobStatus.BUDGET_EXHAUSTED -> Ui.Tone.WARN
        BackgroundJobStatus.STORAGE_BLOCKED -> Ui.Tone.WARN
    }

    companion object {
        private const val JOB_PAGE_SIZE = 25
        private val RETRYABLE = setOf(
            BackgroundJobStatus.FAILED, BackgroundJobStatus.NEEDS_RECOVERY,
            BackgroundJobStatus.STORAGE_BLOCKED, BackgroundJobStatus.BUDGET_EXHAUSTED,
        )
    }
}
