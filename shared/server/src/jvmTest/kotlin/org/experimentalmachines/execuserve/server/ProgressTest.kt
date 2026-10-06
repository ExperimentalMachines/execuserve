package org.experimentalmachines.execuserve.server

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.experimentalmachines.execuserve.engine.Engine
import org.experimentalmachines.execuserve.engine.FailureKind
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.engine.StaticModelSource
import org.experimentalmachines.execuserve.testing.FakeRuntime
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgressTest {
    private val lane = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val key = ApiKey("k", "k", "sk-k")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        lane.shutdownNow()
    }

    private fun stream(returnProgress: Boolean): List<String> {
        var lines = emptyList<String>()
        testApplication {
            // Small runtime calls, so a long prompt is read in many chunks.
            // Ensure the response starts while prefill is still running: an asynchronous
            // body writer must not be mistaken for a client leaving the request handler.
            val runtime = FakeRuntime(prefillLength = 64).apply { prefillDelayPerCharMs = 0.2 }
            val models = listOf(ModelEntry("lfm", ModelFiles("/m/l.pte", "/m/l.json"), "lfm2.5", 1, 4096))
            val engine = Engine(runtime, StaticModelSource(models), lane.asCoroutineDispatcher(), scope).also { it.start() }
            application { execuServe(ServerContext(engine, ServerSettings(), StaticKeys(listOf(key)), { emptySet() }, "test", { 0 })) }
            val http = createClient { defaultRequest { headers.append(HttpHeaders.Host, "localhost:8080") } }
            val body = http.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${key.secret}")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"lfm","stream":true,"return_progress":$returnProgress,"messages":[{"role":"user","content":"${"word ".repeat(100)}"}]}""")
            }.bodyAsText()
            assertFalse("\"error\":" in body, body)
            assertTrue(body.trimEnd().endsWith("data: [DONE]"), body)
            lines = body.lines().filter { it.startsWith("data: {") }
        }
        return lines
    }

    @Test
    fun progressComesBeforeTheReplyAndGrows() {
        val lines = stream(returnProgress = true)
        val progress = lines.map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.mapNotNull { it["prompt_progress"]?.jsonObject }
        assertTrue(progress.size > 2, "one per chunk read: $progress")
        val processed = progress.map { it["processed"]!!.jsonPrimitive.content.toInt() }
        assertEquals(processed.sorted(), processed)
        assertEquals("characters", progress.first()["unit"]!!.jsonPrimitive.content)
        // The first chunk with text in it; the opening chunk's role and empty content do not count.
        val firstContent = lines.indexOfFirst { Regex("\"content\":\"[^\"]").containsMatchIn(it) }
        val lastProgress = lines.indexOfLast { "prompt_progress" in it }
        assertTrue(lastProgress < firstContent, "progress is about the prompt, so it all comes before the reply")
    }

    @Test
    fun nobodyGetsProgressWithoutAskingForIt() {
        assertTrue(stream(returnProgress = false).none { "prompt_progress" in it })
    }

    @Test
    fun aPortAnotherAppHoldsIsReportedNotThrownOnAnotherThread() = runBlocking {
        val models = listOf(ModelEntry("lfm", ModelFiles("/m/l.pte", "/m/l.json"), "lfm2.5", 1, 4_096))
        val engine = Engine(FakeRuntime(), StaticModelSource(models), lane.asCoroutineDispatcher(), scope).also { it.start() }
        val uncaught = mutableListOf<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> synchronized(uncaught) { uncaught += failure } }
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { taken ->
            val server = ExecuServer(ServerContext(engine, ServerSettings(port = taken.localPort), StaticKeys(listOf(key)), { emptySet() }, "test", { 0 }))
            try {
                assertFailsWith<ServerStartFailure> { server.start() }
                // CIO's accept coroutine fails after start returns; give it the time to.
                delay(500)
                assertEquals(emptyList(), synchronized(uncaught) { uncaught.toList() })
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(before)
                server.stop()
                engine.stop(0)
            }
        }
    }

    @Test
    fun closingAStreamingSocketStillCancelsItsGeneration() = runBlocking {
        val runtime = FakeRuntime(window = 65_536, reply = { List(1_000) { "word " } }).apply { tokenDelayMs = 5 }
        val models = listOf(ModelEntry("lfm", ModelFiles("/m/l.pte", "/m/l.json"), "lfm2.5", 1, 65_536))
        val engine = Engine(runtime, StaticModelSource(models), lane.asCoroutineDispatcher(), scope).also { it.start() }
        val server = ExecuServer(ServerContext(engine, ServerSettings(port = 0), StaticKeys(listOf(key)), { emptySet() }, "test", { 0 }))
        try {
            // The in-memory test client can buffer an entire response. A real TCP peer
            // verifies actual disconnect propagation through the production CIO listener.
            server.start()
            val port = server.endpoints.first { it.startsWith("127.0.0.1:") }.substringAfterLast(':').toInt()
            withContext(Dispatchers.IO) {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.setSoLinger(true, 0)
                    val body = """{"model":"lfm","stream":true,"max_tokens":1000,"messages":[{"role":"user","content":"Hi"}]}"""
                    val bytes = body.toByteArray()
                    val headers = "POST /v1/chat/completions HTTP/1.1\r\nHost: localhost:$port\r\n" +
                        "Authorization: Bearer ${key.secret}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray())
                        write(bytes)
                        flush()
                    }
                    val response = socket.getInputStream().bufferedReader()
                    var started = false
                    while (!started) {
                        val line = response.readLine() ?: error("The stream closed before any SSE data")
                        started = line.startsWith("data: ")
                    }
                }
            }
            val stopped = withTimeout(5_000) { engine.status.first { it.recent.isNotEmpty() } }
            assertEquals(FailureKind.CLIENT_GONE, stopped.recent.first().failure)
            assertTrue(stopped.recent.first().completionTokens < 1_000)
        } finally {
            server.stop()
            engine.stop(0)
        }
    }
}
