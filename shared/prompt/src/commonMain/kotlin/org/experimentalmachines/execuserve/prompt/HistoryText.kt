package org.experimentalmachines.execuserve.prompt

/**
 * The text of an assistant turn as a client sends it back: its content, and any tool calls
 * written in the family's own syntax. One definition, used both to render a request and to
 * recognise a turn the server itself produced, so the two can never drift apart.
 */
object HistoryText {
    fun of(content: String, calls: List<ToolCall>, template: PromptTemplate): String {
        if (calls.isEmpty()) return content
        val syntax = template.toolCallText(calls)
        return if (content.isEmpty()) syntax else content + "\n" + syntax
    }
}
