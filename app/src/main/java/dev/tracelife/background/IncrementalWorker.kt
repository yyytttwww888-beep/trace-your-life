package dev.tracelife.background

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.tracelife.TraceLifeApplication

/** A bounded pass; domain failures are never translated into WorkManager's automatic paid retry. */
class IncrementalWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val app = applicationContext as? TraceLifeApplication ?: return Result.failure()
        return try {
            app.incrementalOrganizer.process(app.deepSeekProvider, app.deepSeekProvider,
                credentialsAvailable = app.deepSeekCredentials.isConfigured(), cancelled = { isStopped },
                scheduledUnmeteredOnly = inputData.getBoolean(BackgroundScheduler.UNMETERED_INPUT, true))
            // A restore/pause may have happened during a request. Cancel future platform work too.
            val policy = app.incrementalOrganizer.policy()
            if (!policy.enabled || policy.paused) app.backgroundScheduler.update(policy)
            Result.success()
        } catch (_: Exception) {
            // A persistent RUNNING job is held for explicit recovery after unexpected interruption.
            Result.failure()
        }
    }
}
