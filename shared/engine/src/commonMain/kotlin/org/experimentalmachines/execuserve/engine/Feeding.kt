package org.experimentalmachines.execuserve.engine

/** One prefill piece: about two hundred tokens, which is the interrupt latency in text. */
internal const val WARM_PIECE_CHARS = 800

/**
 * The most characters handed to one `generate` call; anything before it is fed ahead in
 * pieces. OpenWeights measured this on the shipped exports: a single call of the full
 * prefill chunk fails ("Attempted to resize a bounded tensor with a maximum capacity of 2047
 * elements to 2048"), and characters are the only measure there is before tokenizing.
 */
internal const val GENERATE_TAIL_CHARS = 1600

/** The rough rate used where the runtime reports no count. */
internal const val CHARS_PER_TOKEN = 4

/**
 * The next piece of [text] to prefill: at most [limit] characters, ending at a line break
 * if one is near, else just before a space.
 *
 * Before a space, not after: tokenizers keep a space with the word that follows, so " not"
 * is one token in the whole prompt, and a piece ending in the space would hand the runtime
 * " " and "not" instead. OpenWeights fuzzed this rule on 156 texts against six tokenizers
 * with no token differing for LFM2.5, Qwen3, Gemma 3, Llama 3.2 or gpt-oss.
 */
internal fun warmPiece(text: String, limit: Int): String {
    var most = minOf(WARM_PIECE_CHARS, limit)
    if (text.length <= most) return text
    if (most > 0 && text[most - 1].isHighSurrogate() && text[most].isLowSurrogate()) most--
    if (most <= 0) throw RuntimeFailure("The prefill bound cannot fit a complete character")
    val window = text.substring(0, most)
    var newline = window.lastIndexOf('\n')
    // A line break stays with whatever whitespace follows it, more breaks or indentation:
    // tokenizers merge "\n" with the spaces after it, so cutting between them would split a
    // token. Indented text therefore falls back to an earlier break, or to a space.
    while (newline > 0 && newline + 1 < text.length && text[newline + 1].isWhitespace()) {
        newline = window.lastIndexOf('\n', newline - 1)
    }
    var cut = if (newline > 0) newline + 1 else window.lastIndexOf(' ').takeIf { it > 0 } ?: most
    if (cut == most) {
        // A hard cut must not split a special token such as <|im_start|>.
        val open = text.lastIndexOf('<', cut - 1)
        if (open > 0 && text.indexOf('>', open) >= cut) cut = open
    }
    return text.substring(0, cut)
}
