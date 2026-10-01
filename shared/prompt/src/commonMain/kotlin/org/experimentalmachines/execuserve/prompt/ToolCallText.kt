package org.experimentalmachines.execuserve.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Each family's spelling of "I call this tool", the inverse of [ToolCallParser].
 *
 * Every form here is one the parser reads back to the same name and arguments, which is
 * the property the tests hold it to: a call that went out, came back as `tool_calls`, and was
 * rendered into history must read as the same call the model made.
 */
object ToolCallText {

    /** `<tool_call>\n{"name": ..., "arguments": {...}}\n</tool_call>`: Qwen3, Qwen2.5, SmolLM3. */
    fun hermes(calls: List<ToolCall>): String = calls.joinToString("\n") { call ->
        "<tool_call>\n{\"name\": ${quote(call.name)}, \"arguments\": ${objectText(call.argumentsJson)}}\n</tool_call>"
    }

    /** Llama 3.2's bare object, with `parameters` rather than `arguments`. One call per turn. */
    fun llamaJson(calls: List<ToolCall>): String = calls.joinToString("\n") { call ->
        "{\"name\": ${quote(call.name)}, \"parameters\": ${objectText(call.argumentsJson)}}"
    }

    /** `<|tool_call_start|>[name(key="value", n=2)]<|tool_call_end|>`: LFM2 and LFM2.5. */
    fun pythonic(calls: List<ToolCall>): String = buildString {
        append("<|tool_call_start|>[")
        calls.forEachIndexed { index, call ->
            if (index > 0) append(", ")
            append(call.name).append('(')
            arguments(call.argumentsJson).entries.forEachIndexed { at, (key, value) ->
                if (at > 0) append(", ")
                append(key).append('=').append(python(value))
            }
            append(')')
        }
        append("]<|tool_call_end|>")
    }

    /** Qwen3.5's XML: one `<parameter=...>` block per argument, strings unquoted. */
    fun qwen35Xml(calls: List<ToolCall>): String = calls.joinToString("\n") { call ->
        buildString {
            append("<tool_call>\n<function=").append(call.name).append(">\n")
            arguments(call.argumentsJson).forEach { (key, value) ->
                append("<parameter=").append(key).append(">\n")
                append(if (value is JsonPrimitive && value.isString) value.content else value.toString())
                append("\n</parameter>\n")
            }
            append("</function>\n</tool_call>")
        }
    }

    private fun arguments(json: String): JsonObject = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    /** The arguments as a JSON object, or `{}` when a client sent something that is not one. */
    private fun objectText(json: String): String = arguments(json).toString()

    private fun quote(text: String): String = JsonPrimitive(text).toString()

    /** A JSON value as a Python literal, which is what LFM writes inside its call list. */
    private fun python(value: JsonElement): String = when (value) {
        is JsonNull -> "None"
        is JsonPrimitive -> when {
            value.isString -> quote(value.content)
            value.booleanOrNull == true -> "True"
            value.booleanOrNull == false -> "False"
            else -> value.content
        }
        is JsonArray -> value.joinToString(", ", "[", "]") { python(it) }
        is JsonObject -> value.entries.joinToString(", ", "{", "}") { (k, v) -> quote(k) + ": " + python(v) }
    }
}
