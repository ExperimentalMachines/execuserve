package org.experimentalmachines.execuserve.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
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
import kotlinx.serialization.json.JsonObject
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

class ResponsesStateTest {
    private val lane = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val alice = ApiKey("ka", "alice", "sk-alice")
    private val bob = ApiKey("kb", "bob", "sk-bob")
    private lateinit var runtime: FakeRuntime

    @AfterTest
    fun tearDown() {
        scope.cancel()
        lane.shutdownNow()
    }

    private fun serve(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        runtime = FakeRuntime(reply = { listOf("Hello", "<|im_end|>") })
        val models = listOf(ModelEntry("lfm", ModelFiles("/m/l.pte", "/m/l.json"), "lfm2.5", 1, 4096))
        val engine = Engine(runtime, StaticModelSource(models), lane.asCoroutineDispatcher(), scope).also { it.start() }
        val ctx = ServerContext(engine, ServerSettings(), StaticKeys(listOf(alice, bob)), { emptySet() }, "test", { 1_700_000_000 })
        application { execuServe(ctx) }
        block(createClient { defaultRequest { headers.append(HttpHeaders.Host, "localhost:8080") } })
    }

    private suspend fun HttpClient.respond(key: ApiKey, body: String) = post("/v1/responses") {
        header(HttpHeaders.Authorization, "Bearer ${key.secret}")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun aStoredResponseContinuesByIdAndHitsTheCache() = serve { http ->
        val first = json(http.respond(alice, """{"model":"lfm","input":"Hi","instructions":"Be brief."}""").bodyAsText())
        assertEquals("true", first["store"]!!.jsonPrimitive.content)
        val id = first["id"]!!.jsonPrimitive.content
        runtime.log.clear()
        val second = http.respond(alice, """{"model":"lfm","input":"Again","previous_response_id":"$id"}""")
        assertEquals(HttpStatusCode.OK, second.status)
        val body = json(second.bodyAsText())
        assertEquals(id, body["previous_response_id"]!!.jsonPrimitive.content)
        // Instructions are not inherited, so the prompt differs from the first turn's
        // system line and starts over; the cache is a separate matter from storage.
        assertTrue(runtime.log.any { it.startsWith("generate ") })
        val third = http.respond(alice, """{"model":"lfm","input":"Once more","previous_response_id":"${body["id"]!!.jsonPrimitive.content}"}""")
        val usage = json(third.bodyAsText())["usage"]!!.jsonObject
        // Turn three extends turn two exactly, as if the client had sent it all again.
        assertTrue(usage["input_tokens_details"]!!.jsonObject["cached_tokens"]!!.jsonPrimitive.content.toInt() > 0, usage.toString())
    }

    @Test
    fun anotherKeyCannotContinueIt() = serve { http ->
        val id = json(http.respond(alice, """{"model":"lfm","input":"Hi"}""").bodyAsText())["id"]!!.jsonPrimitive.content
        val stolen = http.respond(bob, """{"model":"lfm","input":"What did she say?","previous_response_id":"$id"}""")
        assertEquals(HttpStatusCode.BadRequest, stolen.status)
        assertEquals("previous_response_not_found", json(stolen.bodyAsText())["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun storeFalseKeepsNothing() = serve { http ->
        val id = json(http.respond(alice, """{"model":"lfm","input":"Hi","store":false}""").bodyAsText())["id"]!!.jsonPrimitive.content
        val next = http.respond(alice, """{"model":"lfm","input":"Again","previous_response_id":"$id"}""")
        assertEquals(HttpStatusCode.BadRequest, next.status)
    }
}

class ApplyTemplateTest {
    private val lane = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    @kotlin.test.AfterTest
    fun tearDown() {
        scope.cancel()
        lane.shutdownNow()
    }

    @Test
    fun rendersTheExactPromptWithoutRunningIt() = testApplication {
        val runtime = FakeRuntime()
        val models = listOf(ModelEntry("qwen3-1.7b", ModelFiles("/m/q.pte", "/m/q.json"), "qwen3", 1, 4096))
        val engine = Engine(runtime, StaticModelSource(models), lane.asCoroutineDispatcher(), scope).also { it.start() }
        val key = ApiKey("k", "k", "sk-k")
        application { execuServe(ServerContext(engine, ServerSettings(), StaticKeys(listOf(key)), { emptySet() }, "test", { 0 })) }
        val http = createClient { defaultRequest { headers.append(HttpHeaders.Host, "localhost:8080") } }
        val response = http.post("/apply-template") {
            header(HttpHeaders.Authorization, "Bearer ${key.secret}")
            contentType(ContentType.Application.Json)
            setBody("""{"model":"qwen3-1.7b","messages":[{"role":"user","content":"Hi"}]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val prompt = Json.parseToJsonElement(response.bodyAsText()).jsonObject["prompt"]!!.jsonPrimitive.content
        assertTrue(prompt.startsWith("<|im_start|>user\nHi<|im_end|>"), prompt)
        assertTrue(prompt.endsWith("<|im_start|>assistant\n") || "<think>" in prompt, prompt)
        assertTrue(runtime.log.isEmpty(), "rendering never touches the runtime")
    }
}
