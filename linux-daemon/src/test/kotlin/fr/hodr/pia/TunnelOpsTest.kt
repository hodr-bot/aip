package fr.hodr.pia

import pia.core.WireGuardLease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TunnelOpsTest {
    private val lease = WireGuardLease(
        peerIp = "10.4.128.2",
        serverPublicKey = "PUBKEY",
        endpoint = "154.16.20.1:1337",
    )

    @Test
    fun bringUpOrdersKillSwitchFirstHandshakeLast() {
        val fake = FakeRunner()
        val ops = TunnelOps(fake)
        assertTrue(ops.bringUp(lease, "PRIVKEY", listOf("0.0.0.0/0", "::/0")))
        val argv = fake.commands.map { it.first }
        // Teardown best-effort comes first, then link creation.
        assertEquals(listOf("ip", "link", "add", "pia0", "type", "wireguard"), argv[3])
        // Private key on stdin, never in argv.
        val keyCmd = fake.commands.first { "private-key" in it.first }
        assertFalse(keyCmd.first.any { "PRIVKEY" in it })
        assertEquals("PRIVKEY\n", keyCmd.second)
        // Allowed IPs and endpoint carried to wg, routes after link up.
        val peerCmd = fake.commands.first { "peer" in it.first }
        assertTrue("154.16.20.1:1337" in peerCmd.first)
        assertTrue("0.0.0.0/0,::/0" in peerCmd.first)
        val linkUp = argv.indexOfFirst { it.take(3) == listOf("ip", "link", "set") }
        val routeAdd = argv.indexOfFirst { it.take(3) == listOf("ip", "route", "add") }
        assertTrue(linkUp < routeAdd)
        // fwmark exception so handshake UDP keeps using the main table.
        assertTrue(argv.any { it.contains("fwmark") && it.contains("51820") })
    }

    @Test
    fun bringUpFailsWhenWgRefuses() {
        val fake = FakeRunner()
        fake.default = CommandRunner.Result(1, "", "RTNETLINK answers: Operation not supported")
        val ops = TunnelOps(fake)
        assertFalse(ops.bringUp(lease, "PRIVKEY", listOf("0.0.0.0/0")))
    }

    @Test
    fun bringDownIsRuleRoutesLink() {
        val fake = FakeRunner()
        TunnelOps(fake).bringDown()
        assertEquals(
            listOf(
                listOf("ip", "rule", "del", "not", "fwmark", "51820", "table", "100", "priority", "500"),
                listOf("ip", "route", "flush", "table", "100"),
                listOf("ip", "link", "del", "pia0"),
            ),
            fake.commands.map { it.first },
        )
    }

    @Test
    fun latestHandshakeParsesMaxEpoch() {
        val fake = FakeRunner(
            mapOf("wg" to CommandRunner.Result(0, "PUBKEY\t1700000000\nOTHER\t0\n", "")),
        )
        assertEquals(1700000000L, TunnelOps(fake).latestHandshake())
    }
}
