package dev.tracelife.background

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackgroundSchedulerTest {
    private lateinit var context: Context
    private lateinit var scheduler: BackgroundScheduler
    private lateinit var manager: WorkManager

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
        manager = WorkManager.getInstance(context)
        scheduler = BackgroundScheduler(context)
    }

    @After fun tearDown() { manager.cancelAllWork().result.get() }

    @Test fun `repeated scheduling retains one periodic and one wake job with network constraints`() {
        val policy = BackgroundPolicy(enabled = true)
        scheduler.update(policy)
        val original = manager.getWorkInfosForUniqueWork(BackgroundScheduler.PERIODIC_NAME).get().single().id
        scheduler.update(policy)
        scheduler.wake(policy)
        val periodic = manager.getWorkInfosForUniqueWork(BackgroundScheduler.PERIODIC_NAME).get().single()
        val wake = manager.getWorkInfosForUniqueWork(BackgroundScheduler.WAKE_NAME).get().single()
        assertEquals(original, periodic.id)
        assertEquals(WorkInfo.State.ENQUEUED, periodic.state)
        assertEquals(NetworkType.UNMETERED, periodic.constraints.requiredNetworkType)
        assertTrue(periodic.constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.UNMETERED, wake.constraints.requiredNetworkType)
        assertTrue(wake.tags.contains(BackgroundScheduler.TAG))
        assertTrue(periodic.outputData.keyValueMap.isEmpty())
    }

    @Test fun `pause cancels scheduled work and resume applies the new network choice`() {
        val enabled = BackgroundPolicy(enabled = true)
        scheduler.update(enabled)
        scheduler.update(enabled.copy(paused = true))
        assertTrue(manager.getWorkInfosForUniqueWork(BackgroundScheduler.PERIODIC_NAME).get().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(manager.getWorkInfosForUniqueWork(BackgroundScheduler.WAKE_NAME).get().all { it.state == WorkInfo.State.CANCELLED })
        scheduler.update(enabled.copy(unmeteredOnly = false))
        val current = manager.getWorkInfosForUniqueWork(BackgroundScheduler.PERIODIC_NAME).get().single { !it.state.isFinished }
        assertEquals(NetworkType.CONNECTED, current.constraints.requiredNetworkType)
    }

    @Test fun `disabled policy schedules no background work`() {
        scheduler.update(BackgroundPolicy())
        scheduler.wake(BackgroundPolicy())
        assertTrue(manager.getWorkInfosForUniqueWork(BackgroundScheduler.PERIODIC_NAME).get().isEmpty())
        assertTrue(manager.getWorkInfosForUniqueWork(BackgroundScheduler.WAKE_NAME).get().isEmpty())
    }
}
