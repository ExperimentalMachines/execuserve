package org.experimentalmachines.execuserve.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import org.experimentalmachines.execuserve.prompt.ChatMessage
import org.experimentalmachines.execuserve.prompt.PromptTemplate
import org.experimentalmachines.execuserve.prompt.ToolCall
import org.experimentalmachines.execuserve.prompt.ToolDefinition
import kotlin.concurrent.Volatile

/**
 * Who is asking. [id] is the limit bucket: an API key's id, or one shared bucket for
 * unauthenticated loopback callers. [label] is what the request log shows.
 */
data class ClientId(val id: String, val label: String)

sealed interface PromptInput {
    /** Rendered through the model family's chat template. */
    data class Chat(val messages: List<ChatMessage>, val tools: List<ToolDefinition> = emptyList(), val thinking: Boolean? = null) : PromptInput

    /** Fed as written. Never extends the sequence cache (see ARCHITECTURE.md). */
    data class Raw(val text: String) : PromptInput
}

data class GenerationRequest(
    /** The model as the client named it; resolved to an installed id on submission. */
    val model: String,
    val input: PromptInput,
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val stop: List<String> = emptyList(),
    val client: ClientId = ClientId("anonymous", "anonymous"),
    /** Which API asked, for the run history; the engine does not read it. */
    val api: String = "",
    val stream: Boolean = false,
    /** Send [JobEvent.Progress] while the prompt is read. */
    val reportProgress: Boolean = false,
)

enum class FinishReason(val wire: String) { STOP("stop"), LENGTH("length"), TOOL_CALLS("tool_calls") }

data class JobTimings(val queueMs: Long, val loadMs: Long, val prefillMs: Long, val decodeMs: Long, val firstTokenMs: Long)

data class GenerationResult(
    val model: String,
    val finishReason: FinishReason,
    val content: String,
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int,
    val timings: JobTimings,
    /** The request's stop string that ended the reply, when one did. */
    val stopSequence: String? = null,
)

enum class FailureKind {
    CONTEXT_OVERFLOW,
    QUEUE_TIMEOUT,
    DEADLINE,
    CANCELLED,
    CLIENT_GONE,
    SLOW_CLIENT,
    MODEL_UNAVAILABLE,
    RUNTIME,
    SHUTTING_DOWN,
    OVERHEATED,
}

data class Failure(val kind: FailureKind, val message: String)

sealed interface JobEvent {
    /** The job reached the lane and its model is resident. */
    data class Started(val model: String, val queueMs: Long, val loadMs: Long) : JobEvent

    /** Text that became safe to show. */
    data class Delta(val content: String, val reasoning: String) : JobEvent

    /**
     * How far the prompt has been read, sent only when the request asked. In characters,
     * which are exact; tokens are known only when the last runtime call reports them.
     */
    data class Progress(val processedChars: Int, val totalChars: Int, val cachedTokens: Int, val elapsedMs: Long) : JobEvent

    data class Finished(val result: GenerationResult) : JobEvent

    data class Failed(val failure: Failure) : JobEvent
}

/**
 * Why a request was not accepted. Thrown by [Engine.submit] before anything is queued, so
 * the HTTP layer can answer with a real status code.
 */
sealed class Refusal(message: String) : Exception(message) {
    class QueueFull(message: String, val retryAfterMs: Long) : Refusal(message)
    class ClientLimit(message: String, val retryAfterMs: Long) : Refusal(message)
    class UnknownModel(val requested: String, val installed: List<String>) : Refusal("Model '$requested' is not installed")
    class Unsupported(message: String, val param: String) : Refusal(message)
    class Invalid(message: String, val param: String? = null) : Refusal(message)
    class TooLong(message: String) : Refusal(message)
    class Paused(message: String, val retryAfterMs: Long) : Refusal(message)
    class Unavailable(message: String) : Refusal(message)
}

/** A prompt rendered and checked, ready for the lane. */
internal data class PreparedPrompt(
    val text: String,
    val chat: Boolean,
    val template: PromptTemplate?,
    val toolsOffered: Boolean,
    val startsInThought: Boolean,
    /**
     * The same conversation with earlier replies this server produced written back as the
     * exact bytes the runtime holds for them, or null when none were recognised. Used only
     * when it extends the cache; see [Engine]'s reply ledger.
     */
    val verbatim: String? = null,
    /** The rendered conversation's input, kept to learn the opener of this turn's reply. */
    val chatInput: PromptInput.Chat? = null,
    /** The messages [verbatim] was rendered from. */
    val verbatimMessages: List<ChatMessage>? = null,
    val thinking: Boolean = false,
    val bos: String = "",
)

/**
 * One accepted request, as the caller holds it.
 *
 * Events come in two parts so that the last one always arrives: [stream] is bounded and
 * carries [JobEvent.Started] and the deltas, and a client that lets it fill is cancelled
 * rather than buffered; [outcome] carries the one terminal event. Read the stream until it
 * closes, then the outcome.
 */
class Job internal constructor(
    val id: String,
    val request: GenerationRequest,
    val model: ModelEntry,
    internal val prompt: PreparedPrompt,
    internal val submittedAtMs: Long,
    capacity: Int,
) {
    private val channel = Channel<JobEvent>(capacity)
    private val terminal = CompletableDeferred<JobEvent>()

    val stream: ReceiveChannel<JobEvent> get() = channel
    val outcome: Deferred<JobEvent> get() = terminal

    @Volatile
    internal var cancelled: FailureKind? = null
        private set

    @Volatile
    internal var startedAtMs: Long = 0

    /** Ends the job early. Safe from any thread; the lane notices within a token or a piece. */
    fun cancel(kind: FailureKind = FailureKind.CANCELLED) {
        if (cancelled == null) cancelled = kind
    }

    internal fun emit(event: JobEvent) {
        if (channel.trySend(event).isFailure) cancel(FailureKind.SLOW_CLIENT)
    }

    internal fun complete(event: JobEvent) {
        channel.close()
        terminal.complete(event)
    }

    internal val isComplete: Boolean get() = terminal.isCompleted
}
