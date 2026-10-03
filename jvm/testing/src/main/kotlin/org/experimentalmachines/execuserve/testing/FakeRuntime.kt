package org.experimentalmachines.execuserve.testing

import org.experimentalmachines.execuserve.engine.ContextOverflow
import org.experimentalmachines.execuserve.engine.LlmRuntime
import org.experimentalmachines.execuserve.engine.LlmSession
import org.experimentalmachines.execuserve.engine.ModelFacts
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.engine.RuntimeFailure
import org.experimentalmachines.execuserve.engine.RuntimeOutcome
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A runtime that behaves like the ExecuTorch 1.4.0 runner where it matters to the engine:
 *
 * - its position only grows; [FakeSession.reset] is the only way back;
 * - `generate` clears the stop flag when its loop starts, so a stop issued earlier is lost;
 * - the token that stopped a generation is sampled but never fed, so the runtime holds the
 *   prompt and every fragment before it;
 * - a prompt past the window throws [ContextOverflow].
 *
 * One character is one token here, which keeps the arithmetic in the tests readable.
 */
class FakeRuntime(
    private val window: Int = 4096,
    private val prefillLength: Int? = null,
    /** The reply for a prompt, as the fragments the model would emit. */
    private val reply: (prompt: String) -> List<String> = { listOf("Hello", " world", "<|im_end|>") },
    override val id: String = "fake",
) : LlmRuntime {
    val log: MutableList<String> = CopyOnWriteArrayList()

    /** The family each open was told, in order. */
    val openedFamilies: MutableList<String?> = CopyOnWriteArrayList()
    val sessions: MutableList<FakeSession> = CopyOnWriteArrayList()

    /** Milliseconds each prefilled character takes, to make long prompts cost time. */
    @Volatile var prefillDelayPerCharMs: Double = 0.0

    /** Milliseconds each token takes. */
    @Volatile var tokenDelayMs: Long = 0

    /** Files that fail to open, with the message. */
    val broken: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()

    /** When set, generate blocks on this before producing anything: a wedged native call. */
    @Volatile var hang: CountDownLatch? = null

    override fun tokenizerAddsBos(files: ModelFiles): Boolean = true

    override fun probe(files: ModelFiles): ModelFacts = ModelFacts(window, prefillLength, true)

    override fun open(files: ModelFiles, facts: ModelFacts, family: String?): LlmSession {
        broken[files.model]?.let { throw RuntimeFailure(it) }
        log += "open ${files.model}"
        openedFamilies += family
        return FakeSession(files.model).also { sessions += it }
    }

    inner class FakeSession(private val name: String) : LlmSession {
        /** Everything the runtime holds since its last reset. */
        val held = StringBuilder()

        @Volatile private var stopRequested = false

        @Volatile var closed = false

        override fun prefill(text: String) {
            check(!closed)
            log += "prefill $text"
            pause(text.length)
            if (held.length + text.length > window) throw ContextOverflow("Max seq length exceeded")
            held.append(text)
        }

        override fun generate(text: String, temperature: Float, onToken: (String) -> Unit): RuntimeOutcome {
            check(!closed)
            require(text.isNotEmpty()) { "empty prompt" }
            log += "generate $text"
            if (held.length + text.length > window) throw ContextOverflow("Max seq length exceeded")
            held.append(text)
            pause(text.length)
            hang?.await(1, TimeUnit.MINUTES)
            stopRequested = false
            var produced = 0
            for (fragment in reply(held.toString())) {
                if (held.length + 1 > window) break
                if (tokenDelayMs > 0) Thread.sleep(tokenDelayMs)
                onToken(fragment)
                produced++
                // The stopping token and the end-of-sequence token are sampled, never fed.
                if (stopRequested || fragment in EOS) break
                held.append(fragment)
            }
            return RuntimeOutcome(promptTokens = text.length, generatedTokens = produced)
        }

        private fun pause(chars: Int) {
            val ms = (chars * prefillDelayPerCharMs).toLong()
            if (ms > 0) Thread.sleep(ms)
        }

        override fun reset() {
            log += "reset"
            held.clear()
        }

        override fun stop() {
            stopRequested = true
        }

        override fun close() {
            log += "close $name"
            closed = true
        }
    }

    companion object {
        val EOS = setOf("<|im_end|>", "<|eot_id|>")
    }
}
