package pia.app

import pia.android.CredentialVault
import pia.android.PiaVpnAdapter
import pia.android.VpnBackend
import pia.core.Clock
import pia.core.HttpPort
import pia.core.ServerCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class FakeShellHttp : HttpPort {
    val serverListFixture = """
        |{"groups":{"wg":[{"name":"wireguard","ports":[1337]}]},"regions":[
        |{"id":"us_california","name":"US California","country":"US","auto_region":true,"dns":"us-california.privacy.network","port_forward":false,"geo":false,"offline":false,"servers":{"wg":[{"ip":"192.0.2.1","cn":"losangeles436"}]}},
        |{"id":"us_florida","name":"US Florida","country":"US","auto_region":true,"dns":"us-florida.privacy.network","port_forward":false,"geo":false,"offline":false,"servers":{"wg":[{"ip":"192.0.2.3","cn":"miami418"}]}}
        |]}
    """.trimMargin()

    private val tokenReply = """{"status":"OK","token":"dummy-token-value"}"""
    private val addKeyReply = """{"status":"OK","peer_ip":"10.4.0.2","server_key":"dummy-server-key=","server_port":1337,"dns_servers":["10.0.0.243"]}"""

    override fun postForm(url: String, fields: Map<String, String>): String = tokenReply

    override fun get(url: String, headers: Map<String, String>): String =
        if (url == ServerCatalog.SERVER_LIST_URL) serverListFixture else addKeyReply
}

private class FakeShellClock(private val fixedNow: Long = 1_700_000L) : Clock {
    override fun nowEpochSec(): Long = fixedNow
}

private class FakeShellBackend : VpnBackend {
    var installedConf: String? = null
    var blocked: Boolean? = null
    override fun installConf(conf: String) {
        installedConf = conf
    }
    override fun setBlocked(blocked: Boolean) {
        this.blocked = blocked
    }
}

private class FakeShellVault(
    override val username: String? = "dummy-user",
    override val password: String? = "dummy-pass",
) : CredentialVault

private fun buildViewModel(backend: FakeShellBackend = FakeShellBackend()): ServerListViewModel {
    val http = FakeShellHttp()
    val clock = FakeShellClock()
    val catalog = ServerCatalog(http)
    val adapter = PiaVpnAdapter(http, clock, backend, FakeShellVault())
    return ServerListViewModel(catalog, adapter, clock)
}

private val latencies = mapOf("us_california" to 90L, "us_florida" to 20L)

class AppShellTest {
    @Test
    fun `refresh auto-selects lowest latency`() {
        val viewModel = buildViewModel()

        val state = viewModel.refreshServers(latencies)

        assertEquals(2, state.servers.size)
        assertEquals("us_florida", state.selectedId)
    }

    @Test
    fun `selectServer switches the favourite`() {
        val viewModel = buildViewModel()
        viewModel.refreshServers(latencies)

        val state = viewModel.selectServer("us_california")

        assertEquals("us_california", state.selectedId)
    }

    @Test
    fun `connectSelected installs conf for the chosen endpoint`() {
        val backend = FakeShellBackend()
        val viewModel = buildViewModel(backend)
        viewModel.refreshServers(latencies)
        viewModel.selectServer("us_california")

        val plan = viewModel.connectSelected(publicKey = "dummy-pubkey")

        assertEquals("us-california.privacy.network:1337", plan.endpoint)
        assertTrue(plan.conf.contains("Endpoint = us-california.privacy.network:1337"))
        assertEquals(plan.conf, backend.installedConf)
    }

    @Test
    fun `unknown id throws`() {
        val viewModel = buildViewModel()
        viewModel.refreshServers(latencies)

        assertFailsWith<PiaSelectionError> {
            viewModel.selectServer("xx_nowhere")
        }
    }
}
