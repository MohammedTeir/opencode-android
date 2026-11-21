package io.github.mohammedteir.opencodemobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsAddressValidatorTest {
    @Test
    fun rejectsDocumentationAndReservedIpv4Ranges() {
        listOf(
            "0.0.0.0",
            "127.0.0.1",
            "169.254.1.1",
            "192.0.2.3",
            "198.51.100.10",
            "203.0.113.53",
            "198.18.0.1",
            "224.0.0.1",
            "240.0.0.1"
        ).forEach { assertFalse("$it must be rejected", DnsAddressValidator.isUsable(it)) }
    }

    @Test
    fun allowsPublicPrivateAndVpnDnsAddresses() {
        listOf(
            "1.1.1.1",
            "8.8.8.8",
            "10.0.0.53",
            "172.16.0.1",
            "192.168.1.1",
            "100.64.0.1",
            "fd00::53",
            "2001:4860:4860::8888"
        ).forEach { assertTrue("$it must be allowed", DnsAddressValidator.isUsable(it)) }
    }

    @Test
    fun rejectsInvalidAndSpecialIpv6Ranges() {
        listOf(
            "::",
            "::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::53",
            "::ffff:192.0.2.3",
            "not-an-ipv6-address"
        ).forEach { assertFalse("$it must be rejected", DnsAddressValidator.isUsable(it)) }
    }

    @Test
    fun rejectsMalformedIpv4AndHostnames() {
        listOf("256.1.1.1", "1.2.3", "dns.google", " 1.1.1.1", "1.1.1.1 ")
            .forEach { assertFalse("$it must be rejected", DnsAddressValidator.isUsable(it)) }
    }
}
