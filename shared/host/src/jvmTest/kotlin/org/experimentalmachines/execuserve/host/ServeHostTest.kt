package org.experimentalmachines.execuserve.host

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.experimentalmachines.execuserve.engine.Environment
import org.experimentalmachines.execuserve.engine.LlmRuntime
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.server.ApiKey
import org.experimentalmachines.execuserve.server.BindMode
import org.experimentalmachines.execuserve.testing.FakeRuntime
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The host's lifecycle over the real HTTP stack, with the fake runtime behind it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServeHostTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val key = ApiKey("k1", "test", "sk-test-123")
    private val port = ServerSocket(0).use { it.localPort }
    private val store = FakeStore(HostSettings(port = port), key)
    private val library = FakeLibrary(
        listOf(ModelEntry("qwen3-1.7b-8da4w-gptq-2k", ModelFiles("/m/q.pte", "/m/q.json"), "qwen3", 1, 2048, setOf("qwen3-1.7b"))),
    )

    // Small prefill calls, so a prompt is read a chunk at a time the way the phone reads it.
    private val runtime = FakeRuntime(prefillLength = 64)
    private val runStore = MemoryRunStore()
    private val history = RunHistory(runStore, scope, System::currentTimeMillis)
    private val host = ServeHost(FakePlatform(runtime), store, library, history, scope)

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        host.stop()
        scope.cancel()
    }

    @Test
    fun startServesAndStopReleases() = runBlocking<Unit> {
        host.start(onWedged = {})
        val running = assertIs<ServeHost.State.Running>(host.state.value)
        assertEquals("http://127.0.0.1:$port/v1", running.endpoints.single().url)
        assertEquals(1, library.rescans)
        assertNotNull(host.engine)
        host.stop()
        assertIs<ServeHost.State.Stopped>(host.state.value)
        assertNull(host.engine)
        assertNull(host.status.first())
    }

    @Test
    fun startupLoadsDistinctCanonicalModelsBeforeApplyingTheCapacity() = runBlocking<Unit> {
        val startupStore = FakeStore(
            HostSettings(
                port = port,
                defaultModel = "first",
                preloadModels = setOf("a", "b"),
                maxResidentModels = 2,
            ),
            key,
        )
        val startupLibrary = FakeLibrary(
            listOf(
                ModelEntry("a", ModelFiles("/m/a.pte", "/m/a.json"), "qwen3", aliases = setOf("first")),
                ModelEntry("b", ModelFiles("/m/b.pte", "/m/b.json"), "qwen3"),
            ),
        )
        val startupHost = ServeHost(FakePlatform(runtime), startupStore, startupLibrary, history, scope)
        try {
            startupHost.start(onWedged = {})
            val status = withTimeout(5_000) { startupHost.engine!!.status.first { it.resident.size == 2 } }
            assertEquals(setOf("a", "b"), status.resident.map { it.id }.toSet())
            assertEquals(2, runtime.sessions.size)
        } finally {
            startupHost.stop()
        }
    }

    @Test
    fun aModelThatEndedTheProcessStaysRefusedUntilRetried() = runBlocking<Unit> {
        // The system killed the last process while "a" ran (an NPU build too large for the
        // phone): the restarted service opens "b", refuses "a", and keeps refusing it through a
        // rescan and another restart, until the person retries it.
        val startupStore = FakeStore(HostSettings(port = port, preloadModels = setOf("a", "b"), maxResidentModels = 2), key)
        val startupLibrary = FakeLibrary(
            listOf(
                ModelEntry("a", ModelFiles("/m/a.pte", "/m/a.json"), "qwen3"),
                ModelEntry("b", ModelFiles("/m/b.pte", "/m/b.json"), "qwen3"),
            ),
        )
        // The runtime records the file it had open; the host maps it to the model.
        val platform = FakePlatform(runtime, interrupted = "/m/a.pte")
        val startupHost = ServeHost(platform, startupStore, startupLibrary, history, scope)
        try {
            startupHost.start(onWedged = {})
            val status = withTimeout(5_000) { startupHost.engine!!.status.first { it.resident.isNotEmpty() } }
            assertEquals(listOf("b"), status.resident.map { it.id })
            assertTrue("a" in status.broken)
            assertEquals(setOf("a"), platform.quarantined())

            startupHost.forgetFailures()
            assertTrue("a" in startupHost.engine!!.status.value.broken)

            startupHost.stop()
            startupHost.start(onWedged = {})
            assertTrue("a" in startupHost.engine!!.status.value.broken)

            startupHost.retry("a")
            assertEquals(emptySet(), platform.quarantined())
            assertTrue("a" !in startupHost.engine!!.status.value.broken)
        } finally {
            startupHost.stop()
        }
    }

    @Test
    fun theConsoleChatGoesOverHttpLikeAnyClient() = runBlocking<Unit> {
        host.start(onWedged = {})
        val text = StringBuilder()
        val reader = ReplyReader { content, _ -> text.append(content) }
        val http = URI("http://127.0.0.1:$port/v1/chat/completions").toURL().openConnection() as HttpURLConnection
        http.requestMethod = "POST"
        http.doOutput = true
        http.setRequestProperty("Authorization", "Bearer ${key.secret}")
        http.setRequestProperty("Content-Type", "application/json")
        http.outputStream.use { it.write(ConsoleChat.body("qwen3-1.7b", listOf(ConsoleChat.Turn(ConsoleChat.Role.USER, "Hi")), thinking = null).toByteArray()) }
        assertEquals(200, http.responseCode)
        http.inputStream.bufferedReader().useLines { lines -> lines.takeWhile { reader.line(it) }.count() }
        assertEquals("Hello world", text.toString())
        // The run is in the history, as a line that reads back as the same record.
        withTimeout(5_000) { while (history.runs.value.isEmpty()) kotlinx.coroutines.delay(20) }
        val run = history.runs.value.single()
        assertEquals(run, RunCodec.decode(runStore.lines.single()))
        assertEquals("chat.completions", run.api)
        // Two fragments of text and the end marker: the marker was generated too.
        assertEquals(3, reader.result(0, 0).completionTokens)
    }

    @Test
    fun aClientThatHangsUpDuringAPromptFreesTheLane() = runBlocking<Unit> {
        host.start(onWedged = {})
        // 1 800 characters at 5 ms each: nine seconds of prompt, read in chunks of 63.
        runtime.prefillDelayPerCharMs = 5.0
        val body = ConsoleChat.body("qwen3-1.7b", listOf(ConsoleChat.Turn(ConsoleChat.Role.USER, "word ".repeat(360))), thinking = null).toByteArray()
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(
                (
                    "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer ${key.secret}\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n"
                    ).toByteArray() + body,
            )
            withTimeout(5_000) {
                while (host.status.first()?.lane !=
                    org.experimentalmachines.execuserve.engine.LaneState.PREFILLING
                ) {
                    kotlinx.coroutines.delay(20)
                }
            }
        }
        val left = System.currentTimeMillis()
        withTimeout(5_000) {
            while (host.status.first()?.recent?.firstOrNull() == null) kotlinx.coroutines.delay(20)
        }
        val record = host.status.first()!!.recent.first()
        assertEquals(org.experimentalmachines.execuserve.engine.FailureKind.CLIENT_GONE, record.failure)
        assertTrue(System.currentTimeMillis() - left < 3_000, "the lane kept reading for a client that had left")
    }

    @Test
    fun engineSettingsApplyWhileServing() = runBlocking<Unit> {
        host.start(onWedged = {})
        store.flow.value = store.flow.value.copy(maxQueued = 3, temperature = 0.2f)
        withTimeout(5_000) {
            while (host.engine?.config?.maxQueued != 3) kotlinx.coroutines.delay(10)
        }
        assertEquals(0.2f, host.engine!!.config.defaultTemperature)
    }

    @Test
    fun aTakenPortIsAFailureToStartNotACrash() = runBlocking<Unit> {
        ServerSocket(port, 0, java.net.InetAddress.getByName("127.0.0.1")).use {
            host.start(onWedged = {})
            val stopped = assertIs<ServeHost.State.Stopped>(host.state.value)
            assertNotNull(stopped.error)
        }
    }

    @Test
    fun endpointsAreNotRewrittenAfterStop() = runBlocking<Unit> {
        host.start(onWedged = {})
        host.stop()
        host.refreshEndpoints()
        assertIs<ServeHost.State.Stopped>(host.state.value)
    }

    @Test
    fun consoleActionsAreQuietWhileStopped() = runBlocking<Unit> {
        host.load("qwen3-1.7b")
        host.cancel("nothing")
        host.evictAll()
        assertTrue(host.state.value is ServeHost.State.Stopped)
    }
}

private class FakePlatform(private val runtime: LlmRuntime, private var interrupted: String? = null) : HostPlatform {
    private val refused = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun takeInterrupted() = interrupted.also { interrupted = null }

    override fun quarantined(): Set<String> = refused.toSet()

    override fun setQuarantined(id: String, quarantined: Boolean) {
        if (quarantined) refused += id else refused -= id
    }

    override val version = "test"
    override val cpuCores = 8
    override val environment = MutableStateFlow(Environment())

    override fun runtime() = runtime

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun lane(): CloseableCoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    override fun endpoints(port: Int, bind: BindMode) = listOf(Endpoint("http://127.0.0.1:$port/v1", NetworkKind.THIS_DEVICE))

    override fun hosts() = emptySet<String>()
}

private class FakeStore(settings: HostSettings, key: ApiKey) : HostStore {
    val flow = MutableStateFlow(settings)
    override val settings = flow
    override val keys = MutableStateFlow(listOf(key))

    override suspend fun ensureKeys() = keys.value
}

private class MemoryRunStore : RunStore {
    val lines = java.util.concurrent.CopyOnWriteArrayList<String>()

    override suspend fun readLines(): List<String> = lines.toList()

    override suspend fun appendLine(line: String) {
        lines += line
    }

    override suspend fun rewrite(lines: List<String>) {
        this.lines.clear()
        this.lines += lines
    }
}

private class FakeLibrary(private val models: List<ModelEntry>) : ModelLibrary {
    var rescans = 0

    override fun all() = models

    override suspend fun rescan() {
        rescans++
    }
}

class BenchmarkTest {
    @Test
    fun theBenchmarkCountsOnlyWhatTheRuntimeReported() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lane = Executors.newSingleThreadExecutor()
        try {
            val runtime = FakeRuntime(reply = { (1..200).map { " $it," } })
            val engine = org.experimentalmachines.execuserve.engine.Engine(
                runtime,
                org.experimentalmachines.execuserve.engine.StaticModelSource(listOf(ModelEntry("m", ModelFiles("/m.pte", "/m.json"), "qwen3", 1, 4096))),
                lane.asCoroutineDispatcher(),
                scope,
            ).also { it.start() }
            val runs = Benchmark.run(engine, "m")
            assertEquals(Benchmark.REPEATS, runs.size)
            runs.forEach { run ->
                assertEquals(Benchmark.API, run.api)
                // One runtime call: nothing about the prompt is estimated, and nothing reused.
                assertEquals(0, run.estimatedPromptTokens, run.toString())
                assertEquals(0, run.cachedTokens, run.toString())
                assertEquals(Benchmark.DECODE_TOKENS, run.completionTokens)
            }
            // Every repetition read the prompt from a reset runtime.
            assertEquals(Benchmark.REPEATS, runtime.log.count { it.startsWith("generate ") })
            engine.stop(0)
        } finally {
            scope.cancel()
            lane.shutdownNow()
        }
    }
}
