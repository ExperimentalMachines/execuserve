package org.experimentalmachines.execuserve.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.experimentalmachines.execuserve.api.ApiError
import org.experimentalmachines.execuserve.api.ApiJson
import org.experimentalmachines.execuserve.api.ContentBlock
import org.experimentalmachines.execuserve.api.HttpStatus
import org.experimentalmachines.execuserve.api.InputMessage
import org.experimentalmachines.execuserve.api.MessageObjects
import org.experimentalmachines.execuserve.api.MessagesRequest
import org.experimentalmachines.execuserve.api.TypedSse
import org.experimentalmachines.execuserve.api.Usage
import org.experimentalmachines.execuserve.engine.ClientId
import org.experimentalmachines.execuserve.engine.FinishReason
import org.experimentalmachines.execuserve.engine.GenerationRequest
import org.experimentalmachines.execuserve.engine.GenerationResult
import org.experimentalmachines.execuserve.engine.JobEvent
import org.experimentalmachines.execuserve.engine.PromptInput
import org.experimentalmachines.execuserve.engine.Refusal
import org.experimentalmachines.execuserve.prompt.ChatMessage
import org.experimentalmachines.execuserve.prompt.ChatRole
import org.experimentalmachines.execuserve.prompt.HistoryText
import org.experimentalmachines.execuserve.prompt.MessagePart
import org.experimentalmachines.execuserve.prompt.PromptTemplate
import org.experimentalmachines.execuserve.prompt.ToolCall
import org.experimentalmachines.execuserve.prompt.ToolDefinition

/**
 * `POST /v1/messages`: Anthropic's Messages API over the same engine, stateless like the
 * other routes. It answers errors itself, in Anthropic's shape, because the SDKs read
 * `error.type` from that shape and the wrapper the OpenAI routes share writes OpenAI's.
 */
internal suspend fun ApplicationCall.messages(ctx: ServerContext) {
    try {
        answerMessages(ctx)
    } catch (error: ApiError) {
        respondAnthropicError(error)
    } catch (refusal: Refusal) {
        respondAnthropicError(refusalError(refusal))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // After a stream has started the response is committed and this cannot be sent;
        // Ktor then just closes the connection, which is the only thing left to do.
        runCatching { respondAnthropicError(ApiError.internal(failure.message ?: failure::class.simpleName ?: "error")) }
    }
}

private suspend fun ApplicationCall.answerMessages(ctx: ServerContext) {
    val client = anthropicClient(ctx)
    val tree = readObject(ctx.settings.maxBodyBytes)
    val request = decode<MessagesRequest>(tree)
    val entry = resolveModel(ctx, request.model)
    val generation = MessagesTranslate.request(request, client, ctx.engine.templateFor(entry))
    markIgnored(tree, MessagesTranslate.ignored(tree, request))
    val job = submit(ctx, generation)
    val id = "msg_${job.id}"
    if (request.stream) {
        stream(job, MessagesStream(id, job.model.id))
    } else {
        when (val end = drain(job)) {
            is JobEvent.Finished -> respondJson(
                MessageObjects.message(id, end.result.model, contentOf(end.result), stopReasonOf(end.result), usageOf(end.result), end.result.stopSequence),
            )
            is JobEvent.Failed -> throw failureError(end.failure)
            else -> throw ApiError.internal("The job ended without a result.")
        }
    }
}

/**
 * Who is calling. Anthropic's SDKs send the key as `x-api-key`; a bearer token (their
 * `auth_token`) and open loopback work as on every other route. A key presented either way
 * is the same client, so limits and the reply ledger follow the key, not the header.
 */
private fun ApplicationCall.anthropicClient(ctx: ServerContext): ClientId = try {
    client(ctx)
} catch (refused: ApiError) {
    throw unauthorized()
}

private fun unauthorized() = ApiError(
    HttpStatus.UNAUTHORIZED,
    "authentication_error",
    "Missing or invalid API key. Send it as 'x-api-key: <key>' or 'Authorization: Bearer <key>'.",
    "invalid_api_key",
)

private suspend fun ApplicationCall.respondAnthropicError(error: ApiError) {
    error.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    respondJson(MessageObjects.error(error), HttpStatusCode.fromValue(error.status))
}

/**
 * The reply as Anthropic orders it: thinking, text, then one block per tool call. A reply
 * with nothing in it still carries one empty text block, so `content[0].text` never fails.
 */
private fun contentOf(result: GenerationResult): List<ContentBlock> = buildList {
    if (result.reasoning.isNotEmpty()) add(ContentBlock.Thinking(result.reasoning))
    if (result.content.isNotEmpty() || isEmpty() && result.toolCalls.isEmpty()) add(ContentBlock.Text(result.content))
    result.toolCalls.forEach { add(toolUseOf(it)) }
}

private fun toolUseOf(call: ToolCall) = ContentBlock.ToolUse(
    id = TOOL_USE_PREFIX + call.id.removePrefix(ENGINE_CALL_PREFIX),
    name = call.name,
    input = inputOf(call.argumentsJson),
)

/** Anthropic sends arguments as an object, not a string; `{}` when the model's are not one. */
private fun inputOf(argumentsJson: String): JsonObject = try {
    ApiJson.parseToJsonElement(argumentsJson) as? JsonObject
} catch (bad: SerializationException) {
    null
} ?: JsonObject(emptyMap())

private fun stopReasonOf(result: GenerationResult) = when (result.finishReason) {
    FinishReason.STOP -> if (result.stopSequence != null) "stop_sequence" else "end_turn"
    FinishReason.LENGTH -> "max_tokens"
    FinishReason.TOOL_CALLS -> "tool_use"
}

/** Anthropic's request vocabulary, turned into the engine's conversation. */
internal object MessagesTranslate {

    /**
     * Top-level fields Anthropic defines that shape nothing on this server. The sampler's
     * (`top_p`, `top_k`) and bookkeeping ones (`metadata`, `service_tier`) are already in
     * [Translate.IGNORED].
     */
    private val IGNORED = setOf("cache_control", "container", "context_management", "inference_geo", "mcp_servers")

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""

    /**
     * How a failed tool run reads to the model. The engine's tool messages carry no error
     * flag, and without a mark a failure reads like an answer.
     */
    private const val ERROR_PREFIX = "Error: "

    fun request(request: MessagesRequest, client: ClientId, template: PromptTemplate?): GenerationRequest {
        val maxTokens = request.maxTokens ?: throw ApiError.badRequest("max_tokens: Field required", "max_tokens")
        request.outputConfig?.get("format")?.takeIf { it !is JsonNull }?.let { format ->
            val type = (format as? JsonObject)?.string("type") ?: "format"
            throw ApiError.unsupported("output_config.format", "Constrained output ($type) is not supported by this runtime.")
        }
        if (request.messages.isEmpty()) throw ApiError.badRequest("messages: at least one message is required", "messages")
        if (request.messages.size > Translate.MAX_MESSAGES) throw ApiError.badRequest("Too many messages", "messages")
        // A trailing assistant turn asks the model to continue it, and the templates can only
        // close a turn, never leave one open.
        if (request.messages.last().role == "assistant") {
            throw ApiError.unsupported("messages", "Prefilling the assistant turn is not supported; the last message must be the user's.")
        }
        val messages = mutableListOf<ChatMessage>()
        system(request.system)?.let { messages += ChatMessage.text(ChatRole.SYSTEM, it) }
        request.messages.forEach { messages += turn(it, template) }
        return GenerationRequest(
            model = request.model,
            input = PromptInput.Chat(messages, tools(request), thinking(request)),
            maxTokens = maxTokens,
            temperature = request.temperature?.toFloat(),
            stop = stops(request.stopSequences),
            client = client,
            api = "messages",
            stream = request.stream,
        )
    }

    /** What `x-execuserve-ignored` names beyond the shared list. */
    fun ignored(tree: JsonObject, request: MessagesRequest): List<String> = buildList {
        addAll(tree.keys.filter { it in IGNORED && tree[it] !is JsonNull }.sorted())
        if (request.thinking?.budgetTokens != null) add("thinking.budget_tokens")
        if (request.outputConfig?.get("effort").let { it != null && it !is JsonNull }) add("output_config.effort")
        request.tools.orEmpty().filterNot { it.isCustom }.forEach { add("tools[${it.type}:${it.name}]") }
    }

    private fun system(system: JsonElement?): String? = when (system) {
        null, JsonNull -> null
        is JsonPrimitive -> system.contentOrNull
        is JsonArray -> system.joinToString("\n") { element ->
            val block = block(element)
            when (val type = block.string("type")) {
                "text" -> block.string("text").orEmpty()
                else -> throw unsupported(type, "system prompt")
            }
        }
        else -> throw ApiError.badRequest("system must be a string or an array of text blocks", "system")
    }?.takeIf { it.isNotEmpty() }

    private fun turn(message: InputMessage, template: PromptTemplate?): List<ChatMessage> = when (message.role) {
        "user" -> userTurn(message.content)
        "assistant" -> listOf(assistantTurn(message.content, template))
        else -> throw ApiError.badRequest("Unknown role '${message.role}'; messages are 'user' or 'assistant'", "messages")
    }

    /**
     * A user turn is tool results and text in the order sent: each `tool_result` becomes a
     * tool message, and each run of text one user message, exactly the messages Chat
     * Completions produces for the same conversation.
     */
    private fun userTurn(content: JsonElement): List<ChatMessage> {
        if (content !is JsonArray) return listOf(ChatMessage.text(ChatRole.USER, plain(content)))
        val out = mutableListOf<ChatMessage>()
        val text = mutableListOf<String>()

        fun flush() {
            if (text.isEmpty()) return
            out += ChatMessage.text(ChatRole.USER, text.joinToString("\n"))
            text.clear()
        }

        content.forEach { element ->
            val block = block(element)
            when (val type = block.string("type")) {
                "text" -> text += block.string("text").orEmpty()
                "tool_result" -> {
                    flush()
                    out += toolResult(block)
                }
                else -> throw unsupported(type, "user message")
            }
        }
        flush()
        return out
    }

    /**
     * An assistant turn's text and tool calls, written in the family's own syntax by the
     * same [HistoryText] the Chat Completions translation uses, so both APIs render the same
     * prompt and share the reply ledger.
     */
    private fun assistantTurn(content: JsonElement, template: PromptTemplate?): ChatMessage {
        val text = mutableListOf<String>()
        val calls = mutableListOf<ToolCall>()
        if (content is JsonArray) {
            content.forEach { element ->
                val block = block(element)
                when (val type = block.string("type")) {
                    "text" -> text += block.string("text").orEmpty()
                    "tool_use" -> calls += ToolCall(
                        id = block.string("id").orEmpty(),
                        name = block.string("name") ?: throw ApiError.badRequest("A tool_use block needs a name", "messages"),
                        argumentsJson = (block["input"] as? JsonObject ?: JsonObject(emptyMap())).toString(),
                    )
                    // The model's own reasoning from an earlier turn; the templates decide
                    // what history keeps, and none of them keeps it from a client.
                    "thinking", "redacted_thinking" -> Unit
                    else -> throw unsupported(type, "assistant message")
                }
            }
        } else {
            text += plain(content)
        }
        val joined = text.joinToString("\n")
        val written = when {
            calls.isEmpty() -> joined
            template == null -> throw ApiError.badRequest("This model's format cannot express tool calls", "messages")
            else -> HistoryText.of(joined, calls, template)
        }
        return ChatMessage(ChatRole.ASSISTANT, listOf(MessagePart.Text(written)))
    }

    private fun toolResult(block: JsonObject): ChatMessage {
        val text = when (val content = block["content"]) {
            null, JsonNull -> ""
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("\n") { element ->
                val part = block(element)
                when (val type = part.string("type")) {
                    "text" -> part.string("text").orEmpty()
                    else -> throw unsupported(type, "tool_result")
                }
            }
            else -> throw ApiError.badRequest("tool_result content must be a string or an array of blocks", "messages")
        }
        val failed = (block["is_error"] as? JsonPrimitive)?.booleanOrNull == true
        return ChatMessage.toolResult(block.string("tool_use_id").orEmpty(), if (failed) ERROR_PREFIX + text else text)
    }

    private fun plain(content: JsonElement): String = when (content) {
        JsonNull -> ""
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        else -> throw ApiError.badRequest("content must be a string or an array of content blocks", "messages")
    }

    private fun block(element: JsonElement): JsonObject = element as? JsonObject ?: throw ApiError.badRequest("Content blocks must be objects", "messages")

    /** Images and documents are refused, not dropped, as the OpenAI routes refuse image parts. */
    private fun unsupported(type: String?, where: String) = ApiError.unsupported(
        "messages",
        "Content of type '$type' in a $where is not supported; this server reads text only.",
    )

    private fun tools(request: MessagesRequest): List<ToolDefinition> {
        val offered = request.tools.orEmpty()
        if (offered.size > Translate.MAX_TOOLS) throw ApiError.badRequest("Too many tools", "tools")
        when (val type = request.toolChoice?.type) {
            null, "auto" -> Unit
            "none" -> return emptyList()
            "any", "tool" -> throw ApiError.unsupported(
                "tool_choice",
                "tool_choice '$type' is not supported: this runtime cannot force a tool call. Use 'auto' or 'none'.",
            )
            else -> throw ApiError.badRequest("Unknown tool_choice type '$type'", "tool_choice")
        }
        // Anthropic's built-in tools (web search, bash, the text editor) are dropped and named
        // in x-execuserve-ignored, as the OpenAI routes drop hosted tools (see [ignored]).
        return offered.filter { it.isCustom }.map { tool ->
            if (tool.name.isBlank()) throw ApiError.badRequest("A tool needs a name", "tools")
            ToolDefinition(tool.name, tool.description.orEmpty(), tool.inputSchema?.toString() ?: EMPTY_SCHEMA)
        }
    }

    /**
     * Reasoning on or off. `adaptive` lets the model decide, which for a hybrid model is the
     * server's default and then the template's, so it maps to no preference.
     */
    private fun thinking(request: MessagesRequest): Boolean? = when (val type = request.thinking?.type) {
        null, "adaptive" -> null
        "enabled" -> true
        "disabled" -> false
        else -> throw ApiError.badRequest("Unknown thinking type '$type'", "thinking")
    }

    /** The same limits the OpenAI routes apply: every stop string is checked on every token. */
    private fun stops(sequences: List<String>?): List<String> {
        val list = sequences.orEmpty().filter { it.isNotEmpty() }
        if (list.size > Translate.MAX_STOPS) throw ApiError.badRequest("At most ${Translate.MAX_STOPS} stop sequences", "stop_sequences")
        if (list.any { it.length > Translate.MAX_STOP_CHARS }) {
            throw ApiError.badRequest("Stop sequences are limited to ${Translate.MAX_STOP_CHARS} characters", "stop_sequences")
        }
        return list
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * Anthropic's stream events. Blocks open lazily, as the model reaches them: thinking when
 * the first thought arrives, text when the first word of the answer does, and tool calls
 * once the reply has ended and parsed, each sent whole as one `input_json_delta`.
 */
internal class MessagesStream(private val id: String, private val model: String) : StreamFormat {
    /** The index of the open block, or of the next one when none is open. */
    private var index = 0
    private var openType: String? = null

    private fun event(type: String, fields: JsonObjectBuilder.() -> Unit = {}): String = TypedSse.event(
        type,
        buildJsonObject {
            put("type", type)
            fields()
        },
    )

    /**
     * The prompt's size is known only when the reply ends, so `message_start` counts zero and
     * `message_delta` carries every count; its usage is cumulative, and the SDKs overwrite
     * the opening numbers with it.
     */
    override fun opening(): List<String> = listOf(
        event("message_start") { put("message", MessageObjects.message(id, model, emptyList(), null, Usage(0, 0))) },
    )

    override fun delta(delta: JobEvent.Delta): List<String> = buildList {
        if (delta.reasoning.isNotEmpty()) {
            addAll(open(ContentBlock.Thinking("")))
            add(
                blockDelta {
                    put("type", "thinking_delta")
                    put("thinking", delta.reasoning)
                },
            )
        }
        if (delta.content.isNotEmpty()) {
            addAll(open(ContentBlock.Text("")))
            add(
                blockDelta {
                    put("type", "text_delta")
                    put("text", delta.content)
                },
            )
        }
    }

    override fun finish(result: GenerationResult): List<String> = buildList {
        addAll(close())
        // The same blocks the non-streaming reply has: an empty reply is one empty text block.
        if (index == 0 && result.toolCalls.isEmpty()) {
            addAll(open(ContentBlock.Text("")))
            addAll(close())
        }
        result.toolCalls.forEach { call ->
            val block = toolUseOf(call)
            addAll(open(ContentBlock.ToolUse(block.id, block.name, JsonObject(emptyMap()))))
            add(
                blockDelta {
                    put("type", "input_json_delta")
                    put("partial_json", block.input.toString())
                },
            )
            addAll(close())
        }
        add(
            event("message_delta") {
                putJsonObject("delta") {
                    put("stop_reason", stopReasonOf(result))
                    put("stop_sequence", result.stopSequence?.let(::JsonPrimitive) ?: JsonNull)
                }
                put("usage", MessageObjects.usage(usageOf(result)))
            },
        )
        add(event("message_stop"))
    }

    override fun failure(error: ApiError): List<String> = listOf(TypedSse.event("error", MessageObjects.error(error)))

    override fun done(): List<String> = emptyList()

    /** A heartbeat as Anthropic sends one: a named event, which every client's parser expects. */
    override fun heartbeat(): String = event("ping")

    private fun open(block: ContentBlock): List<String> {
        if (openType == block.type) return emptyList()
        val closing = close()
        openType = block.type
        return closing + event("content_block_start") {
            put("index", index)
            put("content_block", block.toJson())
        }
    }

    private fun close(): List<String> {
        if (openType == null) return emptyList()
        openType = null
        return listOf(event("content_block_stop") { put("index", index++) })
    }

    private fun blockDelta(delta: JsonObjectBuilder.() -> Unit): String = event("content_block_delta") {
        put("index", index)
        put("delta", buildJsonObject(delta))
    }
}

private const val TOOL_USE_PREFIX = "toolu_"
private const val ENGINE_CALL_PREFIX = "call_"
