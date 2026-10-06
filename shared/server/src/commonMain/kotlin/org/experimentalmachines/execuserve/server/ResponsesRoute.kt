package org.experimentalmachines.execuserve.server

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.experimentalmachines.execuserve.api.ApiError
import org.experimentalmachines.execuserve.api.OutputItem
import org.experimentalmachines.execuserve.api.ResponseObjects
import org.experimentalmachines.execuserve.api.ResponsesRequest
import org.experimentalmachines.execuserve.api.TypedSse
import org.experimentalmachines.execuserve.engine.ClientId
import org.experimentalmachines.execuserve.engine.FinishReason
import org.experimentalmachines.execuserve.engine.GenerationRequest
import org.experimentalmachines.execuserve.engine.GenerationResult
import org.experimentalmachines.execuserve.engine.JobEvent
import org.experimentalmachines.execuserve.engine.PromptInput
import org.experimentalmachines.execuserve.prompt.ChatMessage
import org.experimentalmachines.execuserve.prompt.ChatRole
import org.experimentalmachines.execuserve.prompt.HistoryText
import org.experimentalmachines.execuserve.prompt.MessagePart
import org.experimentalmachines.execuserve.prompt.PromptTemplate
import org.experimentalmachines.execuserve.prompt.ToolCall
import org.experimentalmachines.execuserve.prompt.ToolDefinition

/**
 * `POST /v1/responses`. A conversation comes whole in `input`, or continues a stored
 * response by `previous_response_id` (see [Conversations]); a finished response is stored
 * unless the request says `store: false`. `instructions` are never inherited, as in OpenAI's.
 */
internal suspend fun ApplicationCall.responses(ctx: ServerContext) {
    val client = client(ctx)
    val tree = readObject(ctx.settings.maxBodyBytes)
    val request = decode<ResponsesRequest>(tree)
    refuseUnsupportedState(tree)
    val entry = resolveModel(ctx, request.model)
    val earlier = request.previousResponseId?.let { previous ->
        ctx.conversations.get(previous, client.id, entry.id) ?: throw ApiError.badRequest(
            "Response '$previous' is not stored here: it expired, belongs to another key or model, or predates a restart. Send the whole conversation as input.",
            "previous_response_id",
            "previous_response_not_found",
        )
    }.orEmpty()
    val conversation = earlier + ResponsesTranslate.inputItems(request.input)
    val generation = ResponsesTranslate.request(request.copy(input = JsonArray(conversation)), client, ctx.engine.templateFor(entry))
    // `store` is honoured here (it keeps the response for previous_response_id), unlike on
    // Chat Completions, where it asks OpenAI to keep a copy for its evals and is ignored.
    markIgnored(JsonObject(tree - "store"), ResponsesTranslate.droppedTools(request))
    val job = submit(ctx, generation)
    val id = "resp_${job.id}"
    val created = ctx.nowSeconds()
    suspend fun keep(result: GenerationResult) {
        if (request.store) ctx.conversations.put(id, client.id, conversation + ResponsesTranslate.asInput(outputOf(job.id, result)), entry.id)
    }
    if (request.stream) {
        // Kept before response.completed is sent: a client that continues from it at once
        // must find it (it raced the store and got previous_response_not_found).
        stream(job, ResponsesStream(id, job.id, created, job.model.id, request), beforeFinish = ::keep)
    } else {
        when (val end = drain(job)) {
            is JobEvent.Finished -> {
                keep(end.result)
                respondJson(
                    ResponseObjects.response(
                        id,
                        created,
                        end.result.model,
                        statusOf(end.result),
                        outputOf(job.id, end.result),
                        usageOf(end.result),
                        request,
                    ),
                )
            }
            is JobEvent.Failed -> throw failureError(end.failure)
            else -> throw ApiError.internal("The job ended without a result.")
        }
    }
}

private fun statusOf(result: GenerationResult) = if (result.finishReason == FinishReason.LENGTH) "incomplete" else "completed"

private fun outputOf(jobId: String, result: GenerationResult): List<OutputItem> = buildList {
    if (result.reasoning.isNotEmpty()) add(OutputItem.Reasoning("rs_$jobId", result.reasoning))
    if (result.content.isNotEmpty() || result.toolCalls.isEmpty()) add(OutputItem.Message("msg_$jobId", result.content))
    result.toolCalls.forEachIndexed { index, call ->
        add(OutputItem.FunctionCall("fc_${jobId}_$index", call.id, call.name, call.argumentsJson))
    }
}

/** The Responses API's input items, turned into the engine's conversation. */
internal object ResponsesTranslate {

    /** `input` as a list of items: a bare string is one user message. */
    fun inputItems(input: JsonElement): List<JsonElement> = when (input) {
        is JsonPrimitive -> listOf(
            buildJsonObject {
                put("type", "message")
                put("role", "user")
                put("content", input.contentOrNull.orEmpty())
            },
        )
        is JsonArray -> input
        else -> throw ApiError.badRequest("input must be a string or an array of items", "input")
    }

    /** A response's output as the input items a client would send back for it. */
    fun asInput(output: List<OutputItem>): List<JsonElement> = output.mapNotNull { item ->
        when (item) {
            // The templates keep no reasoning from a client, so there is nothing to store.
            is OutputItem.Reasoning -> null
            is OutputItem.Message -> buildJsonObject {
                put("type", "message")
                put("role", "assistant")
                put("content", item.text)
            }
            is OutputItem.FunctionCall -> buildJsonObject {
                put("type", "function_call")
                put("call_id", item.callId)
                put("name", item.name)
                put("arguments", item.arguments)
            }
        }
    }

    fun request(request: ResponsesRequest, client: ClientId, template: PromptTemplate?): GenerationRequest {
        request.text?.get("format")?.let { format ->
            val type = (format as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull
            if (type != null && type != "text") {
                throw ApiError.unsupported("text.format", "Constrained output ($type) is not supported by this runtime.")
            }
        }
        val messages = mutableListOf<ChatMessage>()
        request.instructions?.takeIf { it.isNotEmpty() }?.let { messages += ChatMessage.text(ChatRole.SYSTEM, it) }
        when (val input = request.input) {
            is JsonPrimitive -> messages += ChatMessage.text(ChatRole.USER, input.contentOrNull.orEmpty())
            is JsonArray -> messages += items(input, template)
            else -> throw ApiError.badRequest("input must be a string or an array of items", "input")
        }
        if (messages.none { it.role != ChatRole.SYSTEM }) throw ApiError.badRequest("input must not be empty", "input")
        if (messages.size > Translate.MAX_MESSAGES) throw ApiError.badRequest("Too many input items", "input")
        return GenerationRequest(
            model = request.model,
            input = PromptInput.Chat(messages, tools(request), thinking(request)),
            maxTokens = request.maxOutputTokens,
            temperature = request.temperature?.toFloat(),
            client = client,
            api = "responses",
            stream = request.stream,
        )
    }

    /**
     * One assistant turn in this API is a run of items: an optional message, then function
     * calls. They are gathered and written as one turn in the family's own syntax, the same
     * text Chat Completions produces, so both APIs share the reply ledger.
     */
    private fun items(input: JsonArray, template: PromptTemplate?): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        var text: StringBuilder? = null
        val calls = mutableListOf<ToolCall>()

        fun flush() {
            if (text == null && calls.isEmpty()) return
            val content = text?.toString().orEmpty()
            val written = when {
                calls.isEmpty() -> content
                template == null -> throw ApiError.badRequest("This model's format cannot express tool calls", "input")
                else -> HistoryText.of(content, calls, template)
            }
            out += ChatMessage(ChatRole.ASSISTANT, listOf(MessagePart.Text(written)))
            text = null
            calls.clear()
        }

        input.forEach { element ->
            val item = element as? JsonObject ?: throw ApiError.badRequest("input items must be objects", "input")
            when (val type = item.string("type") ?: "message") {
                "message" -> {
                    val role = item.string("role") ?: throw ApiError.badRequest("A message item needs a role", "input")
                    val content = content(item["content"], role)
                    if (role == "assistant") {
                        if (calls.isNotEmpty()) flush()
                        text = (text ?: StringBuilder()).also { if (it.isNotEmpty()) it.append('\n') }.append(content)
                    } else {
                        flush()
                        out += ChatMessage.text(
                            when (role) {
                                "user" -> ChatRole.USER
                                "system", "developer" -> ChatRole.SYSTEM
                                else -> throw ApiError.badRequest("Unknown role '$role'", "input")
                            },
                            content,
                        )
                    }
                }
                "function_call" -> calls += ToolCall(
                    id = item.string("call_id") ?: item.string("id") ?: "call_${calls.size}",
                    name = item.string("name") ?: throw ApiError.badRequest("A function_call item needs a name", "input"),
                    argumentsJson = item.string("arguments") ?: "{}",
                )
                "function_call_output" -> {
                    flush()
                    out += ChatMessage.toolResult(item.string("call_id").orEmpty(), output(item["output"]))
                }
                // The model's own reasoning from an earlier turn; the templates decide what
                // history keeps, and none of them keeps it from a client.
                "reasoning" -> Unit
                else -> throw ApiError.unsupported("input", "Input items of type '$type' are not supported.")
            }
        }
        flush()
        return out
    }

    private fun content(content: JsonElement?, role: String): String = when (content) {
        null, JsonNull -> ""
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("\n") { part ->
            val obj = part as? JsonObject ?: throw ApiError.badRequest("Content parts must be objects", "input")
            when (val type = obj.string("type")) {
                "input_text", "output_text", "text" -> obj.string("text").orEmpty()
                else -> throw ApiError.unsupported("input", "Content of type '$type' in a $role message is not supported; this server reads text only.")
            }
        }
        else -> throw ApiError.badRequest("Unreadable message content", "input")
    }

    private fun output(output: JsonElement?): String = when (output) {
        null, JsonNull -> ""
        is JsonPrimitive -> output.contentOrNull.orEmpty()
        is JsonArray -> output.joinToString("\n") { (it as? JsonObject)?.string("text").orEmpty() }
        else -> output.toString()
    }

    private fun tools(request: ResponsesRequest): List<ToolDefinition> {
        when (val choice = request.toolChoice) {
            null, JsonNull -> Unit
            is JsonPrimitive -> when (choice.contentOrNull) {
                "none" -> return emptyList()
                "auto" -> Unit
                else -> throw ApiError.unsupported("tool_choice", "Only 'auto' and 'none' are supported.")
            }
            else -> throw ApiError.unsupported("tool_choice", "Forcing a specific tool is not supported.")
        }
        val tools = request.tools.orEmpty()
        if (tools.size > Translate.MAX_TOOLS) throw ApiError.badRequest("Too many tools", "tools")
        // Only function tools reach the model. Hosted tools (web_search, file_search) are the
        // server's to run and this one cannot; namespaced MCP groups need a routing
        // convention this server has not verified. Both are dropped and named back in
        // x-execuserve-ignored, as the codex CLI, which sends all three, needs.
        return tools.filter { it.string("type") == "function" }.map { tool ->
            ToolDefinition(
                name = tool.string("name") ?: throw ApiError.badRequest("A function tool needs a name", "tools"),
                description = tool.string("description").orEmpty(),
                parametersJson = (tool["parameters"] as? JsonObject)?.toString() ?: """{"type":"object","properties":{}}""",
            )
        }
    }

    /** Tools this server cannot offer the model, for `x-execuserve-ignored`. */
    fun droppedTools(request: ResponsesRequest): List<String> = request.tools.orEmpty()
        .filter { it.string("type") != "function" }
        .map { tool -> "tools[" + listOfNotNull(tool.string("type"), tool.string("name")).joinToString(":") + "]" }

    private fun thinking(request: ResponsesRequest): Boolean? = when (request.reasoning?.get("effort")?.jsonPrimitive?.contentOrNull) {
        null -> null
        "none", "minimal" -> false
        else -> true
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * The Responses API's typed events. Items open lazily, as the model reaches them: reasoning
 * when the first thought arrives, the message when the first word of the answer does, and
 * function calls once the reply has ended and parsed.
 */
internal class ResponsesStream(
    private val id: String,
    private val jobId: String,
    private val created: Long,
    private val model: String,
    private val request: ResponsesRequest,
) : StreamFormat {
    private var sequence = 0
    private val done = mutableListOf<OutputItem>()
    private var reasoning: StringBuilder? = null
    private var message: StringBuilder? = null

    private fun event(type: String, fields: JsonObjectBuilder.() -> Unit): String = TypedSse.event(
        type,
        buildJsonObject {
            put("type", type)
            fields()
            put("sequence_number", sequence++)
        },
    )

    private fun snapshot(status: String, usage: org.experimentalmachines.execuserve.api.Usage?) =
        ResponseObjects.response(id, created, model, status, done.toList(), usage, request)

    override fun opening(): List<String> = listOf(
        event("response.created") { put("response", snapshot("in_progress", null)) },
        event("response.in_progress") { put("response", snapshot("in_progress", null)) },
    )

    override fun delta(delta: JobEvent.Delta): List<String> = buildList {
        if (delta.reasoning.isNotEmpty()) {
            if (reasoning == null) {
                reasoning = StringBuilder()
                add(
                    event("response.output_item.added") {
                        put("output_index", done.size)
                        put("item", OutputItem.Reasoning(reasoningId, "").toJson("in_progress"))
                    },
                )
                // Each content part opens and closes around its deltas, reasoning included.
                add(
                    event("response.content_part.added") {
                        put("item_id", reasoningId)
                        put("output_index", done.size)
                        put("content_index", 0)
                        put("part", reasoningPart(""))
                    },
                )
            }
            reasoning!!.append(delta.reasoning)
            add(
                event("response.reasoning_text.delta") {
                    put("item_id", reasoningId)
                    put("output_index", done.size)
                    put("content_index", 0)
                    put("delta", delta.reasoning)
                },
            )
        }
        if (delta.content.isNotEmpty()) {
            addAll(closeReasoning())
            if (message == null) addAll(openMessage())
            message!!.append(delta.content)
            add(
                event("response.output_text.delta") {
                    put("item_id", messageId)
                    put("output_index", done.size)
                    put("content_index", 0)
                    put("delta", delta.content)
                    put("logprobs", JsonArray(emptyList()))
                },
            )
        }
    }

    override fun finish(result: GenerationResult): List<String> = buildList {
        addAll(closeReasoning())
        if (message == null && result.toolCalls.isEmpty()) addAll(openMessage())
        addAll(closeMessage())
        result.toolCalls.forEachIndexed { index, call ->
            val item = OutputItem.FunctionCall("fc_${jobId}_$index", call.id, call.name, call.argumentsJson)
            val at = done.size
            add(
                event("response.output_item.added") {
                    put("output_index", at)
                    put("item", item.toJson("in_progress"))
                },
            )
            add(
                event("response.function_call_arguments.delta") {
                    put("item_id", item.id)
                    put("output_index", at)
                    put("delta", call.argumentsJson)
                },
            )
            add(
                event("response.function_call_arguments.done") {
                    put("item_id", item.id)
                    put("output_index", at)
                    put("name", call.name)
                    put("arguments", call.argumentsJson)
                },
            )
            done += item
            add(
                event("response.output_item.done") {
                    put("output_index", at)
                    put("item", item.toJson())
                },
            )
        }
        val status = statusOf(result)
        add(
            event(if (status == "incomplete") "response.incomplete" else "response.completed") {
                put("response", snapshot(status, usageOf(result)))
            },
        )
    }

    override fun failure(error: ApiError): List<String> = listOf(
        event("error") {
            put("code", error.code)
            put("message", error.message)
            put("param", error.param)
        },
    )

    override fun done(): List<String> = emptyList()

    private val reasoningId get() = "rs_$jobId"

    private fun reasoningPart(text: String) = buildJsonObject {
        put("type", "reasoning_text")
        put("text", text)
    }
    private val messageId get() = "msg_$jobId"

    private fun openMessage(): List<String> {
        message = StringBuilder()
        val at = done.size
        return listOf(
            event("response.output_item.added") {
                put("output_index", at)
                put("item", OutputItem.Message(messageId, "").toJson("in_progress"))
            },
            event("response.content_part.added") {
                put("item_id", messageId)
                put("output_index", at)
                put("content_index", 0)
                put("part", OutputItem.textPart(""))
            },
        )
    }

    private fun closeMessage(): List<String> {
        val text = message?.toString() ?: return emptyList()
        message = null
        val item = OutputItem.Message(messageId, text)
        val at = done.size
        done += item
        return listOf(
            event("response.output_text.done") {
                put("item_id", messageId)
                put("output_index", at)
                put("content_index", 0)
                put("text", text)
                put("logprobs", JsonArray(emptyList()))
            },
            event("response.content_part.done") {
                put("item_id", messageId)
                put("output_index", at)
                put("content_index", 0)
                put("part", OutputItem.textPart(text))
            },
            event("response.output_item.done") {
                put("output_index", at)
                put("item", item.toJson())
            },
        )
    }

    private fun closeReasoning(): List<String> {
        val text = reasoning?.toString() ?: return emptyList()
        reasoning = null
        val item = OutputItem.Reasoning(reasoningId, text)
        val at = done.size
        done += item
        return listOf(
            event("response.reasoning_text.done") {
                put("item_id", reasoningId)
                put("output_index", at)
                put("content_index", 0)
                put("text", text)
            },
            event("response.content_part.done") {
                put("item_id", reasoningId)
                put("output_index", at)
                put("content_index", 0)
                put("part", reasoningPart(text))
            },
            event("response.output_item.done") {
                put("output_index", at)
                put("item", item.toJson())
            },
        )
    }
}

/**
 * Responses options whose meaning this server cannot keep, refused rather than dropped: a
 * background response would still block, a conversation id would be forgotten, and a stored
 * prompt does not exist here (codex review).
 */
private fun refuseUnsupportedState(tree: JsonObject) {
    fun present(key: String) = tree[key].let { it != null && it !is JsonNull && !(it is JsonPrimitive && it.booleanOrNull == false) }
    if (present("background")) {
        throw ApiError.badRequest("Background responses are not supported: send the request and wait, or stream it.", "background", "unsupported_parameter")
    }
    if (present("conversation")) {
        throw ApiError.badRequest(
            "Conversations are not stored here. Use previous_response_id, or send the whole conversation as input.",
            "conversation",
            "unsupported_parameter",
        )
    }
    if (present("prompt")) {
        throw ApiError.badRequest("Stored prompts are not available here. Send instructions and input instead.", "prompt", "unsupported_parameter")
    }
}
