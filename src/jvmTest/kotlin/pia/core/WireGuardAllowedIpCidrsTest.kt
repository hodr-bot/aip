package pia.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireGuardAllowedIpCidrsTest {
    @Test
    fun `full tunnel exact list when false`() {
        assertEquals(
            listOf("0.0.0.0/0", "::/0"),
            WireGuard.allowedIpCidrs(false),
        )
    }

    @Test
    fun `lan allowed list covers public only plus ipv6`() {
        val cidrs = WireGuard.allowedIpCidrs(true)
        assertTrue(cidrs.contains("::/0"), "IPv6 ::/0 must stay inside the tunnel")

        // RFC1918 must NOT be covered.
        assertFalse(isCovered(cidrs, "10.1.2.3"))
        assertFalse(isCovered(cidrs, "172.16.5.4"))
        assertFalse(isCovered(cidrs, "172.31.255.1"))
        assertFalse(isCovered(cidrs, "192.168.1.1"))

        // Public IPs must be covered.
        assertTrue(isCovered(cidrs, "8.8.8.8"))
        assertTrue(isCovered(cidrs, "1.1.1.1"))
        assertTrue(isCovered(cidrs, "172.15.0.1"))
        assertTrue(isCovered(cidrs, "172.32.0.1"))
    }

    private fun isCovered(cidrs: List<String>, ip: String): Boolean {
        val addr = ipv4ToLong(ip)
        for (cidr in cidrs) {
            if (cidr.contains(':')) continue // IPv6 entry, IPv4 check only.
            val slash = cidr.indexOf('/')
            val base = ipv4ToLong(cidr.substring(0, slash))
            val prefix = cidr.substring(slash + 1).toInt()
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            if ((base and mask) == (addr and mask)) return true
        }
        return false
    }

    private fun ipv4ToLong(ip: String): Long {
        var acc = 0L
        for (part in ip.split('.')) {
            acc = (acc shl 8) or part.toLong()
        }
        return acc
    }
}
