package org.experimentalmachines.execuserve.api

import kotlinx.serialization.json.Json

/**
 * How request bodies are read. Unknown keys are ignored because OpenAI adds fields to its
 * API every few months and every SDK sends the new ones by default; a server that rejected
 * unknown keys would break the day a client upgraded.
 */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
