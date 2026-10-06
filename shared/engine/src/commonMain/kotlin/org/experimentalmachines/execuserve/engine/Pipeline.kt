package org.experimentalmachines.execuserve.engine

import org.experimentalmachines.execuserve.prompt.ToolCall
import org.experimentalmachines.execuserve.prompt.ToolCallParser

/** Text released to the client by one step of the pipeline. */
internal data class Released(val content: String = "", val reasoning: String = "") {
    val isEmpty: Boolean get() = content.isEmpty() && reasoning.isEmpty()

    operator fun plus(other: Released) = Released(content + other.content, reasoning + other.reasoning)

    companion object {
        val NONE = Released()
    }
}

/**
 * Every fragment the runtime emits, on its way to becoming what a client may see.
 *
 * Four stages, in order, each one because the runtime does not do it:
 *
 * 1. **Stop markers.** ExecuTorch streams whatever it decodes, so `<|im_end|>` arrives as
 *    text, and in pieces. Anything that could still grow into a template marker or one of
 *    the request's `stop` strings is held back until it becomes one or cannot.
 * 2. **Discipline.** The 1.4.0 runner ignores its token budget and has no repetition
 *    penalty. One callback is one token, so the budget is counted here, and a run of
 *    [MAX_TOKEN_RUT] identical fragments (a greedy model in a rut, measured on SmolLM3)
 *    ends the generation.
 * 3. **Reasoning.** A leading `<think>...</think>` goes to `reasoning_content`.
 * 4. **Tool calls.** With tools offered, text from the first call opener on is held and
 *    parsed at the end, so raw call syntax never streams as content.
 *
 * Not thread-safe: fed only from the runtime callback on the compute lane.
 */
internal class TokenPipeline(
    private val markers: List<String>,
    private val stops: List<String>,
    private val budget: Int,
    startsInThought: Boolean,
    toolsOffered: Boolean,
) {
    private val raw = StringBuilder()
    private var released = 0
    private var end = -1
    private var lastFragment = ""
    private var repeats = 0
    private val longestStop = (markers + stops).maxOfOrNull { it.length } ?: 0

    private val splitter = ReasoningSplitter(startsInThought)
    private val guard = ToolCallGuard(toolsOffered)

    /** Callbacks received, which is the number of tokens generated. */
    var tokens = 0
        private set

    /** The model ended its turn with the template's own marker: the only clean end. */
    var endedOnMarker = false
        private set

    /**
     * The end marker began its own fragment, as a special token does. Only then is it
     * certain that every fragment before it was fed and the marker itself was not; a marker
     * a model spelled out across ordinary tokens leaves a partial marker in the runtime.
     */
    var markerAlone = false
        private set

    /** One of the request's `stop` strings ended the generation. */
    var endedOnStop = false
        private set

    /** Which of them, when one did: Anthropic's Messages API reports it. */
    var stoppedBy: String? = null
        private set

    /** The budget or the rut guard ended it. */
    var cut = false
        private set

    val content = StringBuilder()
    val reasoning = StringBuilder()

    /** Everything the model wrote, up to (not including) whatever ended it. */
    val rawAnswer: String get() = raw.substring(0, if (end >= 0) end else raw.length)

    /** Whether the generation should stop after this token. */
    val shouldStop: Boolean get() = end >= 0 || cut

    fun accept(fragment: String): Released {
        // Past the budget the runner can still deliver a token before it sees the stop:
        // neither counted nor released, so max_tokens is never exceeded (codex review).
        if (cut) return Released.NONE
        tokens++
        repeats = if (fragment == lastFragment) repeats + 1 else 0
        lastFragment = fragment
        // The runner can deliver one more token after it was asked to stop.
        if (end >= 0) return Released.NONE

        val searchFrom = (raw.length - longestStop).coerceAtLeast(0)
        raw.append(fragment)
        findEnd(searchFrom)
        if (endedOnMarker) markerAlone = end == raw.length - fragment.length
        val safe = if (end >= 0) end else raw.length - dangling()
        val out = if (safe > released) downstream(raw.substring(released, safe)) else Released.NONE
        if (safe > released) released = safe
        if (end < 0 && (tokens >= budget || repeats >= MAX_TOKEN_RUT)) cut = true
        return out
    }

    /** Releases whatever was held back, and returns it with the parsed tool calls. */
    fun finish(): Pair<Released, List<ToolCall>> {
        val limit = if (end >= 0) end else raw.length
        // downstream() records what it releases; the flushed tail is recorded here.
        val held = if (limit > released) downstream(raw.substring(released, limit)) else Released.NONE
        released = limit
        val (thought, answer) = splitter.flush()
        val flushed = Released(content = guard.accept(answer), reasoning = thought)
        val (rest, calls) = guard.finish()
        val last = flushed + Released(content = rest)
        content.append(last.content)
        reasoning.append(last.reasoning)
        return held + last to calls
    }

    private fun downstream(text: String): Released {
        val (thought, answer) = splitter.accept(text)
        val out = Released(content = guard.accept(answer), reasoning = thought)
        content.append(out.content)
        reasoning.append(out.reasoning)
        return out
    }

    private fun findEnd(from: Int) {
        var best = -1
        var bestIsMarker = false
        var bestStop: String? = null
        for (marker in markers) {
            val at = raw.indexOf(marker, from)
            if (at >= 0 && (best < 0 || at < best)) {
                best = at
                bestIsMarker = true
            }
        }
        for (stop in stops) {
            val at = raw.indexOf(stop, from)
            if (at >= 0 && (best < 0 || at < best)) {
                best = at
                bestIsMarker = false
                bestStop = stop
            }
        }
        if (best >= 0) {
            end = best
            endedOnMarker = bestIsMarker
            endedOnStop = !bestIsMarker
            stoppedBy = if (bestIsMarker) null else bestStop
        }
    }

    /** How much of the tail could still grow into a marker or a stop string. */
    private fun dangling(): Int = (markers + stops).maxOfOrNull { danglingPrefix(raw, it) } ?: 0

    companion object {
        /** Identical consecutive fragments before generation is cut as degenerate. */
        const val MAX_TOKEN_RUT = 32
    }
}

/** The length of the longest tail of [text] that is a proper prefix of [marker]. */
internal fun danglingPrefix(text: CharSequence, marker: String): Int {
    val longest = minOf(marker.length - 1, text.length)
    for (length in longest downTo 1) {
        var matches = true
        val offset = text.length - length
        for (i in 0 until length) {
            if (text[offset + i] != marker[i]) {
                matches = false
                break
            }
        }
        if (matches) return length
    }
    return 0
}

/**
 * Splits a leading `<think>` block from the answer, incrementally.
 *
 * Only a block at the very start counts: Qwen3 and SmolLM3 open with one when reasoning, and
 * a tag later in an answer is the model talking about tags. When the prompt itself ended
 * inside an open block (Qwen3.5 with reasoning on), the stream begins mid-thought.
 */
internal class ReasoningSplitter(startsInThought: Boolean) {
    private enum class Mode { UNDECIDED, THINKING, AFTER_THOUGHT, ANSWER }

    private var mode = if (startsInThought) Mode.THINKING else Mode.UNDECIDED
    private val pending = StringBuilder()
    private var thoughtStarted = false

    /** Returns (reasoning, content) that became safe to release. */
    fun accept(text: String): Pair<String, String> {
        pending.append(text)
        val thought = StringBuilder()
        val answer = StringBuilder()
        var progressed = true
        while (progressed) {
            progressed = false
            when (mode) {
                Mode.UNDECIDED -> {
                    val trimmed = pending.trimStart()
                    when {
                        trimmed.isEmpty() -> Unit
                        trimmed.startsWith(OPEN) -> {
                            pending.clear()
                            pending.append(trimmed.substring(OPEN.length))
                            mode = Mode.THINKING
                            progressed = true
                        }
                        OPEN.startsWith(trimmed) -> Unit
                        else -> {
                            answer.append(pending)
                            pending.clear()
                            mode = Mode.ANSWER
                        }
                    }
                }
                Mode.THINKING -> {
                    if (!thoughtStarted) {
                        val start = pending.indexOfFirst { it != '\n' }
                        if (start < 0) {
                            pending.clear()
                            continue
                        }
                        pending.deleteRange(0, start)
                        thoughtStarted = true
                    }
                    val close = pending.indexOf(CLOSE)
                    if (close >= 0) {
                        thought.append(pending, 0, close)
                        val rest = pending.substring(close + CLOSE.length)
                        pending.clear()
                        pending.append(rest)
                        mode = Mode.AFTER_THOUGHT
                        progressed = true
                    } else {
                        val safe = pending.length - danglingPrefix(pending, CLOSE)
                        thought.append(pending, 0, safe)
                        pending.deleteRange(0, safe)
                    }
                }
                Mode.AFTER_THOUGHT -> {
                    // The template writes a blank line between the block and the answer.
                    val start = pending.indexOfFirst { it != '\n' }
                    if (start >= 0) {
                        answer.append(pending, start, pending.length)
                        pending.clear()
                        mode = Mode.ANSWER
                    } else {
                        pending.clear()
                    }
                }
                Mode.ANSWER -> {
                    answer.append(pending)
                    pending.clear()
                }
            }
        }
        return thought.toString() to answer.toString()
    }

    fun flush(): Pair<String, String> {
        val rest = pending.toString()
        pending.clear()
        return when (mode) {
            Mode.THINKING -> rest to ""
            Mode.UNDECIDED, Mode.ANSWER -> "" to rest
            Mode.AFTER_THOUGHT -> "" to rest.trimStart('\n')
        }
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

/**
 * Holds back tool-call syntax when tools were offered, and parses it at the end.
 *
 * Content before the first opener streams as usual. From the opener on, nothing is
 * released until the reply is complete, because a call is only a call once it parses: a
 * reply whose "call" does not parse is released whole as content, not swallowed.
 */
internal class ToolCallGuard(private val active: Boolean) {
    private val all = StringBuilder()
    private var released = 0
    private var holdFrom = -1

    fun accept(text: String): String {
        if (!active) return text
        all.append(text)
        if (holdFrom < 0) findOpener()
        val safe = if (holdFrom >= 0) holdFrom else all.length - OPENERS.maxOf { danglingPrefix(all, it) }
        if (safe <= released) return ""
        return all.substring(released, safe).also { released = safe }
    }

    fun finish(): Pair<String, List<ToolCall>> {
        if (!active || holdFrom < 0) {
            val rest = if (released < all.length) all.substring(released) else ""
            released = all.length
            return rest to emptyList()
        }
        val parsed = ToolCallParser.parse(all.toString())
        if (parsed.calls.isEmpty()) {
            val rest = all.substring(released)
            released = all.length
            return rest to emptyList()
        }
        // Text around the calls is content too ("Looking it up. <tool_call>...</tool_call>
        // Back soon."). What streamed before the opener cannot be taken back, so only the
        // part of the parser's remaining text past it is released (codex review).
        val sent = all.substring(0, released).trim()
        val remaining = parsed.text.trim()
        val tail = if (remaining.startsWith(sent)) remaining.substring(sent.length).trim() else ""
        released = all.length
        return (
            if (tail.isEmpty()) {
                ""
            } else if (sent.isEmpty()) {
                tail
            } else {
                "\n" + tail
            }
            ) to parsed.calls
    }

    private fun findOpener() {
        // A reply that is nothing but a JSON object is how Llama 3.2 calls a tool.
        val first = all.indexOfFirst { !it.isWhitespace() }
        if (first >= 0 && first >= released && all[first] == '{') {
            holdFrom = first
            return
        }
        holdFrom = OPENERS.mapNotNull { opener -> all.indexOf(opener).takeIf { it >= 0 } }.minOrNull() ?: -1
    }

    private companion object {
        val OPENERS = listOf("<tool_call>", "<|tool_call_start|>", "<|python_tag|>")
    }
}
