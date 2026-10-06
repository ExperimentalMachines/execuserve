package org.experimentalmachines.execuserve.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.experimentalmachines.execuserve.engine.FailureKind
import org.experimentalmachines.execuserve.engine.FinishReason
import org.experimentalmachines.execuserve.engine.JobRecord
import org.experimentalmachines.execuserve.engine.ThermalLevel
import org.experimentalmachines.execuserve.engine.Units

/** Lines of text kept somewhere that survives the process: a file on Android. */
interface RunStore {
    /** Every stored line, oldest first. */
    suspend fun readLines(): List<String>

    suspend fun appendLine(line: String)

    /** Replaces everything stored with [lines]. */
    suspend fun rewrite(lines: List<String>)
}

/** How much history is kept: whichever limit is reached first. */
data class Retention(val maxRuns: Int = DEFAULT_MAX_RUNS, val maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {
    companion object {
        const val DEFAULT_MAX_RUNS = 10_000
        const val DEFAULT_MAX_AGE_MS = 30 * Units.MS_PER_DAY
    }
}

/**
 * Every request the server finished, across restarts: the figures (which model, how long each
 * phase took, how many tokens, the device's state), never the prompt, the reply or a key.
 * Kept as JSON lines, newest last; [runs] is newest first.
 */
class RunHistory(
    private val store: RunStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val retention: Retention = Retention(),
) {
    private val lock = Mutex()
    private val _runs = MutableStateFlow<List<JobRecord>>(emptyList())
    val runs: StateFlow<List<JobRecord>> = _runs.asStateFlow()

    /** Lines in the store, to know when to compact it. */
    private var stored = 0

    /** When the oldest line in the store finished, to know when age makes it due to go. */
    private var oldestStoredMs = Long.MAX_VALUE

    init {
        scope.launch {
            lock.withLock {
                val lines = store.readLines()
                val kept = keep(lines.mapNotNull(RunCodec::decode))
                stored = lines.size
                oldestStoredMs = kept.firstOrNull()?.finishedAtMs ?: Long.MAX_VALUE
                if (kept.size < lines.size) compact(kept)
                _runs.value = kept.asReversed()
            }
        }
    }

    /**
     * Records [run], and lets go of what has passed either limit: at once from [runs] (so the
     * console, exports and the API never show it), and from the store when it is due. The
     * store is rewritten when it runs a fifth over the count, or when its oldest line is past
     * the age limit by 1/720 of it (an hour in thirty days), so appends stay cheap while a
     * process that lives for weeks still forgets on time (codex review).
     */
    suspend fun add(run: JobRecord) = lock.withLock {
        val kept = keep(_runs.value.asReversed() + run)
        _runs.value = kept.asReversed()
        // A full or failing disk must not end hosting: the run is shown, only not kept.
        try {
            store.appendLine(RunCodec.encode(run))
            stored++
            oldestStoredMs = minOf(oldestStoredMs, run.finishedAtMs)
            val overCount = stored > retention.maxRuns + retention.maxRuns / COMPACT_SLACK
            val overAge = oldestStoredMs < clock() - retention.maxAgeMs - retention.maxAgeMs / AGE_SLACK
            if (overCount || overAge) compact(kept)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _storeFailure.value = failure.message ?: failure::class.simpleName
        }
    }

    private val _storeFailure = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Why the last run could not be written to disk, if it could not; the runs stay in memory. */
    val storeFailure: kotlinx.coroutines.flow.StateFlow<String?> = _storeFailure

    suspend fun clear() = lock.withLock {
        store.rewrite(emptyList())
        stored = 0
        oldestStoredMs = Long.MAX_VALUE
        _runs.value = emptyList()
    }

    private fun keep(oldestFirst: List<JobRecord>): List<JobRecord> {
        val cutoff = clock() - retention.maxAgeMs
        return oldestFirst.filter { it.finishedAtMs >= cutoff }.takeLast(retention.maxRuns)
    }

    private suspend fun compact(oldestFirst: List<JobRecord>) {
        store.rewrite(oldestFirst.map(RunCodec::encode))
        stored = oldestFirst.size
        oldestStoredMs = oldestFirst.firstOrNull()?.finishedAtMs ?: Long.MAX_VALUE
    }

    private companion object {
        const val COMPACT_SLACK = 5
        const val AGE_SLACK = 720
    }
}

/** A run as one line of JSON, and back. Unknown fields and values from newer builds are skipped. */
object RunCodec {
    private val JSON = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(run: JobRecord): String = JSON.encodeToString(StoredRun.serializer(), StoredRun.of(run))

    fun decode(line: String): JobRecord? = runCatching { JSON.decodeFromString(StoredRun.serializer(), line).record() }.getOrNull()
}

@Serializable
private data class StoredRun(
    val id: String,
    val model: String,
    val client: String,
    @SerialName("client_id") val clientId: String = "",
    @SerialName("finished_at_ms") val finishedAtMs: Long,
    val finish: String? = null,
    val failure: String? = null,
    val prompt: Int,
    val completion: Int,
    val cached: Int,
    @SerialName("estimated_prompt") val estimatedPrompt: Int = 0,
    @SerialName("queue_ms") val queueMs: Long,
    @SerialName("load_ms") val loadMs: Long = 0,
    @SerialName("prefill_ms") val prefillMs: Long = 0,
    @SerialName("decode_ms") val decodeMs: Long = 0,
    @SerialName("decode_wall_ms") val decodeWallMs: Long = 0,
    @SerialName("first_token_ms") val firstTokenMs: Long = 0,
    @SerialName("total_ms") val totalMs: Long,
    val api: String = "",
    val stream: Boolean = false,
    val thermal: String = "none",
    val battery: Int? = null,
    val charging: Boolean = true,
    val threads: Int? = null,
) {
    fun record() = JobRecord(
        id = id,
        model = model,
        client = client,
        finishedAtMs = finishedAtMs,
        finish = FinishReason.entries.firstOrNull { it.wire == finish },
        failure = FailureKind.entries.firstOrNull { it.name.equals(failure, ignoreCase = true) },
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = cached,
        queueMs = queueMs,
        totalMs = totalMs,
        loadMs = loadMs,
        prefillMs = prefillMs,
        decodeMs = decodeMs,
        decodeWallMs = decodeWallMs,
        firstTokenMs = firstTokenMs,
        estimatedPromptTokens = estimatedPrompt,
        api = api,
        stream = stream,
        thermal = ThermalLevel.entries.firstOrNull { it.name.equals(thermal, ignoreCase = true) } ?: ThermalLevel.NONE,
        batteryPercent = battery,
        charging = charging,
        clientId = clientId,
        threads = threads,
    )

    companion object {
        fun of(run: JobRecord) = StoredRun(
            id = run.id,
            model = run.model,
            client = run.client,
            clientId = run.clientId,
            finishedAtMs = run.finishedAtMs,
            finish = run.finish?.wire,
            failure = run.failure?.name?.lowercase(),
            prompt = run.promptTokens,
            completion = run.completionTokens,
            cached = run.cachedTokens,
            estimatedPrompt = run.estimatedPromptTokens,
            queueMs = run.queueMs,
            loadMs = run.loadMs,
            prefillMs = run.prefillMs,
            decodeMs = run.decodeMs,
            decodeWallMs = run.decodeWallMs,
            firstTokenMs = run.firstTokenMs,
            totalMs = run.totalMs,
            api = run.api,
            stream = run.stream,
            thermal = run.thermal.name.lowercase(),
            battery = run.batteryPercent,
            charging = run.charging,
            threads = run.threads,
        )
    }
}
