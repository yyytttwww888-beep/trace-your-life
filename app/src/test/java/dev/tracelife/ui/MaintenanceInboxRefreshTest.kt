package dev.tracelife.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.tracelife.TraceLifeApplication
import dev.tracelife.background.BackgroundJob
import dev.tracelife.background.BackgroundJobStatus
import dev.tracelife.background.BackgroundPolicy
import dev.tracelife.background.BackgroundStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Synthetic operational rows; lifecycle navigation must refresh local state without model calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MaintenanceInboxRefreshTest {
    private lateinit var app: TraceLifeApplication
    private var controller: ActivityController<MaintenanceInboxActivity>? = null

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        app.io.submit {}.get(20, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build())
    }
    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        WorkManager.getInstance(app).cancelAllWork().result.get(20, TimeUnit.SECONDS)
        app.io.submit { app.incrementalOrganizer.close(); app.repository.close() }.get(20, TimeUnit.SECONDS)
        app.io.shutdownNow()
    }

    @Test fun `returning from settings shows current authorization without recreating inbox`() {
        controller = Robolectric.buildActivity(MaintenanceInboxActivity::class.java).setup(); drain()
        assertTrue(texts().contains("尚未开启自动整理"))
        controller!!.pause()
        app.io.submit { app.incrementalOrganizer.configure(BackgroundPolicy(enabled = true, paused = true)) }
            .get(20, TimeUnit.SECONDS)
        controller!!.resume(); drain()
        assertTrue(texts().contains("已暂停"))
        assertFalse(texts().contains("尚未开启自动整理"))
        assertEquals(0, app.incrementalOrganizer.settings().reservedRequestsToday)
        assertTrue(app.repository.analysisRunIndex().isEmpty())
    }

    @Test fun `return while previous local result awaits delivery refreshes after busy read`() {
        controller = Robolectric.buildActivity(MaintenanceInboxActivity::class.java).setup()
        // The first read captured disabled settings; its main-thread result remains queued.
        app.io.submit {}.get(20, TimeUnit.SECONDS)
        controller!!.pause()
        app.io.submit { app.incrementalOrganizer.configure(BackgroundPolicy(enabled = true, paused = true)) }
            .get(20, TimeUnit.SECONDS)
        controller!!.resume(); drain()
        assertTrue(texts().contains("已暂停"))
        assertFalse(texts().contains("尚未开启自动整理"))
        assertEquals(0, app.incrementalOrganizer.settings().reservedRequestsToday)
    }

    @Test fun `return refresh preserves the selected job page while showing changed state`() {
        app.io.submit {
            app.incrementalOrganizer.settings()
            BackgroundStore(File(app.noBackupFilesDir, "incremental-analysis")).use { store ->
                store.transaction {
                    repeat(21) { index ->
                        save(BackgroundJob("synthetic-$index", 1, "record-$index", 1, index.toLong(),
                            BackgroundJobStatus.QUEUED, null, 0, 0, false, Instant.now(), Instant.now()))
                    }
                }
            }
        }.get(20, TimeUnit.SECONDS)
        controller = Robolectric.buildActivity(MaintenanceInboxActivity::class.java).setup(); drain()
        view("nextInboxJobs").performClick(); drain()
        controller!!.pause()
        app.io.submit {
            BackgroundStore(File(app.noBackupFilesDir, "incremental-analysis")).use { store ->
                store.transaction {
                    val old = requireNotNull(job("synthetic-20"))
                    save(old.copy(version = 2, status = BackgroundJobStatus.FAILED))
                }
            }
        }.get(20, TimeUnit.SECONDS)
        controller!!.resume(); drain()
        assertNotNull(view("previousInboxJobs"))
        assertTrue(texts().contains("已停止，等待重试"))
        assertNotNull(view("resumeJob:synthetic-20"))
        assertEquals(0, app.incrementalOrganizer.settings().reservedRequestsToday)
    }

    private fun view(tag: String): View = requireNotNull(controller!!.get().window.decorView.findViewWithTag<View>(tag))
    private fun texts() = descendants(controller!!.get().window.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun drain() {
        repeat(4) { app.io.submit {}.get(20, TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle() }
    }
}
