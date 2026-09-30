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
import kotlinx.serialization.json.Json
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
            val runtime = FakeRuntime(prefillLength = 64)
            val models = listOf(ModelEntry("lfm", ModelFiles("/m/l.pte", "/m/l.json"), "lfm2.5", 1, 4096))
            val engine = Engine(runtime, StaticModelSource(models), lane.asCoroutineDispatcher(), scope).also { it.start() }
            application { execuServe(ServerContext(engine, ServerSettings(), StaticKeys(listOf(key)), { emptySet() }, "test", { 0 })) }
            val http = createClient { defaultRequest { headers.append(HttpHeaders.Host, "localhost:8080") } }
            val body = http.post("/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${key.secret}")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"lfm","stream":true,"return_progress":$returnProgress,"messages":[{"role":"user","content":"${"word ".repeat(100)}"}]}""")
            }.bodyAsText()
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
}
