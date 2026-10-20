package dev.tracelife.ui

import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.EditText
import dev.tracelife.background.BackgroundConflictException
import dev.tracelife.background.BackgroundJob
import dev.tracelife.background.BackgroundJobStatus
import dev.tracelife.background.BackgroundPolicy
import dev.tracelife.background.BackgroundSettings
import dev.tracelife.background.BackgroundJobKind
import dev.tracelife.profile.ProfileDimension
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
                Ui.note(card, "恢复备份后已撤销自动整理授权。重新开启会从新的记录变更和之后结束的周期开始。")
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
            val weekly = checkIn(card, "每周形成一次回看草稿", policy.weeklyReflection).apply { tag = "weeklyReflection" }
            val monthly = checkIn(card, "每月形成一次回看草稿", policy.monthlyReflection).apply { tag = "monthlyReflection" }
            val profile = checkIn(card, "每月整理一项跨阶段描述", policy.monthlyProfileDimension != null).apply { tag = "monthlyProfile" }
            Ui.fieldLabel(card, "跨阶段描述的侧面")
            val dimension = spinnerIn(card, ProfileDimension.entries.map(::profileDimensionTitle),
                ProfileDimension.entries.indexOf(policy.monthlyProfileDimension ?: ProfileDimension.ABILITIES_AND_STRATEGIES), "automaticProfileDimension")
            Ui.note(card, "这些选项随自动整理总开关生效。首次开启从之后结束的周或月开始，跨阶段描述每次读取最近三个完整月；周一和月初后由手机安排，不保证整点执行。")
            Ui.note(card, "新记录、周/月回看和跨阶段描述共用下面的请求额度。只生成待查看的草稿，不会替你确认、保存经验或采用画像。")
            val unmetered = checkIn(card, "仅在非计费网络下整理（如 Wi-Fi）", policy.unmeteredOnly)
            lateinit var perPass: EditText
            lateinit var daily: EditText
            lateinit var perJob: EditText
            Ui.disclosure(card, "用量限制", "usageLimits") { limits ->
                perPass = labeledFieldIn(limits, "每轮最多请求数", "1–10", policy.maxRequestsPerPass.toString(), maxLength = 4)
                perPass.inputType = InputType.TYPE_CLASS_NUMBER
                daily = labeledFieldIn(limits, "每日请求上限（UTC）", "1–1000", policy.dailyRequestLimit.toString(), maxLength = 5)
                daily.inputType = InputType.TYPE_CLASS_NUMBER
                perJob = labeledFieldIn(limits, "每项整理的请求上限", "1–100", policy.maxRequestsPerJob.toString(), maxLength = 4)
                perJob.inputType = InputType.TYPE_CLASS_NUMBER
                Ui.note(limits, "这里限制请求次数，不是费用金额。失败和未收到结果的请求也占用额度。")
            }
            primaryIn(card, "保存设置", "applyPolicy") {
                applyPolicy(settings, enabled.isChecked, perPass.text.toString().trim(),
                    daily.text.toString().trim(), perJob.text.toString().trim(), unmetered.isChecked,
                    weekly.isChecked, monthly.isChecked, if (profile.isChecked) ProfileDimension.entries[dimension.selectedItemPosition] else null)
            }
            addButton(card, "查看待处理内容", "maintenanceInbox") { startActivity(Intent(this, MaintenanceInboxActivity::class.java)) }
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
                            dailyText: String, perJobText: String, unmeteredOnly: Boolean,
                            weekly: Boolean, monthly: Boolean, profileDimension: ProfileDimension?) {
        val perPass = perPassText.toIntOrNull()?.takeIf { it in 1..10 }
            ?: run { showError(IllegalArgumentException("每轮最多请求数须为 1 至 10")); return }
        val daily = dailyText.toIntOrNull()?.takeIf { it in 1..1000 }
            ?: run { showError(IllegalArgumentException("每日请求上限须为 1 至 1000")); return }
        val perJob = perJobText.toIntOrNull()?.takeIf { it in 1..100 }
            ?: run { showError(IllegalArgumentException("每项整理的请求上限须为 1 至 100")); return }
        val policy = BackgroundPolicy(
            enabled = enabled,
            paused = enabled && current.policy.paused,
            zoneId = current.policy.zoneId,
            maxRequestsPerPass = perPass,
            dailyRequestLimit = daily,
            maxRequestsPerJob = perJob,
            unmeteredOnly = unmeteredOnly,
            weeklyReflection = weekly,
            monthlyReflection = monthly,
            monthlyProfileDimension = profileDimension,
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
        val expanded = weekly && !current.policy.weeklyReflection || monthly && !current.policy.monthlyReflection ||
            profileDimension != null && profileDimension != current.policy.monthlyProfileDimension
        if (enabled && (!current.policy.enabled || current.requiresEnableAfterRestore || expanded)) {
            val periods = buildList {
                if (weekly) add("每个结束的周形成回看草稿")
                if (monthly) add("每个结束的月形成回看草稿")
                if (profileDimension != null) add("每月比较最近三个完整月的${profileDimensionTitle(profileDimension)}记录")
            }.joinToString("；")
            confirm("开启自动整理？", "之后新增或修改的记录片段会发送给 DeepSeek，可能产生费用。${if (periods.isBlank()) "" else "$periods。"}合计每天最多 $daily 次请求。首次开启不补跑已经结束的周期；结果仍需你核对和明确保存。", "开启", save)
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
                Ui.body(card, "${backgroundJobTitle(job)} · ${job.updatedAt.toString().take(10)}")
                chip(card, statusLabel(job.status), statusTone(job.status))
                Ui.note(card, "已占用 ${job.reservedRequests} 次请求额度")
                Ui.disclosure(card, "查看详情", "jobDetails:${job.id}") { details ->
                    Ui.note(details, if (job.kind == BackgroundJobKind.RECORD) "记录编号：${job.recordId}\n记录版本：${job.recordRevision}" else
                        "${job.from} 至 ${job.through} · ${job.zoneId}")
                    Ui.note(details, "整理编号：${job.runId ?: "尚未开始"}\n更新于 ${job.updatedAt}")
                }
                if (job.runId != null && job.status != BackgroundJobStatus.SOURCE_UNAVAILABLE)
                    addButton(card, "查看整理结果", "openJob:${job.id}") { startActivity(backgroundJobIntent(this, job)) }
                if (job.status in RETRYABLE) {
                    if (job.status == BackgroundJobStatus.BUDGET_EXHAUSTED &&
                        job.reservedRequests >= settings.policy.maxRequestsPerJob) {
                        Ui.note(card, "这项整理的请求额度已用完。可在「用量限制」中提高上限，保存后再重试。")
                    } else {
                        addButton(card, "重新整理", "retry:${job.id}") {
                            confirm("重新整理？", "将继续或为已变化的范围建立新任务，并在当前共享额度内重新调用 DeepSeek。已有请求额度不会退回，旧结果仍保留。", "重新整理") {
                                runIo({
                                    app.incrementalOrganizer.retry(job.id, job.version).also { app.backgroundScheduler.wake() }
                                }) {
                                    toast("已重新排队，等待下一轮后台处理")
                                    showHome()
                                }
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

    private fun statusLabel(status: BackgroundJobStatus) = backgroundJobStatus(status)

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
        BackgroundJobStatus.REBUILD_REQUIRED -> Ui.Tone.WARN
    }

    companion object {
        private const val JOB_PAGE_SIZE = 25
        private val RETRYABLE = BACKGROUND_RETRYABLE
    }
}
