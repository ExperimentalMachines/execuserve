package org.experimentalmachines.execuserve.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `POST /v1/messages`: Anthropic's Messages API, the one the Anthropic SDKs and Claude Code
 * speak. `content` and `system` stay raw because each is a string or an array of typed
 * blocks (text, tool_use, tool_result, thinking), read in the server's translator.
 */
@Serializable
data class MessagesRequest(
    val model: String,
    val messages: List<InputMessage>,
    /**
     * Required by Anthropic. Nullable here so that its absence is refused in Anthropic's own
     * words (`max_tokens: Field required`) rather than the decoder's.
     */
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val system: JsonElement? = null,
    val tools: List<ToolParam>? = null,
    @SerialName("tool_choice") val toolChoice: ToolChoiceParam? = null,
    @SerialName("stop_sequences") val stopSequences: List<String>? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
    val thinking: ThinkingParam? = null,
    @SerialName("output_config") val outputConfig: JsonObject? = null,
)

@Serializable
data class InputMessage(val role: String, val content: JsonElement)

/**
 * A tool offered to the model. The client's own tools have no type (or `custom`) and carry
 * [inputSchema]; Anthropic's built-in tools carry a versioned type and a schema only Claude
 * knows, so the server drops them.
 */
@Serializable
data class ToolParam(
    val name: String,
    val type: String? = null,
    val description: String? = null,
    @SerialName("input_schema") val inputSchema: JsonObject? = null,
) {
    val isCustom: Boolean get() = type == null || type == "custom"
}

/** Only the type is read: `auto` and `none` are honoured, and forcing a tool is refused. */
@Serializable
data class ToolChoiceParam(val type: String)

@Serializable
data class ThinkingParam(
    val type: String,
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
    /** "omitted" keeps the thinking blocks but not their text. */
    val display: String? = null,
)

/** One block of an assistant message, in the order the model produced them. */
sealed interface ContentBlock {
    val type: String

    fun toJson(): JsonObject

    /**
     * Anthropic signs thinking so that it can verify the blocks a client sends back. This
     * server verifies nothing (a client's thinking blocks are dropped on the way in, as the
     * templates drop client-sent reasoning), so the signature is empty rather than a value
     * that looks checkable.
     */
    data class Thinking(val thinking: String) : ContentBlock {
        override val type = "thinking"

        override fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            put("thinking", thinking)
            put("signature", "")
        }
    }

    data class Text(val text: String) : ContentBlock {
        override val type = "text"

        override fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            put("text", text)
        }
    }

    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock {
        override val type = "tool_use"

        override fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            put("id", id)
            put("name", name)
            put("input", input)
        }
    }
}

object MessageObjects {

    /** A whole message. [stopReason] is null only in the stream's opening `message_start`. */
    fun message(id: String, model: String, content: List<ContentBlock>, stopReason: String?, usage: Usage, stopSequence: String? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            put("content", JsonArray(content.map { it.toJson() }))
            put("stop_reason", stopReason)
            put("stop_sequence", stopSequence)
            put("usage", usage(usage))
        }

    /**
     * Anthropic splits the prompt three ways that sum to the whole: uncached input, cache
     * writes, and cache reads. The sequence cache is written as a side effect of every
     * request at no extra cost, so writes are always 0 and `input_tokens` is what was fed.
     */
    fun usage(usage: Usage): JsonObject = buildJsonObject {
        put("input_tokens", usage.promptTokens - usage.cachedTokens)
        put("cache_creation_input_tokens", 0)
        put("cache_read_input_tokens", usage.cachedTokens)
        put("output_tokens", usage.completionTokens)
    }

    /**
     * [error] in Anthropic's shape. The type follows the HTTP status, as Anthropic's does,
     * and the status stays the one the server already uses: an overloaded server answers 503
     * (not Anthropic's 529), which the SDKs also retry.
     */
    fun error(error: ApiError): JsonObject = buildJsonObject {
        put("type", "error")
        putJsonObject("error") {
            put("type", errorType(error.status))
            put("message", error.message)
        }
    }

    private fun errorType(status: Int): String = when (status) {
        HttpStatus.BAD_REQUEST -> "invalid_request_error"
        HttpStatus.UNAUTHORIZED -> "authentication_error"
        HttpStatus.FORBIDDEN -> "permission_error"
        HttpStatus.NOT_FOUND -> "not_found_error"
        HttpStatus.REQUEST_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT -> "timeout_error"
        HttpStatus.PAYLOAD_TOO_LARGE -> "request_too_large"
        HttpStatus.TOO_MANY_REQUESTS -> "rate_limit_error"
        HttpStatus.SERVICE_UNAVAILABLE -> "overloaded_error"
        else -> "api_error"
    }
}
