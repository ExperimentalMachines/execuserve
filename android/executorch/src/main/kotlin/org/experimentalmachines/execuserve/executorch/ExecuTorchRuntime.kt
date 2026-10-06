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
 * ExecuTorch 1.5.1 on XNNPACK, Vulkan and Qualcomm's NPU (QNN): one library carries all three
 * delegates, and the install's recorded backend says which runner opens a file. Everything below was measured in OpenWeights on 1.4.0, which has
 * run these exports on phones since 2026-08; the comments say what each line works around.
 * 1.5.1 left the JNI LLM layer (jni_layer_llama.cpp) and the threadpool unchanged, so the
 * BOS, token-budget and stop behaviour described here carries over.
 */
class ExecuTorchRuntime(private val allowMultipleResidents: () -> Boolean = { true }) : LlmRuntime {

    override val id: String = "executorch-xnnpack"

    override val maxResidentModels: Int
        get() = synchronized(poolLock) {
            if (threads == 0 && residentThreads == 0 && pinnedPoolThreads != null && allowMultipleResidents()) Int.MAX_VALUE else 1
        }

    /**
     * Never: the 1.4.0 JNI layer's `num_bos_` is zero with the constructor this uses, and
     * the C++ tokenizer adds BOS only when asked, even when `tokenizer.json`'s
     * post-processor declares one. LFM2.5-2.6B answered garbage without it (0/30 GSM8K on
     * four chips), so the engine writes the family's BOS into the text instead.
     */
    // Qualcomm's and MediaTek's runners each add the model's BOS themselves.
    override fun tokenizerAddsBos(files: ModelFiles): Boolean = files.backend == QNN || files.backend == NEUROPILOT

    /**
     * The export's own constants, read through a second, memory-mapped handle: a few
     * milliseconds and no copy of the weights. The LLM wrapper reads the same constants
     * and offers no way to ask for them.
     */
    override fun probe(files: ModelFiles): ModelFacts = synchronized(poolLock) {
        // Module.load's default is cores / 2, unlike LlmModule's performant cores - 1.
        // A different count destroys the pool that XNNPACK residents still reference.
        val probeThreads = if (openSessions > 0) {
            pinnedPoolThreads
                ?: throw RuntimeFailure("The active thread pool could not be identified safely.")
        } else {
            0
        }
        val program = Module.load(files.model, Module.LOAD_MODE_MMAP, probeThreads)
        try {
            val methods = program.getMethods().toSet()
            fun read(name: String): Int? = name.takeIf { it in methods }?.let {
                program.execute(it).firstOrNull()?.takeIf { v -> v.isInt }?.toInt()?.toInt()
            }?.takeIf { it > 0 }
            ModelFacts(
                contextLength = read("get_max_context_len") ?: read("get_max_seq_len"),
                prefillLength = read("get_max_seq_len"),
                stateResetAtZero = read("get_state_reset_at_zero")?.let { it != 0 },
                // Qualcomm's and MediaTek's runners split a prompt into their own 128-token blocks.
                chunkedPrefill = files.backend != QNN && files.backend != NEUROPILOT,
            )
        } finally {
            program.destroy()
        }
    }

    override fun open(files: ModelFiles, facts: ModelFacts, family: String?): LlmSession = synchronized(poolLock) {
        if (!File(files.model).isFile) throw RuntimeFailure("${files.model} is missing")
        if (!File(files.tokenizer).isFile) throw RuntimeFailure("${files.tokenizer} is missing")
        if (files.backend == NEUROPILOT) return@synchronized NeuroPilotSession(files, facts, EngineConfig().defaultTemperature)
        val selectedThreads = threads
        if (openSessions > 0 && (selectedThreads != 0 || residentThreads != 0 || pinnedPoolThreads == null)) {
            throw RuntimeFailure("Unload the current models before changing CPU threads or opening another model.")
        }
        ExecuTorchSession(files, facts, family, selectedThreads, poolLock) {
            openSessions--
            if (openSessions == 0) pinnedPoolThreads = null
        }.also {
            openSessions++
            residentThreads = selectedThreads
            pinnedPoolThreads = activeThreads()
        }
    }

    /**
     * ExecuTorch 1.4 has a process-wide pool. Its JNI constructors choose different
     * defaults; resetting to a different size frees the pool bound by loaded XNNPACK
     * models. Same-count resets are explicitly no-ops in upstream threadpool.cpp.
     *
     * Multiple residents therefore use the LLM constructor's unchanged default. Probes
     * preserve that count, and custom thread settings limit the engine to one resident.
     * Never mutate the pool while a session is alive, including during reset/reopen.
     */
    @Volatile override var threads: Int = 0

    /** Read back from ExecuTorch's own log, the line it writes whenever the pool is resized. */
    override fun activeThreads(): Int? = runCatching {
        Module.readLogBufferStatic()?.asList()?.asReversed()?.firstNotNullOfOrNull { line ->
            POOL_RESIZE.find(line)?.groupValues?.get(1)?.toIntOrNull()
        }
    }.getOrNull()

    companion object {
        // ExecuTorch's pool belongs to the process, not one Engine or runtime adapter.
        // Synchronizing lifecycle operations also protects metadata probes in another host.
        private val poolLock = Any()
        private var openSessions = 0
        private var residentThreads = 0
        private var pinnedPoolThreads: Int? = null

        /** The runtime this build links, for the console and for catalog compatibility checks. */
        const val VERSION: String = BuildConfig.EXECUTORCH_VERSION

        private val POOL_RESIZE = Regex("Resetting threadpool to (\\d+) threads")
    }
}

private class ExecuTorchSession(
    private val files: ModelFiles,
    private val facts: ModelFacts,
    private val family: String?,
    private val threads: Int,
    private val poolLock: Any,
    private val onClosed: () -> Unit,
) : LlmSession {

    /**
     * A QNN export runs on Qualcomm's own static-graph runner (the JNI layer's model type 4),
     * not the generic text runner: prefill and decode graphs compiled for one chip and one
     * window. The family picks its stop tokens; see [QnnSupport.prepare]. Declared before
     * [module], whose initializer reads it.
     */
    private val qnn: Boolean = files.backend == QNN

    private val guardId = NativeCrashGuard.idOf(files.model)

    private var module: LlmModule = NativeCrashGuard.around(guardId) { openModule() }
    private var hasRun = false
    private var closed = false

    /**
     * LFM2 exports made before 2026-09-17 keep their short-convolution state in a buffer the
     * graph never clears, and `resetContext` only rewinds the position: the next prompt
     * starts on the previous one's state (tool-call probability 0.94 fell to 0.38 in
     * OpenWeights' probe). An export that clears it says so with `get_state_reset_at_zero`;
     * any other used LFM2 file is reopened instead, about a second. The installed family says
     * which model this is; the file name is the fallback for files copied in by hand, since the
     * catalog names every file `model.pte`.
     */
    private val reopenOnReset: Boolean = isLfm2(family ?: File(files.model).name) && facts.stateResetAtZero != true

    private fun openModule(): LlmModule = synchronized(poolLock) {
        try {
            if (qnn) QnnSupport.prepare(family)
            // Each generation supplies its own temperature; the constructor needs one too.
            val type = if (qnn) MODEL_TYPE_QNN else LlmModule.MODEL_TYPE_TEXT
            val opened = LlmModule(type, files.model, files.tokenizer, EngineConfig().defaultTemperature)
            try {
                // XNNPACK binds the existing pool at load(). A custom count is safe only
                // while this is the process's sole model; the runtime enforces that limit.
                if (!qnn) threads.takeIf { it > 0 }?.let { Module.load(files.model, Module.LOAD_MODE_MMAP, it).destroy() }
                // The Kotlin wrapper throws on the JNI error code and returns Unit.
                opened.load()
                opened
            } catch (failure: Throwable) {
                runCatching { opened.close() }
                throw failure
            }
        } catch (failure: Throwable) {
            val detail = failure.message ?: failure::class.java.simpleName
            gpuRefusal(failure)?.let { throw it }
            npuRefusal(failure)?.let { throw it }
            // Link failures are Errors, not Exceptions; surface them as model load failures. The
            // runner's own reasons go only to ExecuTorch's log buffer ("Failed to load model
            // runner" says nothing), so its last lines come with the error.
            val log = recentRuntimeLog()
            throw RuntimeFailure("ExecuTorch could not open ${File(files.model).name}: $detail" + log.let { if (it.isEmpty()) "" else "\n$it" }, failure)
        }
    }

    /**
     * This phone's GPU cannot run a Vulkan export at all: the runtime said so in its own words
     * (see [VulkanSupport.recordIfIncompatible]). Recorded, so the catalog stops listing GPU
     * builds here, and reported with the way out. Any other failure returns null and is left
     * to the caller: a bad file, a full window or an allocation never costs the phone its GPU
     * builds, and no file name has to be trusted to say which delegate a model uses.
     */
    private fun gpuRefusal(failure: Throwable): RuntimeFailure? {
        val reason = VulkanSupport.recordIfIncompatible(failure) ?: return null
        return RuntimeFailure(
            "This phone's GPU cannot run this Vulkan build ($reason). GPU builds will not be listed on this phone " +
                "again; the same model's CPU build runs anywhere.",
            failure,
        )
    }

    /** This phone's NPU cannot start at all, in the runtime's words (see [QnnSupport.recordIfIncompatible]). */
    private fun npuRefusal(failure: Throwable): RuntimeFailure? {
        if (!qnn) return null
        val reason = QnnSupport.recordIfIncompatible(failure) ?: return null
        return RuntimeFailure(
            "This device's NPU cannot run this build ($reason). Its NPU builds will not be listed again until the app " +
                "is updated; try the same model's CPU build, which does not need the NPU.",
            failure,
        )
    }

    override fun prefill(text: String) {
        hasRun = true
        try {
            NativeCrashGuard.around(guardId) { module.prefillPrompt(text) }
        } catch (failure: Throwable) {
            throw gpuRefusal(failure) ?: npuRefusal(failure) ?: failure.asOverflow()
                ?: RuntimeFailure("ExecuTorch could not prefill: ${failure.message}", failure)
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
            // Qualcomm's runner stops at seq_len total positions and defaults it low; give it
            // the compiled window, and let stop() end the reply at the client's limit.
            .let { builder -> facts.contextLength?.takeIf { qnn }?.let(builder::seqLen) ?: builder }
            .build()
        try {
            NativeCrashGuard.around(guardId) {
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
            }
        } catch (failure: Throwable) {
            throw gpuRefusal(failure) ?: npuRefusal(failure) ?: failure.asOverflow()
                ?: RuntimeFailure("ExecuTorch failed while generating: ${failure.message}", failure)
        }
        error?.let { message ->
            val failure = RuntimeFailure(message)
            throw gpuRefusal(failure) ?: npuRefusal(failure) ?: failure.asOverflow() ?: failure
        }
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

    override fun close() = synchronized(poolLock) {
        if (closed) return@synchronized
        closed = true
        try {
            module.close()
        } finally {
            onClosed()
        }
    }

    /** The one failure a full window produces, whichever call it lands in. */
    private fun Throwable.asOverflow(): ContextOverflow? {
        val text = message.orEmpty()
        // The runner's own two diagnostics only (text_llm_runner.cpp: the conversation so far,
        // and a single prompt, past the window). The wrapper appends recent runtime log lines
        // to its messages, and those mention max_context_len in passing, so the name alone
        // would turn an unrelated failure into a full window (codex QA).
        if ("Max seq length exceeded" !in text && "Prompt exceeds KV cache capacity" !in text) return null
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

    private fun String.longField(name: String): Long = Regex("\"$name\"\\s*:\\s*(\\d+)").find(this)?.groupValues?.get(1)?.toLongOrNull() ?: 0
}

/** ExecuTorch's last few log lines, newest last, for an error that would otherwise say nothing. */
private fun recentRuntimeLog(lines: Int = 12): String = runCatching {
    Module.readLogBufferStatic()?.takeLast(lines)?.joinToString("\n").orEmpty()
}.getOrDefault("")

/** The install backend whose files Qualcomm's own LLM runner opens. */
internal const val QNN = "qnn"

/** The install backend whose NPU chunks MediaTek's runner prefills, decoding on the CPU build. */
internal const val NEUROPILOT = "mtk"

/** The JNI layer's model type for Qualcomm's static LLM runner (jni_layer_llama.cpp); the Java API names no constant for it. */
private const val MODEL_TYPE_QNN = 4

/** Whether [name] (a family such as `lfm2.5`, or a file name) is an LFM2 model. */
internal fun isLfm2(name: String): Boolean = "lfm2" in name.lowercase().filter { it.isLetterOrDigit() }
