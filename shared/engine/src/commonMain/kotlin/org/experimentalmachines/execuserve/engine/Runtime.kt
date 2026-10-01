package org.experimentalmachines.execuserve.engine

/**
 * The files a compiled model needs. A `.pte` carries a graph and no tokenizer, so both are
 * always named together; handing a model the wrong tokenizer produces fluent nonsense
 * rather than an error.
 */
data class ModelFiles(val model: String, val tokenizer: String)

/**
 * What a model file says about itself before it is opened.
 *
 * @property contextLength the window it was exported with. The runtime cannot resize it.
 * @property prefillLength the most tokens one prefill call may carry; a call of exactly this
 * many fails on the 1.4.0 runtime, so the engine stays under it.
 * @property stateResetAtZero whether the graph clears its recurrent state at position zero.
 */
data class ModelFacts(val contextLength: Int?, val prefillLength: Int? = null, val stateResetAtZero: Boolean? = null)

/** What one `generate` call measured. Zero means the runtime did not say. */
data class RuntimeOutcome(val promptTokens: Int = 0, val generatedTokens: Int = 0, val prefillMs: Long = 0, val decodeMs: Long = 0)

/** A failure the runtime reported, as text a person can act on. */
open class RuntimeFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The one failure a full window produces. */
class ContextOverflow(message: String) : RuntimeFailure(message)

/**
 * An inference backend: the ExecuTorch AAR on Android today, the Apple frameworks on iOS
 * later, a Vulkan or NPU build of the same, or a script in tests.
 */
interface LlmRuntime {
    /** A stable name for logs and `/v1/models`, e.g. `executorch-xnnpack`. */
    val id: String

    /** Maximum safely resident sessions under the current runtime configuration. */
    val maxResidentModels: Int get() = Int.MAX_VALUE

    /**
     * Whether this runtime's tokenizer writes the model's BOS itself. The ExecuTorch 1.4.0
     * Android runner never does, so the engine writes the family's BOS into the text.
     */
    fun tokenizerAddsBos(files: ModelFiles): Boolean

    /** Reads [ModelFacts] without opening the model for generation. Throws if unreadable. */
    fun probe(files: ModelFiles): ModelFacts

    /** Opens the model. Blocking; called only on the compute lane. */
    fun open(files: ModelFiles, facts: ModelFacts): LlmSession

    /**
     * CPU threads for models opened from now on; 0 leaves the choice to the runtime. A
     * runtime without such a knob ignores it.
     */
    var threads: Int
        get() = 0
        set(@Suppress("UNUSED_PARAMETER") value) = Unit

    /** The threads the runtime computes with now, when it can say; null when it cannot. */
    fun activeThreads(): Int? = null
}

/**
 * One loaded model: one KV cache, one sequence.
 *
 * Not thread-safe, and never called from more than one thread at a time: the engine calls
 * it only from its compute lane. [stop] is called only from inside [generate]'s callback,
 * on that same lane, so it can only ever reach the generation that is running.
 */
interface LlmSession : AutoCloseable {

    /** Appends [text] at the current position without generating. Not interruptible. */
    fun prefill(text: String)

    /**
     * Appends [text] and generates until the model ends, [stop] is called from [onToken],
     * or the window is full. [text] must not be empty.
     *
     * @throws ContextOverflow when the window fills before or while generating.
     */
    fun generate(text: String, temperature: Float, onToken: (String) -> Unit): RuntimeOutcome

    /**
     * Back to position zero with no state from any earlier prompt, including recurrent
     * state the runner itself does not clear (the binding reopens the file when it must).
     */
    fun reset()

    /** Ends the running [generate]. Called only from its callback. */
    fun stop()
}
