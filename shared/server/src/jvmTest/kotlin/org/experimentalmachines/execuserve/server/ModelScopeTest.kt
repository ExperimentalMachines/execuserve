package org.experimentalmachines.execuserve.server

import io.ktor.client.HttpClient
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.experimentalmachines.execuserve.engine.Engine
import org.experimentalmachines.execuserve.engine.EngineConfig
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.engine.StaticModelSource
import org.experimentalmachines.execuserve.testing.FakeRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelScopeTest {
    private val twoModels = listOf(
        ModelEntry("alpha", ModelFiles("/alpha.pte", "/alpha.json"), "qwen3", 1, 4096, setOf("first")),
        ModelEntry("vendor/beta", ModelFiles("/beta.pte", "/beta.json"), "qwen3", 1, 4096, setOf("second")),
    )

    private fun serve(
        runtime: FakeRuntime = FakeRuntime(),
        config: EngineConfig = EngineConfig(),
        models: List<ModelEntry> = twoModels,
        block: suspend (HttpClient, Engine) -> Unit,
    ) = testApplication {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = Engine(runtime, StaticModelSource(models), dispatcher, scope, config)
        engine.start()
        val ctx =
            ServerContext(engine, ServerSettings(), StaticKeys(listOf(ApiKey("one", "One", "sk-one"), ApiKey("two", "Two", "sk-two"))), {
                emptySet()
            }, "test", { 1_700_000_000 })
        application { execuServe(ctx) }
        val http = createClient { defaultRequest { if (HttpHeaders.Host !in headers) header(HttpHeaders.Host, "localhost:8080") } }
        try {
            block(http, engine)
        } finally {
            engine.stop(0)
            scope.cancel()
            dispatcher.close()
        }
    }

    private suspend fun HttpClient.getKey(path: String) = get(path) { header(HttpHeaders.Authorization, "Bearer sk-one") }
    private suspend fun HttpClient.send(path: String, body: String, anthropic: Boolean = false) = post(path) {
        header(if (anthropic) "x-api-key" else HttpHeaders.Authorization, if (anthropic) "sk-one" else "Bearer sk-one")
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun body(model: String) = """{"model":"$model","messages":[{"role":"user","content":"Hi"}],"max_tokens":20}"""

    @Test
    fun whatCannotBeHonouredIsRefusedInTheCallersShape() = serve { http, engine ->
        // An unknown route answers JSON a client SDK can read, in its own protocol's shape.
        val openai = http.getKey("/v1/embeddings")
        assertEquals(HttpStatusCode.NotFound, openai.status)
        assertEquals("not_found", json(openai.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        val anthropic = http.get("/v1/messages/batches") {
            header("x-api-key", "sk-one")
            header("anthropic-version", "2023-06-01")
        }
        assertEquals(HttpStatusCode.NotFound, anthropic.status)
        assertEquals("error", json(anthropic.bodyAsText())["type"]!!.jsonPrimitive.content)
        // Fields that would otherwise vanish in decoding, and change nothing, are refused.
        val legacy = http.send("/v1/chat/completions", """{"model":"first","messages":[{"role":"user","content":"Hi"}],"functions":[{"name":"f"}]}""")
        assertEquals(HttpStatusCode.BadRequest, legacy.status, legacy.bodyAsText())
        val background = http.send("/v1/responses", """{"model":"first","input":"Hi","background":true}""")
        assertEquals(HttpStatusCode.BadRequest, background.status, background.bodyAsText())
        val tokens = http.send("/v1/completions", """{"model":"first","prompt":[123, 456]}""")
        assertEquals(HttpStatusCode.BadRequest, tokens.status, tokens.bodyAsText())
        // A field of the wrong kind is the client's mistake: 400, not 500.
        val malformed = http.send("/v1/chat/completions", """{"model":"first","messages":[{"role":"user","content":"Hi"}],"response_format":{"type":{}}}""")
        assertEquals(HttpStatusCode.BadRequest, malformed.status, malformed.bodyAsText())
        val hot = http.send(
            "/v1/messages",
            """{"model":"first","max_tokens":5,"temperature":1.5,"messages":[{"role":"user","content":"Hi"}]}""",
            anthropic = true,
        )
        assertEquals(HttpStatusCode.BadRequest, hot.status, hot.bodyAsText())
        assertEquals(0, engine.status.value.totals.completed)
    }

    @Test
    fun countTokensEstimatesTheRenderedPrompt() = serve { http, engine ->
        val reply = http.send("/v1/messages/count_tokens", """{"model":"first","messages":[{"role":"user","content":"Hello there"}]}""", anthropic = true)
        assertEquals(HttpStatusCode.OK, reply.status, reply.bodyAsText())
        val tokens = json(reply.bodyAsText())["input_tokens"]!!.jsonPrimitive.content.toInt()
        assertTrue(tokens in 3..200, tokens.toString())
        // Nothing ran: a count is not a request.
        assertEquals(0, engine.status.value.totals.completed)
    }

    @Test
    fun aModelsOwnEndpointAnswersANameItDoesNotKnow() = serve { http, _ ->
        // An app with a fixed model name ("gpt-4o") pointed at a model's base URL.
        for (route in listOf("chat/completions", "messages")) {
            val reply = http.send("/models/alpha/v1/$route", body("gpt-4o"), anthropic = route == "messages")
            assertEquals(HttpStatusCode.OK, reply.status, reply.bodyAsText())
            assertEquals("alpha", json(reply.bodyAsText())["model"]!!.jsonPrimitive.content)
        }
        // A name in the path is an address: a wrong one is not found, and unloads nothing.
        assertEquals(HttpStatusCode.NotFound, http.getKey("/models/alpha/v1/models/typo").status)
        assertEquals(HttpStatusCode.NotFound, http.send("/models/alpha/v1/execuserve/models/typo/unload", "{}").status)
        assertEquals(HttpStatusCode.OK, http.send("/models/alpha/apply-template", body("gpt-4o")).status)
        // Without a mount, an unknown name is still not found.
        assertEquals(HttpStatusCode.NotFound, http.send("/v1/chat/completions", body("gpt-4o")).status)
    }

    @Test
    fun anthropicsKeyHeaderWorksOnEveryRoute() = serve { http, _ ->
        val models = http.get("/v1/models") { header("x-api-key", "sk-one") }
        assertEquals(HttpStatusCode.OK, models.status, models.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/models") { header("x-api-key", "wrong") }.status)
        // In Anthropic's shape for an Anthropic client.
        val anthropic = json(
            http.get("/v1/models") {
                header("x-api-key", "sk-one")
                header("anthropic-version", "2023-06-01")
            }.bodyAsText(),
        )
        val first = anthropic["data"]!!.jsonArray.first().jsonObject
        assertEquals("model", first["type"]!!.jsonPrimitive.content)
        assertTrue("display_name" in first && "created_at" in first, first.toString())
        assertEquals("false", anthropic["has_more"]!!.jsonPrimitive.content)
    }

    @Test
    fun modelsReportTheDelegateTheInstallRecorded() = serve(
        models = listOf(
            ModelEntry("qwen3-0.6b-8da4w-2k-vulkan", ModelFiles("/g.pte", "/g.json"), "qwen3", backend = "vulkan"),
            ModelEntry("qwen3-0.6b-8da4w-2k", ModelFiles("/c.pte", "/c.json"), "qwen3", backend = "xnnpack"),
            // Copied in by hand: no record, so the name is all there is.
            ModelEntry("pushed-vulkan", ModelFiles("/p.pte", "/p.json"), "qwen3"),
            ModelEntry("pushed", ModelFiles("/q.pte", "/q.json"), "qwen3"),
        ),
    ) { http, engine ->
        val backends = json(http.getKey("/v1/models").bodyAsText())["data"]!!.jsonArray
            .associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["backend"]!!.jsonPrimitive.content }
        assertEquals("executorch-vulkan", backends["qwen3-0.6b-8da4w-2k-vulkan"])
        assertEquals(engine.runtimeId, backends["qwen3-0.6b-8da4w-2k"])
        assertEquals("executorch-vulkan", backends["pushed-vulkan"])
        assertEquals(engine.runtimeId, backends["pushed"])
    }

    @Test
    fun scopedDiscoveryAndBrowserAcceptEncodedIdsWithoutExposingOtherModels() = serve { http, _ ->
        assertEquals(2, json(http.getKey("/v1/models").bodyAsText())["data"]!!.jsonArray.size)
        for ((path, id) in listOf("alpha" to "alpha", "first" to "alpha", "vendor%2Fbeta" to "vendor/beta")) {
            val base = "/models/$path"
            assertEquals(HttpStatusCode.Unauthorized, http.get("$base/v1/models").status)
            val found = json(http.getKey("$base/v1/models").bodyAsText())["data"]!!.jsonArray
            assertEquals(listOf(id), found.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            val page = http.get("$base/")
            assertEquals(HttpStatusCode.OK, page.status)
            assertTrue(page.bodyAsText().contains("ExecuServe"))
            assertFalse(page.bodyAsText().contains("sk-one"))
            assertEquals("DENY", page.headers["X-Frame-Options"])
        }
        assertEquals(HttpStatusCode.NotFound, http.getKey("/models/missing/v1/models").status)
        assertEquals(HttpStatusCode.NotFound, http.get("/models/missing/").status)
        assertEquals(HttpStatusCode.BadRequest, http.getKey("/models/alpha/v1/models/second").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            http.get("/models/alpha/") {
                headers.remove(HttpHeaders.Host)
                header(HttpHeaders.Host, "attacker.example")
            }.status,
        )
    }

    @Test
    fun allProtocolsAcceptAliasesAndRejectCrossModelBodiesBeforeExecution() = serve { http, engine ->
        val protocols = listOf(
            "chat/completions" to body("first"),
            "completions" to """{"model":"first","prompt":"Hi","max_tokens":20}""",
            "responses" to """{"model":"first","input":"Hi","max_output_tokens":20}""",
            "messages" to body("first"),
        )
        for ((route, body) in protocols) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                http.post("/models/alpha/v1/$route") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status,
            )
            val response = http.send("/models/alpha/v1/$route", body, route == "messages")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("alpha", json(response.bodyAsText())["model"]!!.jsonPrimitive.content)
            val before = engine.status.value.totals.completed
            val mismatch = http.send("/models/alpha/v1/$route", body.replace("first", "second"), route == "messages")
            assertEquals(HttpStatusCode.BadRequest, mismatch.status, mismatch.bodyAsText())
            if (route == "messages") {
                assertEquals("error", json(mismatch.bodyAsText())["type"]!!.jsonPrimitive.content)
            } else {
                assertEquals("model_endpoint_mismatch", json(mismatch.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            }
            assertEquals(before, engine.status.value.totals.completed)
        }
        assertEquals(HttpStatusCode.BadRequest, http.send("/models/alpha/apply-template", body("second")).status)
        assertEquals(HttpStatusCode.BadRequest, http.send("/models/alpha/v1/execuserve/models/second/load", "{}").status)
        assertEquals(HttpStatusCode.BadRequest, http.send("/models/alpha/v1/execuserve/models/second/unload", "{}").status)
        assertEquals(HttpStatusCode.OK, http.send("/models/vendor%2Fbeta/v1/chat/completions", body("second")).status)
        val status = json(http.getKey("/models/alpha/v1/execuserve/status").bodyAsText())
        assertTrue(status["recent"]!!.jsonArray.all { it.jsonObject["model"]!!.jsonPrimitive.content == "alpha" })
    }

    @Test
    fun queryParametersCannotOverrideModelRoutesOrTurnGlobalRoutesIntoScopedRoutes() = serve { http, engine ->
        val global = json(http.getKey("/v1/models?hostedModel=alpha").bodyAsText())["data"]!!.jsonArray
        assertEquals(2, global.size)
        val scoped = json(http.getKey("/models/alpha/v1/models?hostedModel=second").bodyAsText())["data"]!!.jsonArray
        assertEquals(listOf("alpha"), scoped.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        val mismatch = http.send("/models/alpha/v1/chat/completions?hostedModel=second", body("second"))
        assertEquals(HttpStatusCode.BadRequest, mismatch.status)
        assertEquals(0, engine.status.value.resident.size)
        val model = http.getKey("/models/alpha/v1/models/first?id=second&hostedModel=second")
        assertEquals(HttpStatusCode.OK, model.status)
        assertEquals("alpha", json(model.bodyAsText())["id"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, http.send("/models/alpha/v1/execuserve/models/first/load?id=second&hostedModel=second", "{}").status)
        assertEquals(listOf("alpha"), engine.status.value.resident.map { it.id })
        assertEquals(HttpStatusCode.OK, http.send("/models/alpha/v1/execuserve/models/first/unload?id=second&hostedModel=second", "{}").status)
        assertTrue(engine.status.value.resident.isEmpty())
        assertEquals(HttpStatusCode.NotFound, http.get("/models/missing/?hostedModel=alpha").status)
    }

    @Test
    fun scopedStreamingUsesExistingSseContract() = serve { http, _ ->
        val response = http.send("/models/alpha/v1/chat/completions", body("first").dropLast(1) + ",\"stream\":true}")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Text.EventStream))
        assertTrue(response.bodyAsText().contains("data: [DONE]"))
        assertTrue(response.bodyAsText().contains("\"model\":\"alpha\""))
    }

    @Test
    fun responsesContinueAcrossAliasesButNeverSwitchModelsImplicitly() = serve { http, _ ->
        val first = http.send("/models/alpha/v1/responses", """{"model":"first","input":"Hi","max_output_tokens":20}""")
        val id = json(first.bodyAsText())["id"]!!.jsonPrimitive.content
        val continuation = """{"model":"alpha","previous_response_id":"$id","input":"Again","max_output_tokens":20}"""
        assertEquals(HttpStatusCode.OK, http.send("/v1/responses", continuation).status)
        for (base in listOf("/v1", "/models/second/v1")) {
            val refused = http.send("$base/responses", continuation.replace("\"alpha\"", "\"second\""))
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertEquals("previous_response_not_found", json(refused.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun anotherModelEndpointCannotBypassTheSameKeysOutstandingRequestLimit() {
        val latch = CountDownLatch(1)
        val runtime = FakeRuntime().also { it.hang = latch }
        serve(runtime, EngineConfig(maxPerClient = 1)) { http, engine ->
            coroutineScope {
                val first = async { http.send("/models/alpha/v1/chat/completions", body("first")) }
                try {
                    withTimeout(5_000) { while (engine.status.value.running == null) delay(5) }
                    withTimeout(5_000) { while (engine.status.value.running?.prefillStartedAtMs == 0L) delay(5) }
                    val live = json(http.getKey("/models/alpha/v1/execuserve/status").bodyAsText())["running"]!!.jsonObject
                    assertTrue(live["prefill_started_at_ms"]!!.jsonPrimitive.content.toLong() > 0)
                    assertTrue(live["prompt_chars"]!!.jsonPrimitive.content.toInt() > 0)
                    assertEquals(0, live["prefilled_chars"]!!.jsonPrimitive.content.toInt())
                    assertEquals("One", live["client"]!!.jsonPrimitive.content)
                    // Another key sees that the phone is busy, not whose request it is or its size.
                    val seen = http.get("/models/alpha/v1/execuserve/status") { header(HttpHeaders.Authorization, "Bearer sk-two") }
                    val theirs = json(seen.bodyAsText())["running"]!!.jsonObject
                    assertEquals("alpha", theirs["model"]!!.jsonPrimitive.content)
                    assertFalse("client" in theirs)
                    assertEquals(0, theirs["prompt_chars"]!!.jsonPrimitive.content.toInt())
                    val other = http.send("/models/second/v1/messages", body("second"), anthropic = true)
                    assertEquals(HttpStatusCode.TooManyRequests, other.status, other.bodyAsText())
                } finally {
                    latch.countDown()
                }
                assertEquals(HttpStatusCode.OK, first.await().status)
            }
        }
    }
}
