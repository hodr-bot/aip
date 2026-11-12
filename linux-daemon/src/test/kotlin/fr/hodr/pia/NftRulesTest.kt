package fr.hodr.pia

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NftRulesTest {
    private val base = NftParams(
        endpointIp = "154.16.20.1",
        endpointPort = 1337,
        dnsIps = listOf("10.0.0.243"),
        allowLan = false,
    )

    @Test
    fun failClosedByDefault() {
        val rules = NftRules.build(base)
        assertTrue("policy drop" in rules)
        assertTrue("hook forward" in rules)
        assertTrue("oifname \"pia0\" accept" in rules)
    }

    @Test
    fun handshakeEndpointIsTheOnlyHole() {
        val rules = NftRules.build(base)
        assertTrue("ip daddr 154.16.20.1 udp dport 1337 accept" in rules)
        assertFalse("accept" in rules.substringAfter("chain forward"))
    }

    @Test
    fun dnsOffTunnelIsDropped() {
        val rules = NftRules.build(base)
        assertTrue("oifname != \"pia0\" udp dport { 53, 853 } drop" in rules)
        assertTrue("oifname != \"pia0\" tcp dport { 53, 853 } drop" in rules)
    }

    @Test
    fun ipv6OffTunnelIsDropped() {
        val rules = NftRules.build(base)
        assertTrue("meta nfproto ipv6 oifname != \"pia0\" drop" in rules)
    }

    @Test
    fun lanBypassOnlyWhenEnabled() {
        val off = NftRules.build(base)
        assertFalse("192.168.0.0/16" in off)
        val on = NftRules.build(base.copy(allowLan = true, extraLanNets = listOf("10.9.0.0/16")))
        assertTrue("ip daddr { 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 10.9.0.0/16 } accept" in on)
    }
}
