package org.experimentalmachines.execuserve.engine

/**
 * How much free memory a MediaTek NPU build needs to load and answer, estimated before any of
 * it is touched. A build that does not fit is not refused by ExecuTorch: Android's low-memory
 * killer closes the phone's other apps one after another and then this one, mid-load or on
 * the first request (Qwen3-0.6B's 4k build on a 12 GB phone, 2026-10-06).
 *
 * Measured on that phone, the Neuron backend holds two buffers the size of the NPU's KV cache
 * (LFM2.5-1.2B 2k: 1.08 GB of graphics memory for a 0.54 GB cache), the files are read in
 * whole, and the CPU half decodes into a cache of its own. The total was 3.54 GB for
 * LFM2.5-1.2B 2k (estimated here at 3.9) and over 6.6 GB for Qwen3-0.6B 4k (6.55).
 */
object NpuMemory {
    /** Kept for the system and the app itself: lmkd starts killing well above zero. */
    const val RESERVE_BYTES = 1L shl 30

    /**
     * The estimate for an install of [filesBytes] whose runner options are [runnerJson], with a
     * tenth added, or null when the options do not describe the cache.
     */
    fun needBytes(runnerJson: String, filesBytes: Long): Long? {
        val keys = listOf("num_layer", "num_head", "head_dim", "cache_size")
        val shape = keys.mapNotNull { int(runnerJson, it)?.toLong() }
        if (shape.size != keys.size) return null
        val bytes = when (Regex("\"cache_type\"\\s*:\\s*\"(\\w+)\"").find(runnerJson)?.groupValues?.get(1)) {
            "fp16", "int16", "bf16" -> HALF
            "int8" -> 1L
            else -> SINGLE
        }
        // K and V, for every layer, head, position and dimension.
        val cache = 2L * shape.reduce(Long::times) * bytes
        // The CPU half's own cache: half the NPU's at most (its attention layers share heads).
        val estimate = filesBytes + 2 * cache + cache / 2
        return estimate + estimate / MARGIN
    }

    /** Whether [needBytes] fits in [availableBytes] with [RESERVE_BYTES] to spare. */
    fun fits(needBytes: Long, availableBytes: Long): Boolean = needBytes + RESERVE_BYTES <= availableBytes

    private const val SINGLE = 4L
    private const val HALF = 2L

    /** A tenth on top: the files and the caches are not all the backend allocates. */
    private const val MARGIN = 10

    private fun int(json: String, key: String): Int? = Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
}
