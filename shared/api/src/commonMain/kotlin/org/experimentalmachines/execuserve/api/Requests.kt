package org.experimentalmachines.execuserve.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * `POST /v1/chat/completions`, as OpenAI defines it, plus the two extensions every local
 * server has converged on for reasoning models (`chat_template_kwargs`, `reasoning_effort`).
 *
 * Fields the runtime cannot honour are still declared, so that a request carrying them is
 * understood rather than rejected: the official SDKs send `top_p` and friends by default, and
 * refusing them would break every client that never asked for them on purpose.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<MessageDto>,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    val temperature: Double? = null,
    val stop: JsonElement? = null,
    val tools: List<ToolDto>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val n: Int? = null,
    @SerialName("response_format") val responseFormat: JsonObject? = null,
    @SerialName("chat_template_kwargs") val chatTemplateKwargs: JsonObject? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val logprobs: Boolean? = null,
    /** llama.cpp's extension: stream `prompt_progress` chunks while the prompt is read. */
    @SerialName("return_progress") val returnProgress: Boolean? = null,
)

@Serializable
data class StreamOptions(@SerialName("include_usage") val includeUsage: Boolean = false)

/**
 * One message. `content` stays a raw element because OpenAI allows three shapes for it: a
 * string, an array of typed parts, or null (an assistant turn that only called tools).
 */
@Serializable
data class MessageDto(
    val role: String,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/** A tool offered to the model. Only `function` tools carry [function]; others are dropped. */
@Serializable
data class ToolDto(val type: String = "function", val function: FunctionDto? = null, val name: String? = null)

@Serializable
data class FunctionDto(val name: String, val description: String? = null, val parameters: JsonObject? = null)

@Serializable
data class ToolCallDto(val id: String? = null, val type: String = "function", val function: FunctionCallDto)

@Serializable
data class FunctionCallDto(
    val name: String,
    /** A JSON document serialised as a string, as OpenAI sends it. */
    val arguments: String = "{}",
)

/** `POST /v1/completions`: a raw prompt, rendered by nobody. */
@Serializable
data class CompletionRequest(
    val model: String,
    val prompt: JsonElement,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    /** OpenAI's legacy default is 16, which surprises everyone; kept for compatibility. */
    @SerialName("max_tokens") val maxTokens: Int? = 16,
    val temperature: Double? = null,
    val stop: JsonElement? = null,
    val n: Int? = null,
    val echo: Boolean? = null,
    val suffix: String? = null,
    val logprobs: Int? = null,
)
