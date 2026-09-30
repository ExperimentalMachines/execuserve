package org.experimentalmachines.execuserve.host

import org.experimentalmachines.execuserve.engine.FinishReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleTestTest {

    @Test
    fun aStreamIsReadIntoTextAndFigures() {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val reader = ReplyReader { c, r ->
            content.append(c)
            reasoning.append(r)
        }
        val lines = listOf(
            """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
            ": keep-alive",
            """data: {"choices":[{"delta":{"reasoning_content":"Hmm."}}]}""",
            """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
            """data: {"choices":[{"delta":{"content":" world"},"finish_reason":"stop"}]}""",
            """data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":8}},"timings":{"predicted_per_second":25.5}}""",
        )
        lines.forEach { assertTrue(reader.line(it)) }
        assertFalse(reader.line("data: [DONE]"))
        assertEquals("Hello world", content.toString())
        assertEquals("Hmm.", reasoning.toString())
        val result = reader.result(firstTokenMs = 100, totalMs = 400)
        assertEquals(12, result.promptTokens)
        assertEquals(3, result.completionTokens)
        assertEquals(8, result.cachedTokens)
        assertEquals(25.5, result.decodeTokensPerSecond)
        assertEquals(FinishReason.STOP, result.finish)
    }

    @Test
    fun anErrorInTheStreamIsAFailure() {
        val reader = ReplyReader { _, _ -> }
        val failure = assertFailsWith<TestFailure> {
            reader.line("""data: {"error":{"message":"The prompt does not fit.","code":"context_length_exceeded"}}""")
        }
        assertEquals("The prompt does not fit.", failure.message)
    }

    @Test
    fun aWholeResponseIsReadToo() {
        var text = ""
        val reader = ReplyReader { c, _ -> text += c }
        reader.whole(
            """{"choices":[{"message":{"role":"assistant","content":"Hi"},"finish_reason":"length"}],""" +
                """"usage":{"prompt_tokens":5,"completion_tokens":1}}""",
        )
        assertEquals("Hi", text)
        assertEquals(FinishReason.LENGTH, reader.result(10, 10).finish)
    }

    @Test
    fun theCurlCommandSurvivesQuotesInThePrompt() {
        val curl = ConsoleTest.curl("http://127.0.0.1:8080/v1", "sk-1", "qwen3", "It's \"quoted\"", stream = true)
        assertTrue(curl.startsWith("curl -N http://127.0.0.1:8080/v1/chat/completions"))
        // A single quote closes the shell string, so it is written as '\''.
        assertTrue("""It'\''s \"quoted\"""" in curl)
        assertTrue("\"max_tokens\":${ConsoleTest.MAX_TOKENS}" in curl)
    }

    @Test
    fun errorBodiesAreReadInOpenAIShapeOrShortened() {
        assertEquals("No such model.", ConsoleTest.errorMessage("""{"error":{"message":"No such model."}}"""))
        assertEquals("plain text", ConsoleTest.errorMessage("plain text"))
        assertEquals("x".repeat(200), ConsoleTest.errorMessage("x".repeat(500)))
    }

    @Test
    fun exportsAreTheTwoVariablesSdksRead() {
        assertEquals(
            "export OPENAI_BASE_URL=http://h:1/v1\nexport OPENAI_API_KEY=sk\n",
            ConsoleTest.exports("http://h:1/v1", "sk"),
        )
    }
}
