package dev.tracelife.ui

import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
                .setMessage("无法访问本机加密存储，请重新配置 KEY。")
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
                Ui.note(card, "配置损坏或设备密钥不可用，须重新配置（不会退回明文保存）")
            }
            val key = fieldIn(card, "DeepSeek API KEY（8 至 512 个可打印 ASCII 字符，不含空格）", maxLength = 512)
            key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            Ui.primary(card, "保存 KEY", "saveKey") onSave@{
                if (busy) return@onSave
                val value = key.text.toString()
                if (value.length !in 8..512 || value.any { it.code !in 0x21..0x7e }) {
                    showError(IllegalArgumentException("KEY 须为 8 至 512 个可打印 ASCII 字符，不含空格"))
                    return@onSave
                }
                val chars = value.toCharArray()
                runIo({
                    try { app.deepSeekCredentials.configure(chars) } finally { chars.fill('\u0000') }
                }) {
                    toast("DeepSeek KEY 已保存")
                    showHome()
                }
            }
            if (status != CredentialStatus.MISSING) {
                Ui.dangerButton(card, "清除 KEY", "clearKey") {
                    if (!busy) confirm("清除 DeepSeek KEY？", "清除后须重新设置才能使用 AI 功能。", "清除") {
                        runIo({ app.deepSeekCredentials.clear() }) {
                            toast("DeepSeek KEY 已清除")
                            showHome()
                        }
                    }
                }
            }
            Ui.note(card, "KEY 只保存在本机：经 Android Keystore 密钥加密后存放于应用私有目录，不进入任何备份（含系统备份），更换手机须重新配置。" +
                "应用默认使用 deepseek-flash 模型，服务器地址固定为官方 HTTPS 地址；请求发出后无法撤回。")
        }
    }

    private fun renderBackground(settings: BackgroundSettings) {
        val policy = settings.policy
        card().also { card ->
            Ui.sectionTitle(card, "后台增量整理")
            if (settings.requiresEnableAfterRestore) {
                chip(card, "恢复后须重新启用", Ui.Tone.WARN)
                Ui.note(card, "本机资料已从备份恢复，后台增量整理已被强制停用，须明确重新启用。此时「继续」不可用，请在下方勾选启用并应用策略；重新启用只处理启用后的新变更，不自动补分析整库。")
            }
            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                card.addView(this)
            }
            Ui.body(statusRow, "状态：")
            chip(statusRow, if (policy.enabled) "已启用" else "已停用", if (policy.enabled) Ui.Tone.OK else Ui.Tone.NEUTRAL)
            if (policy.paused) chip(statusRow, "已暂停", Ui.Tone.WARN)
            Ui.note(card, "时区：${policy.zoneId} · 仅非计费网络：${if (policy.unmeteredOnly) "是" else "否"}\n" +
                "每轮最多 ${policy.maxRequestsPerPass} 次请求 · 每日上限 ${policy.dailyRequestLimit} 次（UTC）· 单任务上限 ${policy.maxRequestsPerJob} 次\n" +
                "当日已预留请求：${settings.reservedRequestsToday} 次（UTC 当日；失败与未知完成的请求也消耗预留，这是请求数，不是金额）")
            val enabled = checkIn(card, "启用后台增量整理", policy.enabled)
            val perPass = fieldIn(card, "每轮最多请求数（1–10）", policy.maxRequestsPerPass.toString(), maxLength = 4)
            perPass.inputType = InputType.TYPE_CLASS_NUMBER
            val daily = fieldIn(card, "每日请求上限（UTC，1–1000）", policy.dailyRequestLimit.toString(), maxLength = 5)
            daily.inputType = InputType.TYPE_CLASS_NUMBER
            val perJob = fieldIn(card, "单任务请求上限（1–100）", policy.maxRequestsPerJob.toString(), maxLength = 4)
            perJob.inputType = InputType.TYPE_CLASS_NUMBER
            val unmetered = checkIn(card, "仅非计费网络（如 Wi-Fi）", policy.unmeteredOnly)
            Ui.primary(card, "应用策略", "applyPolicy") {
                if (!busy) applyPolicy(settings, enabled.isChecked, perPass.text.toString().trim(),
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
            Ui.note(card, "后台增量整理默认关闭。首次启用只处理启用之后新增或修改的记录，不自动读取旧记录；停用会撤销队列意图，停用期间的资料不补发，也不重置当日额度。" +
                "系统调度周期最小 15 分钟，默认要求非计费网络且电量非低，不保证保存后立即运行或整点运行；已发出的请求无法撤回。")
        }
    }

    private fun applyPolicy(current: BackgroundSettings, enabled: Boolean, perPassText: String,
                            dailyText: String, perJobText: String, unmeteredOnly: Boolean) {
        val perPass = perPassText.toIntOrNull()?.takeIf { it in 1..10 }
            ?: run { showError(IllegalArgumentException("每轮最多请求数须为 1 至 10")); return }
        val daily = dailyText.toIntOrNull()?.takeIf { it in 1..1000 }
            ?: run { showError(IllegalArgumentException("每日请求上限须为 1 至 1000")); return }
        val perJob = perJobText.toIntOrNull()?.takeIf { it in 1..100 }
            ?: run { showError(IllegalArgumentException("单任务请求上限须为 1 至 100")); return }
        val policy = BackgroundPolicy(
            enabled = enabled,
            paused = enabled && current.policy.paused,
            zoneId = current.policy.zoneId,
            maxRequestsPerPass = perPass,
            dailyRequestLimit = daily,
            maxRequestsPerJob = perJob,
            unmeteredOnly = unmeteredOnly,
        )
        runIo({
            try {
                app.incrementalOrganizer.configure(policy, expectedVersion = current.version)
            } catch (_: BackgroundConflictException) {
                null
            }
        }) { saved ->
            toast(if (saved == null) "设置已变化，请核对最新状态后重试" else "后台策略已保存")
            showHome()
        }
    }

    private fun loadJobs(settings: BackgroundSettings) {
        runIo({ app.incrementalOrganizer.jobs(jobOffset, JOB_PAGE_SIZE) }) { jobs -> renderJobs(settings, jobs) }
    }

    private fun renderJobs(settings: BackgroundSettings, jobs: List<BackgroundJob>) {
        card().also { card ->
            Ui.sectionTitle(card, "后台任务")
            Ui.note(card, "任务条目只含身份与计数，不含记录正文；「已生成待核对提案」只表示已生成待核对提案，不表示已发布、完整生活覆盖或语义必然正确，提案请在「核对与整理」中查看。")
            if (jobs.isEmpty()) Ui.note(card, "当前页没有后台任务。")
            jobs.forEachIndexed { index, job ->
                if (index > 0) Ui.divider(card)
                Ui.body(card, "记录 ${job.recordId} · 修订 ${job.recordRevision}")
                chip(card, statusLabel(job.status), statusTone(job.status))
                Ui.note(card, "已预留请求 ${job.reservedRequests} 次 · 分析任务：${job.runId ?: "无"} · 更新于 ${job.updatedAt}")
                if (job.status in RETRYABLE) {
                    if (job.status == BackgroundJobStatus.BUDGET_EXHAUSTED &&
                        job.reservedRequests >= settings.policy.maxRequestsPerJob) {
                        Ui.note(card, "该任务累计请求额度已用尽；请先在上方提高「单任务请求上限」并应用策略后再重试。")
                    } else {
                        addButton(card, "重试：记录 ${job.recordId.take(8)}", "retry:${job.id}") {
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
                    Ui.dangerButton(card, "移除条目：记录 ${job.recordId.take(8)}", "forget:${job.id}") {
                        if (!busy) confirm("移除这个后台任务条目？", "只移除已结束的操作条目，不删除已保存的分析历史与提案。", "移除") {
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

    private fun fieldIn(parent: LinearLayout, hint: String, value: String = "", multiline: Boolean = false, maxLength: Int = 2_000): EditText {
        val input = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0
            minLines = if (multiline) 3 else 1
            gravity = Gravity.TOP or Gravity.START
            setText(value)
            setFreezesText(false)
            isSaveEnabled = false
        }
        Ui.styleField(this, input)
        parent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(8))
        })
        input.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        return input
    }

    private fun checkIn(parent: LinearLayout, label: String, checked: Boolean) = CheckBox(this).apply {
        text = label
        isChecked = checked
        Ui.styleCheck(this@SettingsActivity, this)
        parent.addView(this)
    }

    private fun statusLabel(status: BackgroundJobStatus) = when (status) {
        BackgroundJobStatus.QUEUED -> "排队中"
        BackgroundJobStatus.RUNNING -> "处理中"
        BackgroundJobStatus.PROPOSALS_READY -> "已生成待核对提案（未发布）"
        BackgroundJobStatus.NEEDS_ATTENTION -> "需要人工关注"
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
