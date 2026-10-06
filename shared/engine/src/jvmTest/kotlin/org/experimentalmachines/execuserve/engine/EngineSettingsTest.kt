package org.experimentalmachines.execuserve.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.experimentalmachines.execuserve.testing.FakeRuntime
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings that act on a running engine: what it admits and what it keeps in memory. */
class EngineSettingsTest {
    private val laneExecutor = Executors.newSingleThreadExecutor { Thread(it, "lane") }
    private val lane = laneExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val env = MutableStateFlow(Environment())

    @AfterTest
    fun tearDown() {
        scope.cancel()
        laneExecutor.shutdownNow()
    }

    private var wedged = 0

    private fun engine(runtime: LlmRuntime, models: List<ModelEntry> = emptyList(), config: EngineConfig = EngineConfig()) =
        Engine(runtime, StaticModelSource(models), lane, scope, config, env, onWedged = { wedged++ }).also { it.start() }

    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    @Test
    fun twoModelsThatDoNotFitTogetherAreNotKeptTogether() = test {
        // Qwen3-0.6B at 2k: about 0.8 GB each with a 0.5 GB file. Room for one, not two.
        val small = ModelEntry("qwen3-0.6b-a", ModelFiles("/a.pte", "/a.json"), "qwen3", 500_000_000, 2048)
        val other = ModelEntry("qwen3-0.6b-b", ModelFiles("/b.pte", "/b.json"), "qwen3", 500_000_000, 2048)
        val need = ModelMemory.needFor(small)!!
        val engine = engine(FakeRuntime(), models = listOf(small, other), config = EngineConfig(maxResidentModels = 2, memoryBudgetBytes = need + need / 2))
        engine.load(small.id)
        engine.load(other.id)
        assertEquals(listOf(other.id), engine.status.value.resident.map { it.id })
        // With room for both, both stay.
        engine.config = engine.config.copy(memoryBudgetBytes = need * 3)
        engine.load(small.id)
        assertEquals(setOf(small.id, other.id), engine.status.value.resident.map { it.id }.toSet())
    }

    @Test
    fun aRaisedBatteryFloorPausesAtOnce() = test {
        val engine = engine(FakeRuntime())
        env.value = Environment(batteryPercent = 15, charging = false)
        engine.status.first { it.admission == Admission.OPEN }
        // The setting changes; the battery does not. Admission follows the setting anyway.
        engine.config = engine.config.copy(minBatteryPercent = 20)
        assertEquals(Admission.PAUSED_BATTERY, engine.status.value.admission)
        engine.config = engine.config.copy(minBatteryPercent = 0)
        assertEquals(Admission.OPEN, engine.status.value.admission)
    }

    @Test
    fun aLoadThatNeverReturnsWedgesTheEngine() = test {
        val runtime = FakeRuntime().apply { openGate = java.util.concurrent.CountDownLatch(1) }
        val model = ModelEntry("m", ModelFiles("/m.pte", "/m.json"), "qwen3", 1, 2048)
        val engine = engine(runtime, listOf(model), EngineConfig(loadDeadlineMs = 300))
        scope.launch { runCatching { engine.load(model.id) } }
        engine.status.first { it.lane == LaneState.WEDGED }
        assertEquals(1, wedged)
        runtime.openGate?.countDown()
    }

    @Test
    fun aModelBeingDeletedIsNeitherServedNorReloaded() = test {
        val model = ModelEntry("m", ModelFiles("/m.pte", "/m.json"), "qwen3", 1, 2048)
        val engine = engine(FakeRuntime(), listOf(model))
        engine.load(model.id)
        engine.retire(model.id)
        assertEquals(emptyList(), engine.status.value.resident)
        kotlin.test.assertFailsWith<Refusal.UnknownModel> {
            engine.submit(GenerationRequest(model = model.id, input = PromptInput.Raw("Hi"), client = ClientId("c", "c")))
        }
    }
}
