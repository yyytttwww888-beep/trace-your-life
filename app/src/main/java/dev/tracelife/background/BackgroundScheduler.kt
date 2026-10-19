package dev.tracelife.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** WorkManager carries no record IDs, model text or credentials; the bounded queue remains authoritative. */
class BackgroundScheduler(context: Context) {
    private val applicationContext = context.applicationContext

    fun update(policy: BackgroundPolicy) {
        val manager = WorkManager.getInstance(applicationContext)
        if (!policy.enabled || policy.paused) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            manager.cancelUniqueWork(WAKE_NAME)
            return
        }
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(policy))
        wake(policy)
    }

    /** Call after a successful record save for lower latency; durable events and periodic work repair missed wakes. */
    fun wake(policy: BackgroundPolicy? = null) {
        val active = policy ?: (applicationContext as? dev.tracelife.TraceLifeApplication)?.incrementalOrganizer?.policy() ?: return
        if (!active.enabled || active.paused) return
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(WAKE_NAME, ExistingWorkPolicy.KEEP, oneTimeRequest(active))
    }

    internal fun periodicRequest(policy: BackgroundPolicy): PeriodicWorkRequest =
        PeriodicWorkRequest.Builder(IncrementalWorker::class.java, 15, TimeUnit.MINUTES)
            .setConstraints(constraints(policy)).setInputData(dispatchPolicy(policy)).addTag(TAG).build()

    internal fun oneTimeRequest(policy: BackgroundPolicy): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(IncrementalWorker::class.java).setConstraints(constraints(policy))
            .setInputData(dispatchPolicy(policy)).addTag(TAG).build()

    private fun dispatchPolicy(policy: BackgroundPolicy) = Data.Builder().putBoolean(UNMETERED_INPUT, policy.unmeteredOnly).build()

    private fun constraints(policy: BackgroundPolicy) = Constraints.Builder()
        .setRequiredNetworkType(if (policy.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true).build()

    companion object {
        const val PERIODIC_NAME = "trace-life-incremental-periodic-v1"
        const val WAKE_NAME = "trace-life-incremental-wake-v1"
        const val TAG = "trace-life-incremental-v1"
        const val UNMETERED_INPUT = "unmeteredOnly"
    }
}
