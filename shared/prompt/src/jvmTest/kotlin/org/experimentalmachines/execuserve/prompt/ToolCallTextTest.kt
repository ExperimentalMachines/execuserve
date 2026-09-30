package org.experimentalmachines.execuserve.prompt

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/** Every family's spelling must read back, through the parser the server uses, as the same call. */
class ToolCallTextTest {

    private val call = ToolCall("call_0", "web_search", """{"query":"tide times","limit":3,"fresh":true}""")

    private fun roundTrip(text: String) {
        val parsed = ToolCallParser.parse(text)
        assertThat(parsed.calls).hasSize(1)
        assertThat(parsed.calls[0].name).isEqualTo("web_search")
        assertThat(Json.parseToJsonElement(parsed.calls[0].argumentsJson))
            .isEqualTo(Json.parseToJsonElement(call.argumentsJson))
        assertThat(parsed.text).isEmpty()
    }

    @Test fun hermesReadsBack() = roundTrip(ToolCallText.hermes(listOf(call)))

    @Test fun llamaReadsBack() = roundTrip(ToolCallText.llamaJson(listOf(call)))

    @Test fun pythonicReadsBack() = roundTrip(ToolCallText.pythonic(listOf(call)))

    @Test fun templatesPickTheirOwnSpelling() {
        assertThat(PromptTemplates.forModel("LFM2.5-1.2B-Instruct.pte")!!.toolCallText(listOf(call)))
            .startsWith("<|tool_call_start|>[web_search(")
        assertThat(PromptTemplates.forModel("Qwen3-1.7B.pte")!!.toolCallText(listOf(call)))
            .startsWith("<tool_call>\n{\"name\": \"web_search\"")
        assertThat(PromptTemplates.forModel("Llama-3.2-1B-Instruct.pte")!!.toolCallText(listOf(call)))
            .startsWith("{\"name\": \"web_search\", \"parameters\"")
    }

    @Test fun qwen35XmlNamesTheFunction() {
        val parsed = ToolCallParser.parse(ToolCallText.qwen35Xml(listOf(call)))
        assertThat(parsed.calls.single().name).isEqualTo("web_search")
    }
}
