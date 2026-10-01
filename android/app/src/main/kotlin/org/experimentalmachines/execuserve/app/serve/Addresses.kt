package org.experimentalmachines.execuserve.app.serve

import org.experimentalmachines.execuserve.host.Endpoint
import org.experimentalmachines.execuserve.host.NetworkKind
import org.experimentalmachines.execuserve.server.BindMode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * This device's addresses: to show where the server can be reached, and to tell the `Host`
 * check which names are this device's own.
 */
object Addresses {

    fun endpoints(port: Int, bind: BindMode): List<Endpoint> {
        val local = Endpoint("http://127.0.0.1:$port/v1", NetworkKind.THIS_DEVICE)
        if (bind == BindMode.LOOPBACK) return listOf(local)
        val remote = interfaces().flatMap { nic ->
            nic.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .mapNotNull { address -> kindOf(nic, address)?.let { Endpoint("http://${address.hostAddress}:$port/v1", it) } }
        }
        // Enum order is preference order: Wi-Fi first, the loopback address last.
        return remote.sortedBy { it.network.ordinal } + local
    }

    /** Every address and name of this device, lowercased, as a `Host` header would carry it. */
    fun hosts(): Set<String> {
        val names = mutableSetOf<String>()
        interfaces().forEach { nic ->
            nic.inetAddresses.toList().forEach { address ->
                names += address.hostAddress.orEmpty().substringBefore('%').lowercase()
                if (address is Inet6Address) names += address.hostAddress.orEmpty().lowercase()
            }
        }
        runCatching { InetAddress.getLocalHost().hostName.lowercase() }.getOrNull()?.let {
            names += it
            names += "$it.local"
        }
        return names
    }

    private fun interfaces(): List<NetworkInterface> = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        .filter { runCatching { it.isUp }.getOrDefault(false) }

    /** Cellular data is left out: carriers put phones behind NAT, so nothing can connect in. */
    private fun kindOf(nic: NetworkInterface, address: Inet4Address): NetworkKind? {
        val name = nic.name.lowercase()
        if (isTailscale(address)) return NetworkKind.TAILSCALE
        return when {
            name.startsWith("wlan") -> NetworkKind.WIFI
            name.startsWith("tun") || name.startsWith("tailscale") || name.startsWith("wg") -> NetworkKind.VPN
            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> NetworkKind.HOTSPOT
            name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> NetworkKind.USB
            name.startsWith("eth") -> NetworkKind.ETHERNET
            else -> null
        }
    }

    /** Tailscale hands out the shared address space 100.64.0.0/10 (RFC 6598). */
    private fun isTailscale(address: Inet4Address): Boolean {
        val bytes = address.address
        val first = bytes[0].toInt() and BYTE_MASK
        val second = bytes[1].toInt() and BYTE_MASK
        return first == CGNAT_FIRST_OCTET && second in CGNAT_SECOND_OCTETS
    }

    private const val BYTE_MASK = 0xff
    private const val CGNAT_FIRST_OCTET = 100
    private val CGNAT_SECOND_OCTETS = 64..127
}
