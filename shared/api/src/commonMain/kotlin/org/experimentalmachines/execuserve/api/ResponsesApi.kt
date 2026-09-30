package org.experimentalmachines.execuserve.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `POST /v1/responses`: OpenAI's newer API, the one the Agents SDK and the codex CLI speak.
 * `input` stays raw because it is a string or an array of typed items (messages, function
 * calls, function call outputs, reasoning), read in the server's translator.
 */
@Serializable
data class ResponsesRequest(
    val model: String,
    val input: JsonElement,
    val instructions: String? = null,
    val tools: List<JsonObject>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
    val reasoning: JsonObject? = null,
    @SerialName("previous_response_id") val previousResponseId: String? = null,
    val text: JsonObject? = null,
    /** Keep this response for `previous_response_id`. OpenAI's default, and this server's. */
    val store: Boolean = true,
)

/** One output item of a response, in the order the model produced them. */
sealed interface OutputItem {
    val id: String

    fun toJson(status: String = "completed"): JsonObject

    data class Reasoning(override val id: String, val text: String) : OutputItem {
        override fun toJson(status: String): JsonObject = buildJsonObject {
            put("id", id)
            put("type", "reasoning")
            put("summary", JsonArray(emptyList()))
            putJsonArray("content") {
                if (text.isNotEmpty()) add(buildJsonObject { put("type", "reasoning_text"); put("text", text) })
            }
            put("status", status)
        }
    }

    data class Message(override val id: String, val text: String) : OutputItem {
        override fun toJson(status: String): JsonObject = buildJsonObject {
            put("id", id)
            put("type", "message")
            put("status", status)
            put("role", "assistant")
            putJsonArray("content") { if (status == "completed" || text.isNotEmpty()) add(textPart(text)) }
        }
    }

    data class FunctionCall(override val id: String, val callId: String, val name: String, val arguments: String) : OutputItem {
        override fun toJson(status: String): JsonObject = buildJsonObject {
            put("id", id)
            put("type", "function_call")
            put("call_id", callId)
            put("name", name)
            put("arguments", if (status == "completed") arguments else "")
            put("status", status)
        }
    }

    companion object {
        fun textPart(text: String): JsonObject = buildJsonObject {
            put("type", "output_text")
            put("text", text)
            put("annotations", JsonArray(emptyList()))
            put("logprobs", JsonArray(emptyList()))
        }
    }
}

object ResponseObjects {

    /**
     * A whole response object. [status] is `in_progress` for the opening stream events,
     * `completed`, or `incomplete` when the output budget ran out.
     */
    fun response(
        id: String,
        createdAt: Long,
        model: String,
        status: String,
        output: List<OutputItem>,
        usage: Usage?,
        request: ResponsesRequest,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("object", "response")
        put("created_at", createdAt)
        put("status", status)
        put("model", model)
        put("output", JsonArray(output.map { it.toJson() }))
        put("output_text", output.filterIsInstance<OutputItem.Message>().joinToString("") { it.text })
        put("error", JsonNull)
        if (status == "incomplete") {
            putJsonObject("incomplete_details") { put("reason", "max_output_tokens") }
        } else {
            put("incomplete_details", JsonNull)
        }
        put("instructions", request.instructions?.let(::JsonPrimitive) ?: JsonNull)
        put("max_output_tokens", request.maxOutputTokens?.let(::JsonPrimitive) ?: JsonNull)
        put("parallel_tool_calls", true)
        put("previous_response_id", request.previousResponseId?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("reasoning") {
            put("effort", request.reasoning?.get("effort") ?: JsonNull)
            put("summary", JsonNull)
        }
        put("store", request.store)
        put("temperature", request.temperature?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("text") { putJsonObject("format") { put("type", "text") } }
        put("tool_choice", request.toolChoice ?: JsonPrimitive("auto"))
        put("tools", JsonArray(request.tools.orEmpty()))
        put("top_p", JsonNull)
        put("truncation", "disabled")
        put("metadata", JsonObject(emptyMap()))
        put("usage", usage?.let(::usageJson) ?: JsonNull)
    }

    private fun usageJson(usage: Usage): JsonObject = buildJsonObject {
        put("input_tokens", usage.promptTokens)
        putJsonObject("input_tokens_details") { put("cached_tokens", usage.cachedTokens) }
        put("output_tokens", usage.completionTokens)
        putJsonObject("output_tokens_details") { put("reasoning_tokens", 0) }
        put("total_tokens", usage.promptTokens + usage.completionTokens)
    }
}

/** Server-sent events in the Responses API's framing: a named event and a typed payload. */
object TypedSse {
    fun event(type: String, payload: JsonObject): String = "event: $type\ndata: $payload\n\n"
}
