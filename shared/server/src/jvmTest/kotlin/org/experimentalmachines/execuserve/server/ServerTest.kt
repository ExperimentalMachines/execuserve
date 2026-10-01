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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {

    private val laneExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val key = ApiKey("k1", "test client", "sk-test-123")

    @AfterTest
    fun tearDown() {
        scope.cancel()
        laneExecutor.shutdownNow()
    }

    private fun serve(
        runtime: FakeRuntime = FakeRuntime(),
        settings: ServerSettings = ServerSettings(),
        config: EngineConfig = EngineConfig(),
        window: Int = 4096,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val models = listOf(
            ModelEntry("qwen3-1.7b-8da4w-gptq-2k", ModelFiles("/m/q.pte", "/m/q.json"), "qwen3", 1, window, setOf("qwen3-1.7b")),
        )
        val engine = Engine(runtime, StaticModelSource(models), laneExecutor.asCoroutineDispatcher(), scope, config)
        engine.start()
        val ctx = ServerContext(engine, settings, StaticKeys(listOf(key)), { setOf("192.168.1.20") }, "test", { 1_700_000_000 })
        application { execuServe(ctx) }
        // Real HTTP/1.1 clients always send Host; Ktor's in-memory test client does not.
        http = createClient { defaultRequest { if (HttpHeaders.Host !in headers) headers.append(HttpHeaders.Host, "localhost:8080") } }
        block()
    }

    private lateinit var http: io.ktor.client.HttpClient

    private suspend fun ApplicationTestBuilder.chat(body: String, auth: Boolean = true) =
        http.post("/v1/chat/completions") {
            if (auth) header(HttpHeaders.Authorization, "Bearer ${key.secret}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val hello = """{"model":"qwen3-1.7b","messages":[{"role":"user","content":"Hi"}]"""

    @Test
    fun healthAndChatNeedNoKey() = serve {
        assertEquals(HttpStatusCode.OK, http.get("/health").status)
        val page = http.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.contentType()!!.match(ContentType.Text.Html))
        assertTrue("ExecuServe" in page.bodyAsText())
        assertFalse(key.secret in page.bodyAsText())
        assertTrue("connect-src 'self'" in page.headers["Content-Security-Policy"]!!)
        assertEquals("DENY", page.headers["X-Frame-Options"])
        for ((path, type) in listOf("chat.css" to "text/css", "chat.js" to "application/javascript", "mark.svg" to "image/svg+xml")) {
            val asset = http.get("/chat/assets/$path")
            assertEquals(HttpStatusCode.OK, asset.status)
            assertTrue(asset.headers["Content-Type"]!!.startsWith(type))
            assertEquals("nosniff", asset.headers["X-Content-Type-Options"])
            assertEquals("no-cache", asset.headers["Cache-Control"])
            assertFalse(key.secret in asset.bodyAsText())
        }
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/models").status)
    }

    @Test
    fun chatMarkup() = serve {
        // style-src 'self' drops inline styles without a word, and the mark's die fell back to
        // black that way once: the page and the mark colour only through chat.css and attributes.
        for (path in listOf("/", "/chat/assets/mark.svg")) {
            val body = http.get(path).bodyAsText()
            assertFalse(Regex("""\sstyle\s*=""").containsMatchIn(body), "$path has an inline style attribute")
            assertFalse("<style" in body, "$path has a <style> element")
        }
        assertTrue("""fill="#EE4C2C"""" in http.get("/").bodyAsText(), "the mark's die is ember")
    }

    @Test
    fun chatAssetsRetainHostProtection() = serve {
        for (path in listOf("/", "/chat/assets/chat.js", "/chat/assets/chat.css", "/chat/assets/mark.svg")) {
            assertEquals(HttpStatusCode.Forbidden, http.get(path) { header(HttpHeaders.Host, "attacker.example") }.status)
        }
        assertEquals(HttpStatusCode.NotFound, http.get("/chat/assets/missing.js").status)
    }

    @Test
    fun everyApiRouteNeedsAKey() = serve {
        assertEquals(HttpStatusCode.Unauthorized, http.get("/v1/models").status)
        assertEquals(HttpStatusCode.Unauthorized, chat("$hello}", auth = false).status)
        val wrong = http.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer nope") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("invalid_api_key", json(wrong.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun openLoopbackLetsLocalAppsInWithoutAKey() = serve(settings = ServerSettings(openLoopback = true)) {
        assertEquals(HttpStatusCode.OK, chat("$hello}", auth = false).status)
    }

    @Test
    fun aForeignHostHeaderIsRefused() = serve {
        val rebound = http.get("/v1/models") {
            header(HttpHeaders.Host, "attacker.example")
            header(HttpHeaders.Authorization, "Bearer ${key.secret}")
        }
        assertEquals(HttpStatusCode.Forbidden, rebound.status)
    }

    @Test
    fun theDevicesOwnAddressIsAcceptedInNetworkMode() = serve(settings = ServerSettings(bind = BindMode.NETWORK)) {
        val direct = http.get("/v1/models") {
            header(HttpHeaders.Host, "192.168.1.20:8080")
            header(HttpHeaders.Authorization, "Bearer ${key.secret}")
        }
        assertEquals(HttpStatusCode.OK, direct.status)
    }

    @Test
    fun modelsAreListedInOpenAisShape() = serve {
        val body = json(http.get("/v1/models") { header(HttpHeaders.Authorization, "Bearer ${key.secret}") }.bodyAsText())
        assertEquals("list", body["object"]!!.jsonPrimitive.content)
        val model = body["data"]!!.jsonArray.single().jsonObject
        assertEquals("qwen3-1.7b-8da4w-gptq-2k", model["id"]!!.jsonPrimitive.content)
        assertEquals("model", model["object"]!!.jsonPrimitive.content)
        assertEquals("execuserve", model["owned_by"]!!.jsonPrimitive.content)
    }

    @Test
    fun aChatCompletionHasOpenAisShape() = serve {
        val response = chat("""$hello,"top_p":0.9,"seed":3}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("seed, top_p", response.headers["x-execuserve-ignored"])
        val body = json(response.bodyAsText())
        assertEquals("chat.completion", body["object"]!!.jsonPrimitive.content)
        assertTrue(body["id"]!!.jsonPrimitive.content.startsWith("chatcmpl-"))
        val choice = body["choices"]!!.jsonArray.single().jsonObject
        assertEquals("Hello world", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals("qwen3-1.7b-8da4w-gptq-2k", body["model"]!!.jsonPrimitive.content)
        val usage = body["usage"]!!.jsonObject
        assertEquals(3, usage["completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["timings"]!!.jsonObject["first_token_ms"]!!.jsonPrimitive.content.toLong() >= 0)
    }

    @Test
    fun aStreamIsFramedAsTheSdksExpect() = serve(runtime = FakeRuntime().apply { tokenDelayMs = 5 }) {
        val text = chat("""$hello,"stream":true,"stream_options":{"include_usage":true}}""").bodyAsText()
        val events = text.split("\n\n").filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }
        assertEquals("[DONE]", events.last())
        val chunks = events.dropLast(1).map { json(it) }
        val first = chunks.first()["choices"]!!.jsonArray[0].jsonObject
        assertEquals("assistant", first["delta"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        val content = chunks.flatMap { it["choices"]!!.jsonArray }
            .mapNotNull { it.jsonObject["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.content }
            .joinToString("")
        assertEquals("Hello world", content)
        val finish = chunks.dropLast(1).last()["choices"]!!.jsonArray[0].jsonObject
        assertEquals("stop", finish["finish_reason"]!!.jsonPrimitive.content)
        val usage = chunks.last()
        assertTrue(usage["choices"]!!.jsonArray.isEmpty())
        assertTrue("usage" in usage)
        val firstTokenMs = usage["timings"]!!.jsonObject["first_token_ms"]!!.jsonPrimitive.content.toLong()
        assertTrue(firstTokenMs > 0, "The first token includes the runtime's initial token delay")
        val status = json(http.get("/v1/execuserve/status") { header(HttpHeaders.Authorization, "Bearer ${key.secret}") }.bodyAsText())
        assertEquals(firstTokenMs, status["recent"]!!.jsonArray.first().jsonObject["first_token_ms"]!!.jsonPrimitive.content.toLong())
        assertTrue(chunks.all { it["id"] == chunks.first()["id"] })
    }

    @Test
    fun toolCallsComeBackAsStructure() = serve(
        runtime = FakeRuntime(
            reply = { listOf("<tool_call>", "\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Manila\"}}\n", "</tool_call>", "<|im_end|>") },
        ),
    ) {
        val body = json(
            chat(
                """{"model":"qwen3-1.7b","messages":[{"role":"user","content":"Weather?"}],
                "tools":[{"type":"function","function":{"name":"get_weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}]}""",
            ).bodyAsText(),
        )
        val choice = body["choices"]!!.jsonArray.single().jsonObject
        assertEquals("tool_calls", choice["finish_reason"]!!.jsonPrimitive.content)
        val message = choice["message"]!!.jsonObject
        assertEquals(JsonNull, message["content"])
        val call = message["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("get_weather", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city": "Manila"}""", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun anAssistantToolCallInHistoryIsWrittenInTheFamilysSyntax() {
        val runtime = FakeRuntime()
        serve(runtime = runtime) {
            chat(
                """{"model":"qwen3-1.7b","messages":[
                {"role":"user","content":"Weather?"},
                {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Manila\"}"}}]},
                {"role":"tool","tool_call_id":"call_1","content":"31C"}],
                "tools":[{"type":"function","function":{"name":"get_weather","parameters":{"type":"object"}}}]}""",
            )
        }
        val prompt = runtime.log.first { it.startsWith("generate") || it.startsWith("prefill") }
        val all = runtime.log.filter { it.startsWith("generate") || it.startsWith("prefill") }.joinToString("")
        assertTrue("<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"city\":\"Manila\"}}\n</tool_call>" in all, prompt)
        assertTrue("<tool_response>\n31C\n</tool_response>" in all, all)
    }

    @Test
    fun anUnknownModelIsA404() = serve {
        val response = chat("""{"model":"gpt-4o","messages":[{"role":"user","content":"Hi"}]}""")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("model_not_found", json(response.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun unsupportedParametersAreNamed() = serve {
        val response = chat("""$hello,"n":2}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = json(response.bodyAsText())["error"]!!.jsonObject
        assertEquals("unsupported_parameter", error["code"]!!.jsonPrimitive.content)
        assertEquals("n", error["param"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, chat("""$hello,"response_format":{"type":"json_object"}}""").status)
        assertEquals(HttpStatusCode.BadRequest, chat("""$hello,"tool_choice":"required"}""").status)
    }

    @Test
    fun malformedAndOversizedBodiesAreRefused() = serve(settings = ServerSettings(maxBodyBytes = 200)) {
        assertEquals(HttpStatusCode.BadRequest, chat("{not json").status)
        assertEquals(HttpStatusCode.BadRequest, chat("""{"model":"qwen3-1.7b"}""").status)
        val big = """{"model":"qwen3-1.7b","messages":[{"role":"user","content":"${"x".repeat(500)}"}]}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, chat(big).status)
    }

    @Test
    fun aFullServerIs503WithRetryAfter() {
        val runtime = FakeRuntime()
        val gate = CountDownLatch(1)
        runtime.hang = gate
        serve(runtime = runtime, config = EngineConfig(maxQueued = 0, maxPerClient = 10)) {
            val response = chat("$hello}")
            // The server is full, not this client over its share: 429 is for the latter.
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.headers[HttpHeaders.RetryAfter]!!.toInt() >= 1)
            gate.countDown()
        }
    }

    @Test
    fun aStreamThatFailsBeforeItsFirstTokenStillGetsAStatus() = serve(runtime = FakeRuntime(window = 40), window = 40) {
        val response = chat("""{"model":"qwen3-1.7b","stream":true,"messages":[{"role":"user","content":"${"word ".repeat(30)}"}]}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("context_length_exceeded", json(response.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun rawCompletionsWork() = serve(runtime = FakeRuntime(reply = { listOf(" there", "<|im_end|>") })) {
        val response = http.post("/v1/completions") {
            header(HttpHeaders.Authorization, "Bearer ${key.secret}")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"qwen3-1.7b","prompt":"Hello","max_tokens":null}""")
        }
        val body = json(response.bodyAsText())
        assertEquals("text_completion", body["object"]!!.jsonPrimitive.content)
        assertEquals(" there", body["choices"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun statusReportsTheLane() = serve {
        chat("$hello}")
        val status = json(http.get("/v1/execuserve/status") { header(HttpHeaders.Authorization, "Bearer ${key.secret}") }.bodyAsText())
        assertEquals("idle", status["lane"]!!.jsonPrimitive.content)
        assertEquals(1, status["totals"]!!.jsonObject["completed"]!!.jsonPrimitive.content.toInt())
        assertFalse(status["recent"]!!.jsonArray.isEmpty())
    }

    @Test
    fun hostHeadersAreParsed() {
        assertEquals("::1", Hosts.hostOf("[::1]:8080"))
        assertEquals("phone.tail1234.ts.net", Hosts.hostOf("Phone.tail1234.ts.net:8080"))
        assertEquals("fe80::1", Hosts.hostOf("fe80::1"))
        assertEquals("localhost", Hosts.hostOf("localhost.:8080"))
        assertEquals("2001:db8::5", Hosts.hostOf("[2001:0db8:0:0:0:0:0:5]:8080"))
        assertTrue(Hosts.allowed("[2001:0DB8::5]:80", setOf("2001:db8:0:0:0:0:0:5"), ServerSettings(bind = BindMode.NETWORK)))
        assertFalse(Hosts.allowed("[2001:db8::6]", setOf("2001:db8::5"), ServerSettings(bind = BindMode.NETWORK)))
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "abcd"))
    }
}
