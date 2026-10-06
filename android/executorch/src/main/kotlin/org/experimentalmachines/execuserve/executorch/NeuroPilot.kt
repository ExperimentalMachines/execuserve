package org.experimentalmachines.execuserve.executorch

import android.os.Build
import org.experimentalmachines.execuserve.engine.ContextOverflow
import org.experimentalmachines.execuserve.engine.LlmSession
import org.experimentalmachines.execuserve.engine.ModelFacts
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.engine.RuntimeFailure
import org.experimentalmachines.execuserve.engine.RuntimeOutcome

/**
 * The JNI surface of the MediaTek runtime (libexecutorch_pd_jni, built by
 * tools/executorch/build_mediatek.py from OpenWeights' runner): the NPU prefills through
 * compiled chunks, then hands the cache to the CPU build of the same model, which decodes.
 */
internal class NeuroPilotBridge {
    external fun nativeLoad(
        runnerOptionsJson: String,
        promptModelPaths: String,
        tokenEmbeddingPath: String,
        cpuModelPath: String,
        tokenizerPath: String,
        temperature: Float,
    ): Long

    /** Tokens held after feeding [prompt], or 0 when it was refused ([nativeLastError] says why). */
    external fun nativePrefill(handle: Long, prompt: String): Int

    /** Why the last call returned nothing: [ERROR_OVERFLOW], [ERROR_RUNTIME], or 0. */
    external fun nativeLastError(handle: Long): Int

    /**
     * Returns [reason, promptTokens, generatedTokens, prefillMs, decodeMs], or null on failure.
     * Greedy at [temperature] 0, sampled above it.
     */
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, callback: Any): LongArray?

    external fun nativeResetContext(handle: Long)

    external fun nativeStop(handle: Long)

    external fun nativeClose(handle: Long)

    companion object {
        const val ERROR_OVERFLOW = 1
        const val ERROR_RUNTIME = 2
    }
}

/**
 * Whether this phone can run a MediaTek NPU export.
 *
 * Answered by loading the libraries, not by matching a chip name, as OpenWeights does: the
 * adapter is the phone's own copy (MediaTek lists it as public; the manifest names it), and a
 * Neuron backend without it cannot allocate shared weights and aborts the process from
 * inside ExecuTorch, so a phone that cannot load it must never reach the NPU path.
 */
object NeuroPilotSupport {
    /** This phone's chip as Android names it (`MT6991`), or null when it does not say. */
    val soc: String? get() = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }

    /** True when the adapter, the Neuron backend and the runner all load in this process. */
    val usable: Boolean by lazy {
        Build.SOC_MANUFACTURER.equals("Mediatek", ignoreCase = true) &&
            soc != null &&
            runCatching {
                System.loadLibrary("neuronusdk_adapter.mtk")
                System.loadLibrary("neuron_backend")
                System.loadLibrary("executorch_pd_jni")
            }.isSuccess
    }
}

/**
 * One MediaTek install: the NPU chunks prefill, the CPU build ([ModelFiles.model]) decodes.
 * The runner adds BOS itself, splits prompts into its own 128-token NPU batches, and refuses a
 * prompt past the NPU window rather than truncating it.
 */
internal class NeuroPilotSession(private val files: ModelFiles, private val facts: ModelFacts, temperature: Float) : LlmSession {
    private val bridge = NeuroPilotBridge()
    private val guardId = NativeCrashGuard.idOf(files.model)
    private var handle: Long

    init {
        val npu = files.npu ?: throw RuntimeFailure("${files.model} is a MediaTek install without its NPU files")
        if (!NeuroPilotSupport.usable) {
            throw RuntimeFailure("This device cannot run MediaTek NPU builds; try the same model's CPU build, which does not need the NPU.")
        }
        handle = NativeCrashGuard.around(guardId) {
            bridge.nativeLoad(npu.runnerOptions, npu.chunks.joinToString(","), npu.embedding, files.model, files.tokenizer, temperature)
        }
        if (handle == 0L) throw RuntimeFailure("The MediaTek runtime could not open ${npu.chunks.firstOrNull() ?: files.model}")
    }

    private fun live(): Long = handle.takeIf { it != 0L } ?: throw RuntimeFailure("The MediaTek session is closed")

    override fun prefill(text: String) {
        val handle = live()
        if (NativeCrashGuard.around(guardId) { bridge.nativePrefill(handle, text) } <= 0) throw failure(handle, "could not prefill")
    }

    override fun generate(text: String, temperature: Float, onToken: (String) -> Unit): RuntimeOutcome {
        // The engine ends a reply with stop() from inside onToken; the runner's own limit is the window.
        val limit = facts.contextLength ?: Int.MAX_VALUE
        val callback = NeuroPilotTokens(onToken)
        val handle = live()
        val result = NativeCrashGuard.around(guardId) { bridge.nativeGenerate(handle, text, limit, temperature, callback) }
            ?: throw failure(handle, "failed while generating")
        // The reply reached the end of the window: the engine reports that as a full context.
        if (result.getOrElse(RESULT_REASON) { 0L } == REASON_WINDOW_FULL) throw overflow()
        return RuntimeOutcome(
            promptTokens = result.getOrElse(RESULT_PROMPT_TOKENS) { 0L }.toInt(),
            generatedTokens = result.getOrElse(RESULT_GENERATED_TOKENS) { 0L }.toInt(),
            prefillMs = result.getOrElse(RESULT_PREFILL_MS) { 0L },
            decodeMs = result.getOrElse(RESULT_DECODE_MS) { 0L },
        )
    }

    /** The runner's own reason for refusing: a prompt past its window, or a runtime failure. */
    private fun failure(handle: Long, what: String): RuntimeFailure =
        if (bridge.nativeLastError(handle) == NeuroPilotBridge.ERROR_OVERFLOW) overflow() else RuntimeFailure("The MediaTek runtime $what.")

    private fun overflow() = ContextOverflow("The prompt does not fit this build's NPU window of ${facts.contextLength ?: "unknown"} tokens.")

    override fun reset() = bridge.nativeResetContext(live())

    override fun stop() = bridge.nativeStop(live())

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) bridge.nativeClose(h)
    }

    private companion object {
        // Positions in nativeGenerate's result, as the JNI side writes them.
        const val RESULT_REASON = 0
        const val RESULT_PROMPT_TOKENS = 1
        const val RESULT_GENERATED_TOKENS = 2
        const val RESULT_PREFILL_MS = 3
        const val RESULT_DECODE_MS = 4
        const val REASON_WINDOW_FULL = 3L
    }
}

/**
 * What the MediaTek runtime streams each piece of a reply to. Its JNI looks up [onToken] by
 * name, which R8 cannot see: consumer-rules.pro keeps the class and the method.
 */
internal class NeuroPilotTokens(private val sink: (String) -> Unit) {
    @Suppress("unused") // called by name from JNI
    fun onToken(piece: String): Boolean {
        sink(piece)
        return true
    }
}
