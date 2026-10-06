package org.experimentalmachines.execuserve.app.ui

/** A reply while it is still arriving; [MarkdownText] draws it. */
object ChatText {
    /**
     * [text] as it can be shown while it is still arriving: a bold or code span the model has
     * opened on the last line and not yet closed is closed, so it reads as bold or code now
     * rather than as raw asterisks until its end arrives. An opening `**` with nothing after it
     * yet is held back. Inside an open code fence nothing is touched. The browser chat does the
     * same (chat.js closeOpen).
     */
    fun closeOpen(text: String): String {
        if (FENCE.findAll(text).count() % 2 == 1) return text
        val lastLine = text.substringAfterLast('\n')
        val ticks = lastLine.count { it == '`' }
        val stars = BOLD.findAll(lastLine).count()
        return when {
            ticks % 2 == 1 -> "$text`"
            stars % 2 == 1 && lastLine.trimEnd().endsWith("**") -> text.trimEnd().removeSuffix("**").trimEnd()
            stars % 2 == 1 -> "$text**"
            else -> text
        }
    }

    private val FENCE = Regex("^```", RegexOption.MULTILINE)
    private val BOLD = Regex("""\*\*""")
}
