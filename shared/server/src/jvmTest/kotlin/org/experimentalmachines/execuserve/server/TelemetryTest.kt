package org.experimentalmachines.execuserve.server

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.experimentalmachines.execuserve.engine.Engine
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.engine.StaticModelSource
import org.experimentalmachines.execuserve.testing.FakeRuntime
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TelemetryTest {
    private val lane = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val alice = ApiKey("ka", "alice", "sk-alice")
    private val bob = ApiKey("kb", "bob", "sk-bob")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        lane.shutdownNow()
    }

    private fun serve(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) = testApplication {
        val models = listOf(ModelEntry("qwen3-1.7b-8da4w-gptq-2k", ModelFiles("/m/q.pte", "/m/q.json"), "qwen3", 1, 4096, setOf("qwen3-1.7b")))
        // A little time per token, so a first token has a time of its own to be measured.
        val runtime = FakeRuntime().apply { tokenDelayMs = 5 }
        val engine = Engine(runtime, StaticModelSource(models), lane.asCoroutineDispatcher(), scope).also { it.start() }
        val ctx = ServerContext(
            engine,
            ServerSettings(),
            StaticKeys(listOf(alice, bob)),
            { emptySet() },
            "test",
            { 1_700_000_000 },
            threads = { 7 },
            runs = { engine.status.value.recent },
        )
        application { execuServe(ctx) }
        block(createClient { defaultRequest { headers.append(HttpHeaders.Host, "localhost:8080") } })
    }

    private suspend fun io.ktor.client.HttpClient.ask(key: ApiKey) = post("/v1/chat/completions") {
        header(HttpHeaders.Authorization, "Bearer ${key.secret}")
        contentType(ContentType.Application.Json)
        setBody("""{"model":"qwen3-1.7b","messages":[{"role":"user","content":"Hi ${key.name}"}]}""")
    }

    @Test
    fun eachKeySeesOnlyItsOwnRuns() = serve { http ->
        assertEquals(HttpStatusCode.OK, http.ask(alice).status)
        assertEquals(HttpStatusCode.OK, http.ask(bob).status)
        assertEquals(HttpStatusCode.OK, http.ask(bob).status)
        val runs = Json.parseToJsonElement(
            http.get("/v1/execuserve/runs") { header(HttpHeaders.Authorization, "Bearer ${alice.secret}") }.bodyAsText(),
        ).jsonObject
        assertEquals(1, runs["total"]!!.jsonPrimitive.content.toInt())
        val run = runs["data"]!!.jsonArray.single().jsonObject
        assertEquals("chat.completions", run["api"]!!.jsonPrimitive.content)
        assertEquals(0, run["discrepancies"]!!.jsonArray.size)
        assertEquals(1, runs["models"]!!.jsonArray.single().jsonObject["runs"]!!.jsonPrimitive.content.toInt())
        val status = Json.parseToJsonElement(
            http.get("/v1/execuserve/status") { header(HttpHeaders.Authorization, "Bearer ${bob.secret}") }.bodyAsText(),
        ).jsonObject
        assertEquals(2, status["recent"]!!.jsonArray.size, "bob sees his two requests, not alice's")
        assertEquals(7, status["threads"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun runsExportAsCsv() = serve { http ->
        http.ask(alice)
        val csv = http.get("/v1/execuserve/runs?format=csv") { header(HttpHeaders.Authorization, "Bearer ${alice.secret}") }.bodyAsText()
        val lines = csv.trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("id,finished_at_ms,model,api"))
        assertTrue("qwen3-1.7b-8da4w-gptq-2k" in lines[1])
    }

    @Test
    fun metricsArePrometheusTextBehindAKey() = serve { http ->
        assertEquals(HttpStatusCode.Unauthorized, http.get("/metrics").status)
        http.ask(alice)
        // The reply reaches the client a moment before the lane books it: wait for the count.
        var text = ""
        withTimeout(5_000) {
            while (true) {
                text = http.get("/metrics") { header(HttpHeaders.Authorization, "Bearer ${alice.secret}") }.bodyAsText()
                if ("execuserve_requests_processing 0" in text) break
                delay(10)
            }
        }
        assertTrue("# TYPE execuserve_requests_total counter" in text, text)
        assertTrue("execuserve_requests_total{outcome=\"completed\"} 1" in text, text)
        assertTrue("execuserve_threads 7" in text, text)
        assertTrue(Regex("execuserve_time_to_first_token_seconds\\{quantile=\"0.5\"} [0-9.]+").containsMatchIn(text), text)
        // A rolling window is a gauge: no _count that a rate() would misread as cumulative.
        assertTrue("# TYPE execuserve_time_to_first_token_seconds gauge" in text, text)
        assertTrue("_count" !in text, text)
        // No client appears in a label: the counters are the server's, not any one key's.
        assertTrue("alice" !in text)
    }
}
