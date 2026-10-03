package org.experimentalmachines.execuserve.engine

enum class LaneState { IDLE, LOADING, PREFILLING, GENERATING, WEDGED, STOPPED }

enum class Admission { OPEN, PAUSED_THERMAL, PAUSED_BATTERY, WEDGED, STOPPED }

data class RunningJob(
    val id: String,
    val model: String,
    val client: String,
    val startedAtMs: Long,
    val generatedTokens: Int,
    val cachedTokens: Int,
    /** When the first token came, zero before it: decoding speed is measured from here. */
    val firstTokenAtMs: Long = 0,
    /** Start of prompt evaluation, excluding model loading and queue time. */
    val prefillStartedAtMs: Long = 0,
    /** Characters are progress units, not an unverified tokenizer count. */
    val promptChars: Int = 0,
    val prefilledChars: Int = 0,
    /** The key that asked, so the API can show its owner what it hides from other keys. */
    val clientId: String = "",
) {
    fun prefillElapsedMs(nowMs: Long): Long = if (prefillStartedAtMs > 0) {
        ((firstTokenAtMs.takeIf { it > 0 } ?: nowMs) - prefillStartedAtMs).coerceAtLeast(0)
    } else {
        0
    }

    /** Tokens per second since the first token, or null until there is something to divide. */
    fun decodeRate(nowMs: Long): Double? {
        val elapsed = nowMs - firstTokenAtMs
        return if (firstTokenAtMs > 0 && generatedTokens > 1 && elapsed > 0) (generatedTokens - 1) * Units.MS_PER_SECOND.toDouble() / elapsed else null
    }
}

data class ResidentInfo(val id: String, val contextLength: Int?, val heldTokens: Int, val loadedAtMs: Long, val lastUsedMs: Long)

/**
 * One finished request: it either finished ([finish]) or failed ([failure]). Durations
 * are kept, not rates: a rate is derived from its own count and time, so the two cannot
 * disagree, and the history can be recomputed however the questions change.
 */
data class JobRecord(
    val id: String,
    val model: String,
    /** The key's name, for people. */
    val client: String,
    val finishedAtMs: Long,
    val finish: FinishReason?,
    val failure: FailureKind?,
    /** Everything the model read for this reply, cached tokens included. */
    val promptTokens: Int,
    val completionTokens: Int,
    /** Prompt tokens already in the KV cache from an earlier turn, not read again. */
    val cachedTokens: Int,
    val queueMs: Long,
    val totalMs: Long,
    /** Opening the model for this request; zero when it was already loaded. */
    val loadMs: Long = 0,
    /** Reading the prompt's uncached tokens. */
    val prefillMs: Long = 0,
    /** Writing the reply after its first token, as the runtime timed it when it did. */
    val decodeMs: Long = 0,
    /** The same stretch on this side's clock, kept to check the runtime's against. */
    val decodeWallMs: Long = 0,
    /** From submission to the first token: the wait a client felt. */
    val firstTokenMs: Long = 0,
    /**
     * Prompt tokens counted by estimate, not by the tokenizer: text fed ahead of the final
     * runtime call is scaled by that call's characters per token.
     */
    val estimatedPromptTokens: Int = 0,
    /** Which API asked: `chat.completions`, `completions`, `responses`, `messages`. */
    val api: String = "",
    val stream: Boolean = false,
    /** The device when the reply finished. */
    val thermal: ThermalLevel = ThermalLevel.NONE,
    val batteryPercent: Int? = null,
    val charging: Boolean = true,
    /** The key's opaque id: whose run this is, for showing each caller only its own. */
    val clientId: String = "",
    /** CPU threads the runtime computed with, when it could say. */
    val threads: Int? = null,
) {
    /** The finish reason as OpenAI spells it, or the failure kind in the same style. */
    val outcome: String get() = finish?.wire ?: failure?.name?.lowercase().orEmpty()

    /** Tokens after the first, per second of decoding: the first token's time belongs to the prompt. */
    val decodeTokensPerSecond: Double
        get() = if (decodeMs > 0 && completionTokens > 1) (completionTokens - 1) * Units.MS_PER_SECOND.toDouble() / decodeMs else 0.0

    /** Uncached prompt tokens per second of prefill. */
    val prefillTokensPerSecond: Double
        get() = if (prefillMs > 0 && promptTokens > cachedTokens) (promptTokens - cachedTokens) * Units.MS_PER_SECOND.toDouble() / prefillMs else 0.0
}

/** Running sums since the engine started, for `/metrics` and the status panel. */
data class Totals(
    val completed: Long = 0,
    val failed: Long = 0,
    val refused: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val prefillMs: Long = 0,
    val decodeMs: Long = 0,
    val loads: Long = 0,
    val loadMs: Long = 0,
)

data class EngineStatus(
    val lane: LaneState = LaneState.IDLE,
    val admission: Admission = Admission.OPEN,
    val running: RunningJob? = null,
    val queued: Int = 0,
    val resident: List<ResidentInfo> = emptyList(),
    /** Models that failed to load, with the runtime's reason, until the next rescan. */
    val broken: Map<String, String> = emptyMap(),
    val recent: List<JobRecord> = emptyList(),
    val totals: Totals = Totals(),
)
