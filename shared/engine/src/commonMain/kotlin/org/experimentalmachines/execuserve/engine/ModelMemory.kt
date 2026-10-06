package org.experimentalmachines.execuserve.engine

/**
 * How much memory a model needs once loaded: not its file size. An ExecuTorch export plans
 * its KV cache for the whole window it was exported with and allocates it at load, before
 * any conversation, in 32-bit floats. Measured on a POCO X8 Pro Max (2026-10-07), Qwen3-0.6B's
 * CPU build took 1.06 GB at a 2k window and 7.55 GB at 32k, from files of the same size; the
 * arithmetic below gives 1.16 and 7.75.
 *
 * The cache's shape (attention layers, KV heads, head size) is not in a .pte or in the
 * exporter's config, so it comes from each source model's published config.json, for the
 * models the catalog offers. A model this table does not know gets no estimate rather than
 * a guess. MediaTek builds keep their own arithmetic ([NpuMemory]).
 */
object ModelMemory {
    /** Attention layers (not all layers: LFM2's convolution layers keep no cache), KV heads, head size. */
    data class Shape(val attentionLayers: Int, val kvHeads: Int, val headDim: Int)

    /** From each model's config.json on Hugging Face (Llama 3.2's is gated; its values are Meta's model card). */
    private val SHAPES = mapOf(
        "qwen3-0.6b" to Shape(28, 8, 128),
        "qwen3-1.7b" to Shape(28, 8, 128),
        "qwen3-4b" to Shape(36, 8, 128),
        "qwen2.5-0.5b" to Shape(24, 2, 64),
        "qwen2.5-1.5b" to Shape(28, 2, 128),
        "qwen2.5-math-1.5b" to Shape(28, 2, 128),
        "qwen2.5-3b" to Shape(36, 2, 128),
        "lfm2.5-1.2b" to Shape(6, 8, 64),
        "lfm2.5-2.6b" to Shape(8, 8, 64),
        "smollm2-135m" to Shape(30, 3, 64),
        "smollm2-360m" to Shape(32, 5, 64),
        "llama-3.2-1b" to Shape(16, 8, 64),
        "llama-3.2-3b" to Shape(28, 8, 128),
    )

    /**
     * The runtime and the app around a loaded model, and what a model keeps beside its cache:
     * 0.19 to 0.25 GB measured before any model; LFM2.5-1.2B at 32k measured 0.14 GB above the
     * arithmetic with 0.2 here, so 0.3, erring high.
     */
    const val RUNTIME_BYTES = 300L * 1024 * 1024

    private const val FLOAT_BYTES = 4L

    /** The shape for a model named [name] (a repository, a source model or an install id), or null. */
    fun shapeFor(name: String): Shape? {
        val normal = name.substringAfterLast('/').lowercase()
        return SHAPES.entries.filter { normal.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
    }

    /** Bytes of KV cache for [window] tokens: K and V, every attention layer, head and dimension. */
    fun cacheBytes(shape: Shape, window: Int): Long = 2L * shape.attentionLayers * shape.kvHeads * shape.headDim * window * FLOAT_BYTES

    /**
     * Bytes in memory once a CPU or GPU build of [name] with a [window]-token window and
     * [fileBytes] of weights is loaded, or null when the window or the shape is not known.
     */
    fun needBytes(name: String, window: Int?, fileBytes: Long): Long? {
        val shape = shapeFor(name) ?: return null
        if (window == null || window <= 0) return null
        return fileBytes + cacheBytes(shape, window) + RUNTIME_BYTES
    }

    /**
     * What [entry] takes once loaded, by its build: a MediaTek build from its runner's own
     * numbers, a Qualcomm build not at all (its cache is the NPU's), anything else from its
     * source model's shape. Null when unknown.
     */
    fun needFor(entry: ModelEntry): Long? {
        val npu = entry.files.npu
        return when {
            npu != null -> NpuMemory.needBytes(npu.runnerOptions, entry.sizeBytes)
            entry.backend == QNN_BACKEND -> null
            else -> needBytes(entry.source ?: entry.id, entry.contextLength, entry.sizeBytes)
                ?: needBytes(entry.id, entry.contextLength, entry.sizeBytes)
        }
    }

    private const val QNN_BACKEND = "qnn"

    /** How a need compares with what a phone can spare. */
    enum class Fit { COMFORTABLE, TIGHT, WONT_FIT }

    /**
     * Against [totalBytes] of memory, of which Android lets a foreground app use about two
     * thirds before it starts killing (OpenWeights' measure), and [availableBytes] free now.
     */
    fun fit(needBytes: Long, totalBytes: Long, availableBytes: Long): Fit {
        val usable = usableBytes(totalBytes)
        return when {
            needBytes > usable -> Fit.WONT_FIT
            needBytes > usable * TIGHT_SHARE || needBytes > availableBytes -> Fit.TIGHT
            else -> Fit.COMFORTABLE
        }
    }

    /** The most a model may take on a phone with [totalBytes]: about two thirds of it. */
    fun usableBytes(totalBytes: Long): Long = (totalBytes * USABLE_SHARE).toLong()

    private const val USABLE_SHARE = 0.65
    private const val TIGHT_SHARE = 0.8
}
