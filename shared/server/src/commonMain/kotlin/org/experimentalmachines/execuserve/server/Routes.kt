package org.experimentalmachines.execuserve.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.experimentalmachines.execuserve.api.ApiError
import org.experimentalmachines.execuserve.api.ApiJson
import org.experimentalmachines.execuserve.api.ChatCompletionRequest
import org.experimentalmachines.execuserve.api.ChatResponses
import org.experimentalmachines.execuserve.api.CompletionRequest
import org.experimentalmachines.execuserve.api.CompletionResponses
import org.experimentalmachines.execuserve.api.HttpStatus
import org.experimentalmachines.execuserve.api.ModelOut
import org.experimentalmachines.execuserve.api.ModelResponses
import org.experimentalmachines.execuserve.api.Sse
import org.experimentalmachines.execuserve.engine.ClientId
import org.experimentalmachines.execuserve.engine.Engine
import org.experimentalmachines.execuserve.engine.FailureKind
import org.experimentalmachines.execuserve.engine.GenerationRequest
import org.experimentalmachines.execuserve.engine.GenerationResult
import org.experimentalmachines.execuserve.engine.Job
import org.experimentalmachines.execuserve.engine.JobEvent
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.Refusal
import org.experimentalmachines.execuserve.engine.RuntimeFailure
import org.experimentalmachines.execuserve.engine.Units

/** Everything the routes need, supplied by whichever platform hosts the server. */
class ServerContext(
    val engine: Engine,
    val settings: ServerSettings,
    val keys: KeyVerifier,
    /** This device's current addresses and names, for the `Host` check. */
    val deviceHosts: () -> Set<String>,
    val version: String,
    val nowSeconds: () -> Long,
    /** CPU threads the runtime computes with, when known. */
    val threads: () -> Int? = { null },
    /** The run history, newest first; empty where none is kept (the dev server). */
    val runs: () -> List<JobRecord> = { emptyList() },
    /** Responses kept for `previous_response_id`. */
    val conversations: Conversations = Conversations(clock = { nowSeconds() * Units.MS_PER_SECOND }),
)

/** The whole HTTP surface. See ARCHITECTURE.md, "HTTP API". */
fun Application.execuServe(ctx: ServerContext) {
    // A client that hangs up cancels its handler at once, and the handler cancels its job:
    // without this, CIO said nothing until a write failed, so a client that left during a
    // long prompt kept the lane reading it for nobody (37 s on the POCO, 2026-09-30).
    install(HttpRequestLifecycle) { cancelCallOnClose = true }
    if (ctx.settings.corsOrigins.isNotEmpty()) {
        install(CORS) {
            ctx.settings.corsOrigins.forEach { origin ->
                val scheme = origin.substringBefore("://", "http")
                allowHost(origin.substringAfter("://"), schemes = listOf(scheme))
            }
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            // Anthropic's clients authenticate and version with headers of their own.
            allowHeader("x-api-key")
            allowHeader("anthropic-version")
            allowHeader("anthropic-beta")
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
        }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        if (!Hosts.allowed(call.request.headers[HttpHeaders.Host], ctx.deviceHosts(), ctx.settings)) {
            call.respondError(
                ApiError(
                    HttpStatus.FORBIDDEN,
                    "invalid_request_error",
                    "The Host header does not name this server. Add the name in ExecuServe's settings if it is yours.",
                    "host_not_allowed",
                ),
            )
            finish()
        }
    }

    routing {
        webChat()
        get("/health") {
            val stopping = ctx.engine.status.value.admission.name == "STOPPED"
            call.respondText(
                if (stopping) """{"status":"stopping"}""" else """{"status":"ok"}""",
                ContentType.Application.Json,
                if (stopping) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
            )
        }
        apiRoutes(ctx)
        telemetry(ctx)
        route("/models/{hostedModel}") {
            modelChat(ctx)
            apiRoutes(ctx)
        }
    }
}

/** Both mounts use one engine, key identity, queue and resource policy. */
private fun Route.apiRoutes(ctx: ServerContext) {
    get("/v1/models") {
        call.handle {
            client(ctx)
            respondJson(ModelResponses.list(hostedModels(ctx).map { modelOut(ctx, it) }))
        }
    }
    get("/v1/models/{id...}") {
        call.handle {
            client(ctx)
            val name = routeParameters.getAll("id").orEmpty().joinToString("/")
            val entry = resolveModel(ctx, name)
            respondJson(modelOut(ctx, entry).toJson())
        }
    }
    post("/v1/chat/completions") { call.handle { chat(ctx) } }
    post("/v1/completions") { call.handle { completion(ctx) } }
    post("/v1/responses") { call.handle { responses(ctx) } }
    // Not wrapped in handle: the Messages API answers its errors in Anthropic's shape.
    post("/v1/messages") { call.messages(ctx) }
    post("/apply-template") { call.handle { applyTemplate(ctx) } }
    get("/v1/execuserve/status") {
        call.handle {
            val caller = client(ctx)
            // A key sees its own recent requests, not what other clients asked.
            respondJson(statusBody(ctx, caller.id))
        }
    }
    post("/v1/execuserve/models/{id}/load") {
        call.handle {
            val caller = client(ctx)
            val id = resolveModel(ctx, routeParameters["id"].orEmpty()).id
            try {
                ctx.engine.load(id)
            } catch (failure: RuntimeFailure) {
                throw ApiError(HttpStatus.INTERNAL_SERVER_ERROR, "server_error", failure.message ?: "The model could not be loaded.", "model_load_failed")
            }
            respondJson(statusBody(ctx, caller.id))
        }
    }
    post("/v1/execuserve/models/{id}/unload") {
        call.handle {
            val caller = client(ctx)
            ctx.engine.unload(resolveModel(ctx, routeParameters["id"].orEmpty()).id)
            respondJson(statusBody(ctx, caller.id))
        }
    }
}

// ----------------------------------------------------------------------------------------
// Handlers
// ----------------------------------------------------------------------------------------

private suspend fun ApplicationCall.chat(ctx: ServerContext) {
    val client = client(ctx)
    val tree = readObject(ctx.settings.maxBodyBytes)
    val request = decode<ChatCompletionRequest>(tree)
    val entry = resolveModel(ctx, request.model)
    val generation = Translate.chat(request, client, ctx.engine.templateFor(entry))
    markIgnored(tree, Translate.droppedTools(request))
    val job = submit(ctx, generation)
    val id = "chatcmpl-${job.id}"
    val created = ctx.nowSeconds()
    val model = job.model.id
    if (request.stream) {
        stream(job, ChatStream(id, created, model, request.streamOptions?.includeUsage == true))
    } else {
        when (val end = drain(job)) {
            is JobEvent.Finished -> respondJson(chatBody(id, created, end.result))
            is JobEvent.Failed -> throw failureError(end.failure)
            else -> throw ApiError.internal("The job ended without a result.")
        }
    }
}

/**
 * `POST /apply-template`, llama.cpp's name for it: a Chat Completions body in, the exact
 * prompt text the model would read out. There is no tokenizer on this side of the runtime,
 * so the length is given in characters and no token count is claimed.
 */
private suspend fun ApplicationCall.applyTemplate(ctx: ServerContext) {
    val client = client(ctx)
    val tree = readObject(ctx.settings.maxBodyBytes)
    val request = decode<ChatCompletionRequest>(tree)
    val entry = resolveModel(ctx, request.model)
    val prompt = try {
        ctx.engine.render(Translate.chat(request, client, ctx.engine.templateFor(entry)))
    } catch (refusal: Refusal) {
        throw refusalError(refusal)
    }
    respondJson(
        buildJsonObject {
            put("model", entry.id)
            put("prompt", prompt)
            put("characters", prompt.length)
        },
    )
}

private suspend fun ApplicationCall.completion(ctx: ServerContext) {
    val client = client(ctx)
    val tree = readObject(ctx.settings.maxBodyBytes)
    val request = decode<CompletionRequest>(tree)
    val generation = Translate.completion(request, client)
    markIgnored(tree)
    val job = submit(ctx, generation)
    val id = "cmpl-${job.id}"
    val created = ctx.nowSeconds()
    if (request.stream) {
        stream(job, CompletionStream(id, created, job.model.id, request.streamOptions?.includeUsage == true))
    } else {
        when (val end = drain(job)) {
            is JobEvent.Finished -> respondJson(
                CompletionResponses.completion(
                    id,
                    created,
                    job.model.id,
                    end.result.content,
                    end.result.finishReason.wire,
                    usageOf(end.result),
                    timingsOf(end.result),
                ),
            )
            is JobEvent.Failed -> throw failureError(end.failure)
            else -> throw ApiError.internal("The job ended without a result.")
        }
    }
}

private fun chatBody(id: String, created: Long, result: GenerationResult): JsonObject = ChatResponses.completion(
    id = id,
    created = created,
    model = result.model,
    content = result.content,
    reasoning = result.reasoning.ifEmpty { null },
    toolCalls = result.toolCalls.map { toolCallOut(it) },
    finishReason = result.finishReason.wire,
    usage = usageOf(result),
    timings = timingsOf(result),
)

internal suspend fun ApplicationCall.submit(ctx: ServerContext, request: GenerationRequest): Job = try {
    resolveModel(ctx, request.model)
    ctx.engine.submit(request)
} catch (refusal: Refusal) {
    throw refusalError(refusal)
}

/** Waits for a non-streaming job; a handler cancelled meanwhile cancels the job. */
internal suspend fun drain(job: Job): JobEvent {
    try {
        for (event in job.stream) Unit
        return job.outcome.await()
    } finally {
        if (!job.outcome.isCompleted) job.cancel(FailureKind.CLIENT_GONE)
    }
}

// ----------------------------------------------------------------------------------------
// Streaming
// ----------------------------------------------------------------------------------------

/**
 * How one kind of response turns job events into server-sent events, already framed: Chat
 * Completions sends bare `data:` lines ending in `[DONE]`, the Responses API sends named,
 * typed events and no terminator.
 */
internal interface StreamFormat {
    fun opening(): List<String>
    fun delta(delta: JobEvent.Delta): List<String>
    fun finish(result: GenerationResult): List<String>
    fun failure(error: ApiError): List<String>
    fun done(): List<String>

    /** Prompt-reading progress, for formats that carry it; none by default. */
    fun progress(progress: JobEvent.Progress): List<String> = emptyList()

    /** What an idle gap sends: an SSE comment, unless the protocol names its own (Anthropic's `ping`). */
    fun heartbeat(): String = Sse.comment("working")
}

/**
 * Streams [job] as server-sent events.
 *
 * Nothing is written until the first token (or [COMMIT_AFTER_MS] of silence), so a job
 * that fails on the way there (a queue timeout, a model that will not load, a prompt the
 * window cannot hold) still answers with a real status, which is what the OpenAI SDKs
 * retry on. After that a failure can only travel in-stream, as an `error` object the
 * Python SDK raises. One coroutine does all the writing, heartbeats included.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun ApplicationCall.stream(job: Job, format: StreamFormat) {
    try {
        val early = mutableListOf<JobEvent>()
        var terminal: JobEvent? = null
        while (true) {
            val next = receiveWithin(job, COMMIT_AFTER_MS) ?: break
            if (next.isClosed) {
                terminal = job.outcome.await()
                break
            }
            // Progress was asked for, so it opens the stream: a client that wants it has
            // traded an early failure's HTTP status for seeing the prompt being read.
            val event = next.getOrNull()
            if (event is JobEvent.Delta || event is JobEvent.Progress) {
                early += event
                break
            }
        }
        (terminal as? JobEvent.Failed)?.let { throw failureError(it.failure) }

        response.header(HttpHeaders.CacheControl, "no-cache")
        response.header("X-Accel-Buffering", "no")
        val written = CompletableDeferred<Unit>()
        respondBytesWriter(contentType = ContentType.Text.EventStream) {
            try {
                format.opening().forEach { send(it) }
                early.forEach { event -> format.lines(event).forEach { send(it) } }
                var end = terminal
                while (end == null) {
                    val next = receiveWithin(job, HEARTBEAT_MS)
                    when {
                        next == null -> send(format.heartbeat())
                        next.isClosed -> end = job.outcome.await()
                        else -> next.getOrNull()?.let { event -> format.lines(event).forEach { send(it) } }
                    }
                }
                when (end) {
                    is JobEvent.Finished -> format.finish(end.result).forEach { send(it) }
                    is JobEvent.Failed -> format.failure(failureError(end.failure)).forEach { send(it) }
                    else -> Unit
                }
                format.done().forEach { send(it) }
            } catch (gone: Throwable) {
                // A write to a closed socket is how a disconnect shows itself.
                job.cancel(FailureKind.CLIENT_GONE)
                throw gone
            } finally {
                written.complete(Unit)
            }
        }
        // Some engines schedule the body writer and return immediately from respond().
        // Keep this handler alive until it finishes; otherwise our cleanup mistakes that
        // handoff for a disconnect and cancels a healthy request during prompt evaluation.
        // A real disconnect still cancels this await and reaches the cleanup below.
        written.await()
    } finally {
        if (!job.outcome.isCompleted) job.cancel(FailureKind.CLIENT_GONE)
    }
}

/** What [event] adds to a stream in this format; events a format does not carry add nothing. */
private fun StreamFormat.lines(event: JobEvent): List<String> = when (event) {
    is JobEvent.Delta -> delta(event)
    is JobEvent.Progress -> progress(event)
    else -> emptyList()
}

/**
 * The next event, or null after [timeoutMs]. A select rather than a timeout around
 * `receive`, because a receive cancelled by its timeout can drop an element it had already
 * taken off the channel, and a dropped element is a missing piece of someone's reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun receiveWithin(job: Job, timeoutMs: Long): ChannelResult<JobEvent>? = select {
    job.stream.onReceiveCatching { it }
    onTimeout(timeoutMs) { null }
}

private suspend fun ByteWriteChannel.send(text: String) {
    writeStringUtf8(text)
    flush()
}

/** Chat Completions and legacy Completions: bare `data:` chunks, then `[DONE]`. */
private abstract class DataStream : StreamFormat {
    abstract fun openingChunks(): List<JsonObject>
    abstract fun deltaChunks(delta: JobEvent.Delta): List<JsonObject>
    abstract fun finishChunks(result: GenerationResult): List<JsonObject>

    override fun opening() = openingChunks().map(Sse::data)
    override fun delta(delta: JobEvent.Delta) = deltaChunks(delta).map(Sse::data)
    override fun finish(result: GenerationResult) = finishChunks(result).map(Sse::data)
    override fun failure(error: ApiError) = listOf(Sse.data(error.toJson()))
    override fun done() = listOf(Sse.DONE)
}

private class ChatStream(private val id: String, private val created: Long, private val model: String, private val includeUsage: Boolean) : DataStream() {
    override fun progress(progress: JobEvent.Progress) = listOf(
        Sse.data(
            ChatResponses.progressChunk(id, created, model, progress.processedChars, progress.totalChars, progress.cachedTokens, progress.elapsedMs),
        ),
    )

    override fun openingChunks() = listOf(ChatResponses.chunk(id, created, model, ChatResponses.roleDelta()))

    override fun deltaChunks(delta: JobEvent.Delta): List<JsonObject> = buildList {
        if (delta.reasoning.isNotEmpty()) add(ChatResponses.chunk(id, created, model, ChatResponses.reasoningDelta(delta.reasoning)))
        if (delta.content.isNotEmpty()) add(ChatResponses.chunk(id, created, model, ChatResponses.contentDelta(delta.content)))
    }

    override fun finishChunks(result: GenerationResult): List<JsonObject> = buildList {
        if (result.toolCalls.isNotEmpty()) {
            add(ChatResponses.chunk(id, created, model, ChatResponses.toolCallsDelta(result.toolCalls.map { toolCallOut(it) })))
        }
        add(ChatResponses.chunk(id, created, model, ChatResponses.emptyDelta(), result.finishReason.wire))
        if (includeUsage) add(ChatResponses.usageChunk(id, created, model, usageOf(result), timingsOf(result)))
    }
}

private class CompletionStream(private val id: String, private val created: Long, private val model: String, private val includeUsage: Boolean) :
    DataStream() {
    override fun openingChunks() = emptyList<JsonObject>()

    override fun deltaChunks(delta: JobEvent.Delta): List<JsonObject> =
        if (delta.content.isEmpty()) emptyList() else listOf(CompletionResponses.chunk(id, created, model, delta.content, null))

    override fun finishChunks(result: GenerationResult): List<JsonObject> = buildList {
        add(CompletionResponses.chunk(id, created, model, "", result.finishReason.wire))
        if (includeUsage) add(CompletionResponses.usageChunk(id, created, model, usageOf(result)))
    }
}

// ----------------------------------------------------------------------------------------
// Plumbing
// ----------------------------------------------------------------------------------------

/**
 * Who is calling. A presented key must be valid, whatever the address; no key is accepted
 * only from loopback, and only when the user opened loopback.
 */
internal fun ApplicationCall.client(ctx: ServerContext): ClientId {
    val header = request.headers[HttpHeaders.Authorization]
    if (header != null) {
        val token = header.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(BEARER.length)?.trim()
            ?: throw ApiError.unauthorized()
        val key = ctx.keys.verify(token) ?: throw ApiError.unauthorized()
        return ClientId("key:${key.id}", key.name)
    }
    if (ctx.settings.openLoopback && isLoopbackPeer(request.local.remoteAddress)) {
        return ClientId("loopback", "local app")
    }
    throw ApiError.unauthorized()
}

internal fun isLoopbackPeer(address: String): Boolean {
    val a = address.lowercase().removePrefix("/")
    return a.startsWith("127.") || a == "::1" || a == "0:0:0:0:0:0:0:1" || a.startsWith("::ffff:127.") || a == "localhost"
}

internal suspend fun ApplicationCall.readObject(limit: Long): JsonObject {
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if (it > limit) throw ApiError.tooLarge(limit) }
    // Read one byte past the limit so a chunked body that exceeds it is caught while
    // reading, not trusted on its word.
    val bytes = receiveChannel().readBuffer(limit + 1).readByteArray()
    if (bytes.size > limit) throw ApiError.tooLarge(limit)
    val element: JsonElement = try {
        ApiJson.parseToJsonElement(bytes.decodeToString())
    } catch (bad: SerializationException) {
        throw ApiError.badRequest("The body is not valid JSON: ${bad.message?.lineSequence()?.firstOrNull()}")
    }
    return element as? JsonObject ?: throw ApiError.badRequest("The body must be a JSON object")
}

internal inline fun <reified T> decode(tree: JsonObject): T = try {
    ApiJson.decodeFromJsonElement<T>(tree)
} catch (bad: SerializationException) {
    throw ApiError.badRequest("Malformed request: ${bad.message?.lineSequence()?.firstOrNull()}")
} catch (bad: IllegalArgumentException) {
    throw ApiError.badRequest("Malformed request: ${bad.message?.lineSequence()?.firstOrNull()}")
}

internal fun ApplicationCall.markIgnored(tree: JsonObject, more: List<String> = emptyList()) {
    val ignored = Translate.ignoredIn(tree) + more
    if (ignored.isNotEmpty()) response.header("x-execuserve-ignored", ignored.joinToString(", "))
}

internal suspend fun ApplicationCall.respondJson(json: JsonElement, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(json.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(error: ApiError) {
    error.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    respondJson(error.toJson(), HttpStatusCode.fromValue(error.status))
}

/** Runs a handler and turns anything it throws into OpenAI's error shape. */
internal suspend fun ApplicationCall.handle(block: suspend ApplicationCall.() -> Unit) {
    try {
        block()
    } catch (error: ApiError) {
        respondError(error)
    } catch (refusal: Refusal) {
        respondError(refusalError(refusal))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        // After a stream has started the response is committed and this cannot be sent;
        // Ktor then just closes the connection, which is the only thing left to do.
        runCatching { respondError(ApiError.internal(failure.message ?: failure::class.simpleName ?: "error")) }
    }
}

private fun modelOut(ctx: ServerContext, entry: ModelEntry): ModelOut {
    val resident = ctx.engine.status.value.resident.firstOrNull { it.id == entry.id }
    val template = ctx.engine.templateFor(entry)
    return ModelOut(
        id = entry.id,
        created = entry.installedAtMs / Units.MS_PER_SECOND,
        contextLength = entry.contextLength ?: resident?.contextLength,
        loaded = resident != null,
        family = entry.family,
        sizeBytes = entry.sizeBytes,
        // The delegate the file was exported for, not only the runtime: one ExecuTorch runtime
        // opens both CPU and GPU exports. Catalog installs record it; a file copied in by hand
        // has only its name to go on (codex QA).
        backend = when (entry.backend ?: if ("vulkan" in entry.id.lowercase()) "vulkan" else null) {
            "vulkan" -> "executorch-vulkan"
            "qnn" -> "executorch-qnn"
            "mtk" -> "executorch-neuropilot"
            else -> ctx.engine.runtimeId
        },
        aliases = entry.aliases.sorted(),
        ownedBy = entry.lab ?: "execuserve",
        capabilities = buildList {
            add("completions")
            if (template != null) {
                add("chat")
                if (template.supportsTools) add("tools")
                if (template.supportsThinking) add("reasoning")
            }
        },
    )
}

private const val BEARER = "Bearer "
private const val COMMIT_AFTER_MS = 15_000L
private const val HEARTBEAT_MS = 5_000L
