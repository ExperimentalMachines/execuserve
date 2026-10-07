package org.experimentalmachines.execuserve.host

/**
 * Stable model routes on the existing listener; credentials never belong in these URLs
 * ([signIn] alone carries one, after the `#`).
 * [Endpoint.url] is an API base ending in `/v1`; callers may also supply the server root.
 */
object ModelEndpoints {
    fun browser(baseUrl: String, modelId: String): String = "${baseUrl.trimEnd('/').removeSuffix("/v1")}/models/${segment(modelId)}/"

    fun api(baseUrl: String, modelId: String): String = browser(baseUrl, modelId) + "v1"

    /** The browser chat at the server root: what a person opens on a laptop. */
    fun page(baseUrl: String): String = baseUrl.trimEnd('/').removeSuffix("/v1") + "/"

    /**
     * The browser chat with [key] after `#`: the one URL here that carries a credential, for the
     * sign-in QR code. A fragment never leaves the browser (not in the request, not in a
     * Referer), and the page removes it from the address bar as soon as it reads it.
     */
    fun signIn(baseUrl: String, key: String): String = page(baseUrl) + "#key=" + segment(key)

    // Encode the complete UTF-8 ID as one segment, including slashes in imported IDs.
    private fun segment(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val n = byte.toInt() and BYTE_MASK
            val c = n.toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~") {
                append(c)
            } else {
                append('%')
                append(HEX[n ushr NIBBLE_BITS])
                append(HEX[n and NIBBLE_MASK])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
    private const val BYTE_MASK = 0xff
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0f
}
