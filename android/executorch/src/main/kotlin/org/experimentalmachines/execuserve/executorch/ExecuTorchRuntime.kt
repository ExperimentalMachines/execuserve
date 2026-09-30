package org.experimentalmachines.execuserve.executorch

import org.experimentalmachines.execuserve.engine.ContextOverflow
import org.experimentalmachines.execuserve.engine.EngineConfig
import org.experimentalmachines.execuserve.engine.LlmRuntime
import org.experimentalmachines.execuserve.engine.LlmSession
import org.experimentalmachines.execuserve.engine.ModelFacts
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.engine.RuntimeFailure
import org.experimentalmachines.execuserve.engine.RuntimeOutcome
import org.pytorch.executorch.Module
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File

/**
 * ExecuTorch 1.4.0 on XNNPACK. Everything below was measured in OpenWeights, which has run
 * these exports on phones since 2026-08; the comments say what each line works around.
 */
class ExecuTorchRuntime : LlmRuntime {

    override val id: String = "executorch-xnnpack"

    /**
     * Never: the 1.4.0 JNI layer's `num_bos_` is zero with the constructor this uses, and
     * the C++ tokenizer adds BOS only when asked, even when `tokenizer.json`'s
     * post-processor declares one. LFM2.5-2.6B answered garbage without it (0/30 GSM8K on
     * four chips), so the engine writes the family's BOS into the text instead.
     */
    override fun tokenizerAddsBos(files: ModelFiles): Boolean = false

    /**
     * The export's own constants, read through a second, memory-mapped handle: a few
     * milliseconds and no copy of the weights. The LLM wrapper reads the same constants
     * and offers no way to ask for them.
     */
    override fun probe(files: ModelFiles): ModelFacts {
        val program = Module.load(files.model, Module.LOAD_MODE_MMAP)
        try {
            val methods = program.getMethods().toSet()
            fun read(name: String): Int? = name.takeIf { it in methods }?.let {
                program.execute(it).firstOrNull()?.takeIf { v -> v.isInt }?.toInt()?.toInt()
            }?.takeIf { it > 0 }
            return ModelFacts(
                contextLength = read("get_max_context_len") ?: read("get_max_seq_len"),
                prefillLength = read("get_max_seq_len"),
                stateResetAtZero = read("get_state_reset_at_zero")?.let { it != 0 },
            )
        } finally {
            program.destroy()
        }
    }

    override fun open(files: ModelFiles, facts: ModelFacts): LlmSession {
        if (!File(files.model).isFile) throw RuntimeFailure("${files.model} is missing")
        if (!File(files.tokenizer).isFile) throw RuntimeFailure("${files.tokenizer} is missing")
        return ExecuTorchSession(files, facts) { threads }
    }

    /**
     * ExecuTorch has one thread pool per process, and two calls replace it: constructing an
     * `LlmModule` sizes it to the performance cores minus one (7 on the POCO), and
     * `Module.load(..., numThreads)` sizes it as asked. The second is the only knob the 1.4.0
     * AAR exposes, so a chosen count is applied by a throwaway memory-mapped load between
     * a model's constructor and its load.
     *
     * Replacing the pool frees the old one, and a model already loaded keeps pointing at it.
     * That is why one model is kept loaded at a time ([EngineConfig.maxResidentModels]):
     * opening a second would free the first one's threads under it.
     */
    @Volatile override var threads: Int = 0

    /** Read back from ExecuTorch's own log, the line it writes whenever the pool is resized. */
    override fun activeThreads(): Int? = runCatching {
        Module.readLogBufferStatic()?.asList()?.asReversed()?.firstNotNullOfOrNull { line ->
            POOL_RESIZE.find(line)?.groupValues?.get(1)?.toIntOrNull()
        }
    }.getOrNull()

    companion object {
        /** The runtime this build links, for the console and for catalog compatibility checks. */
        const val VERSION: String = BuildConfig.EXECUTORCH_VERSION

        private val POOL_RESIZE = Regex("Resetting threadpool to (\\d+) threads")
    }
}

private class ExecuTorchSession(
    private val files: ModelFiles,
    private val facts: ModelFacts,
    private val threads: () -> Int,
) : LlmSession {

    private var module: LlmModule = openModule()
    private var hasRun = false

    /**
     * LFM2 exports made before 2026-09-17 keep their short-convolution state in a buffer the
     * graph never clears, and `resetContext` only rewinds the position: the next prompt
     * starts on the previous one's state (tool-call probability 0.94 fell to 0.38 in
     * OpenWeights' probe). An export that clears it says so with `get_state_reset_at_zero`;
     * any other used LFM2 file is reopened instead, about a second.
     */
    private val reopenOnReset: Boolean =
        "lfm2" in File(files.model).name.lowercase().filter { it.isLetterOrDigit() } && facts.stateResetAtZero != true

    private fun openModule(): LlmModule = try {
        // Every generate passes its own temperature; the constructor wants one anyway.
        LlmModule(LlmModule.MODEL_TYPE_TEXT, files.model, files.tokenizer, EngineConfig().defaultTemperature).also {
            // Between the constructor, which sizes the pool its own way, and load(): XNNPACK
            // binds the pool that exists when the model loads, and a pool replaced after that
            // is freed under it (SIGSEGV in generate on the POCO, 2026-09-30).
            threads().takeIf { n -> n > 0 }?.let { n -> Module.load(files.model, Module.LOAD_MODE_MMAP, n).destroy() }
            it.load()
        }
    } catch (failure: Throwable) {
        // UnsatisfiedLinkError is an Error, not an Exception, and a .pte built for another
        // runtime version fails inside the loader; both become one failure with a reason.
        throw RuntimeFailure("ExecuTorch could not open ${File(files.model).name}: ${failure.message ?: failure::class.java.simpleName}", failure)
    }

    override fun prefill(text: String) {
        hasRun = true
        try {
            module.prefillPrompt(text)
        } catch (failure: Throwable) {
            throw failure.asOverflow() ?: RuntimeFailure("ExecuTorch could not prefill: ${failure.message}", failure)
        }
    }

    override fun generate(text: String, temperature: Float, onToken: (String) -> Unit): RuntimeOutcome {
        hasRun = true
        var reported: String? = null
        var error: String? = null
        val config = LlmGenerationConfig.create()
            // Set per call: the AAR otherwise defaults each call to 0.8.
            .temperature(temperature)
            // Echo would replay the prompt through the callback as if the model wrote it.
            .echo(false)
            // Applied per call, and would otherwise append an EOS to every suffix fed in,
            // turning a continuation into a string of terminated fragments.
            .numEos(0)
            .build()
        try {
            module.generate(
                text,
                config,
                object : LlmCallback {
                    override fun onResult(result: String) = onToken(result)

                    override fun onStats(stats: String) {
                        reported = stats
                    }

                    override fun onError(errorCode: Int, message: String) {
                        error = message.ifBlank { "ExecuTorch error $errorCode" }
                    }
                },
            )
        } catch (failure: Throwable) {
            throw failure.asOverflow() ?: RuntimeFailure("ExecuTorch failed while generating: ${failure.message}", failure)
        }
        error?.let { message -> throw RuntimeFailure(message).asOverflow() ?: RuntimeFailure(message) }
        return outcomeFrom(reported)
    }

    override fun reset() {
        if (reopenOnReset && hasRun) {
            module.close()
            module = openModule()
        } else {
            module.resetContext()
        }
        hasRun = false
    }

    override fun stop() = module.stop()

    override fun close() = module.close()

    /** The one failure a full window produces, whichever call it lands in. */
    private fun Throwable.asOverflow(): ContextOverflow? {
        val text = message.orEmpty()
        if ("Max seq length exceeded" !in text && "max_context_len" !in text) return null
        return ContextOverflow("The prompt does not fit ${File(files.model).name}'s exported window of ${facts.contextLength ?: "unknown"} tokens.")
    }

    /**
     * The runtime's own counts and timings, read by name from the JSON it reports, because
     * these field names belong to ExecuTorch and change between releases. A missing field
     * leaves a zero, which the engine replaces with its own wall-clock measure.
     */
    private fun outcomeFrom(stats: String?): RuntimeOutcome {
        if (stats == null) return RuntimeOutcome()
        val start = stats.longField("inference_start_ms")
        val promptEval = stats.longField("prompt_eval_end_ms")
        val end = stats.longField("inference_end_ms")
        val complete = start > 0 && promptEval > 0
        return RuntimeOutcome(
            promptTokens = stats.longField("prompt_tokens").toInt(),
            generatedTokens = stats.longField("generated_tokens").toInt(),
            prefillMs = if (complete) (promptEval - start).coerceAtLeast(0) else 0,
            decodeMs = if (complete && end > 0) (end - promptEval).coerceAtLeast(0) else 0,
        )
    }

    private fun String.longField(name: String): Long =
        Regex("\"$name\"\\s*:\\s*(\\d+)").find(this)?.groupValues?.get(1)?.toLongOrNull() ?: 0
}
