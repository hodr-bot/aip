package fr.hodr.pia

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TunnelManagerTest {
    private fun manager(
        text: String = "[tunnel.a]\nserver = testland\n[tunnel.b]\nserver = testland\nauto-connect = false",
        runner: FakeRunner = FakeRunner(),
    ): Pair<TunnelManager, FakeRunner> {
        val dir = Files.createTempDirectory("piad-mgr")
        val m = TunnelManager(TunnelConfigs.parse(text), dir, runner = runner)
        return m to runner
    }

    @Test
    fun unknownTunnelFails() {
        val (m, _) = manager()
        assertFalse(m.up("nope"))
        assertFalse(m.down("nope"))
    }

    @Test
    fun downAppliesMergedRulesAndReportsStatus() {
        val (m, runner) = manager()
        assertTrue(m.down("a"))
        assertTrue(runner.commands.any { it.first.take(2) == listOf("nft", "-f") })
        val line = m.statusLines().first { it.startsWith("tunnel=a ") }
        assertTrue("status=DOWN" in line, line)
    }

    @Test
    fun autoConnectFalseStaysDownWithoutDialing() {
        val (m, runner) = manager()
        m.restore(emptyMap())
        val b = m.tunnels.getValue("b")
        assertEquals(DaemonEngine.Status.DOWN, b.engine.status)
        assertFalse(runner.commands.any { it.first.take(2) == listOf("wg", "set") })
    }

    @Test
    fun mergedRulesetHoldsBothTunnels() {
        val rules = NftRules.buildMulti(
            listOf(
                NftParams("1.2.3.4", 1337, emptyList(), false, iface = "pia-a"),
                NftParams("5.6.7.8", 1337, listOf("10.0.0.243"), true, iface = "pia-b"),
            ),
        )
        assertTrue("iifname { \"pia-a\", \"pia-b\" } accept" in rules, rules)
        assertTrue("ip daddr 1.2.3.4 udp dport 1337 accept" in rules, rules)
        assertTrue("ip daddr 5.6.7.8 udp dport 1337 accept" in rules, rules)
        assertTrue("oifname { \"pia-a\", \"pia-b\" } accept" in rules, rules)
    }

    @Test
    fun emptyRulesetIsFullyClosed() {
        val rules = NftRules.buildMulti(emptyList())
        assertTrue("policy drop" in rules, rules)
        assertFalse("iifname" in rules, rules)
        assertFalse("oifname" in rules, rules)
    }
}
