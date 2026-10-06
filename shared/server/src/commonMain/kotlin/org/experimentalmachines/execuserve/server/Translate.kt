package org.experimentalmachines.execuserve.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.experimentalmachines.execuserve.api.ApiError
import org.experimentalmachines.execuserve.api.ChatCompletionRequest
import org.experimentalmachines.execuserve.api.CompletionRequest
import org.experimentalmachines.execuserve.api.MessageDto
import org.experimentalmachines.execuserve.engine.ClientId
import org.experimentalmachines.execuserve.engine.GenerationRequest
import org.experimentalmachines.execuserve.engine.PromptInput
import org.experimentalmachines.execuserve.prompt.ChatMessage
import org.experimentalmachines.execuserve.prompt.ChatRole
import org.experimentalmachines.execuserve.prompt.HistoryText
import org.experimentalmachines.execuserve.prompt.MessagePart
import org.experimentalmachines.execuserve.prompt.PromptTemplate
import org.experimentalmachines.execuserve.prompt.ToolCall
import org.experimentalmachines.execuserve.prompt.ToolDefinition

/**
 * OpenAI's request vocabulary, turned into the engine's. Every refusal here is a 400 that
 * names the parameter, so a client learns what to change rather than guessing.
 */
internal object Translate {

    const val MAX_MESSAGES = 2_048
    const val MAX_TOOLS = 128
    const val MAX_STOPS = 4
    const val MAX_STOP_CHARS = 512

    /**
     * Fields the runtime's sampler cannot honour. Accepted rather than refused because the
     * SDKs and chat front ends send several of them by default; named back to the client in
     * `x-execuserve-ignored` so nobody is misled about what shaped the reply.
     */
    val IGNORED = setOf(
        "top_p", "top_k", "min_p", "frequency_penalty", "presence_penalty", "repetition_penalty",
        "seed", "user", "logit_bias", "parallel_tool_calls", "metadata", "store", "service_tier",
        "top_logprobs", "prediction", "modalities", "audio", "web_search_options",
        "include", "prompt_cache_key", "client_metadata", "safety_identifier", "truncation",
    )

    fun ignoredIn(body: JsonObject): List<String> = body.keys.filter { it in IGNORED && body[it] !is JsonNull }.sorted()

    fun chat(request: ChatCompletionRequest, client: ClientId, template: PromptTemplate?): GenerationRequest {
        if ((request.n ?: 1) != 1) throw ApiError.unsupported("n", "Only n=1 is supported: one model, one sequence.")
        if (request.logprobs == true) throw ApiError.unsupported("logprobs", "This runtime does not expose logprobs.")
        request.responseFormat?.let { format ->
            val type = format["type"]?.jsonPrimitive?.contentOrNull
            if (type != null && type != "text") {
                throw ApiError.unsupported("response_format", "Constrained output ($type) is not supported by this runtime.")
            }
        }
        if (request.messages.isEmpty()) throw ApiError.badRequest("messages must not be empty", "messages")
        if (request.messages.size > MAX_MESSAGES) throw ApiError.badRequest("Too many messages", "messages")
        val tools = tools(request)
        return GenerationRequest(
            model = request.model,
            input = PromptInput.Chat(
                messages = request.messages.map { message(it, template) },
                tools = tools,
                thinking = thinking(request),
            ),
            maxTokens = request.maxCompletionTokens ?: request.maxTokens,
            temperature = request.temperature?.toFloat(),
            stop = stops(request.stop),
            client = client,
            api = "chat.completions",
            stream = request.stream,
            reportProgress = request.stream && request.returnProgress == true,
        )
    }

    /** The one text prompt of a Completions request. */
    private fun promptText(p: JsonElement?): String = when (p) {
        is JsonPrimitive -> p.takeIf { it.isString }?.content
        is JsonArray -> when {
            // Token IDs ([123, 456] or [[...]]) are a prompt in the model's own vocabulary,
            // which this server cannot read: refused, not taken as the text "123".
            p.any { it is JsonArray || (it is JsonPrimitive && !it.isString) } ->
                throw ApiError.unsupported("prompt", "Token-ID prompts are not supported. Send the prompt as text.")
            p.size == 1 -> p[0].jsonPrimitive.content
            else -> throw ApiError.unsupported("prompt", "Send one prompt per request.")
        }
        else -> null
    } ?: throw ApiError.badRequest("prompt must be a string", "prompt")

    fun completion(request: CompletionRequest, client: ClientId): GenerationRequest {
        if ((request.n ?: 1) != 1) throw ApiError.unsupported("n", "Only n=1 is supported.")
        if (request.echo == true) throw ApiError.unsupported("echo", "echo is not supported.")
        if (!request.suffix.isNullOrEmpty()) throw ApiError.unsupported("suffix", "suffix is not supported.")
        if (request.logprobs != null) throw ApiError.unsupported("logprobs", "This runtime does not expose logprobs.")
        val prompt = promptText(request.prompt)
        return GenerationRequest(
            model = request.model,
            input = PromptInput.Raw(prompt),
            maxTokens = request.maxTokens,
            temperature = request.temperature?.toFloat(),
            stop = stops(request.stop),
            client = client,
            api = "completions",
            stream = request.stream,
        )
    }

    /** Tools this server cannot offer the model, for `x-execuserve-ignored`. */
    fun droppedTools(request: ChatCompletionRequest): List<String> = request.tools.orEmpty()
        .filter { it.type != "function" || it.function == null }
        .map { tool -> "tools[" + listOfNotNull(tool.type, tool.name ?: tool.function?.name).joinToString(":") + "]" }

    private fun tools(request: ChatCompletionRequest): List<ToolDefinition> {
        val offered = request.tools.orEmpty()
        if (offered.size > MAX_TOOLS) throw ApiError.badRequest("Too many tools", "tools")
        when (val choice = request.toolChoice) {
            null, JsonNull -> Unit
            is JsonPrimitive -> when (choice.contentOrNull) {
                "none" -> return emptyList()
                "auto" -> Unit
                else -> throw ApiError.unsupported(
                    "tool_choice",
                    "tool_choice '${choice.contentOrNull}' is not supported; the model decides ('auto') or no tools ('none').",
                )
            }
            else -> throw ApiError.unsupported("tool_choice", "Forcing a specific tool is not supported.")
        }
        // Tools of other types (custom grammars, hosted search) cannot be offered to the
        // model; they are dropped and named in x-execuserve-ignored rather than failing a
        // request whose function tools work (see [droppedTools]).
        return offered.mapNotNull { tool ->
            val function = tool.function?.takeIf { tool.type == "function" } ?: return@mapNotNull null
            ToolDefinition(
                name = function.name,
                description = function.description.orEmpty(),
                parametersJson = function.parameters?.toString() ?: EMPTY_SCHEMA,
            )
        }
    }

    /**
     * Reasoning on or off. `chat_template_kwargs.enable_thinking` is what vLLM and
     * llama.cpp read for Qwen3; `reasoning_effort` is OpenAI's own knob. Null leaves it to
     * the server default, then the template's.
     */
    private fun thinking(request: ChatCompletionRequest): Boolean? {
        request.chatTemplateKwargs?.get("enable_thinking")?.let { value ->
            return (value as? JsonPrimitive)?.booleanOrNull
                ?: throw ApiError.badRequest("enable_thinking must be a boolean", "chat_template_kwargs")
        }
        return when (request.reasoningEffort) {
            null -> null
            "none", "minimal" -> false
            "low", "medium", "high" -> true
            else -> throw ApiError.badRequest("Unknown reasoning_effort '${request.reasoningEffort}'", "reasoning_effort")
        }
    }

    private fun stops(stop: JsonElement?): List<String> {
        val list = when (stop) {
            null, JsonNull -> emptyList()
            is JsonPrimitive -> listOfNotNull(stop.contentOrNull)
            is JsonArray -> stop.map { (it as? JsonPrimitive)?.contentOrNull ?: throw ApiError.badRequest("stop must be strings", "stop") }
            else -> throw ApiError.badRequest("stop must be a string or an array of strings", "stop")
        }.filter { it.isNotEmpty() }
        if (list.size > MAX_STOPS) throw ApiError.badRequest("At most $MAX_STOPS stop sequences", "stop")
        if (list.any { it.length > MAX_STOP_CHARS }) throw ApiError.badRequest("Stop sequences are limited to $MAX_STOP_CHARS characters", "stop")
        return list
    }

    private fun message(dto: MessageDto, template: PromptTemplate?): ChatMessage = when (dto.role) {
        "system", "developer" -> ChatMessage.text(ChatRole.SYSTEM, text(dto.content, dto.role))
        "user" -> ChatMessage.text(ChatRole.USER, text(dto.content, dto.role))
        "assistant" -> {
            val content = text(dto.content, dto.role)
            val calls = dto.toolCalls.orEmpty().mapIndexed { index, call ->
                ToolCall(call.id ?: "call_$index", call.function.name, call.function.arguments)
            }
            // The templates render history verbatim, so a call the client sends back as
            // structure is written in the family's own syntax, as the model wrote it.
            val written = when {
                calls.isEmpty() -> content
                template == null -> throw ApiError.badRequest("This model's format cannot express tool calls", "messages")
                else -> HistoryText.of(content, calls, template)
            }
            ChatMessage(ChatRole.ASSISTANT, listOf(MessagePart.Text(written)))
        }
        "tool", "function" -> ChatMessage.toolResult(dto.toolCallId ?: dto.name.orEmpty(), text(dto.content, dto.role))
        else -> throw ApiError.badRequest("Unknown role '${dto.role}'", "messages")
    }

    /** A message's text: a string, or its text parts joined. Images are refused, not dropped. */
    private fun text(content: JsonElement?, role: String): String = when (content) {
        null, JsonNull -> ""
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("\n") { part ->
            val obj = part as? JsonObject ?: throw ApiError.badRequest("Content parts must be objects", "messages")
            when (val type = obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text", "input_text" -> obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                else -> throw ApiError.unsupported(
                    "messages",
                    "Content of type '$type' in a $role message is not supported; this server reads text only.",
                )
            }
        }
        is JsonObject -> content.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            ?: throw ApiError.badRequest("Unreadable message content", "messages")
    }

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""
}
