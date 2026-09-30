package org.experimentalmachines.execuserve.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Responses are built as JSON trees by hand rather than serialised from data classes,
 * because the exact presence of nulls matters and differs by field: OpenAI sends
 * `"finish_reason": null` on every chunk before the last, `"content": null` beside
 * `tool_calls`, and never sends a null `tool_calls`. One `explicitNulls` switch cannot say
 * all three, and a client that checks `"content" in delta` sees the difference.
 */
data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int = 0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("prompt_tokens", promptTokens)
        put("completion_tokens", completionTokens)
        put("total_tokens", promptTokens + completionTokens)
        putJsonObject("prompt_tokens_details") { put("cached_tokens", cachedTokens) }
    }
}

data class ToolCallOut(val id: String, val name: String, val argumentsJson: String)

/**
 * What the run cost, in llama.cpp's `timings` vocabulary so dashboards built for
 * `llama-server` read it unchanged. Milliseconds and tokens per second.
 */
data class Timings(
    val queueMs: Long,
    val loadMs: Long,
    val promptTokens: Int,
    val promptMs: Long,
    val predictedTokens: Int,
    val predictedMs: Long,
    val cachedTokens: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("queue_ms", queueMs)
        put("load_ms", loadMs)
        put("cache_n", cachedTokens)
        put("prompt_n", promptTokens)
        put("prompt_ms", promptMs)
        put("prompt_per_second", perSecond(promptTokens, promptMs))
        put("predicted_n", predictedTokens)
        put("predicted_ms", predictedMs)
        put("predicted_per_second", perSecond(predictedTokens, predictedMs))
    }

    private fun perSecond(tokens: Int, ms: Long): Double =
        if (ms <= 0 || tokens <= 0) 0.0 else (tokens * 1000.0 / ms * 100).toLong() / 100.0
}

object ChatResponses {

    fun completion(
        id: String,
        created: Long,
        model: String,
        content: String,
        reasoning: String?,
        toolCalls: List<ToolCallOut>,
        finishReason: String,
        usage: Usage,
        timings: Timings?,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "chat.completion")
        put("created", created)
        put("model", model)
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                putJsonObject("message") {
                    put("role", "assistant")
                    // OpenAI's own shape: content is null, not empty, when the turn only calls tools.
                    if (toolCalls.isNotEmpty() && content.isEmpty()) put("content", JsonNull) else put("content", content)
                    if (!reasoning.isNullOrEmpty()) put("reasoning_content", reasoning)
                    if (toolCalls.isNotEmpty()) put("tool_calls", toolCallsJson(toolCalls, withIndex = false))
                }
                put("logprobs", JsonNull)
                put("finish_reason", finishReason)
            }
        }
        put("usage", usage.toJson())
        timings?.let { put("timings", it.toJson()) }
    }

    /** One `chat.completion.chunk`. [delta] is built by the helpers below. */
    /**
     * A chunk with no choices, like the usage chunk, carrying how far the prompt has been
     * read: llama.cpp's `prompt_progress`, counted in characters, which this server knows
     * exactly, rather than tokens, which it would have to estimate.
     */
    fun progressChunk(id: String, created: Long, model: String, processed: Int, total: Int, cachedTokens: Int, timeMs: Long): JsonObject =
        buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            putJsonArray("choices") { }
            putJsonObject("prompt_progress") {
                put("unit", "characters")
                put("total", total)
                put("processed", processed)
                put("cache_tokens", cachedTokens)
                put("time_ms", timeMs)
            }
        }

    fun chunk(
        id: String,
        created: Long,
        model: String,
        delta: JsonObject,
        finishReason: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "chat.completion.chunk")
        put("created", created)
        put("model", model)
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                put("delta", delta)
                put("logprobs", JsonNull)
                put("finish_reason", finishReason)
            }
        }
    }

    /**
     * The trailing usage chunk that `stream_options.include_usage` asks for: an empty
     * choices array and the usage, sent after the finish chunk and before `[DONE]`.
     */
    fun usageChunk(id: String, created: Long, model: String, usage: Usage, timings: Timings?): JsonObject =
        buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", model)
            put("choices", JsonArray(emptyList()))
            put("usage", usage.toJson())
            timings?.let { put("timings", it.toJson()) }
        }

    fun roleDelta(): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", "")
    }

    fun contentDelta(text: String): JsonObject = buildJsonObject { put("content", text) }

    /** `reasoning_content`, the field DeepSeek introduced and most clients now read. */
    fun reasoningDelta(text: String): JsonObject = buildJsonObject { put("reasoning_content", text) }

    fun toolCallsDelta(calls: List<ToolCallOut>): JsonObject = buildJsonObject {
        put("tool_calls", toolCallsJson(calls, withIndex = true))
    }

    fun emptyDelta(): JsonObject = JsonObject(emptyMap())

    /**
     * Streaming tool calls carry an `index` (clients accumulate argument fragments by it);
     * complete messages do not. Each call arrives whole here, in one delta.
     */
    private fun toolCallsJson(calls: List<ToolCallOut>, withIndex: Boolean): JsonArray = buildJsonArray {
        calls.forEachIndexed { index, call ->
            addJsonObject {
                if (withIndex) put("index", index)
                put("id", call.id)
                put("type", "function")
                putJsonObject("function") {
                    put("name", call.name)
                    put("arguments", call.argumentsJson)
                }
            }
        }
    }
}

object CompletionResponses {

    fun completion(
        id: String,
        created: Long,
        model: String,
        text: String,
        finishReason: String,
        usage: Usage,
        timings: Timings?,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "text_completion")
        put("created", created)
        put("model", model)
        putJsonArray("choices") { addJsonObject { choice(text, finishReason) } }
        put("usage", usage.toJson())
        timings?.let { put("timings", it.toJson()) }
    }

    fun chunk(id: String, created: Long, model: String, text: String, finishReason: String?): JsonObject =
        buildJsonObject {
            put("id", id)
            put("object", "text_completion")
            put("created", created)
            put("model", model)
            putJsonArray("choices") { addJsonObject { choice(text, finishReason) } }
        }

    fun usageChunk(id: String, created: Long, model: String, usage: Usage): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "text_completion")
        put("created", created)
        put("model", model)
        put("choices", JsonArray(emptyList()))
        put("usage", usage.toJson())
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.choice(text: String, finishReason: String?) {
        put("text", text)
        put("index", 0)
        put("logprobs", JsonNull)
        put("finish_reason", finishReason)
    }
}

/** One installed model, as `/v1/models` lists it. The extra fields are ExecuServe's. */
data class ModelOut(
    val id: String,
    val created: Long,
    val contextLength: Int?,
    val loaded: Boolean,
    val family: String?,
    val sizeBytes: Long,
    val backend: String,
    val aliases: List<String>,
    /** The lab that released the weights, as OpenAI's `owned_by` means it. */
    val ownedBy: String = "execuserve",
    /**
     * What a client may ask of this model: `completions` always, `chat` with a known
     * template, `tools` and `reasoning` where the template supports them. An agent reads
     * this rather than discovering a refusal mid-task.
     */
    val capabilities: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "model")
        put("created", created)
        put("owned_by", ownedBy)
        put("context_length", contextLength)
        put("loaded", loaded)
        put("family", family)
        put("size_bytes", sizeBytes)
        put("backend", backend)
        putJsonArray("aliases") { aliases.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        putJsonArray("capabilities") { capabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    }
}

object ModelResponses {
    fun list(models: List<ModelOut>): JsonObject = buildJsonObject {
        put("object", "list")
        put("data", JsonArray(models.map { it.toJson() }))
    }
}

/** Server-sent-event framing, exactly as the OpenAI SDKs parse it. */
object Sse {
    fun data(json: JsonElement): String = "data: $json\n\n"

    const val DONE: String = "data: [DONE]\n\n"

    /** A comment line: every SSE parser skips it, and writing it detects a closed socket. */
    fun comment(text: String): String = ": ${text.replace('\n', ' ')}\n\n"
}
