package org.experimentalmachines.execuserve.server

/**
 * The `Host` check that stops DNS rebinding.
 *
 * A web page in the phone's browser can point its own hostname at `127.0.0.1`, after which
 * its script is same-origin with this server and CORS is never consulted. The one thing it
 * cannot change is the `Host` header, which still carries the attacker's name. So a request
 * is served only when `Host` names this device: a loopback name, one of its addresses, or a
 * name the user added (a Tailscale MagicDNS name, say).
 */
internal object Hosts {
    private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

    /** An IPv6 address is eight groups of up to four hex digits. */
    private const val IPV6_GROUPS = 8
    private const val GROUP_DIGITS = 4
    private const val HEX = 16

    /**
     * The host part of a `Host` header in one canonical spelling: lowercased, without port,
     * brackets or a trailing root dot, and IPv6 literals compressed the way the device's own
     * addresses are, so `[2001:0db8:0:0:0:0:0:5]` and `2001:db8::5` compare equal.
     */
    fun hostOf(header: String): String {
        val value = header.trim().lowercase()
        val host = when {
            value.startsWith("[") -> value.substringAfter('[').substringBefore(']')
            // An unbracketed IPv6 literal has several colons and no port.
            value.count { it == ':' } > 1 -> value
            else -> value.substringBefore(':')
        }.removeSuffix(".")
        return canonical(host)
    }

    /** [host] with an IPv6 literal rewritten in RFC 5952's compressed form. */
    fun canonical(host: String): String {
        val bare = host.lowercase().substringBefore('%')
        if (':' !in bare) return bare
        val groups = expand(bare) ?: return bare
        // The longest run of zero groups (two or more) becomes "::".
        var bestStart = -1
        var bestLength = 1
        var i = 0
        while (i < groups.size) {
            if (groups[i] == 0) {
                var j = i
                while (j < groups.size && groups[j] == 0) j++
                if (j - i > bestLength) {
                    bestStart = i
                    bestLength = j - i
                }
                i = j
            } else {
                i++
            }
        }
        val hex = groups.map { it.toString(HEX) }
        if (bestStart < 0) return hex.joinToString(":")
        val head = hex.subList(0, bestStart).joinToString(":")
        val tail = hex.subList(bestStart + bestLength, hex.size).joinToString(":")
        return "$head::$tail"
    }

    private fun expand(address: String): List<Int>? {
        val halves = address.split("::")
        if (halves.size > 2) return null
        fun parse(part: String): List<Int>? = if (part.isEmpty()) {
            emptyList()
        } else {
            part.split(':').map { group ->
                if (group.length > GROUP_DIGITS) return null
                group.toIntOrNull(HEX) ?: return null
            }
        }
        val head = parse(halves[0]) ?: return null
        val tail = if (halves.size == 2) parse(halves[1]) ?: return null else emptyList()
        val missing = IPV6_GROUPS - head.size - tail.size
        if (halves.size == 1 && missing != 0) return null
        if (missing < 0) return null
        return head + List(if (halves.size == 2) missing else 0) { 0 } + tail
    }

    fun isLoopback(host: String): Boolean = host in LOOPBACK || host.startsWith("127.") || host.endsWith(".localhost")

    fun allowed(header: String?, deviceHosts: Set<String>, settings: ServerSettings): Boolean {
        if (settings.anyHost) return true
        val host = hostOf(header ?: return false)
        if (host.isEmpty()) return false
        if (isLoopback(host)) return true
        if (settings.bind == BindMode.LOOPBACK) return false
        return host in deviceHosts.map(::canonical) || host in settings.extraHosts.map { it.lowercase().removeSuffix(".") }
    }
}
