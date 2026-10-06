package org.experimentalmachines.execuserve.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The little Markdown phone models write, as styled text: `**bold**`, `` `code` `` and `#`
 * headings; `---` rules are dropped. Lists and paragraphs already read correctly as plain
 * lines, so they are left alone, and anything unmatched (an odd asterisk mid-stream) shows as
 * typed rather than vanishing.
 */
object ChatText {
    fun styled(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        lines(text).forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val heading = HEADING.matchAt(line, 0)
            if (heading != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(line.substring(heading.range.last + 1), codeBackground) }
            } else {
                inline(line, codeBackground)
            }
        }
    }

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

    /** The lines to show: a horizontal rule goes, and the blank lines around it become one. */
    private fun lines(text: String): List<String> = text.lines().filterNot { RULE.matches(it.trim()) }
        .fold(mutableListOf<String>()) { kept, line ->
            if (!(line.isBlank() && kept.lastOrNull()?.isBlank() == true)) kept += line
            kept
        }

    private fun AnnotatedString.Builder.inline(line: String, codeBackground: Color) {
        var at = 0
        while (at < line.length) {
            val next = INLINE.find(line, at) ?: break
            append(line, at, next.range.first)
            val (bold, code) = next.destructured
            if (bold.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
            } else {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(code) }
            }
            at = next.range.last + 1
        }
        append(line, at, line.length)
    }

    private val HEADING = Regex("#{1,4} ")
    private val RULE = Regex("""(-{3,}|\*{3,}|_{3,})""")
    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
    private val FENCE = Regex("^```", RegexOption.MULTILINE)
    private val BOLD = Regex("""\*\*""")
}
