package org.experimentalmachines.execuserve.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.routing.post
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias Auth = HttpRequestBuilder.() -> Unit

class MessagesTest {

    private val laneExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val key = ApiKey("k1", "test client", "sk-test-123")
    private lateinit var http: HttpClient

    @AfterTest
    fun tearDown() {
        scope.cancel()
        laneExecutor.shutdownNow()
    }

    private fun serve(runtime: FakeRuntime = FakeRuntime(), window: Int = 4096, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val models = listOf(
            ModelEntry("qwen3-1.7b-8da4w-gptq-2k", ModelFiles("/m/q.pte", "/m/q.json"), "qwen3", 1, window, setOf("qwen3-1.7b")),
        )
        val engine = Engine(runtime, StaticModelSource(models), laneExecutor.asCoroutineDispatcher(), scope)
        engine.start()
        val ctx = ServerContext(engine, ServerSettings(), StaticKeys(listOf(key)), { setOf("192.168.1.20") }, "test", { 1_700_000_000 })
        application {
            execuServe(ctx)
            // Registered here until Routes.kt registers it; a route registered twice is
            // answered by the first handler and the second is skipped.
        }
        http = createClient { defaultRequest { if (HttpHeaders.Host !in headers) headers.append(HttpHeaders.Host, "localhost:8080") } }
        block()
    }

    private suspend fun messages(body: String, auth: Auth? = apiKey): HttpResponse = http.post("/v1/messages") {
        auth?.invoke(this)
        header("anthropic-version", "2023-06-01")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private val apiKey: Auth = { header("x-api-key", key.secret) }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

    private fun errorOf(text: String): JsonObject {
        val body = json(text)
        assertEquals("error", body.str("type"), text)
        return body.obj("error")
    }

    /** Every generate and prefill the runtime saw, as one string: the prompt as fed. */
    private fun fed(runtime: FakeRuntime) = runtime.log
        .filter { it.startsWith("generate ") || it.startsWith("prefill ") }
        .joinToString("") { it.substringAfter(' ') }

    /** A stream as (event name, payload) pairs, checking each `event:` names its `data:`. */
    private fun events(text: String): List<Pair<String, JsonObject>> = text.split("\n\n").filter { it.isNotBlank() }.map { block ->
        val lines = block.lines()
        val name = lines.single { it.startsWith("event: ") }.removePrefix("event: ")
        val data = json(lines.single { it.startsWith("data: ") }.removePrefix("data: "))
        assertEquals(name, data.str("type"), block)
        name to data
    }

    private val hello = """{"model":"qwen3-1.7b","max_tokens":64,"messages":[{"role":"user","content":"Hi"}]"""

    private val weatherTool =
        """{"name":"get_weather","description":"Weather","input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}"""

    private val toolCall = listOf("<tool_call>", "\n{\"name\": \"get_weather\", \"arguments\": {\"city\": \"Manila\"}}\n", "</tool_call>", "<|im_end|>")

    @Test
    fun aKeyIsAcceptedAsXApiKeyOrBearerAndNothingElse() = serve {
        assertEquals(HttpStatusCode.OK, messages("$hello}").status)
        assertEquals(HttpStatusCode.OK, messages("$hello}", auth = { header(HttpHeaders.Authorization, "Bearer ${key.secret}") }).status)
        val wrongKey: Auth = { header("x-api-key", "nope") }
        val wrongBearer: Auth = { header(HttpHeaders.Authorization, "Bearer nope") }
        for (auth in listOf(null, wrongKey, wrongBearer)) {
            val refused = messages("$hello}", auth = auth)
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            val error = errorOf(refused.bodyAsText())
            assertEquals("authentication_error", error.str("type"))
            assertTrue("x-api-key" in error.str("message"))
        }
    }

    @Test
    fun aTextReplyHasAnthropicsShape() = serve {
        val response = messages("""$hello,"top_k":5,"metadata":{"user_id":"u1"},"container":null,"inference_geo":"us"}""")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("metadata, top_k, inference_geo", response.headers["x-execuserve-ignored"])
        val body = json(response.bodyAsText())
        assertEquals("message", body.str("type"))
        assertEquals("assistant", body.str("role"))
        assertTrue(body.str("id").startsWith("msg_"))
        assertEquals("qwen3-1.7b-8da4w-gptq-2k", body.str("model"))
        val block = body["content"]!!.jsonArray.single().jsonObject
        assertEquals("text", block.str("type"))
        assertEquals("Hello world", block.str("text"))
        assertEquals("end_turn", body.str("stop_reason"))
        assertEquals(JsonNull, body["stop_sequence"])
        val usage = body.obj("usage")
        assertEquals(3, usage["output_tokens"]!!.jsonPrimitive.int)
        assertTrue(usage["input_tokens"]!!.jsonPrimitive.int > 0)
        assertEquals(0, usage["cache_read_input_tokens"]!!.jsonPrimitive.int)
        assertEquals(0, usage["cache_creation_input_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun aSystemPromptAsStringOrBlocksRendersAsChatCompletionsDoes() {
        val prompts = listOf(
            "/v1/messages" to """{"model":"qwen3-1.7b","max_tokens":64,"system":"Be brief.","messages":[{"role":"user","content":"Hi"}]}""",
            "/v1/messages" to """{"model":"qwen3-1.7b","max_tokens":64,"system":[{"type":"text","text":"Be brief.","cache_control":{"type":"ephemeral"}}],
                "messages":[{"role":"user","content":[{"type":"text","text":"Hi"}]}]}""",
            "/v1/chat/completions" to
                // Thinking off on both: absent on the Messages API means off.
                """{"model":"qwen3-1.7b","max_tokens":64,"chat_template_kwargs":{"enable_thinking":false},"messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"Hi"}]}""",
        ).map { (path, body) -> promptFor(path, body) }
        assertTrue("<|im_start|>system\nBe brief.<|im_end|>" in prompts[0], prompts[0])
        assertEquals(prompts[2], prompts[0])
        assertEquals(prompts[2], prompts[1])
    }

    /** The prompt one request feeds a fresh runtime. */
    private fun promptFor(path: String, body: String, reply: List<String> = listOf("Ok", "<|im_end|>")): String {
        val runtime = FakeRuntime(reply = { reply })
        serve(runtime = runtime) {
            val response = http.post(path) {
                header("x-api-key", key.secret)
                header(HttpHeaders.Authorization, "Bearer ${key.secret}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
        return fed(runtime)
    }

    @Test
    fun aToolLoopRendersTheSamePromptAsChatCompletions() {
        val anthropic = promptFor(
            "/v1/messages",
            """{"model":"qwen3-1.7b","max_tokens":64,"tools":[$weatherTool],"messages":[
            {"role":"user","content":"Weather?"},
            {"role":"assistant","content":[{"type":"thinking","thinking":"Use the tool.","signature":""},
                {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Manila"}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":[{"type":"text","text":"31C"}]}]}]}""",
        )
        val chat = promptFor(
            "/v1/chat/completions",
            """{"model":"qwen3-1.7b","max_tokens":64,"chat_template_kwargs":{"enable_thinking":false},
            "tools":[{"type":"function","function":{"name":"get_weather","description":"Weather","parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}],
            "messages":[{"role":"user","content":"Weather?"},
            {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Manila\"}"}}]},
            {"role":"tool","tool_call_id":"call_1","content":"31C"}]}""",
        )
        assertTrue("<tool_call>\n{\"name\": \"get_weather\", \"arguments\": {\"city\":\"Manila\"}}\n</tool_call>" in anthropic, anthropic)
        assertTrue("<tool_response>\n31C\n</tool_response>" in anthropic, anthropic)
        assertEquals(chat, anthropic)
    }

    @Test
    fun aFailedToolRunIsMarkedForTheModel() {
        val prompt = promptFor(
            "/v1/messages",
            """{"model":"qwen3-1.7b","max_tokens":64,"tools":[$weatherTool],"messages":[
            {"role":"user","content":"Weather?"},
            {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Manila"}}]},
            {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"timed out","is_error":true}]}]}""",
        )
        assertTrue("<tool_response>\nError: timed out\n</tool_response>" in prompt, prompt)
    }

    @Test
    fun aToolLoopsSecondTurnReadsTheCache() {
        var turn = 0
        val runtime = FakeRuntime(reply = { if (++turn == 1) toolCall else listOf("It", " is", " 31C", ".", "<|im_end|>") })
        serve(runtime = runtime) {
            val user = """{"role":"user","content":"Weather in Manila?"}"""
            val first = json(messages("""{"model":"qwen3-1.7b","max_tokens":64,"tools":[$weatherTool],"messages":[$user]}""").bodyAsText())
            assertEquals("tool_use", first.str("stop_reason"))
            val call = first["content"]!!.jsonArray.single().jsonObject
            assertEquals("tool_use", call.str("type"))
            assertTrue(call.str("id").startsWith("toolu_"), call.toString())
            assertEquals("get_weather", call.str("name"))
            assertEquals("Manila", call.obj("input").str("city"))

            runtime.log.clear()
            // What the SDK sends back: the assistant's content as it came, then the result.
            val second = messages(
                """{"model":"qwen3-1.7b","max_tokens":64,"tools":[$weatherTool],"messages":[$user,
                {"role":"assistant","content":${first["content"]}},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"${call.str("id")}","content":"31C"}]}]}""",
            )
            val body = json(second.bodyAsText())
            assertEquals("It is 31C.", body["content"]!!.jsonArray.single().jsonObject.str("text"))
            assertFalse("reset" in runtime.log, runtime.log.toString())
            val usage = body.obj("usage")
            val cached = usage["cache_read_input_tokens"]!!.jsonPrimitive.int
            assertTrue(cached > 0, usage.toString())
            // Only the tool result and the next turn's opener were fed; the rest was read.
            assertTrue(usage["input_tokens"]!!.jsonPrimitive.int < cached, usage.toString())
            assertTrue(fed(runtime).startsWith("<|im_end|>"), fed(runtime))
        }
    }

    @Test
    fun aTextStreamIsFramedAsAnthropicsEvents() = serve {
        val response = messages("""$hello,"stream":true}""")
        assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
        val events = events(response.bodyAsText())
        assertEquals(
            listOf("message_start", "content_block_start") + List(events.size - 5) { "content_block_delta" } +
                listOf("content_block_stop", "message_delta", "message_stop"),
            events.map { it.first },
        )
        val start = events.first().second.obj("message")
        assertTrue(start.str("id").startsWith("msg_"))
        assertEquals(JsonArray(emptyList()), start["content"])
        assertEquals(JsonNull, start["stop_reason"])
        assertEquals("text", events[1].second.obj("content_block").str("type"))
        val deltas = events.filter { it.first == "content_block_delta" }.map { it.second }
        assertTrue(deltas.all { it["index"]!!.jsonPrimitive.int == 0 && it.obj("delta").str("type") == "text_delta" })
        assertEquals("Hello world", deltas.joinToString("") { it.obj("delta").str("text") })
        val end = events.first { it.first == "message_delta" }.second
        assertEquals("end_turn", end.obj("delta").str("stop_reason"))
        assertEquals(JsonNull, end.obj("delta")["stop_sequence"])
        assertEquals(3, end.obj("usage")["output_tokens"]!!.jsonPrimitive.int)
        assertTrue(end.obj("usage")["input_tokens"]!!.jsonPrimitive.int > 0)
    }

    @Test
    fun aToolCallStreamsAsOneToolUseBlock() = serve(runtime = FakeRuntime(reply = { toolCall })) {
        val events = events(
            messages("""{"model":"qwen3-1.7b","max_tokens":64,"stream":true,"tools":[$weatherTool],"messages":[{"role":"user","content":"Weather?"}]}""")
                .bodyAsText(),
        )
        assertEquals(
            listOf("message_start", "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop"),
            events.map { it.first },
        )
        val block = events[1].second.obj("content_block")
        assertEquals("tool_use", block.str("type"))
        assertEquals("get_weather", block.str("name"))
        assertTrue(block.str("id").startsWith("toolu_"))
        assertEquals(JsonObject(emptyMap()), block["input"])
        val delta = events[2].second.obj("delta")
        assertEquals("input_json_delta", delta.str("type"))
        assertEquals("""{"city":"Manila"}""", delta.str("partial_json"))
        assertEquals("tool_use", events[4].second.obj("delta").str("stop_reason"))
    }

    @Test
    fun omittedThinkingKeepsTheBlockButNotTheText() {
        val reply = listOf("<think>", "\nPondering.\n", "</think>", "\n\nHi", "<|im_end|>")
        val thinking = """"thinking":{"type":"enabled","budget_tokens":1024,"display":"omitted"}"""
        serve(runtime = FakeRuntime(reply = { reply })) {
            val content = json(messages("""$hello,$thinking}""").bodyAsText())["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("thinking", "text"), content.map { it.str("type") })
            assertEquals("", content[0].str("thinking"))
            val streamed = messages("""$hello,$thinking,"stream":true}""").bodyAsText()
            assertTrue("thinking_delta" !in streamed, streamed)
            assertTrue("Pondering" !in streamed, streamed)
        }
    }

    @Test
    fun thinkingComesFirstInItsOwnBlock() {
        val reply = listOf("<think>", "\nPondering.\n", "</think>", "\n\nHi", "<|im_end|>")
        val thinking = """"thinking":{"type":"enabled","budget_tokens":1024}"""
        serve(runtime = FakeRuntime(reply = { reply })) {
            val response = messages("""$hello,$thinking}""")
            assertEquals("thinking.budget_tokens", response.headers["x-execuserve-ignored"])
            val content = json(response.bodyAsText())["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("thinking", "text"), content.map { it.str("type") })
            assertTrue("Pondering." in content[0].str("thinking"))
            assertEquals("", content[0].str("signature"))
            assertEquals("Hi", content[1].str("text"))

            val events = events(messages("""$hello,$thinking,"stream":true}""").bodyAsText())
            val starts = events.filter { it.first == "content_block_start" }.map { it.second }
            assertEquals(listOf(0, 1), starts.map { it["index"]!!.jsonPrimitive.int })
            assertEquals(listOf("thinking", "text"), starts.map { it.obj("content_block").str("type") })
            assertEquals("", starts[0].obj("content_block").str("signature"))
            val kinds = events.filter { it.first == "content_block_delta" }.map { it.second.obj("delta").str("type") }.distinct()
            assertEquals(listOf("thinking_delta", "text_delta"), kinds)
        }
    }

    @Test
    fun stopReasonsMapToAnthropicsNames() = serve(runtime = FakeRuntime(reply = { listOf("One", " two", " three", "<|im_end|>") })) {
        val cut = json(messages("""{"model":"qwen3-1.7b","max_tokens":1,"messages":[{"role":"user","content":"Count"}]}""").bodyAsText())
        assertEquals("max_tokens", cut.str("stop_reason"))
        val ended = json(messages("""{"model":"qwen3-1.7b","max_tokens":64,"messages":[{"role":"user","content":"Count"}]}""").bodyAsText())
        assertEquals("end_turn", ended.str("stop_reason"))
        val stopped = json(
            messages("""{"model":"qwen3-1.7b","max_tokens":64,"stop_sequences":[" two"],"messages":[{"role":"user","content":"Count"}]}""").bodyAsText(),
        )
        assertEquals("One", stopped["content"]!!.jsonArray.single().jsonObject.str("text"))
        assertEquals("stop_sequence", stopped.str("stop_reason"))
        assertEquals(" two", stopped.str("stop_sequence"))
    }

    @Test
    fun forcedToolChoiceImagesAndAMissingMaxTokensAre400s() = serve {
        val refusals = mapOf(
            """{"model":"qwen3-1.7b","max_tokens":64,"tools":[$weatherTool],"tool_choice":{"type":"any"},"messages":[{"role":"user","content":"Hi"}]}""" to
                "cannot force",
            """{"model":"qwen3-1.7b","max_tokens":64,"tools":[$weatherTool],"tool_choice":{"type":"tool","name":"get_weather"},""" +
                """"messages":[{"role":"user","content":"Hi"}]}""" to
                "cannot force",
            """{"model":"qwen3-1.7b","max_tokens":64,"messages":[{"role":"user","content":""" +
                """[{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}]}]}""" to
                "'image'",
            """{"model":"qwen3-1.7b","max_tokens":64,"messages":[{"role":"user","content":""" +
                """[{"type":"document","source":{"type":"text","media_type":"text/plain","data":"x"}}]}]}""" to
                "'document'",
            """{"model":"qwen3-1.7b","messages":[{"role":"user","content":"Hi"}]}""" to "max_tokens: Field required",
            """{"model":"qwen3-1.7b","max_tokens":64,"messages":[{"role":"user","content":"Hi"},{"role":"assistant","content":"The answer is"}]}""" to
                "Prefilling",
        )
        for ((body, words) in refusals) {
            val response = messages(body)
            val text = response.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, response.status, text)
            val error = errorOf(text)
            assertEquals("invalid_request_error", error.str("type"))
            assertTrue(words in error.str("message"), text)
        }
        assertEquals(HttpStatusCode.OK, messages("""$hello,"tools":[$weatherTool],"tool_choice":{"type":"none"}}""").status)
    }

    @Test
    fun anUnknownModelIsANotFoundError() = serve {
        val response = messages("""{"model":"claude-opus-5","max_tokens":64,"messages":[{"role":"user","content":"Hi"}]}""")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found_error", errorOf(response.bodyAsText()).str("type"))
    }

    @Test
    fun aStreamThatFailsBeforeItsFirstTokenStillGetsAStatus() = serve(runtime = FakeRuntime(window = 40), window = 40) {
        val response = messages("""{"model":"qwen3-1.7b","max_tokens":8,"stream":true,"messages":[{"role":"user","content":"${"word ".repeat(30)}"}]}""")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request_error", errorOf(response.bodyAsText()).str("type"))
    }

    @Test
    fun aFailureMidStreamIsAnErrorEvent() {
        // The first fragment streams, then the runtime throws, as a native generate can.
        val failing = object : AbstractList<String>() {
            override val size = 3
            override fun get(index: Int): String = if (index == 0) "Hello" else error("runtime fell over")
        }
        serve(runtime = FakeRuntime(reply = { failing })) {
            val response = messages("""$hello,"stream":true}""")
            assertEquals(HttpStatusCode.OK, response.status)
            val events = events(response.bodyAsText())
            assertEquals("text_delta", events.first { it.first == "content_block_delta" }.second.obj("delta").str("type"))
            val (name, error) = events.last()
            assertEquals("error", name)
            assertEquals("api_error", error.obj("error").str("type"))
            assertTrue("runtime fell over" in error.obj("error").str("message"))
            assertFalse(events.any { it.first == "message_stop" })
        }
    }
}
