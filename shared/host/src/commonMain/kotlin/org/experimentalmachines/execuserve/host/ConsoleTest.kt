package org.experimentalmachines.execuserve.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.experimentalmachines.execuserve.engine.FinishReason

/** What a console test request came back with, measured on the client's side of the socket. */
data class TestResult(
    val firstTokenMs: Long,
    val totalMs: Long,
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int,
    val decodeTokensPerSecond: Double,
    val finish: FinishReason?,
    /** Uncached prompt tokens read per second, as the server measured it; 0 when not reported. */
    val prefillTokensPerSecond: Double = 0.0,
)

/** The server said no, in OpenAI's error shape; [message] is its own words. */
class TestFailure(val status: Int?, message: String) : Exception(message)

/**
 * Small pieces of the console's own HTTP use of its server, shared by every platform: the
 * variables an SDK reads, and the server's error message out of its error body.
 */
object ConsoleTest {
    /** What most OpenAI SDKs and tools read from the environment. */
    fun exports(baseUrl: String, key: String): String = "export OPENAI_BASE_URL=$baseUrl\nexport OPENAI_API_KEY=$key\n"

    /** The error message from an OpenAI-shaped error body, or the body itself, shortened. */
    fun errorMessage(body: String?): String = runCatching {
        val error = JSON.parseToJsonElement(body.orEmpty()).jsonObject["error"]!!.jsonObject
        (error["message"] as JsonPrimitive).content
    }.getOrNull()?.ifEmpty { null } ?: body.orEmpty().take(ERROR_CHARS)

    internal val JSON = Json { ignoreUnknownKeys = true }
    private const val ERROR_CHARS = 200
}

/**
 * Reads a chat completion, streamed as server-sent events or whole, into text as it arrives
 * and the figures at the end.
 */
class ReplyReader(private val onText: (content: String, reasoning: String) -> Unit) {
    private var usage: JsonObject? = null
    private var timings: JsonObject? = null
    private var finish: String? = null

    /** Reads one line of a stream; false once the stream has ended. */
    fun line(line: String): Boolean {
        if (!line.startsWith(DATA)) return true
        val payload = line.removePrefix(DATA)
        if (payload == DONE) return false
        val chunk = ConsoleTest.JSON.parseToJsonElement(payload).jsonObject
        if ("error" in chunk) throw TestFailure(null, ConsoleTest.errorMessage(payload))
        figures(chunk)
        val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return true
        choice.text("finish_reason").ifEmpty { null }?.let { finish = it }
        val delta = choice["delta"] as? JsonObject ?: return true
        emit(delta)
        return true
    }

    /** Reads a whole, non-streamed response. */
    fun whole(json: String) {
        val response = ConsoleTest.JSON.parseToJsonElement(json).jsonObject
        figures(response)
        val choice = response["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        finish = choice?.text("finish_reason")?.ifEmpty { null }
        (choice?.get("message") as? JsonObject)?.let(::emit)
    }

    fun result(firstTokenMs: Long, totalMs: Long) = TestResult(
        firstTokenMs = firstTokenMs,
        totalMs = totalMs,
        promptTokens = usage.int("prompt_tokens"),
        completionTokens = usage.int("completion_tokens"),
        cachedTokens = (usage?.get("prompt_tokens_details") as? JsonObject).int("cached_tokens"),
        decodeTokensPerSecond = (timings?.get("predicted_per_second") as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        finish = FinishReason.entries.firstOrNull { it.wire == finish },
        prefillTokensPerSecond = (timings?.get("prompt_per_second") as? JsonPrimitive)?.doubleOrNull ?: 0.0,
    )

    private fun figures(json: JsonObject) {
        (json["usage"] as? JsonObject)?.let { usage = it }
        (json["timings"] as? JsonObject)?.let { timings = it }
    }

    private fun emit(message: JsonObject) {
        val content = message.text("content")
        val reasoning = message.text("reasoning_content")
        if (content.isNotEmpty() || reasoning.isNotEmpty()) onText(content, reasoning)
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject?.int(key: String): Int = (this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0

    private companion object {
        const val DATA = "data: "
        const val DONE = "[DONE]"
    }
}
