package org.experimentalmachines.execuserve.host

/**
 * Stable model routes on the existing listener; credentials never belong in these URLs.
 * [Endpoint.url] is an API base ending in `/v1`; callers may also supply the server root.
 */
object ModelEndpoints {
    fun browser(baseUrl: String, modelId: String): String =
        "${baseUrl.trimEnd('/').removeSuffix("/v1")}/models/${segment(modelId)}/"

    fun api(baseUrl: String, modelId: String): String = browser(baseUrl, modelId) + "v1"

    // Encode the complete UTF-8 ID as one segment, including slashes in imported IDs.
    private fun segment(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val n = byte.toInt() and 0xff
            val c = n.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~") append(c)
            else {
                append('%')
                append(HEX[n ushr 4])
                append(HEX[n and 15])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
