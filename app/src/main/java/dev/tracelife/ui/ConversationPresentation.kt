package dev.tracelife.ui

import dev.tracelife.analysis.ReflectionRun
import dev.tracelife.analysis.ReflectionStatus

/** Local display only: no inference of growth, completeness or factual certainty from task status. */
internal object ConversationPresentation {
    /** Preflight refusals may not create a ledger call, so the saved run alone cannot explain a stop. */
    fun completionNotice(outcome: ReflectionStatus, storedStatus: ReflectionStatus): String? {
        if (outcome == ReflectionStatus.BUDGET_EXHAUSTED)
            return "这次汇总所需材料超过本任务上限，已保留部分发现。请缩小日期范围或拆成具体问题重新分析。"
        if (outcome == storedStatus) return null
        return when (outcome) {
            ReflectionStatus.DEPENDENCY_BLOCKED -> "继续分析需要的背景无法使用，或相关判断已变化。已有发现和进度已保留，请补充或调整问题后重新分析。"
            ReflectionStatus.STALE -> "记录在分析期间发生了变化。已有发现已保留，请根据最新记录重新提问。"
            ReflectionStatus.EXPIRED -> "这次分析已过期。已有发现已保留，请重新确认范围后提问。"
            ReflectionStatus.NEEDS_RECOVERY -> "这次分析中断，已有进度已保留。请打开回答与依据，恢复中断进度后再决定是否继续。"
            ReflectionStatus.RETRY_NEEDED -> "本轮请求未能完成，已有进度已保留。请检查服务配置与网络，之后可明确重试。"
            ReflectionStatus.CONFLICT -> "分析期间相关资料已变化。已有进度已保留，请重新打开回答后再操作。"
            else -> null
        }
    }

    fun status(value: ReflectionStatus) = when (value) {
        ReflectionStatus.ANALYZING -> "还在查看记录"
        ReflectionStatus.INTEGRATING -> "待汇总各批发现"
        ReflectionStatus.READY -> "本次回答"
        ReflectionStatus.NO_TEXT -> "没有可分析的文字"
        ReflectionStatus.RETRY_NEEDED -> "请求未成功，进度已保留"
        ReflectionStatus.NEEDS_RECOVERY -> "上次分析中断，请打开回答恢复"
        ReflectionStatus.BUDGET_EXHAUSTED -> "本次分析额度已用完"
        ReflectionStatus.DEPENDENCY_BLOCKED -> "相关判断已变化，请重新提问"
        ReflectionStatus.STALE -> "记录已变化，旧回答需要更新"
        ReflectionStatus.EXPIRED -> "分析已过期，请重新提问"
        ReflectionStatus.CONFLICT -> "需要重新打开回答"
    }

    fun progress(run: ReflectionRun): String = run.coverage?.let {
        "已处理 ${it.completedTextCodePoints} / ${it.textCodePoints} 字" +
            (if (run.actionEvents > 0) " · 行动材料 ${run.processedActionEvents} / ${run.actionEvents} 份" else "") +
            " · ${run.totalRequests} 次请求"
    } ?: "累计 ${run.totalRequests} 次请求。当前不能提供有效覆盖数据。"

    fun coverage(run: ReflectionRun): String = run.coverage?.let {
        "范围内 ${it.records} 条记录，${it.remainingTextFragments} 段待处理。日期未知 ${it.unknownTimeRecords} 条，跨越范围 ${it.overlappingRecords} 条，未读附件 ${it.unreadAttachments} 个，不可读取 ${it.unreadableRecords} 条。\n" +
            (if (it.missingExactIds.isEmpty()) "" else "${it.missingExactIds.size} 条指定记录当前不可用。\n") +
            (if (run.actionEvents > 0) "另有 ${run.actionEvents} 份行动材料，已处理 ${run.processedActionEvents} 份。计划、执行自述和后续调整分别读取，未回看不代表未执行。\n" else "") +
            "日期未知和跨范围的记录不能直接认定发生在这段时间。没有记录不等于没有发生；处理完成也不保证理解正确。"
    } ?: "当前资料或判断已变化，不能使用旧的覆盖统计。请查看回答状态。"
}
