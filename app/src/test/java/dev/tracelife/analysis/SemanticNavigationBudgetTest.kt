package dev.tracelife.analysis

import android.content.Context
import dev.tracelife.storage.AnalysisQuery
import dev.tracelife.storage.LifeRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Synthetic navigation text must obey the same durable output ceiling as personal claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SemanticNavigationBudgetTest {
    private lateinit var context: Context
    private lateinit var repository: LifeRepository
    private lateinit var analysis: ControlledAnalysis
    private val database = "synthetic-navigation-budget-${UUID.randomUUID()}.db"
    private val clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneOffset.UTC)
    private val scope = AnalysisQuery(LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-20"), "UTC")
    private val oversized = NavigationSuggestion(listOf("学习"), listOf("讲解练习"), "合成说明".repeat(50))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = LifeRepository(context, clock, database)
        analysis = ControlledAnalysis(repository, clock)
    }
    @After fun cleanup() { repository.close(); context.deleteDatabase(database) }

    @Test fun `navigation exceeding 256 output bytes records charged failure without saving suggestion`() {
        val provider = Provider(oversized)
        analysis.start(AnalysisRequest("合成检索问题", scope, AnalysisLimits(maxOutputBytes = 256)), "bounded")
        assertTrue(AnalysisRunCodec.json(SemanticNavigationProtocol.map(oversized)).toByteArray(Charsets.UTF_8).size > 256)
        assertThrows(AnalysisValidationException::class.java) { analysis.navigate("bounded", provider) }
        val call = analysis.task("bounded").state.calls.single()
        assertEquals(1, provider.dispatched)
        assertEquals(CallStatus.FAILED, call.status)
        assertNotNull(call.finishedAt)
        assertTrue(call.inputBytes > 0)
        assertNull(call.navigation)
        assertNull(call.navigationContext)
        assertEquals(AnalysisStatus.NEEDS_RETRY, analysis.task("bounded").status)
    }

    @Test fun `codec refuses navigation saved under a tampered smaller output budget`() {
        analysis.start(AnalysisRequest("合成检索问题", scope), "unbounded")
        analysis.navigate("unbounded", Provider(oversized))
        val raw = JSONObject(analysis.task("unbounded").stored.payload)
        assertEquals(17, raw.getInt("formatVersion"))
        raw.getJSONObject("request").getJSONObject("limits").put("maxOutputBytes", 256)
        assertThrows(IllegalArgumentException::class.java) { AnalysisRunCodec.decode(raw.toString()) }
    }

    private class Provider(private val result: NavigationSuggestion) : SemanticNavigationProvider {
        override val id = "synthetic-navigation"
        override val contextWindowTokens = 100_000
        var dispatched = 0
        override fun prepareNavigation(request: ModelRequest) = PreparedModelRequest(request, request.payloadJson,
            request.payloadJson.toByteArray(Charsets.UTF_8).size, TokenCountKind.CONSERVATIVE_UTF8_BOUND)
        override fun navigatePrepared(request: PreparedModelRequest): NavigationCompletion {
            dispatched++
            return NavigationCompletion(result)
        }
    }
}
