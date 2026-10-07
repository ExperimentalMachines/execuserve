package org.experimentalmachines.execuserve.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExampleRequestTest {
    @Test
    fun eachApiGetsItsOwnPathAndKeyHeader() {
        val openai = ExampleRequest.curl(ExampleRequest.Api.OPENAI, "http://127.0.0.1:8080/v1", "es-key", "qwen3-0.6b")
        assertTrue(openai.startsWith("curl http://127.0.0.1:8080/v1/chat/completions"), openai)
        assertTrue("Authorization: Bearer es-key" in openai, openai)
        assertTrue("\"model\": \"qwen3-0.6b\"" in openai, openai)
        val anthropic = ExampleRequest.curl(ExampleRequest.Api.ANTHROPIC, "http://127.0.0.1:8080/v1", "es-key", "qwen3-0.6b")
        // Not /v1/v1/messages: the Anthropic base is the server's root.
        assertTrue(anthropic.startsWith("curl http://127.0.0.1:8080/v1/messages"), anthropic)
        assertTrue("x-api-key: es-key" in anthropic && "anthropic-version" in anthropic, anthropic)
    }

    @Test
    fun noKeyMeansNoKeyHeader() {
        val open = ExampleRequest.curl(ExampleRequest.Api.OPENAI, "http://127.0.0.1:8080/v1", null, "m")
        assertFalse("Authorization" in open, open)
        assertEquals("Base URL: http://127.0.0.1:8080\nModel: m", ExampleRequest.settings(ExampleRequest.Api.ANTHROPIC, "http://127.0.0.1:8080/v1", null, "m"))
    }
}
