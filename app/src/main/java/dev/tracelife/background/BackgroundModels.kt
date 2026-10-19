package dev.tracelife.background

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Device-local consent and limits. Enabling starts with future record events, never the whole store. */
data class BackgroundPolicy(
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val zoneId: String = "Asia/Shanghai",
    val maxRequestsPerPass: Int = 3,
    val dailyRequestLimit: Int = 10,
    val maxRequestsPerJob: Int = 20,
    val unmeteredOnly: Boolean = true,
) {
    init {
        ZoneId.of(zoneId)
        require(maxRequestsPerPass in 1..10)
        require(dailyRequestLimit in 1..1_000)
        require(maxRequestsPerJob in 1..100)
        require(!paused || enabled) { "停用策略不能同时暂停" }
    }
}

data class BackgroundSettings(
    val version: Int,
    val policy: BackgroundPolicy,
    val storeId: String,
    val restoreEpoch: Long,
    val eventCursor: Long,
    /** UTC prevents changing the presentation timezone from granting a second daily allowance. */
    val usageDay: LocalDate,
    val reservedRequestsToday: Int,
    val lastObservedAt: Instant,
    val requiresEnableAfterRestore: Boolean = false,
)

enum class BackgroundJobStatus {
    QUEUED, RUNNING, PROPOSALS_READY, NEEDS_ATTENTION, FAILED, NEEDS_RECOVERY,
    SUPERSEDED, SOURCE_UNAVAILABLE, BUDGET_EXHAUSTED, STORAGE_BLOCKED,
}

/** No record body, model text or credentials are copied into the operational queue. */
data class BackgroundJob(
    val id: String,
    val version: Int,
    val recordId: String,
    val recordRevision: Int,
    val eventId: Long,
    val status: BackgroundJobStatus,
    val runId: String?,
    val runSequence: Int,
    val reservedRequests: Int,
    val retryAuthorized: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class BackgroundPassStatus {
    IDLE, PROGRESSED, PASS_LIMIT_REACHED, DISABLED, PAUSED, CREDENTIALS_REQUIRED,
    DAILY_BUDGET_EXHAUSTED, JOB_BUDGET_EXHAUSTED, CLOCK_ROLLBACK, RESTORE_REQUIRES_ENABLE, BUSY,
    NEEDS_ATTENTION, STORAGE_BLOCKED, CANCELLED, CONSTRAINTS_CHANGED,
}

data class BackgroundPassReport(
    val status: BackgroundPassStatus,
    /** Actual adapter dispatches, including failures; interrupted dispatches remain reserved. */
    val modelRequestsThisPass: Int,
    val finishedJobsThisPass: Int,
    val queuedJobs: Int,
    val reservedRequestsToday: Int,
)

class BackgroundConflictException(message: String) : IllegalStateException(message)
