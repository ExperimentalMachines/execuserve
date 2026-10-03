package org.experimentalmachines.execuserve.host

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.prompt.PromptTemplates

/**
 * The console's chat: a conversation with the server, sent over HTTP like any client's, so
 * what someone tries here is exactly what their app will get. The whole conversation goes
 * with each turn, as the OpenAI API expects; the server's cache makes that cheap.
 */
object ConsoleChat {
    /** Room for a full answer from a phone model without running away; the web chat's default too. */
    const val MAX_TOKENS = 1024

    enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant") }

    data class Turn(val role: Role, val content: String)

    /**
     * A streamed Chat Completions request for [turns], in order. [thinking] sets the
     * template's thinking switch when the model has one; null leaves the model's default.
     */
    fun body(model: String, turns: List<Turn>, thinking: Boolean?): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            turns.filter { it.content.isNotBlank() }.forEach { turn ->
                addJsonObject {
                    put("role", turn.role.wire)
                    put("content", turn.content)
                }
            }
        }
        put("max_tokens", MAX_TOKENS)
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        if (thinking != null) putJsonObject("chat_template_kwargs") { put("enable_thinking", thinking) }
    }.toString()

    /** Whether [entry]'s chat template has a thinking switch worth offering. */
    fun canThink(entry: ModelEntry): Boolean = PromptTemplates.forModel(entry.family ?: entry.files.model.substringAfterLast('/'))?.supportsThinking == true
}
