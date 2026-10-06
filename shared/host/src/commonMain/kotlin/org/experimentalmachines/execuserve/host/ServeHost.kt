@file:OptIn(ExperimentalCoroutinesApi::class)

package org.experimentalmachines.execuserve.host

import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.experimentalmachines.execuserve.engine.Engine
import org.experimentalmachines.execuserve.engine.EngineStatus
import org.experimentalmachines.execuserve.engine.Environment
import org.experimentalmachines.execuserve.engine.LlmRuntime
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelSource
import org.experimentalmachines.execuserve.engine.Units
import org.experimentalmachines.execuserve.server.ApiKey
import org.experimentalmachines.execuserve.server.BindMode
import org.experimentalmachines.execuserve.server.ExecuServer
import org.experimentalmachines.execuserve.server.KeyVerifier
import org.experimentalmachines.execuserve.server.ServerContext
import org.experimentalmachines.execuserve.server.ServerStartFailure
import org.experimentalmachines.execuserve.server.StaticKeys
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The installed models, which the host rescans before each start. */
interface ModelLibrary : ModelSource {
    suspend fun rescan()
}

/** Where settings and keys live: DataStore on Android, whatever iOS uses later. */
interface HostStore {
    val settings: Flow<HostSettings>
    val keys: Flow<List<ApiKey>>

    /** The keys, making the first one if there is none, so the server is never keyless by accident. */
    suspend fun ensureKeys(): List<ApiKey>
}

/** What only the platform can answer. */
interface HostPlatform {
    val version: String

    /** Processor cores, for the thread setting's range. */
    val cpuCores: Int

    /** Thermal status and battery, live whether or not the server runs. */
    val environment: StateFlow<Environment>

    fun runtime(): LlmRuntime

    /**
     * The one thread every runtime call runs on. The platform makes it because only the
     * platform can give a thread a native-sized stack.
     */
    fun lane(): CloseableCoroutineDispatcher

    /** The URLs a client can use for [port] under [bind], best first. */
    fun endpoints(port: Int, bind: BindMode): List<Endpoint>

    /** Every address and name of this device, lowercased, as a `Host` header carries it. */
    fun hosts(): Set<String>

    /**
     * The model file a native call was running when the process last died, and clears that record.
     * The runtime marks every open, prefill and generate itself; a process the system kills
     * inside one (an NPU build too large for the phone's memory) leaves the id behind. A
     * platform whose process cannot be killed this way keeps nothing.
     */
    fun takeInterrupted(): String? = null

    /**
     * Models that took the process down, kept across restarts and refused until the person
     * retries one or deletes it: a rescan or a restart must not load it into the same death.
     */
    fun quarantined(): Set<String> = emptySet()

    fun setQuarantined(id: String, quarantined: Boolean) = Unit
}

/**
 * The one owner of the engine and the listener in a process: starts and stops them as one,
 * one transition at a time. The platform decides when to serve (on Android, a foreground
 * service); consoles watch [state] and [status].
 */
@OptIn(ExperimentalTime::class)
class ServeHost(
    private val platform: HostPlatform,
    private val store: HostStore,
    private val models: ModelLibrary,
    /** Where finished requests are kept; readable whether or not the server runs. */
    val history: RunHistory,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    sealed interface State {
        data class Stopped(val error: String? = null) : State
        data object Starting : State
        data class Running(val endpoints: List<Endpoint>, val settings: HostSettings, val startedAtMs: Long) : State
        data object Stopping : State
    }

    private val _state = MutableStateFlow<State>(State.Stopped())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _engine = MutableStateFlow<Engine?>(null)

    val status: Flow<EngineStatus?> = _engine.flatMapLatest { it?.status ?: flowOf(null) }

    val engine: Engine? get() = _engine.value

    val environment: StateFlow<Environment> get() = platform.environment

    private val lock = Mutex()
    private var server: ExecuServer? = null
    private var engineScope: CoroutineScope? = null
    private var lane: CloseableCoroutineDispatcher? = null
    private var runtime: LlmRuntime? = null

    private val _activeThreads = MutableStateFlow<Int?>(null)

    /** The threads the runtime computes with, read after each model load; null until one loads. */
    val activeThreads: StateFlow<Int?> = _activeThreads.asStateFlow()

    /** The keys as they are now: a key added or revoked applies to the next request. */
    @Volatile private var keys: List<ApiKey> = emptyList()
    private val liveKeys = KeyVerifier { presented -> StaticKeys(keys).verify(presented) }

    @Volatile private var hostCache: Pair<Long, Set<String>> = 0L to emptySet()

    init {
        scope.launch { store.keys.collect { keys = it } }
    }

    suspend fun start(onWedged: () -> Unit) = lock.withLock {
        if (_state.value is State.Running) return@withLock
        _state.value = State.Starting
        val current = store.settings.first()
        keys = store.ensureKeys()
        models.rescan()
        val dispatcher = platform.lane()
        val child = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = platform.runtime().also { it.threads = current.threads }
        val engine = Engine(
            runtime = runtime,
            models = models,
            lane = dispatcher,
            scope = child,
            config = current.engineConfig(),
            environment = platform.environment,
            clock = clock,
            onWedged = onWedged,
        )
        engine.start()
        // Before the listener takes a request: the last process died while this model ran, so
        // it is refused, saying why, until the person retries it.
        platform.takeInterrupted()?.let { platform.setQuarantined(idOfFile(it, engine), true) }
        val refused = refuseQuarantined(engine)
        val listener = ExecuServer(
            ServerContext(
                engine = engine,
                settings = current.serverSettings(),
                keys = liveKeys,
                deviceHosts = ::deviceHosts,
                threads = { _activeThreads.value },
                runs = { history.runs.value },
                version = platform.version,
                nowSeconds = { clock() / Units.MS_PER_SECOND },
            ),
        )
        try {
            listener.start()
        } catch (failure: ServerStartFailure) {
            engine.stop(0)
            child.cancel()
            dispatcher.close()
            _state.value = State.Stopped(failure.message)
            return@withLock
        }
        server = listener
        engineScope = child
        lane = dispatcher
        this.runtime = runtime
        _engine.value = engine
        // What the engine can take live, it takes live; the listener's settings need a restart.
        child.launch {
            store.settings.collect { settings ->
                engine.config = settings.engineConfig()
                // A thread count applies when a model opens, so the open ones are closed and
                // the next request reopens with it.
                if (settings.threads != runtime.threads) {
                    runtime.threads = settings.threads
                    runCatching { engine.unload(null) }
                }
            }
        }
        child.launch { engine.records.collect { history.add(it) } }
        // Which models are open changes only on a load or an unload: read the pool size then.
        child.launch {
            engine.status.map { status -> status.resident.map { it.id } }.distinctUntilChanged().collect {
                _activeThreads.value = if (it.isEmpty()) null else runtime.activeThreads()
            }
        }
        child.launch {
            current.startupModels { engine.resolve(it)?.id }.forEach { model ->
                if (model !in refused) runCatching { engine.load(model) }
            }
        }
        _state.value = State.Running(platform.endpoints(current.port, current.bind), current, clock())
    }

    suspend fun stop() = lock.withLock {
        if (_state.value is State.Stopped) return@withLock
        _state.value = State.Stopping
        runCatching { server?.stop() }
        runCatching { _engine.value?.stop(STOP_GRACE_MS) }
        engineScope?.cancel()
        lane?.close()
        server = null
        engineScope = null
        lane = null
        runtime = null
        _activeThreads.value = null
        _engine.value = null
        _state.value = State.Stopped()
    }

    /** Networks change under a running server: recompute where it can be reached. */
    fun refreshEndpoints() {
        hostCache = 0L to emptySet()
        // Atomically, and only while still running: a network callback that raced stop()
        // must not write a stale Running back over Stopped (codex review).
        _state.update { state ->
            if (state is State.Running) state.copy(endpoints = platform.endpoints(state.settings.port, state.settings.bind)) else state
        }
    }

    // The console's actions. Each is a no-op while stopped, and a failure lands in [status]
    // (a model that will not load is listed there), so none of them throws.

    suspend fun load(id: String) = act { it.load(id) }

    /** Loads [id] again after a failure: the person asked, so the recorded failure is set aside. */
    suspend fun retry(id: String) {
        platform.setQuarantined(id, false)
        act {
            it.forgetFailure(id)
            it.load(id)
        }
    }

    suspend fun unload(id: String?) = act { it.unload(id) }

    suspend fun cancel(jobId: String) = act { it.cancel(jobId) }

    /** Sets aside ordinary failures (files may have changed); quarantined models stay refused. */
    suspend fun forgetFailures() = act { engine -> engine.forgetFailures(keep = quarantinedIds(engine)) }

    /** Frees memory the system asked back; the next request reloads what it needs. */
    suspend fun evictAll() = act { it.evictIdle(force = true) }

    /** Unloads a model before its files are deleted; a new install under its id starts clean. */
    suspend fun release(entry: ModelEntry) {
        platform.setQuarantined(entry.id, false)
        unload(entry.id)
    }

    /** Records every quarantined model the library still has as failed; returns their ids. */
    private suspend fun refuseQuarantined(engine: Engine): Set<String> = quarantinedIds(engine).onEach { runCatching { engine.recordFailure(it, INTERRUPTED) } }

    private fun quarantinedIds(engine: Engine): Set<String> = platform.quarantined().mapNotNull { engine.resolve(it)?.id }.toSet()

    /**
     * The installed model whose file [path] is: the runtime records the file it opened, which
     * names the model whether it has a folder of its own or lies loose beside others.
     */
    private fun idOfFile(path: String, engine: Engine): String = models.all().firstOrNull { it.files.model == path }?.id ?: engine.resolve(path)?.id ?: path

    private suspend fun act(action: suspend (Engine) -> Unit) {
        val engine = _engine.value ?: return
        runCatching { action(engine) }
    }

    private fun deviceHosts(): Set<String> {
        val (at, hosts) = hostCache
        val now = clock()
        if (now - at < HOST_CACHE_MS) return hosts
        return platform.hosts().also { hostCache = now to it }
    }

    private companion object {
        /** What a model is refused with after the process died while it ran. */
        const val INTERRUPTED =
            "The app was stopped while this model was running, most likely because the phone ran out of memory. " +
                "It will not be loaded again until you retry it."

        const val STOP_GRACE_MS = 3_000L

        /** Addresses are read for every request's `Host` check; five seconds is fresh enough. */
        const val HOST_CACHE_MS = 5_000L
    }
}
