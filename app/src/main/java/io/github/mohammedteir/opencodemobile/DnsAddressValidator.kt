package io.github.mohammedteir.opencodemobile

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Validates numeric DNS server addresses without performing hostname resolution.
 * Private and carrier-grade NAT addresses remain valid for VPN and local DNS.
 */
internal object DnsAddressValidator {
    fun isUsable(address: String): Boolean {
        if (address.isBlank() || address != address.trim()) return false
        return try {
            when {
                address.contains(':') -> isUsableIpv6(address)
                IPV4_PATTERN.matches(address) -> isUsableIpv4(address)
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isUsableIpv4(address: String): Boolean {
        val octets = address.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val (a, b, c, _) = octets

        // Reject non-routable, documentation, benchmarking and reserved blocks.
        // RFC1918 and 100.64.0.0/10 remain valid for private/VPN DNS endpoints.
        if (a == 0 || a == 127 || (a == 169 && b == 254)) return false
        if (a == 192 && b == 0 && c == 0) return false
        if (a == 192 && b == 0 && c == 2) return false
        if (a == 192 && b == 88 && c == 99) return false
        if (a == 198 && (b == 18 || b == 19)) return false
        if (a == 198 && b == 51 && c == 100) return false
        if (a == 203 && b == 0 && c == 113) return false
        if (a >= 224) return false
        return true
    }

    private fun isUsableIpv6(address: String): Boolean {
        // Numeric syntax only: InetAddress must never receive a hostname here.
        if (!IPV6_PATTERN.matches(address)) return false
        val parsed = InetAddress.getByName(address)
        val bytes = parsed.address
        if (parsed !is Inet4Address && bytes.size == 16) {
            val mapped = bytes.sliceArray(0 until 10).all { it == 0.toByte() } &&
                bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
            if (mapped) {
                val ipv4 = bytes.sliceArray(12 until 16)
                    .joinToString(".") { (it.toInt() and 0xff).toString() }
                return isUsableIpv4(ipv4)
            }

            if (parsed.isAnyLocalAddress || parsed.isLoopbackAddress ||
                parsed.isLinkLocalAddress || parsed.isMulticastAddress
            ) return false

            // 2001:db8::/32 is reserved for documentation.
            if ((bytes[0].toInt() and 0xff) == 0x20 &&
                (bytes[1].toInt() and 0xff) == 0x01 &&
                (bytes[2].toInt() and 0xff) == 0x0d &&
                (bytes[3].toInt() and 0xff) == 0xb8
            ) return false

            // Reject deprecated IPv4-compatible ::/96 space.
            if (bytes.sliceArray(0 until 12).all { it == 0.toByte() }) return false
            return true
        }
        return isUsableIpv4(parsed.hostAddress)
    }

    private val IPV4_PATTERN = Regex("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")
    private val IPV6_PATTERN = Regex("[0-9a-fA-F:.]+")
}
