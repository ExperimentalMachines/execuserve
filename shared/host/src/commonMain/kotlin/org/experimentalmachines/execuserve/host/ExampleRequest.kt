package org.experimentalmachines.execuserve.host

/**
 * A complete request another app can copy and run, with this server's address, a key and a
 * model already filled in: the shortest answer to "how do I call it", in the two shapes the
 * server speaks.
 */
object ExampleRequest {
    enum class Api { OPENAI, ANTHROPIC }

    /** [baseUrl] is the OpenAI base, ending in /v1. A null [key] leaves the key header out. */
    fun curl(api: Api, baseUrl: String, key: String?, model: String): String {
        val root = baseUrl.trimEnd('/').removeSuffix("/v1")
        val body = """{"model": "$model", "max_tokens": 200, "messages": [{"role": "user", "content": "Hello!"}]}"""
        return when (api) {
            Api.OPENAI -> listOfNotNull(
                "curl $root/v1/chat/completions",
                key?.let { "-H \"Authorization: Bearer $it\"" },
                "-H \"Content-Type: application/json\"",
                "-d '$body'",
            )
            Api.ANTHROPIC -> listOfNotNull(
                "curl $root/v1/messages",
                key?.let { "-H \"x-api-key: $it\"" },
                "-H \"anthropic-version: 2023-06-01\"",
                "-H \"Content-Type: application/json\"",
                "-d '$body'",
            )
        }.joinToString(" \\\n  ")
    }

    /** What an app's settings ask for, in the words most of them use. */
    fun settings(api: Api, baseUrl: String, key: String?, model: String): String {
        val root = baseUrl.trimEnd('/').removeSuffix("/v1")
        return listOfNotNull(
            "Base URL: " + if (api == Api.OPENAI) "$root/v1" else root,
            key?.let { "API key: $it" },
            "Model: $model",
        ).joinToString("\n")
    }
}
