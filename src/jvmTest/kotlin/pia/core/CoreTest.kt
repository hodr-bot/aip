package pia.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun `fresh token needs no refresh`() {
        val token = AuthToken("tok", issuedAtEpochSec = 1_000_000)
        assertFalse(token.shouldRefresh(nowEpochSec = 1_000_000 + 3600))
    }

    @Test
    fun `token refreshes after 12h`() {
        val token = AuthToken("tok", issuedAtEpochSec = 1_000_000)
        assertTrue(token.shouldRefresh(nowEpochSec = 1_000_000 + 12 * 3600 + 1))
    }

    @Test
    fun `token refreshes before hitting 24h expiry`() {
        val token = AuthToken("tok", issuedAtEpochSec = 1_000_000)
        // 20h old: only 4h left, must already refresh
        assertTrue(token.shouldRefresh(nowEpochSec = 1_000_000 + 20 * 3600))
    }

    @Test
    fun `region picker takes lowest latency`() {
        val regions = listOf(
            RegionEndpoint("a", "a.pia", latencyMs = 90),
            RegionEndpoint("b", "b.pia", latencyMs = 20),
            RegionEndpoint("c", "c.pia", latencyMs = null),
        )
        assertEquals("b", RegionPicker.pickBest(regions)?.id)
    }

    @Test
    fun `kill switch fails closed`() {
        assertEquals(
            TrafficVerdict.BLOCK_ALL,
            KillSwitch.decide(ConnectionState.DISCONNECTED, killSwitchEnabled = true),
        )
        assertEquals(
            TrafficVerdict.ALLOW_ALL,
            KillSwitch.decide(ConnectionState.CONNECTED, killSwitchEnabled = true),
        )
        assertEquals(
            TrafficVerdict.ALLOW_LAN_ONLY,
            KillSwitch.decide(ConnectionState.DISCONNECTED, killSwitchEnabled = false),
        )
    }

    @Test
    fun `conf keeps lan bypass explicit`() {
        val lease = WireGuardLease("10.4.0.2", "srvkey=", "127.0.0.1:1337")
        val conf = WireGuard.buildConf("privkey=", "10.4.0.2/32", "10.0.0.243", lease, excludeLan = true)
        assertTrue(conf.contains("AllowedIPs = 0.0.0.0/1, 128.0.0.0/1"))
        assertTrue(conf.contains("PersistentKeepalive = 25"))
    }
}
