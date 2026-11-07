package pia.android

import pia.core.Clock
import pia.core.HttpPort
import pia.core.PiaAuthError
import pia.core.TrafficVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class FakeHttpPort(
    var postReply: String = """{"status":"OK","token":"dummy-token-value"}""",
    var getReply: String = """{"status":"OK","peer_ip":"10.4.0.2","server_key":"dummy-server-key=","server_port":1337,"dns_servers":["10.0.0.243"]}""",
) : HttpPort {
    override fun postForm(url: String, fields: Map<String, String>): String = postReply
    override fun get(url: String, headers: Map<String, String>): String = getReply
}

private class FakeClock(private val fixedNow: Long = 1_700_000L) : Clock {
    override fun nowEpochSec(): Long = fixedNow
}

private class FakeBackend : VpnBackend {
    var installedConf: String? = null
    var blocked: Boolean? = null
    override fun installConf(conf: String) {
        installedConf = conf
    }
    override fun setBlocked(blocked: Boolean) {
        this.blocked = blocked
    }
}

private class FakeVault(
    override val username: String? = "dummy-user",
    override val password: String? = "dummy-pass",
) : CredentialVault

class PiaVpnAdapterTest {
    @Test
    fun `connect installs conf unblocks and returns plan`() {
        val backend = FakeBackend()
        val adapter = PiaVpnAdapter(FakeHttpPort(), FakeClock(), backend, FakeVault())
        val hostname = "uk-london.privacy.network"

        val plan = adapter.connect(publicKey = "dummy-pubkey", hostname = hostname)

        assertEquals("$hostname:1337", plan.endpoint)
        assertEquals(TrafficVerdict.ALLOW_ALL, plan.verdict)
        assertTrue(plan.conf.contains("Endpoint = $hostname:1337"))
        assertEquals(plan.conf, backend.installedConf)
        assertEquals(false, backend.blocked)
    }

    @Test
    fun `connect without credentials blocks then rethrows`() {
        val backend = FakeBackend()
        val adapter = PiaVpnAdapter(FakeHttpPort(), FakeClock(), backend, FakeVault(null, null))

        assertFailsWith<PiaAuthError> {
            adapter.connect(publicKey = "dummy-pubkey", hostname = "uk-london.privacy.network")
        }
        assertEquals(true, backend.blocked)
    }

    @Test
    fun `disconnect blocks`() {
        val backend = FakeBackend()
        val adapter = PiaVpnAdapter(FakeHttpPort(), FakeClock(), backend, FakeVault())

        adapter.disconnect()

        assertEquals(true, backend.blocked)
    }

    @Test
    fun `token store roundtrip keeps behaviour in memory`() {
        val store = InMemoryTokenStore()
        store.saveCredentials("dummy-user", "dummy-pass")
        assertEquals("dummy-user", store.loadUsername())
        assertEquals("dummy-pass", store.loadPassword())
    }
}
