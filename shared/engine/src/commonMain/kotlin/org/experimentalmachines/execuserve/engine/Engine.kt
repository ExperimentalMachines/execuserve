package org.experimentalmachines.execuserve.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.experimentalmachines.execuserve.prompt.ChatMessage
import org.experimentalmachines.execuserve.prompt.ChatRole
import org.experimentalmachines.execuserve.prompt.HistoryText
import org.experimentalmachines.execuserve.prompt.MessagePart
import org.experimentalmachines.execuserve.prompt.PromptTemplate
import org.experimentalmachines.execuserve.prompt.ToolCall
import org.experimentalmachines.execuserve.prompt.PromptTemplates
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The server's scheduler and the only caller of any runtime.
 *
 * **One compute lane.** Every call into every [LlmSession] (load, prefill, generate, reset,
 * close) happens in one coroutine on [lane], which production code backs with a single
 * thread. A phone's CPU runs one sequence well: XNNPACK already spreads it across every
 * performance core, so a second concurrent sequence would halve both and heat the phone
 * twice as fast, and the exports are batch-size one, so there is no batching to win.
 * Serialising on the lane removes every race on the runtime and on the cache record by
 * construction.
 *
 * **Everything else is off the lane and never blocks it:** admission ([submit]) and the
 * reaper that expires queued jobs, enforces deadlines and notices a wedged native call.
 * They share only the queue, under [lock], and flags that are safe to read anywhere.
 */
@OptIn(ExperimentalTime::class)
class Engine(
    private val runtime: LlmRuntime,
    private val models: ModelSource,
    private val lane: CoroutineDispatcher,
    private val scope: CoroutineScope,
    config: EngineConfig = EngineConfig(),
    private val environment: StateFlow<Environment> = MutableStateFlow(Environment()),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Called once when a native call outlives its cancellation; the platform restarts. */
    private val onWedged: () -> Unit = {},
) {
    @Volatile
    var config: EngineConfig = config

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _records = MutableSharedFlow<JobRecord>(extraBufferCapacity = RECORD_BUFFER)

    /** Every finished request as it finishes, for whoever keeps the history. */
    val records: SharedFlow<JobRecord> = _records.asSharedFlow()

    val runtimeId: String get() = runtime.id

    // Shared between admission, the reaper and the lane; always under [lock].
    private val lock = Mutex()
    private val queue = ArrayList<Item>()
    private val perClient = HashMap<String, Int>()
    private var stopping = false

    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Confined to the lane.
    private val residents = LinkedHashMap<String, Resident>()
    private val broken = HashMap<String, String>()

    // Written on the lane, read anywhere.
    @Volatile private var current: Job? = null

    @Volatile private var wedged = false

    /** Windows of models that have been opened, for the length check at admission. */
    @Volatile private var knownWindows: Map<String, Int> = emptyMap()

    @Volatile private var meanJobMs = 5_000.0

    /**
     * The reply ledger, per model: for each recent reply this server produced, the exact
     * bytes its runtime holds for that turn (the generation opener, such as Qwen3's empty
     * `<think></think>` with reasoning off, then everything the model wrote), keyed by the
     * text an OpenAI client sends back for it (content, and tool calls in the family's
     * syntax). A client never sends those bytes back, so without this every multi-turn
     * conversation and every tool loop on Qwen3 missed the cache (measured on the emulator:
     * 0 cached of 48 and 38 prompt tokens). Written on the lane, read at admission.
     *
     * Kept per model and per client ([ledgerKey]): one key's replies never shape another
     * key's prompt, for the same reason the KV cache is not shared (see [Resident.owner]).
     */
    @Volatile private var ledgers: Map<String, List<Pair<String, LedgerEntry>>> = emptyMap()

    private fun ledgerKey(modelId: String, clientId: String) = "$modelId\u0000$clientId"

    private data class LedgerEntry(val stored: String, val reasoned: Boolean)

    private var workers: List<kotlinx.coroutines.Job> = emptyList()

    private sealed interface Item {
        class Generate(val job: Job) : Item

        class Command(val action: Action, val done: CompletableDeferred<Unit>) : Item
    }

    private sealed interface Action {
        data class Load(val entry: ModelEntry) : Action

        data class Unload(val id: String?) : Action

        data class EvictIdle(val force: Boolean) : Action

        data object ForgetFailures : Action
    }

    /** Starts the lane and its helpers. Call once. */
    fun start() {
        check(workers.isEmpty()) { "Engine already started" }
        workers = listOf(
            scope.launch(lane) { laneLoop() },
            scope.launch { reaperLoop() },
            scope.launch { environment.collect { onEnvironment(it) } },
        )
    }

    // ------------------------------------------------------------------------------------
    // Admission, off the lane
    // ------------------------------------------------------------------------------------

    /**
     * Validates, renders and queues [request], or refuses it before anything is queued.
     *
     * @throws Refusal with the reason, so the HTTP layer can answer with a real status.
     */
    suspend fun submit(request: GenerationRequest): Job {
        try {
            return admit(request)
        } catch (refusal: Refusal) {
            _status.update { it.copy(totals = it.totals.copy(refused = it.totals.refused + 1)) }
            throw refusal
        }
    }

    private suspend fun admit(request: GenerationRequest): Job {
        val cfg = config
        val entry = models.resolve(request.model)
            ?: throw Refusal.UnknownModel(request.model, models.all().map { it.id })
        _status.value.broken[entry.id]?.let { reason ->
            throw Refusal.Unavailable("'${entry.id}' failed to load: $reason")
        }
        request.maxTokens?.let { if (it <= 0) throw Refusal.Invalid("max_tokens must be positive", "max_tokens") }
        request.temperature?.let {
            if (it < 0f || it > 2f) throw Refusal.Invalid("temperature must be between 0 and 2", "temperature")
        }
        val prompt = prepare(entry, request)
        refusalFor(environment.value, cfg)?.let { throw it }

        val job = lock.withLock {
            if (stopping) throw Refusal.Unavailable("The server is stopping.")
            val waiting = queue.count { it is Item.Generate }
            if (waiting >= cfg.maxQueued) {
                throw Refusal.QueueFull(
                    "The queue is full ($waiting waiting). Retry shortly.",
                    retryAfterMs(waiting),
                )
            }
            val mine = perClient[request.client.id] ?: 0
            if (mine >= cfg.maxPerClient) {
                throw Refusal.ClientLimit(
                    "This client already has $mine requests waiting or running (limit ${cfg.maxPerClient}).",
                    retryAfterMs(waiting),
                )
            }
            val created = Job(newId(), request, entry, prompt, clock(), cfg.eventBuffer)
            queue += Item.Generate(created)
            perClient[request.client.id] = mine + 1
            publishQueueLocked()
            created
        }
        wake.trySend(Unit)
        return job
    }

    /** Every installed model. */
    fun installed(): List<ModelEntry> = models.all()

    /** The installed model a client means by [name], if exactly one. */
    fun resolve(name: String): ModelEntry? = models.resolve(name)

    /** The chat template [entry] is rendered with, or null for raw prompts only. */
    fun templateFor(entry: ModelEntry): PromptTemplate? =
        PromptTemplates.forModel(entry.family ?: entry.files.model.substringAfterLast('/'))

    /** Makes [modelName] resident, waiting for the lane like any request. */
    suspend fun load(modelName: String) {
        val entry = models.resolve(modelName)
            ?: throw Refusal.UnknownModel(modelName, models.all().map { it.id })
        command(Action.Load(entry))
    }

    /** Unloads [modelName], or every model when null. */
    suspend fun unload(modelName: String?) {
        val id = modelName?.let { name ->
            (models.resolve(name) ?: throw Refusal.UnknownModel(name, models.all().map { it.id })).id
        }
        command(Action.Unload(id))
    }

    /** Frees memory: idle models without waiting requests, or all of them when [force]d. */
    suspend fun evictIdle(force: Boolean) = command(Action.EvictIdle(force))

    /** Forgets load failures, after the model files were replaced. */
    suspend fun forgetFailures() = command(Action.ForgetFailures)

    private suspend fun command(action: Action) {
        val done = CompletableDeferred<Unit>()
        lock.withLock {
            if (stopping) throw Refusal.Unavailable("The server is stopping.")
            queue += Item.Command(action, done)
        }
        wake.trySend(Unit)
        done.await()
    }

    /** Cancels one job by id, queued or running. */
    suspend fun cancel(jobId: String) {
        current?.takeIf { it.id == jobId }?.cancel(FailureKind.CANCELLED)
        lock.withLock {
            queue.forEach { if (it is Item.Generate && it.job.id == jobId) it.job.cancel(FailureKind.CANCELLED) }
        }
        wake.trySend(Unit)
    }

    /**
     * Closes admission, fails whatever is queued, gives the running job [graceMs] to finish,
     * cancels it, and closes every model. The engine cannot be restarted.
     */
    suspend fun stop(graceMs: Long = 5_000) {
        val dropped = lock.withLock {
            stopping = true
            queue.toList().also {
                queue.clear()
                perClient.clear()
                publishQueueLocked()
            }
        }
        dropped.forEach { item ->
            when (item) {
                is Item.Generate -> fail(item.job, FailureKind.SHUTTING_DOWN, "The server is stopping.")
                is Item.Command -> item.done.complete(Unit)
            }
        }
        _status.update { it.copy(admission = Admission.STOPPED) }
        val until = clock() + graceMs
        while (current != null && clock() < until) delay(POLL_MS)
        current?.cancel(FailureKind.SHUTTING_DOWN)
        withTimeoutOrNull(config.wedgeGraceMs) {
            withContext(lane) {
                residents.values.toList().forEach(::evict)
                publishResidents()
            }
        }
        workers.forEach { it.cancel() }
        _status.update { it.copy(lane = LaneState.STOPPED, admission = Admission.STOPPED, running = null) }
    }

    // ------------------------------------------------------------------------------------
    // Preparation, off the lane: pure text work, done before queueing so a bad request is
    // refused with a status instead of discovered by the lane.
    // ------------------------------------------------------------------------------------

    /**
     * The prompt [request] would feed, rendered by the model's template and checked the same
     * way, without queueing anything: what a client compares against its own rendering when
     * a turn misses the cache. The canonical rendering, not the reply ledger's, so nothing
     * one key sent shapes what another key is shown.
     *
     * @throws Refusal as [submit] would.
     */
    fun render(request: GenerationRequest): String {
        val entry = models.resolve(request.model) ?: throw Refusal.UnknownModel(request.model, models.all().map { it.id })
        return prepare(entry, request.copy(client = ClientId(RENDER_ONLY, RENDER_ONLY))).text
    }

    private fun prepare(entry: ModelEntry, request: GenerationRequest): PreparedPrompt {
        val template = templateFor(entry)
        val bos = template?.bosToken?.takeUnless { runtime.tokenizerAddsBos(entry.files) }.orEmpty()
        val prepared = when (val input = request.input) {
            is PromptInput.Chat -> {
                template ?: throw Refusal.Unsupported(
                    "'${entry.id}' has no chat template this server knows (it renders " +
                        PromptTemplates.known.joinToString(", ") +
                        "). Send a raw prompt to /v1/completions instead.",
                    "messages",
                )
                if (input.messages.isEmpty()) throw Refusal.Invalid("messages must not be empty", "messages")
                if (input.tools.isNotEmpty() && !template.supportsTools) {
                    throw Refusal.Unsupported("The chat format of '${entry.id}' cannot express tools.", "tools")
                }
                val thinking = input.thinking ?: config.defaultThinking ?: template.supportsThinking
                val text = try {
                    bos + template.render(input.messages, input.tools, thinking)
                } catch (invalid: IllegalArgumentException) {
                    throw Refusal.Invalid(invalid.message ?: "These messages cannot be rendered", "messages")
                }
                val substituted = substituted(ledgerKey(entry.id, request.client.id), input)
                val verbatim = substituted?.let { runCatching { bos + template.render(it, input.tools, thinking) }.getOrNull() }
                PreparedPrompt(
                    text = text,
                    chat = true,
                    template = template,
                    toolsOffered = input.tools.isNotEmpty(),
                    startsInThought = text.endsWith("<think>\n"),
                    verbatim = verbatim?.takeIf { it != text },
                    chatInput = input,
                    verbatimMessages = substituted,
                    thinking = thinking,
                    bos = bos,
                )
            }
            is PromptInput.Raw -> {
                if (input.text.isEmpty()) throw Refusal.Invalid("prompt must not be empty", "prompt")
                val text = if (bos.isNotEmpty() && !input.text.startsWith(bos)) bos + input.text else input.text
                PreparedPrompt(text, chat = false, template = template, toolsOffered = false, startsInThought = false)
            }
        }
        val window = entry.contextLength ?: knownWindows[entry.id]
        // Six characters a token is more than any shipped tokenizer averages on prose, so
        // a prompt refused here would certainly not have fitted. Anything closer is left to
        // the runtime, which knows; the estimate must never refuse what would have run.
        if (window != null && prepared.text.length / GENEROUS_CHARS_PER_TOKEN >= window) {
            throw Refusal.TooLong(
                "This prompt is about ${prepared.text.length / CHARS_PER_TOKEN} tokens; " +
                    "'${entry.id}' was exported with a $window-token window.",
            )
        }
        return prepared
    }

    /**
     * [input]'s messages with each reply this server recognises written back as the bytes
     * its runtime holds, or null when none is recognised. Reasoning is written back only
     * where Qwen's own template keeps it (after the latest question, as in a tool loop)
     * unless the user opted in.
     */
    private fun substituted(ledgerKey: String, input: PromptInput.Chat): List<ChatMessage>? {
        val ledger = ledgers[ledgerKey]?.toMap() ?: return null
        val latestQuestion = input.messages.indexOfLast { it.role == ChatRole.USER }
        var changed = false
        val messages = input.messages.mapIndexed { index, message ->
            if (message.role != ChatRole.ASSISTANT) return@mapIndexed message
            val known = ledger[message.text] ?: return@mapIndexed message
            if (known.reasoned && index < latestQuestion && !config.keepReasoningInHistory) return@mapIndexed message
            changed = true
            ChatMessage(ChatRole.ASSISTANT, listOf(MessagePart.Text(known.stored)))
        }
        return messages.takeIf { changed }
    }

    /** Records what the runtime holds for the reply just finished. See [ledgers]. */
    private fun learn(
        ledgerKey: String,
        prompt: PreparedPrompt,
        rendered: String,
        messages: List<ChatMessage>,
        raw: String,
        content: String,
        calls: List<ToolCall>,
        reasoned: Boolean,
    ) {
        val input = prompt.chatInput ?: return
        val template = prompt.template ?: return
        // The opener is what the generation prompt wrote after the assistant header, found
        // as the part of this prompt a rendering with one more (probe) turn does not share.
        val probe = runCatching {
            prompt.bos + template.render(
                messages + ChatMessage.text(ChatRole.ASSISTANT, PROBE) + ChatMessage.text(ChatRole.USER, "x"),
                input.tools,
                prompt.thinking,
            )
        }.getOrNull() ?: return
        val opener = rendered.substring(rendered.commonPrefixWith(probe).length)
        if (opener.length > MAX_OPENER_CHARS || PROBE in opener) return
        val key = HistoryText.of(content, calls, template)
        val entry = key to LedgerEntry(opener + raw, reasoned)
        val kept = ledgers[ledgerKey].orEmpty().filter { it.first != key }
        ledgers = ledgers + (ledgerKey to (listOf(entry) + kept).take(LEDGER_SIZE))
    }

    // ------------------------------------------------------------------------------------
    // The lane
    // ------------------------------------------------------------------------------------

    private suspend fun laneLoop() {
        while (true) {
            val item = next()
            // Nothing a model does may end the lane: a surprise here fails one job, and the
            // next one runs.
            try {
                when (item) {
                    is Item.Generate -> runJob(item.job)
                    is Item.Command -> runCommand(item)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (surprise: Throwable) {
                if (item is Item.Generate) {
                    fail(item.job, FailureKind.RUNTIME, surprise.message ?: surprise::class.simpleName ?: "error")
                }
            }
        }
    }

    private suspend fun next(): Item {
        while (true) {
            val picked = lock.withLock {
                purgeLocked(clock())
                pickLocked(clock())?.also {
                    queue.remove(it)
                    if (it is Item.Generate) current = it.job
                    publishQueueLocked()
                }
            }
            if (picked != null) return picked
            wake.receive()
        }
    }

    /**
     * Commands go in arrival order. Among requests, the oldest one whose model is already
     * resident goes first, unless the oldest of all has waited past the affinity limit: a
     * preference that stops two clients on two models from reloading on every request, with
     * a bound on how long it may pass anyone over.
     */
    private fun pickLocked(now: Long): Item? {
        val head = queue.firstOrNull() ?: return null
        if (head is Item.Command) return head
        val oldest = queue.first { it is Item.Generate } as Item.Generate
        if (oldest.job.model.id in residents) return oldest
        if (now - oldest.job.submittedAtMs >= config.maxAffinityWaitMs) return oldest
        return queue.firstOrNull { it is Item.Generate && it.job.model.id in residents } ?: oldest
    }

    /** Fails queued jobs that were cancelled or waited too long. Under [lock]. */
    /** Fails queued jobs that were cancelled or waited too long; true when it removed any. */
    private fun purgeLocked(now: Long): Boolean {
        val cfg = config
        var purged = false
        val iterator = queue.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() as? Item.Generate ?: continue
            val job = item.job
            val waited = now - job.submittedAtMs
            val reason = when {
                job.cancelled != null -> job.cancelled!! to "Cancelled while queued."
                waited >= cfg.queueTimeoutMs -> FailureKind.QUEUE_TIMEOUT to
                    "Waited ${waited / MS_PER_SECOND} s for the model without reaching it."
                else -> null
            } ?: continue
            iterator.remove()
            releaseLocked(job)
            fail(job, reason.first, reason.second)
            purged = true
        }
        if (purged) publishQueueLocked()
        return purged
    }

    private fun releaseLocked(job: Job) {
        val id = job.request.client.id
        val left = (perClient[id] ?: 1) - 1
        if (left <= 0) perClient.remove(id) else perClient[id] = left
    }

    private suspend fun runJob(job: Job) {
        val started = clock()
        job.startedAtMs = started
        try {
            job.cancelled?.let { return fail(job, it, "Cancelled before it started.") }
            val loadStarted = clock()
            val opening = job.model.id !in residents
            val resident = try {
                residentFor(job.model)
            } catch (failure: RuntimeFailure) {
                return fail(job, FailureKind.MODEL_UNAVAILABLE, failure.message ?: "The model could not be loaded.")
            }
            // Zero unless this request paid for the open: a resident model costs nothing here.
            val loadMs = if (opening) clock() - loadStarted else 0
            job.emit(JobEvent.Started(job.model.id, started - job.submittedAtMs, loadMs))
            generate(job, resident, started - job.submittedAtMs, loadMs)
        } catch (cancelled: CancellationException) {
            fail(job, FailureKind.SHUTTING_DOWN, "The server is stopping.")
            throw cancelled
        } finally {
            current = null
            withContext(NonCancellable) {
                lock.withLock {
                    releaseLocked(job)
                    publishQueueLocked()
                }
            }
            setLane(LaneState.IDLE, running = null)
            publishResidents()
            meanJobMs = meanJobMs * (1 - EWMA) + (clock() - started) * EWMA
        }
    }

    private fun generate(job: Job, resident: Resident, queueMs: Long, loadMs: Long) {
        val prompt = job.prompt
        val session = resident.session
        val markers = prompt.template?.stopMarkers.orEmpty()

        // The sequence cache. An extension is fed as a suffix only when the boundary sits
        // right before a special token, which tokenizes the same whether split there or
        // not; anything else starts from zero. See ARCHITECTURE.md, "The sequence cache".
        val held = resident.fedText
        fun extendsCache(text: String) = prompt.chat &&
            // Only the key whose turn the runtime holds may continue it: another key would
            // learn from cached_tokens and the first token's time what that turn began with.
            resident.owner == job.request.client.id &&
            held.isNotEmpty() &&
            text.length > held.length &&
            text.startsWith(held) &&
            markers.any { text.startsWith(it, held.length) }
        // The ledger's rendering is used only when it is what makes the cache hit.
        val verbatim = prompt.verbatim?.takeIf { extendsCache(it) }
        val useVerbatim = verbatim != null
        val text = verbatim ?: prompt.text
        val extending = useVerbatim || extendsCache(text)
        resident.fedText = ""
        val reused = if (extending) resident.heldTokens else 0
        var fresh = if (extending) text.substring(held.length) else text

        var fedChars = 0
        var fedMs = 0L
        try {
            if (!extending) {
                if (resident.dirty) session.reset()
                resident.dirty = false
                resident.heldTokens = 0
            }
            setLane(LaneState.PREFILLING, running = running(job, 0, reused))
            val total = fresh.length
            val readingSince = clock()
            fun progress() {
                if (job.request.reportProgress) job.emit(JobEvent.Progress(total - fresh.length, total, reused, clock() - readingSince))
            }
            progress()
            while (fresh.length > resident.callChars) {
                job.cancelled?.let { return fail(job, it, cancelMessage(it)) }
                val piece = warmPiece(fresh, resident.callChars)
                val before = clock()
                resident.dirty = true
                session.prefill(piece)
                fedMs += clock() - before
                fedChars += piece.length
                fresh = fresh.substring(piece.length)
                progress()
            }
        } catch (overflow: ContextOverflow) {
            return fail(job, FailureKind.CONTEXT_OVERFLOW, overflow.message ?: "The prompt does not fit.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A runtime that failed mid-prefill, or could not reset, holds an unknown state.
            evict(resident)
            return fail(job, FailureKind.RUNTIME, failure.message ?: "The runtime failed.")
        }
        job.cancelled?.let { return fail(job, it, cancelMessage(it)) }

        val window = resident.window
        val budget = minOf(job.request.maxTokens ?: Int.MAX_VALUE, window ?: Int.MAX_VALUE)
        val pipeline = TokenPipeline(markers, job.request.stop, budget, prompt.startsInThought, prompt.toolsOffered)
        val temperature = job.request.temperature ?: config.defaultTemperature
        val generateStarted = clock()
        var firstTokenAt = 0L
        var lastProgressAt = 0L
        var windowFilled = false
        setLane(LaneState.GENERATING, running = running(job, 0, reused))
        resident.dirty = true

        val outcome = try {
            session.generate(fresh, temperature) { fragment ->
                val now = clock()
                if (firstTokenAt == 0L) firstTokenAt = now
                val out = pipeline.accept(fragment)
                if (!out.isEmpty) job.emit(JobEvent.Delta(out.content, out.reasoning))
                if (now - lastProgressAt >= PROGRESS_MS) {
                    lastProgressAt = now
                    _status.update { it.copy(running = running(job, pipeline.tokens, reused, firstTokenAt)) }
                }
                // The only place stop() is called: inside the running generation's own
                // callback, so it cannot reach any other job. Re-issued on every token
                // because the runner clears its flag when its loop starts.
                if (job.cancelled != null || pipeline.shouldStop) session.stop()
            }
        } catch (overflow: ContextOverflow) {
            if (pipeline.tokens == 0) {
                return fail(job, FailureKind.CONTEXT_OVERFLOW, overflow.message ?: "The prompt does not fit.")
            }
            windowFilled = true
            RuntimeOutcome()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // A native generate that failed leaves state nobody can vouch for: reopen it.
            evict(resident)
            publishResidents()
            return fail(job, FailureKind.RUNTIME, failure.message ?: "The runtime failed.")
        }
        val finished = clock()

        val (tail, calls) = pipeline.finish()
        if (!tail.isEmpty) job.emit(JobEvent.Delta(tail.content, tail.reasoning))
        job.cancelled?.let { return fail(job, it, cancelMessage(it)) }

        // Accounting. The runtime counts the generate call's own prompt; text fed ahead is
        // estimated at that call's characters-per-token rate, since there is no tokenizer
        // on this side of the runtime.
        val tailTokens = outcome.promptTokens.takeIf { it > 0 } ?: (fresh.length / CHARS_PER_TOKEN).coerceAtLeast(1)
        val aheadTokens = if (fedChars == 0) 0 else (fedChars.toLong() * tailTokens / fresh.length.coerceAtLeast(1)).toInt()
        val promptTokens = reused + aheadTokens + tailTokens
        val completionTokens = pipeline.tokens
        // The last token sampled is never fed back: a stop or an end ends the loop first.
        resident.heldTokens = promptTokens + (completionTokens - 1).coerceAtLeast(0)
        if (window != null && resident.heldTokens >= window - 1) windowFilled = true

        // Kept only after the template's own end marker, when the runtime is known to hold
        // exactly the prompt and the reply (the unfed end token is re-fed by the next turn).
        val clean = prompt.chat && pipeline.endedOnMarker && pipeline.markerAlone && !pipeline.cut
        if (clean) {
            resident.fedText = text + pipeline.rawAnswer
            resident.owner = job.request.client.id
        }

        val finish = when {
            calls.isNotEmpty() -> FinishReason.TOOL_CALLS
            pipeline.endedOnMarker || pipeline.endedOnStop -> FinishReason.STOP
            pipeline.cut || windowFilled -> FinishReason.LENGTH
            else -> FinishReason.STOP
        }
        val prefillMs = fedMs + (outcome.prefillMs.takeIf { it > 0 } ?: ((firstTokenAt.takeIf { it > 0 } ?: finished) - generateStarted))
        val decodeMs = outcome.decodeMs.takeIf { it > 0 } ?: (if (firstTokenAt > 0) finished - firstTokenAt else 0)
        val result = GenerationResult(
            model = job.model.id,
            finishReason = finish,
            content = pipeline.content.toString(),
            reasoning = pipeline.reasoning.toString(),
            toolCalls = calls.mapIndexed { index, call -> call.copy(id = "call_${job.id.takeLast(ID_TAIL)}_$index") },
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = reused,
            stopSequence = pipeline.stoppedBy,
            timings = JobTimings(
                queueMs = queueMs,
                loadMs = loadMs,
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                firstTokenMs = if (firstTokenAt > 0) firstTokenAt - job.submittedAtMs else 0,
            ),
        )
        if (clean) {
            learn(
                ledgerKey = ledgerKey(job.model.id, job.request.client.id),
                prompt = prompt,
                rendered = text,
                messages = (if (useVerbatim) prompt.verbatimMessages else null) ?: prompt.chatInput?.messages.orEmpty(),
                raw = pipeline.rawAnswer,
                content = result.content,
                calls = result.toolCalls,
                reasoned = result.reasoning.isNotBlank(),
            )
        }
        job.complete(JobEvent.Finished(result))
        record(
            job, finish, null,
            Measured(
                prompt = result.promptTokens,
                completion = completionTokens,
                cached = reused,
                estimated = aheadTokens,
                loadMs = loadMs,
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                decodeWallMs = if (firstTokenAt > 0) finished - firstTokenAt else 0,
                firstTokenMs = result.timings.firstTokenMs,
            ),
        )
    }

    private fun runCommand(item: Item.Command) {
        var failure: Throwable? = null
        try {
            when (val action = item.action) {
                is Action.Load -> try {
                    residentFor(action.entry)
                } catch (loadFailure: RuntimeFailure) {
                    failure = loadFailure
                }
                is Action.Unload -> residents.values
                    .filter { action.id == null || it.entry.id == action.id }
                    .forEach(::evict)
                is Action.EvictIdle -> {
                    val now = clock()
                    val idleFor = config.idleUnloadMs
                    residents.values
                        .filter { action.force || (idleFor > 0 && now - it.lastUsedMs >= idleFor) }
                        .forEach(::evict)
                }
                Action.ForgetFailures -> {
                    broken.clear()
                    _status.update { it.copy(broken = emptyMap()) }
                }
            }
            publishResidents()
        } finally {
            setLane(LaneState.IDLE, running = null)
            failure?.let { item.done.completeExceptionally(it) } ?: item.done.complete(Unit)
        }
    }

    /** The resident session for [entry], loading it (and evicting to make room) if needed. */
    private fun residentFor(entry: ModelEntry): Resident {
        residents[entry.id]?.let {
            it.lastUsedMs = clock()
            return it
        }
        broken[entry.id]?.let { throw RuntimeFailure(it) }
        while (residents.isNotEmpty() && residents.size >= config.maxResidentModels.coerceAtLeast(1)) {
            evict(residents.values.minBy { it.lastUsedMs })
        }
        setLane(LaneState.LOADING, running = current?.let { running(it, 0, 0) })
        val facts = runCatching { runtime.probe(entry.files) }.getOrElse { ModelFacts(entry.contextLength) }
        val session = try {
            runtime.open(entry.files, facts)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val reason = failure.message ?: failure::class.simpleName ?: "unknown error"
            broken[entry.id] = reason
            _status.update { it.copy(broken = broken.toMap()) }
            throw RuntimeFailure(reason, failure)
        }
        val window = facts.contextLength ?: entry.contextLength
        val callChars = minOf(GENERATE_TAIL_CHARS, (facts.prefillLength ?: Int.MAX_VALUE) - 1).coerceAtLeast(1)
        val now = clock()
        val resident = Resident(entry, session, window, callChars, now, runtime.activeThreads())
        residents[entry.id] = resident
        window?.let { knownWindows = knownWindows + (entry.id to it) }
        publishResidents()
        return resident
    }

    private fun evict(resident: Resident) {
        residents.remove(resident.entry.id)
        runCatching { resident.session.close() }
    }

    // ------------------------------------------------------------------------------------
    // The reaper, off the lane
    // ------------------------------------------------------------------------------------

    private suspend fun reaperLoop() {
        var cancelSeenAt = 0L
        var watched: Job? = null
        var idleCheckAt = 0L
        while (true) {
            delay(REAPER_MS)
            val now = clock()
            // The lane is woken only when the queue changed: an idle server stays asleep.
            if (lock.withLock { purgeLocked(now) }) wake.trySend(Unit)

            val running = current
            if (running != null && now - running.submittedAtMs >= config.requestTimeoutMs) {
                running.cancel(FailureKind.DEADLINE)
            }
            // A cancelled job whose native call does not return is a wedged runtime, not a
            // slow one: nothing else can run until it does.
            if (running != null && running.cancelled != null) {
                if (watched !== running) {
                    watched = running
                    cancelSeenAt = now
                } else if (!wedged && now - cancelSeenAt >= config.wedgeGraceMs) {
                    wedged = true
                    _status.update { it.copy(lane = LaneState.WEDGED, admission = Admission.WEDGED) }
                    onWedged()
                }
            } else {
                watched = null
            }

            val idleFor = config.idleUnloadMs
            if (idleFor > 0 && running == null && now >= idleCheckAt) {
                idleCheckAt = now + IDLE_CHECK_MS
                val stale = _status.value.resident.any { now - it.lastUsedMs >= idleFor }
                if (stale && !stopping) scope.launch { runCatching { evictIdle(force = false) } }
            }
        }
    }

    private fun onEnvironment(env: Environment) {
        if (env.thermal >= config.cancelRunningAt) current?.cancel(FailureKind.OVERHEATED)
        val admission = when (refusalFor(env, config)) {
            null -> if (stopping) Admission.STOPPED else Admission.OPEN
            is Refusal.Paused -> if (env.thermal >= config.stopAdmittingAt) Admission.PAUSED_THERMAL else Admission.PAUSED_BATTERY
            else -> _status.value.admission
        }
        _status.update { it.copy(admission = admission) }
    }

    private fun refusalFor(env: Environment, cfg: EngineConfig): Refusal? = when {
        wedged -> Refusal.Unavailable("The runtime stopped responding; the server is restarting.")
        env.thermal >= cfg.stopAdmittingAt -> Refusal.Paused(
            "The phone is too hot to take more work (thermal ${env.thermal.name.lowercase()}).",
            THERMAL_RETRY_MS,
        )
        cfg.minBatteryPercent > 0 && !env.charging && (env.batteryPercent ?: 100) < cfg.minBatteryPercent ->
            Refusal.Paused("The battery is below ${cfg.minBatteryPercent}% and not charging.", BATTERY_RETRY_MS)
        else -> null
    }

    // ------------------------------------------------------------------------------------
    // Bookkeeping
    // ------------------------------------------------------------------------------------

    private fun fail(job: Job, kind: FailureKind, message: String) {
        if (job.isComplete) return
        job.complete(JobEvent.Failed(Failure(kind, message)))
        record(job, null, kind)
    }

    /** What one finished generation measured, for its record. */
    private class Measured(
        val prompt: Int = 0,
        val completion: Int = 0,
        val cached: Int = 0,
        val estimated: Int = 0,
        val loadMs: Long = 0,
        val prefillMs: Long = 0,
        val decodeMs: Long = 0,
        val decodeWallMs: Long = 0,
        val firstTokenMs: Long = 0,
    )

    private fun record(job: Job, finish: FinishReason?, failure: FailureKind?, measured: Measured = Measured()) {
        val now = clock()
        val succeeded = finish != null
        val device = environment.value
        val entry = JobRecord(
            id = job.id,
            model = job.model.id,
            client = job.request.client.label,
            finishedAtMs = now,
            finish = finish,
            failure = failure,
            promptTokens = measured.prompt,
            completionTokens = measured.completion,
            cachedTokens = measured.cached,
            queueMs = (job.startedAtMs.takeIf { it > 0 } ?: now) - job.submittedAtMs,
            totalMs = now - job.submittedAtMs,
            loadMs = measured.loadMs,
            prefillMs = measured.prefillMs,
            decodeMs = measured.decodeMs,
            decodeWallMs = measured.decodeWallMs,
            firstTokenMs = measured.firstTokenMs,
            estimatedPromptTokens = measured.estimated,
            api = job.request.api,
            stream = job.request.stream,
            clientId = job.request.client.id,
            threads = residents[job.model.id]?.threads,
            thermal = device.thermal,
            batteryPercent = device.batteryPercent,
            charging = device.charging,
        )
        _status.update { status ->
            val totals = status.totals
            status.copy(
                recent = (listOf(entry) + status.recent).take(config.recentJobs),
                totals = totals.copy(
                    completed = totals.completed + if (succeeded) 1 else 0,
                    failed = totals.failed + if (succeeded) 0 else 1,
                    promptTokens = totals.promptTokens + measured.prompt,
                    completionTokens = totals.completionTokens + measured.completion,
                    cachedTokens = totals.cachedTokens + measured.cached,
                    prefillMs = totals.prefillMs + measured.prefillMs,
                    decodeMs = totals.decodeMs + measured.decodeMs,
                    loads = totals.loads + if (measured.loadMs > 0) 1 else 0,
                    loadMs = totals.loadMs + measured.loadMs,
                ),
            )
        }
        _records.tryEmit(entry)
    }

    private fun running(job: Job, tokens: Int, cached: Int, firstTokenAt: Long = 0) = RunningJob(
        id = job.id,
        model = job.model.id,
        client = job.request.client.label,
        startedAtMs = job.startedAtMs,
        generatedTokens = tokens,
        cachedTokens = cached,
        firstTokenAtMs = firstTokenAt,
    )

    private fun setLane(state: LaneState, running: RunningJob?) {
        if (wedged) return
        _status.update { it.copy(lane = state, running = running) }
    }

    private fun publishQueueLocked() {
        val waiting = queue.count { it is Item.Generate }
        _status.update { it.copy(queued = waiting) }
    }

    private fun publishResidents() {
        val snapshot = residents.values.map {
            ResidentInfo(it.entry.id, it.window, it.heldTokens, it.loadedAtMs, it.lastUsedMs)
        }
        _status.update { it.copy(resident = snapshot) }
    }

    private fun retryAfterMs(waiting: Int): Long =
        (meanJobMs * (waiting + 1)).toLong().coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)

    private fun cancelMessage(kind: FailureKind): String = when (kind) {
        FailureKind.DEADLINE -> "The request ran past its deadline."
        FailureKind.CLIENT_GONE -> "The client disconnected."
        FailureKind.SLOW_CLIENT -> "The client read the stream too slowly."
        FailureKind.OVERHEATED -> "The phone overheated."
        FailureKind.SHUTTING_DOWN -> "The server is stopping."
        else -> "Cancelled."
    }

    private fun newId(): String = buildString {
        repeat(ID_LENGTH) { append(ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)]) }
    }

    /** One loaded model and what its runtime is known to hold. Lane-confined. */
    private class Resident(
        val entry: ModelEntry,
        val session: LlmSession,
        val window: Int?,
        val callChars: Int,
        val loadedAtMs: Long,
        /** CPU threads the runtime reported after opening this model. */
        val threads: Int?,
    ) {
        var lastUsedMs: Long = loadedAtMs

        /** The exact text the runtime holds since its last reset, or empty when unknown. */
        var fedText: String = ""

        /** The client whose conversation [fedText] is; only it may extend the cache. */
        var owner: String? = null

        /** Occupied positions, estimated; kept even when [fedText] is unknown. */
        var heldTokens: Int = 0

        /** Whether the runtime has consumed anything since its last reset. */
        var dirty: Boolean = false
    }

    private companion object {
        const val GENEROUS_CHARS_PER_TOKEN = 6
        const val POLL_MS = 50L
        const val REAPER_MS = 250L
        const val PROGRESS_MS = 250L
        const val IDLE_CHECK_MS = 30_000L
        const val THERMAL_RETRY_MS = 60_000L
        const val BATTERY_RETRY_MS = 300_000L
        const val MIN_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 120_000L
        const val MS_PER_SECOND = 1_000L
        const val RECORD_BUFFER = 64

        /** A client id no key has, so a rendering never picks up anyone's ledger. */
        const val RENDER_ONLY = "\u0000render"
        const val EWMA = 0.2
        const val ID_LENGTH = 24
        const val ID_TAIL = 8
        const val ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        const val LEDGER_SIZE = 16
        const val MAX_OPENER_CHARS = 64
        const val PROBE = "\u0001execuserve-probe\u0001"
    }
}
