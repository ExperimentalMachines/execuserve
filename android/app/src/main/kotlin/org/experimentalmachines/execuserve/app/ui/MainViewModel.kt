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
import org.experimentalmachines.execuserve.host.CONSOLE_TEST_KEY
import org.experimentalmachines.execuserve.host.ConsoleTest
import org.experimentalmachines.execuserve.host.HostSettings
import org.experimentalmachines.execuserve.host.ServeHost
import org.experimentalmachines.execuserve.host.TestFailure
import org.experimentalmachines.execuserve.host.TestResult
import org.experimentalmachines.execuserve.server.ApiKey
import org.experimentalmachines.execuserve.server.BindMode

/** The console's test request. */
data class TryState(
    val stream: Boolean = true,
    val running: Boolean = false,
    val content: String = "",
    val reasoning: String = "",
    val result: TestResult? = null,
    val failure: TestFailure? = null,
    val stopped: Boolean = false,
)

sealed interface BenchmarkState {
    data object Idle : BenchmarkState
    data class Running(val model: String, val finished: Int) : BenchmarkState
    data class Done(val model: String, val runs: List<JobRecord>) : BenchmarkState
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
    private val _tryState = MutableStateFlow(TryState())
    val tryState: StateFlow<TryState> = _tryState.asStateFlow()

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

    /** The key a person shares: theirs, not the one the console's own test requests use. */
    fun shareableKey(keys: List<ApiKey>): ApiKey? = keys.firstOrNull { it.name != CONSOLE_TEST_KEY } ?: keys.firstOrNull()

    /** The endpoint and key as one message, for the share sheet. */
    fun shareConnection(baseUrl: String, key: String, model: String?) {
        val text = app.getString(R.string.connect_share_text, baseUrl, key) +
            (model?.let { app.getString(R.string.connect_share_model, it) } ?: "")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        app.startActivity(Intent.createChooser(send, app.getString(R.string.connect_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The two lines most OpenAI tools read, for pasting into a shell. */
    fun terminalExports(baseUrl: String, key: String): String = ConsoleTest.exports(baseUrl, key)

    // Runs --------------------------------------------------------------------------------

    /** Every request the server finished, newest first, across restarts. */
    val runs: StateFlow<List<JobRecord>> = graph.history.runs

    private val _benchmark = MutableStateFlow<BenchmarkState>(BenchmarkState.Idle)
    val benchmark: StateFlow<BenchmarkState> = _benchmark.asStateFlow()

    /** Measures [model] three times through the engine; the runs land in the history too. */
    fun runBenchmark(model: String) {
        if (_benchmark.value is BenchmarkState.Running || server.value !is ServeHost.State.Running) return
        _benchmark.value = BenchmarkState.Running(model, 0)
        viewModelScope.launch {
            val done = graph.host.benchmark(model) {
                _benchmark.update { state ->
                    (state as? BenchmarkState.Running)?.let { it.copy(finished = it.finished + 1) }
                        ?: state
                }
            }
            _benchmark.value = BenchmarkState.Done(model, done)
        }
    }

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

    // Try it ------------------------------------------------------------------------------

    fun setTryStream(on: Boolean) = _tryState.update { it.copy(stream = on) }

    /**
     * Sends [prompt] to this app's own server over loopback, streamed or whole as the
     * switch says, so the console shows exactly what a client would get.
     */
    fun runTry(prompt: String, model: String) {
        if (_tryState.value.running) return
        val port = (server.value as? ServeHost.State.Running)?.settings?.port ?: return
        _tryState.update { TryState(stream = it.stream, running = true) }
        viewModelScope.launch {
            val key = graph.settings.keyFor(CONSOLE_TEST_KEY)
            var result: TestResult? = null
            var failure: TestFailure? = null
            var stopped = false
            try {
                result = client.chat("http://127.0.0.1:$port/v1", key.secret, model, prompt, _tryState.value.stream) { content, reasoning ->
                    _tryState.update { it.copy(content = it.content + content, reasoning = it.reasoning + reasoning) }
                }
            } catch (_: StoppedByUser) {
                stopped = true
            } catch (refused: TestFailure) {
                failure = refused
            }
            _tryState.update { it.copy(running = false, result = result, failure = failure, stopped = stopped) }
        }
    }

    fun stopTry() = client.cancel()

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
        graph.downloader.enqueue(HfCatalog.plan(variant, System.currentTimeMillis()))
        ServeService.keepAlive(app)
    }

    fun cancelDownload(id: String) = graph.downloader.cancel(id)

    /** What deleting [entry] frees on disk: every file that goes, not only the model. */
    fun bytesFreedBy(entry: ModelEntry): Long = graph.models.bytesFreedBy(entry)

    fun delete(entry: ModelEntry) = viewModelScope.launch {
        graph.host.release(entry)
        graph.models.delete(entry)
        graph.settings.update { it.copy(defaultModel = it.defaultModel.takeUnless { id -> id == entry.id }, preloadModels = it.preloadModels - entry.id) }
    }

    fun load(id: String) = viewModelScope.launch { graph.host.load(id) }

    fun unload(id: String) = viewModelScope.launch { graph.host.unload(id) }

    // Settings ----------------------------------------------------------------------------

    fun update(change: (HostSettings) -> HostSettings) = viewModelScope.launch { graph.settings.update(change) }

    fun addKey(name: String) = viewModelScope.launch { graph.settings.addKey(name) }

    fun revokeKey(id: String) = viewModelScope.launch { graph.settings.revokeKey(id) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MEMORY_SAMPLE_MS = 5_000L
    }
}
