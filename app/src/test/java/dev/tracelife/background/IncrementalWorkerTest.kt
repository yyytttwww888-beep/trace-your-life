package dev.tracelife.background

import androidx.work.ListenableWorker
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestWorkerBuilder
import dev.tracelife.TraceLifeApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncrementalWorkerTest {
    @Test fun `real application worker honors disabled consent without retry or analysis`() {
        val app = RuntimeEnvironment.getApplication() as TraceLifeApplication
        val worker = TestWorkerBuilder.from(app, IncrementalWorker::class.java, SynchronousExecutor()).build()
        assertFalse(app.incrementalOrganizer.policy().enabled)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(app.analysis.tasks().isEmpty())
        assertEquals(0, app.incrementalOrganizer.settings().reservedRequestsToday)
    }
}
