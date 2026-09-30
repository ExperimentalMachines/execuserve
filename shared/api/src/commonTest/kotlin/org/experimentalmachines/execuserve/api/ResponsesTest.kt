package org.experimentalmachines.execuserve.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResponsesTest {

    @Test
    fun chunkCarriesAnExplicitNullFinishReasonUntilTheLast() {
        val chunk = ChatResponses.chunk("id", 1, "m", ChatResponses.contentDelta("hi"))
        val choice = chunk["choices"]!!.jsonArray[0].jsonObject
        assertEquals(JsonNull, choice["finish_reason"])
        assertEquals("hi", choice["delta"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse("tool_calls" in choice["delta"]!!.jsonObject)
    }

    @Test
    fun toolOnlyMessageHasNullContentAndNoIndexOnCalls() {
        val body = ChatResponses.completion(
            "id", 1, "m", "", null,
            listOf(ToolCallOut("call_0", "search", """{"q":"x"}""")),
            "tool_calls", Usage(10, 5, 3), null,
        )
        val message = body["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals(JsonNull, message["content"])
        val call = message["tool_calls"]!!.jsonArray[0].jsonObject
        assertFalse("index" in call)
        assertEquals("""{"q":"x"}""", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val usage = body["usage"]!!.jsonObject
        assertEquals(15, usage["total_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, usage["prompt_tokens_details"]!!.jsonObject["cached_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun streamedToolCallsCarryAnIndex() {
        val delta = ChatResponses.toolCallsDelta(listOf(ToolCallOut("a", "f", "{}"), ToolCallOut("b", "g", "{}")))
        val calls = delta["tool_calls"]!!.jsonArray
        assertEquals("1", calls[1].jsonObject["index"]!!.jsonPrimitive.content)
    }

    @Test
    fun requestsIgnoreFieldsThisServerHasNeverHeardOf() {
        val request = ApiJson.decodeFromString<ChatCompletionRequest>(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"top_p":0.9,"brand_new_field":{"x":1}}""",
        )
        assertEquals("m", request.model)
        assertTrue(request.messages.single().content != null)
    }

    @Test
    fun sseFramesMatchWhatTheSdksParse() {
        assertEquals("data: [DONE]\n\n", Sse.DONE)
        assertEquals(": queued 2 ahead\n\n", Sse.comment("queued 2 ahead"))
    }
}
