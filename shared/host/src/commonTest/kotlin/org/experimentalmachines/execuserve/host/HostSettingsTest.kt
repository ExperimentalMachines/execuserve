package org.experimentalmachines.execuserve.host

import org.experimentalmachines.execuserve.engine.EngineConfig
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.FailureKind
import org.experimentalmachines.execuserve.engine.FinishReason
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.LaneState
import org.experimentalmachines.execuserve.engine.ThermalLevel
import org.experimentalmachines.execuserve.server.BindMode
import org.experimentalmachines.execuserve.server.ServerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostSettingsTest {

    @Test
    fun untouchedSettingsAreTheEngineAndServerDefaults() {
        assertEquals(EngineConfig(), HostSettings().engineConfig())
        assertEquals(ServerSettings(), HostSettings().serverSettings())
    }

    @Test
    fun everySettingReachesTheEngine() {
        val config = HostSettings(
            maxQueued = 3,
            maxResidentModels = 2,
            maxPerClient = 2,
            queueTimeoutSeconds = 7,
            requestTimeoutSeconds = 11,
            idleUnloadMinutes = 5,
            temperature = 0.2f,
            thinking = ThinkingDefault.OFF,
            keepReasoningInHistory = true,
            minBatteryPercent = 20,
        ).engineConfig()
        assertEquals(2, config.maxResidentModels)
        assertEquals(3, config.maxQueued)
        assertEquals(2, config.maxPerClient)
        assertEquals(7_000, config.queueTimeoutMs)
        assertEquals(11_000, config.requestTimeoutMs)
        assertEquals(300_000, config.idleUnloadMs)
        assertEquals(0.2f, config.defaultTemperature)
        assertEquals(false, config.defaultThinking)
        assertTrue(config.keepReasoningInHistory)
        assertEquals(20, config.minBatteryPercent)
        assertNull(HostSettings(thinking = ThinkingDefault.MODEL).engineConfig().defaultThinking)
    }

    @Test
    fun startupSelectionKeepsLegacyOrderAndHonorsResidentCapacity() {
        val settings = HostSettings(defaultModel = "b", preloadModels = setOf("a", "b", "c"), maxResidentModels = 2)
        assertEquals(listOf("b", "a"), settings.startupModels())
        assertEquals(listOf("b"), settings.copy(threads = 4).startupModels())
        assertEquals(3, settings.copy(maxResidentModels = 99).engineConfig().maxResidentModels)
    }

    @Test
    fun missingModelsAndAliasesDoNotConsumeStartupSlots() {
        val settings = HostSettings(
            defaultModel = "missing",
            preloadModels = setOf("a-alias", "a-canonical", "b-canonical"),
            maxResidentModels = 2,
        )
        fun resolve(name: String): String? = when (name) {
            "a-alias", "a-canonical" -> "a-canonical"
            "b-canonical" -> "b-canonical"
            else -> null
        }
        assertEquals(listOf("a-canonical", "b-canonical"), settings.startupModels(::resolve))
        assertEquals(listOf("a-canonical"), settings.copy(threads = 4).startupModels(::resolve))
    }

    @Test
    fun listsAreSplitOnCommasSpacesAndLines() {
        val server = HostSettings(corsOrigins = "http://a.test, http://b.test\nhttp://c.test", extraHosts = "Phone.Tailnet.ts.net  other").serverSettings()
        assertEquals(listOf("http://a.test", "http://b.test", "http://c.test"), server.corsOrigins)
        assertEquals(setOf("phone.tailnet.ts.net", "other"), server.extraHosts)
    }

    @Test
    fun onlyListenerSettingsNeedARestart() {
        val running = HostSettings()
        assertFalse(running.copy(temperature = 0.1f, maxQueued = 1, wake = WakePolicy.WHILE_BUSY).needsRestartFrom(running))
        assertTrue(running.copy(port = 9090).needsRestartFrom(running))
        assertTrue(running.copy(bind = BindMode.NETWORK).needsRestartFrom(running))
        assertTrue(running.copy(corsOrigins = "http://a.test").needsRestartFrom(running))
    }

    @Test
    fun everyStoredTemperatureShowsAsAChoice() {
        assertEquals(0.7f, Choices.nearestTemperature(0.69f))
        assertTrue(HostSettings().temperature in Choices.TEMPERATURES)
        assertTrue(HostSettings().port in Choices.PORTS)
    }

    @Test
    fun theServerLookFollowsTheLane() {
        val running = ServeHost.State.Running(emptyList(), HostSettings(), 0)
        assertEquals(ServerLook.SERVING, ServerLook.of(running, EngineStatus()))
        assertEquals(ServerLook.WORKING, ServerLook.of(running, EngineStatus(lane = LaneState.GENERATING)))
        assertEquals(ServerLook.NOT_RESPONDING, ServerLook.of(running, EngineStatus(lane = LaneState.WEDGED)))
        assertEquals(ServerLook.STOPPED, ServerLook.of(ServeHost.State.Stopped(), null))
        assertEquals(ServerLook.FAILED_TO_START, ServerLook.of(ServeHost.State.Stopped("port in use"), null))
        assertEquals(Mood.FAILED, ServerLook.FAILED_TO_START.mood)
    }

    @Test
    fun everyEndingHasAnOutcome() {
        fun record(finish: FinishReason?, failure: FailureKind?) = JobRecord("j", "m", "c", 0, finish, failure, 0, 0, 0, 0, 0)
        assertEquals(Outcome.DONE, Outcome.of(record(FinishReason.STOP, null)))
        assertEquals(Outcome.TOOL_CALL, Outcome.of(record(FinishReason.TOOL_CALLS, null)))
        assertEquals(Outcome.CLIENT_LEFT, Outcome.of(record(null, FailureKind.CLIENT_GONE)))
        assertEquals(Outcome.TOO_LONG, Outcome.of(record(null, FailureKind.CONTEXT_OVERFLOW)))
        FailureKind.entries.forEach { Outcome.of(record(null, it)) }
        assertEquals("stop", record(FinishReason.STOP, null).outcome)
        assertEquals("queue_timeout", record(null, FailureKind.QUEUE_TIMEOUT).outcome)
    }

    @Test
    fun heatHasAStepForEveryThermalLevel() {
        assertEquals(Heat.COOL, Heat.of(ThermalLevel.NONE))
        assertEquals(Heat.CRITICAL, Heat.of(ThermalLevel.SHUTDOWN))
        ThermalLevel.entries.forEach { Heat.of(it) }
    }
}

class RunHistoryTest {
    private class MemoryStore : RunStore {
        val lines = mutableListOf<String>()
        override suspend fun readLines() = lines.toList()
        override suspend fun appendLine(line: String) {
            lines += line
        }
        override suspend fun rewrite(lines: List<String>) {
            this.lines.clear()
            this.lines += lines
        }
    }

    private fun run(id: String, at: Long) = JobRecord(id, "m", "c", at, FinishReason.STOP, null, 10, 5, 0, 0, 100, clientId = "k")

    @Test
    fun keepsTheNewestWithinTheLimitsAndCompacts() = kotlinx.coroutines.test.runTest {
        val store = MemoryStore()
        var now = 1_000L
        val history = RunHistory(store, this, { now }, Retention(maxRuns = 5, maxAgeMs = 10_000))
        testScheduler.advanceUntilIdle()
        repeat(12) {
            history.add(run("r$it", now))
            now += 10
        }
        assertEquals(listOf("r11", "r10", "r9", "r8", "r7"), history.runs.value.map { it.id })
        // Appends run a fifth over the limit at most before the file is rewritten.
        assertTrue(store.lines.size <= 6, "${store.lines.size} lines")
        history.clear()
        assertTrue(history.runs.value.isEmpty() && store.lines.isEmpty())
    }

    @Test
    fun oldRunsAgeOutOnLoad() = kotlinx.coroutines.test.runTest {
        val store = MemoryStore().apply {
            lines += RunCodec.encode(run("old", 0))
            lines += RunCodec.encode(run("new", 50_000))
            lines += "not json"
        }
        val history = RunHistory(store, this, { 55_000 }, Retention(maxAgeMs = 10_000))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("new"), history.runs.value.map { it.id })
        assertEquals(1, store.lines.size, "the store is rewritten without the expired and unreadable lines")
    }
}
