package org.experimentalmachines.execuserve.app.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.experimentalmachines.execuserve.app.R
import org.experimentalmachines.execuserve.app.graph
import org.experimentalmachines.execuserve.app.models.CatalogRepo
import org.experimentalmachines.execuserve.app.serve.LocalClient
import org.experimentalmachines.execuserve.app.serve.ServeService
import org.experimentalmachines.execuserve.app.serve.StoppedByUser
import org.experimentalmachines.execuserve.catalog.CatalogVariant
import org.experimentalmachines.execuserve.catalog.HfCatalog
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.Metrics
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.executorch.NeuroPilotSupport
import org.experimentalmachines.execuserve.executorch.QnnSupport
import org.experimentalmachines.execuserve.executorch.VulkanSupport
import org.experimentalmachines.execuserve.host.CONSOLE_KEY
import org.experimentalmachines.execuserve.host.ConsoleChat
import org.experimentalmachines.execuserve.host.ConsoleTest
import org.experimentalmachines.execuserve.host.FORMER_CONSOLE_KEYS
import org.experimentalmachines.execuserve.host.HostSettings
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.TestFailure
import org.experimentalmachines.execuserve.host.TestResult
import org.experimentalmachines.execuserve.server.ApiKey
import org.experimentalmachines.execuserve.server.BindMode
import org.experimentalmachines.execuserve.server.Pairings

/** One message in the console's chat. A reply fills in as it streams. */
data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val content: String,
    val reasoning: String = "",
    /** The model that answered, for its label and a report. */
    val model: String? = null,
    val running: Boolean = false,
    val result: TestResult? = null,
    val failure: TestFailure? = null,
    val stopped: Boolean = false,
    /** How long the model thought before answering, once it has; null while it thinks or if it did not. */
    val reasoningMs: Long? = null,
)

/** The console's chat: one conversation in memory, gone when the app process is. */
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    /** Chosen in the picker; null until someone chooses, so the screen can suggest one. */
    val model: String? = null,
    val thinking: Boolean = false,
) {
    val running: Boolean get() = messages.lastOrNull()?.running == true
}

sealed interface CatalogState {
    data object Idle : CatalogState
    data object Loading : CatalogState
    data class Loaded(val repos: List<CatalogRepo>) : CatalogState
    data class Failed(val message: String) : CatalogState
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val sharing = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS)

    val server: StateFlow<ServeHost.State> = graph.host.state
    val status: StateFlow<EngineStatus?> = graph.host.status.stateIn(viewModelScope, sharing, null)
    val environment = graph.host.environment
    val settings: StateFlow<HostSettings?> = graph.settings.settings.stateIn(viewModelScope, sharing, null)
    val keys: StateFlow<List<ApiKey>> = graph.settings.keys.stateIn(viewModelScope, sharing, emptyList())
    val recovery = graph.settings.recovery.stateIn(viewModelScope, sharing, null)
    val installed = graph.models.installed
    val problems = graph.models.problems
    val downloads = graph.downloader.state
    val modelsFolder: String = graph.models.directory.absolutePath

    /** Each lab's picture, once fetched; [showLab] asks for one. */
    val labImages = graph.labs.images

    fun showLab(lab: String) = graph.labs.request(lab)

    /**
     * Memory the system would give a new model, sampled while the console is on screen. A
     * binder call: once every few seconds, never per token.
     */
    val freeMemory: StateFlow<Long> = flow {
        val activity = app.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        while (true) {
            activity.getMemoryInfo(info)
            emit(info.availMem)
            delay(MEMORY_SAMPLE_MS)
        }
    }.stateIn(viewModelScope, sharing, 0L)

    private val client = LocalClient()

    /** The chat request in flight, for Stop; only touched on the main thread. */
    private var chatCall: LocalClient.Call? = null

    private val _chat = MutableStateFlow(ChatState())
    val chat: StateFlow<ChatState> = _chat.asStateFlow()
    private var nextMessageId = 0L

    private val _catalog = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val catalog: StateFlow<CatalogState> = _catalog.asStateFlow()

    init {
        viewModelScope.launch { graph.settings.ensureKeys() }
    }

    // Server ------------------------------------------------------------------------------

    fun start() = ServeService.start(app)

    fun stop() = ServeService.stop(app)

    fun restart() = ServeService.restart(app)

    /** Who can connect: saved, and applied at once by a restart when the server is running. */
    fun setBind(mode: BindMode) = viewModelScope.launch {
        if (settings.value?.bind == mode) return@launch
        graph.settings.update { it.copy(bind = mode) }
        if (server.value is ServeHost.State.Running) restart()
    }

    fun cancelJob(id: String) = viewModelScope.launch { graph.host.cancel(id) }

    /** The key a person shares: theirs, not the one the console's own chat uses. */
    fun shareableKey(keys: List<ApiKey>): ApiKey? = keys.firstOrNull { it.name != CONSOLE_KEY && it.name !in FORMER_CONSOLE_KEYS } ?: keys.firstOrNull()

    /** The endpoint and key as one message, for the share sheet. */
    fun shareConnection(baseUrl: String, key: String, model: String?) {
        val text = app.getString(R.string.connect_share_text, baseUrl, key) +
            (model?.let { app.getString(R.string.connect_share_model, it) } ?: "")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        app.startActivity(Intent.createChooser(send, app.getString(R.string.connect_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Hands [text] to whichever app the person picks: a chat, notes, the app being set up. */
    fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        app.startActivity(Intent.createChooser(send, app.getString(R.string.connect_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The two lines most OpenAI tools read, for pasting into a shell. */
    fun terminalExports(baseUrl: String, key: String): String = ConsoleTest.exports(baseUrl, key)

    // Runs --------------------------------------------------------------------------------

    /** Every request the server finished, newest first, across restarts. */
    val runs: StateFlow<List<JobRecord>> = graph.history.runs

    fun clearHistory() = viewModelScope.launch { graph.history.clear() }

    /** The history as a CSV file, handed to whichever app the person picks. */
    fun exportRuns() = viewModelScope.launch {
        val file = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.cacheDir.resolve("exports").apply { mkdirs() }.resolve("execuserve-runs.csv").also {
                it.writeText(Metrics.csv(runs.value))
            }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".exports", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(send, app.getString(R.string.runs_export)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** CPU threads the runtime computes with while a model is loaded. */
    val activeThreads = graph.host.activeThreads

    /** Processor cores, for the thread setting's choices. */
    val cpuCores: Int = Runtime.getRuntime().availableProcessors()

    // Chat ------------------------------------------------------------------------------

    /**
     * Choosing the chat's model loads it at once, so the first message does not wait for it.
     * The memory limit decides what makes room: at its limit the engine unloads the model used
     * least recently (at a limit of one, the model the chat leaves), which the picker says
     * before the choice. Under the limit both stay, as the person set.
     */
    fun chooseChatModel(id: String) {
        val changed = _chat.value.model != id
        _chat.update { it.copy(model = id) }
        if (changed && status.value?.resident.orEmpty().none { it.id == id }) viewModelScope.launch { graph.host.load(id) }
    }

    fun setChatThinking(on: Boolean) = _chat.update { it.copy(thinking = on) }

    /** Starts over; a reply still being written is stopped first. */
    fun newChat() {
        // Its reply is about to go, and with it the button that stops reading it.
        reader.stop()
        chatCall?.cancel()
        chatCall = null
        _chat.update { ChatState(model = it.model, thinking = it.thinking) }
    }

    /**
     * Stop is a cancel, not a client walking away: the request this app's chat is running is
     * cancelled on the server first (so Requests says Cancelled), then the connection closes.
     */
    fun stopChat() {
        val running = status.value?.running?.takeIf { it.client == CONSOLE_KEY }
        val call = chatCall
        viewModelScope.launch {
            running?.let { graph.host.cancel(it.id) }
            call?.cancel()
        }
    }

    /** Leaving the console closes a reply's socket, so the server stops writing to nobody. */
    override fun onCleared() {
        chatCall?.cancel()
        dictation.stop()
        reader.release()
    }

    /** Speech in and out for the chat, both on this phone only. */
    val dictation = Dictation(app)
    val reader = SpeechReader(app)

    /**
     * Sends [text] with the conversation so far to this app's own server, over loopback and
     * streamed, exactly as another app would: the console sees what a client gets.
     */
    fun sendChat(text: String, model: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _chat.value.running) return
        val port = (server.value as? ServeHost.State.Running)?.settings?.port ?: return
        // Earlier turns that produced something; a failed or empty reply is not context.
        val history = _chat.value.messages.filter { it.content.isNotBlank() && it.failure == null }
            .map { ConsoleChat.Turn(if (it.fromUser) ConsoleChat.Role.USER else ConsoleChat.Role.ASSISTANT, it.content) }
        val turns = history + ConsoleChat.Turn(ConsoleChat.Role.USER, prompt)
        val entry = installed.value.firstOrNull { it.id == model }
        val thinking = if (entry != null && ConsoleChat.canThink(entry)) _chat.value.thinking else null
        val askId = ++nextMessageId
        val replyId = ++nextMessageId
        // Made before anything suspends, so Stop works even while the key is still being read.
        val call = LocalClient.Call().also { chatCall = it }
        _chat.update {
            it.copy(
                model = model,
                messages = it.messages + ChatMessage(askId, fromUser = true, content = prompt) +
                    ChatMessage(replyId, fromUser = false, content = "", model = model, running = true),
            )
        }
        viewModelScope.launch {
            var result: TestResult? = null
            var failure: TestFailure? = null
            var stopped = false
            val clock = ThoughtClock()
            try {
                val key = graph.settings.keyFor(CONSOLE_KEY, FORMER_CONSOLE_KEYS)
                result = client.chat(call, "http://127.0.0.1:$port/v1", key.secret, ConsoleChat.body(model, turns, thinking)) { content, reasoning ->
                    clock.piece(reasoning, content)
                    updateReply(replyId) {
                        it.copy(content = it.content + content, reasoning = it.reasoning + reasoning, reasoningMs = clock.thought)
                    }
                }
            } catch (_: StoppedByUser) {
                stopped = true
            } catch (refused: TestFailure) {
                failure = refused
            } finally {
                // Whatever happened, the reply stops running: one left running would block every
                // later send (agy review).
                clock.end()
                updateReply(replyId) { it.copy(running = false, result = result, failure = failure, stopped = stopped, reasoningMs = clock.thought) }
                if (chatCall === call) chatCall = null
            }
        }
    }

    /** How long a reply thought: from its first reasoning to its first answer, or to its end. */
    private class ThoughtClock {
        private var from = 0L
        var thought: Long? = null
            private set

        fun piece(reasoning: String, content: String) {
            val now = System.currentTimeMillis()
            if (reasoning.isNotEmpty() && from == 0L) from = now
            if (content.isNotEmpty() && from > 0 && thought == null) thought = now - from
        }

        fun end() {
            if (from > 0 && thought == null) thought = System.currentTimeMillis() - from
        }
    }

    private fun updateReply(id: Long, change: (ChatMessage) -> ChatMessage) =
        _chat.update { state -> state.copy(messages = state.messages.map { if (it.id == id) change(it) else it }) }

    // Models ------------------------------------------------------------------------------

    fun rescan() = viewModelScope.launch {
        graph.models.rescan()
        graph.host.forgetFailures()
    }

    fun loadCatalog() = viewModelScope.launch {
        _catalog.value = CatalogState.Loading
        _catalog.value = runCatching { CatalogState.Loaded(graph.catalog.load()) }
            .getOrElse { CatalogState.Failed(it.message ?: it::class.java.simpleName) }
    }

    fun download(variant: CatalogVariant) {
        // The downloader refuses a GPU build once this phone's GPU has refused one.
        if (graph.downloader.enqueue(HfCatalog.plan(variant, System.currentTimeMillis()))) ServeService.keepAlive(app)
    }

    /** Whether Vulkan builds can run here; drops to false the moment one is refused. */
    val gpuUsable: StateFlow<Boolean> = VulkanSupport.usableState

    /** Whether NPU builds can run here; drops to false the moment one is refused. */
    val npuUsable: StateFlow<Boolean> = QnnSupport.usableState

    /**
     * Whether this phone can run [variant]: CPU builds always, GPU builds while [gpu] is true,
     * NPU builds while [npu] is true (the catalog lists only this chip's NPU folder).
     */
    fun runnableHere(variant: CatalogVariant, gpu: Boolean = VulkanSupport.usable, npu: Boolean = QnnSupport.usable): Boolean = when (variant.backend) {
        HfCatalog.VULKAN -> gpu
        HfCatalog.QNN -> npu
        HfCatalog.NEUROPILOT -> NeuroPilotSupport.usable
        else -> true
    }

    fun cancelDownload(id: String) = graph.downloader.cancel(id)

    /** What deleting [entry] frees on disk: every file that goes, not only the model. */
    fun bytesFreedBy(entry: ModelEntry): Long = graph.models.bytesFreedBy(entry)

    fun delete(entry: ModelEntry) = viewModelScope.launch {
        // Deleted only once nothing holds it; otherwise it stays, and can be deleted again.
        if (!graph.host.release(entry)) return@launch
        graph.models.delete(entry)
        graph.host.released(entry)
        graph.settings.update { it.copy(defaultModel = it.defaultModel.takeUnless { id -> id == entry.id }, preloadModels = it.preloadModels - entry.id) }
    }

    fun load(id: String) = viewModelScope.launch { graph.host.load(id) }

    /** Loads a model that failed before, setting its recorded failure aside. */
    fun retry(id: String) = viewModelScope.launch { graph.host.retry(id) }

    /** Unloads [id] when its turn in the queue comes; [onDone] runs then, whether or not it left memory. */
    fun unload(id: String, onDone: () -> Unit = {}) = viewModelScope.launch {
        graph.host.unload(id)
        onDone()
    }

    // Settings ----------------------------------------------------------------------------

    fun update(change: (HostSettings) -> HostSettings) = viewModelScope.launch { graph.settings.update(change) }

    fun addKey(name: String) = viewModelScope.launch { graph.settings.addKey(name) }

    fun revokeKey(id: String) = viewModelScope.launch { graph.settings.revokeKey(id) }

    /** The browser waiting to sign in that a scanned QR code or a typed code names, or null. */
    suspend fun findPairing(scannedOrTyped: String): Pairings.Request? = graph.host.pairings.find(scannedOrTyped)

    /**
     * Signs in the browser of [request] with a new key of its own, named for the browser and
     * its address, so Requests says which browser asked and Settings can revoke it alone. A key
     * is never shared: two browsers at one address, or a later device given the same address,
     * each get their own.
     */
    fun approvePairing(request: Pairings.Request, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val key = graph.settings.addBrowserKey(app.getString(R.string.pair_key_name, request.client, request.address))
        val ok = graph.host.approvePairing(request.id, key)
        // A browser that stopped waiting never received it: the key goes again, and every
        // other key stays. Only a sign-in that happened retires the oldest browser keys.
        if (ok) graph.settings.keepNewestBrowserKeys() else graph.settings.revokeKey(key.id)
        onDone(ok)
    }

    fun declinePairing(request: Pairings.Request) = viewModelScope.launch { graph.host.pairings.decline(request.id) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MEMORY_SAMPLE_MS = 5_000L
    }
}
