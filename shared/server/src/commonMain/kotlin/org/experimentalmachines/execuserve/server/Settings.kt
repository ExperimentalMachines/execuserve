package org.experimentalmachines.execuserve.server

/** Where the server listens. */
enum class BindMode {
    /** `127.0.0.1` and `::1`: apps on this device only. */
    LOOPBACK,

    /** Every interface, dual-stack. Plain HTTP: see ARCHITECTURE.md, "Exposure". */
    NETWORK,
}

data class ServerSettings(
    val port: Int = 8080,
    val bind: BindMode = BindMode.LOOPBACK,
    /** Let loopback callers in without a key. Off by default: any app can reach loopback. */
    val openLoopback: Boolean = false,
    /** Browser origins allowed by CORS; empty turns CORS off. */
    val corsOrigins: List<String> = emptyList(),
    /** Extra names the `Host` header may carry, such as a Tailscale MagicDNS name. */
    val extraHosts: Set<String> = emptySet(),
    /** Skip the `Host` check entirely, for a reverse proxy that rewrites it. */
    val anyHost: Boolean = false,
    val maxBodyBytes: Long = 4L * 1024 * 1024,
    val idleTimeoutSeconds: Int = 45,
)

/** One issued key. [id] is stable and is the client's identity for limits and logs. */
data class ApiKey(val id: String, val name: String, val secret: String)

/** Looks up the key a request presented. */
fun interface KeyVerifier {
    fun verify(presented: String): ApiKey?
}

/** A fixed set of keys, compared in constant time. */
class StaticKeys(private val keys: List<ApiKey>) : KeyVerifier {
    override fun verify(presented: String): ApiKey? {
        var found: ApiKey? = null
        // Every key is compared in full whatever matches, so timing says nothing about which.
        for (key in keys) if (constantTimeEquals(key.secret, presented)) found = key
        return found
    }
}

internal fun constantTimeEquals(a: String, b: String): Boolean {
    var diff = a.length xor b.length
    val length = maxOf(a.length, b.length)
    for (i in 0 until length) {
        val x = if (i < a.length) a[i].code else 0
        val y = if (i < b.length) b[i].code else 0
        diff = diff or (x xor y)
    }
    return diff == 0
}
