package org.experimentalmachines.execuserve.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.experimentalmachines.execuserve.engine.ModelEntry
import org.experimentalmachines.execuserve.engine.ModelFiles
import org.experimentalmachines.execuserve.host.ConsoleChat.Role
import org.experimentalmachines.execuserve.host.ConsoleChat.Turn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleChatTest {
    private fun parse(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun theWholeConversationGoesInOrderAndEmptyTurnsDoNot() {
        val body = parse(
            ConsoleChat.body(
                "qwen3-1.7b",
                listOf(Turn(Role.USER, "Hi"), Turn(Role.ASSISTANT, "Hello."), Turn(Role.ASSISTANT, " "), Turn(Role.USER, "And now?")),
                thinking = null,
            ),
        )
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content to it.jsonObject["content"]!!.jsonPrimitive.content }
        assertEquals(listOf("user" to "Hi", "assistant" to "Hello.", "user" to "And now?"), messages)
        assertEquals("qwen3-1.7b", body["model"]!!.jsonPrimitive.content)
        assertEquals(ConsoleChat.MAX_TOKENS, body["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertFalse("chat_template_kwargs" in body, "no thinking switch unless asked")
    }

    @Test
    fun theThinkingSwitchIsSentOnlyWhenSet() {
        val off = parse(ConsoleChat.body("m", listOf(Turn(Role.USER, "x")), thinking = false))
        assertFalse(off["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
        val on = parse(ConsoleChat.body("m", listOf(Turn(Role.USER, "x")), thinking = true))
        assertTrue(on["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun onlyTemplatesWithAThinkingSwitchOfferIt() {
        fun entry(family: String) = ModelEntry("id", ModelFiles("x.pte", "x.json"), family = family)
        assertTrue(ConsoleChat.canThink(entry("qwen3")))
        assertFalse(ConsoleChat.canThink(entry("llama3.2")))
    }

    @Test
    fun thePrefillRateIsReadFromTheServersTimings() {
        val reader = ReplyReader { _, _ -> }
        reader.line(
            """data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":5},"timings":{"prompt_per_second":104.5,"predicted_per_second":20.8}}""",
        )
        val result = reader.result(firstTokenMs = 100, totalMs = 400)
        assertEquals(104.5, result.prefillTokensPerSecond)
        assertEquals(20.8, result.decodeTokensPerSecond)
    }
}
