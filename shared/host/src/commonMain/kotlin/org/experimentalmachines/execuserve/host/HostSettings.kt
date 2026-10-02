package org.experimentalmachines.execuserve.host

import org.experimentalmachines.execuserve.engine.EngineConfig
import org.experimentalmachines.execuserve.engine.Units
import org.experimentalmachines.execuserve.server.BindMode
import org.experimentalmachines.execuserve.server.ServerSettings

/** When the CPU is kept awake. */
enum class WakePolicy {
    /** Whenever the server runs: a request arriving with the screen off is answered at once. */
    ALWAYS,

    /** Only while a request is queued or running: less battery, a slower first answer asleep. */
    WHILE_BUSY,
}

/** Reasoning for families that support it, when a request does not say. */
enum class ThinkingDefault { MODEL, ON, OFF }

/** The console's appearance; the server is unaffected. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val ENGINE = EngineConfig()
private val SERVER = ServerSettings()

/**
 * Everything a person can set, in one place. Defaults come from the engine's and the
 * server's own, so a limit is written down once; this adds only what those two do not own.
 */
data class HostSettings(
    val port: Int = SERVER.port,
    val bind: BindMode = SERVER.bind,
    val openLoopback: Boolean = SERVER.openLoopback,
    val corsOrigins: String = "",
    val extraHosts: String = "",
    val defaultModel: String? = null,
    val preloadModels: Set<String> = emptySet(),
    val maxResidentModels: Int = ENGINE.maxResidentModels,
    val startAtBoot: Boolean = false,
    val allowExternalStart: Boolean = true,
    val wake: WakePolicy = WakePolicy.ALWAYS,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val thinking: ThinkingDefault = ThinkingDefault.MODEL,
    val temperature: Float = ENGINE.defaultTemperature,
    val keepReasoningInHistory: Boolean = ENGINE.keepReasoningInHistory,
    val maxQueued: Int = ENGINE.maxQueued,
    val maxPerClient: Int = ENGINE.maxPerClient,
    val queueTimeoutSeconds: Int = (ENGINE.queueTimeoutMs / MS_PER_SECOND).toInt(),
    val requestTimeoutSeconds: Int = (ENGINE.requestTimeoutMs / MS_PER_SECOND).toInt(),
    val idleUnloadMinutes: Int = (ENGINE.idleUnloadMs / MS_PER_MINUTE).toInt(),
    val minBatteryPercent: Int = ENGINE.minBatteryPercent,
    /** CPU threads for inference; 0 lets the runtime choose (ExecuTorch: performance cores minus one). */
    val threads: Int = 0,
) {
    fun serverSettings() = SERVER.copy(
        port = port,
        bind = bind,
        openLoopback = openLoopback,
        corsOrigins = list(corsOrigins),
        extraHosts = list(extraHosts).map { it.lowercase() }.toSet(),
    )

    fun engineConfig() = ENGINE.copy(
        maxResidentModels = residentLimit,
        maxQueued = maxQueued,
        maxPerClient = maxPerClient,
        queueTimeoutMs = queueTimeoutSeconds * MS_PER_SECOND,
        requestTimeoutMs = requestTimeoutSeconds * MS_PER_SECOND,
        idleUnloadMs = idleUnloadMinutes * MS_PER_MINUTE,
        defaultTemperature = temperature,
        defaultThinking = when (thinking) {
            ThinkingDefault.MODEL -> null
            ThinkingDefault.ON -> true
            ThinkingDefault.OFF -> false
        },
        keepReasoningInHistory = keepReasoningInHistory,
        minBatteryPercent = minBatteryPercent,
    )

    /** Resolve aliases and discard missing models before they consume a startup slot. */
    fun startupModels(resolve: (String) -> String? = { it }): List<String> =
        (listOfNotNull(defaultModel) + preloadModels.sorted()).mapNotNull(resolve).distinct()
            .take(if (threads == 0) residentLimit else 1)

    /** Whether going from [running] to this needs the listener restarted; engine limits apply live. */
    fun needsRestartFrom(running: HostSettings) = serverSettings() != running.serverSettings()

    /** The resident-model setting, clamped to what one phone can hold. */
    private val residentLimit get() = maxResidentModels.coerceIn(Choices.RESIDENT_MODELS)

    private fun list(text: String) = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        const val MS_PER_SECOND = Units.MS_PER_SECOND
        const val MS_PER_MINUTE = Units.MS_PER_MINUTE
    }
}

/** The values each setting may take, for any console to offer. */
object Choices {
    val PORTS = 1024..65_535
    val TEMPERATURES = listOf(0f, 0.2f, 0.4f, 0.6f, 0.7f, 0.8f, 1.0f, 1.2f)
    val IDLE_UNLOAD_MINUTES = listOf(0, 5, 15, 60)
    val BATTERY_FLOORS = listOf(0, 10, 20, 30)
    val QUEUE_SIZES = 0..256
    val PER_CLIENT = 1..64
    val QUEUE_TIMEOUT_SECONDS = 5..3_600
    val REQUEST_TIMEOUT_SECONDS = 10..7_200

    /** Models kept in memory at once; each resident model costs its file size plus its window. */
    val RESIDENT_MODELS = 1..3

    /** Automatic, then every count up to [cores]. */
    fun threads(cores: Int): List<Int> = listOf(0) + (1..cores.coerceAtLeast(1))

    /** The offered temperature nearest to [value], so a stored value always shows as a choice. */
    fun nearestTemperature(value: Float) = TEMPERATURES.minBy { kotlin.math.abs(it - value) }
}
