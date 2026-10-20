package dev.tracelife.ui

import dev.tracelife.profile.ProfileDimension
import dev.tracelife.profile.ProfileMaintenanceStatus

internal fun profileDimensionTitle(value: ProfileDimension): String = when (value) {
    ProfileDimension.VALUES_AND_DIRECTION -> "价值与方向"
    ProfileDimension.ROLES_AND_CONTEXT -> "角色与背景"
    ProfileDimension.INTERESTS_AND_PREFERENCES -> "兴趣与偏好"
    ProfileDimension.BEHAVIOR_AND_DECISIONS -> "行为与决策"
    ProfileDimension.ABILITIES_AND_STRATEGIES -> "能力与策略"
    ProfileDimension.ENERGY_AND_RECOVERY -> "精力与恢复"
    ProfileDimension.RELATIONSHIPS_AND_COLLABORATION -> "关系与协作"
    ProfileDimension.NARRATIVE_AND_CHANGES -> "经历与变化"
}

internal fun maintenanceStatusTitle(value: ProfileMaintenanceStatus): String = when (value) {
    ProfileMaintenanceStatus.PLAN_REQUIRED -> "等待建立对照计划"
    ProfileMaintenanceStatus.RELATIONS_READY -> "等待比较历史描述"
    ProfileMaintenanceStatus.SCAN_READY -> "等待检查原文与反例"
    ProfileMaintenanceStatus.FINAL_READY -> "等待最终核对"
    ProfileMaintenanceStatus.READY_TO_APPLY -> "核对完成，等待你的选择"
    ProfileMaintenanceStatus.NEEDS_ATTENTION -> "历史关系仍不确定"
    ProfileMaintenanceStatus.NOT_SUPPORTED -> "核对暂不支持，不能保存"
    ProfileMaintenanceStatus.CONTEXT_REQUIRED -> "还有缺少的上下文"
    ProfileMaintenanceStatus.NO_TEXT -> "没有可读取的文字"
    ProfileMaintenanceStatus.NO_RELEVANT_EVIDENCE -> "没有找到相关原文"
    ProfileMaintenanceStatus.RELATED_EVIDENCE_LIMIT -> "相关原文超出核对上限"
    ProfileMaintenanceStatus.PASS_LIMIT_REACHED -> "本轮已保存，可以继续"
    ProfileMaintenanceStatus.CANCELLED -> "本轮已停止"
    ProfileMaintenanceStatus.RETRY_NEEDED -> "请求未成功，可明确重试"
    ProfileMaintenanceStatus.BUDGET_EXHAUSTED -> "本次整理额度已用完"
    ProfileMaintenanceStatus.NEEDS_RECOVERY -> "上次中断，等待恢复"
    ProfileMaintenanceStatus.DEPENDENCY_BLOCKED, ProfileMaintenanceStatus.STALE -> "相关资料已变化，请重新整理"
    ProfileMaintenanceStatus.EXPIRED -> "本次整理已到期"
    ProfileMaintenanceStatus.CLOCK_ROLLBACK -> "手机时间变化，请检查时间"
    ProfileMaintenanceStatus.TASK_UNAVAILABLE -> "整理已不可用"
    ProfileMaintenanceStatus.CONFLICT -> "内容已有变化，请刷新"
}
