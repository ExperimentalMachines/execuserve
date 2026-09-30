package org.experimentalmachines.execuserve.engine

/**
 * Every limit the engine enforces. Defaults are chosen for one phone serving a handful of
 * clients; each is a setting in the app.
 */
data class EngineConfig(
    /** Requests waiting for the lane, across all clients. */
    val maxQueued: Int = 16,
    /** Requests one client may have waiting or running at once. */
    val maxPerClient: Int = 4,
    /** How long a request may wait for the lane before it fails with a timeout. */
    val queueTimeoutMs: Long = 120_000,
    /** How long a request may take from submission to its last token. */
    val requestTimeoutMs: Long = 600_000,
    /** How long the picker may prefer an already-resident model over an older request. */
    val maxAffinityWaitMs: Long = 10_000,
    /**
     * Models kept loaded at once. Execution is one at a time regardless. One on ExecuTorch
     * 1.4.0: every model open replaces the process's single thread pool and frees the one a
     * loaded model's XNNPACK runtime still points at (see `ExecuTorchRuntime.threads`).
     */
    val maxResidentModels: Int = 1,
    /** Unload a model idle this long; zero keeps it until something else needs the room. */
    val idleUnloadMs: Long = 0,
    val defaultTemperature: Float = 0.7f,
    /** Reasoning on or off when a request does not say; null follows the template. */
    val defaultThinking: Boolean? = null,
    val stopAdmittingAt: ThermalLevel = ThermalLevel.SEVERE,
    val cancelRunningAt: ThermalLevel = ThermalLevel.CRITICAL,
    /** Refuse work below this charge while unplugged; zero never refuses. */
    val minBatteryPercent: Int = 0,
    /** How long a cancelled job's native call may keep the lane before the engine is wedged. */
    val wedgeGraceMs: Long = 30_000,
    /** Fragments buffered for a slow client before its request is cancelled. */
    val eventBuffer: Int = 4096,
    /** Jobs kept for the request log. */
    val recentJobs: Int = 50,
    /**
     * Render an earlier reply with its reasoning when that turns a cache miss into a hit.
     * Off by default: Qwen3's own template drops reasoning from turns before the latest
     * question, so keeping it changes what the model reads. Within a tool loop (after the
     * latest question) the template keeps reasoning itself, and so does the ledger.
     */
    val keepReasoningInHistory: Boolean = false,
)
