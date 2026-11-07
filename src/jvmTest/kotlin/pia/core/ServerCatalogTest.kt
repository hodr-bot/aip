package pia.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private class CatalogFakeHttp(var getReply: String = "") : HttpPort {
    var getCalls = 0
    var lastGetUrl: String? = null

    override fun postForm(url: String, fields: Map<String, String>): String =
        throw UnsupportedOperationException("catalog never posts")

    override fun get(url: String, headers: Map<String, String>): String {
        getCalls += 1
        lastGetUrl = url
        return getReply
    }
}

class ServerCatalogTest {
    private val fixture = """
        |{"groups":{"wg":[{"name":"wireguard","ports":[1337]}]},"regions":[
        |{"id":"us_california","name":"US California","country":"US","auto_region":true,"dns":"us-california.privacy.network","port_forward":false,"geo":false,"offline":false,"servers":{"wg":[{"ip":"192.0.2.1","cn":"losangeles436"}]}},
        |{"id":"nl_amsterdam","name":"NL Amsterdam","country":"NL","auto_region":false,"dns":"nl-amsterdam.privacy.network","port_forward":true,"geo":false,"offline":true,"servers":{"wg":[{"ip":"192.0.2.2","cn":"amsterdam404"}]}},
        |{"id":"us_florida","name":"US Florida","country":"US","auto_region":true,"dns":"us-florida.privacy.network","port_forward":false,"geo":false,"offline":false,"servers":{"wg":[{"ip":"192.0.2.3","cn":"miami418"}]}}
        |]}
        |
        |ZHVtbXktYmFja2Ryb3Atc2lnbmF0dXJlLWlnbm9yZWQ=
        |ZHVtbXktYmFja2Ryb3Atc2lnbmF0dXJlLWlnbm9yZWQtMg==
    """.trimMargin()

    @Test
    fun `fetchServers GETs the vpninfo endpoint and parses online regions`() {
        val fakeHttp = CatalogFakeHttp(getReply = fixture)
        val catalog = ServerCatalog(fakeHttp)

        val servers = catalog.fetchServers()

        assertEquals(ServerCatalog.SERVER_LIST_URL, fakeHttp.lastGetUrl)
        assertEquals(1, fakeHttp.getCalls)
        assertEquals(
            listOf(
                ServerEntry("us_california", "us-california.privacy.network"),
                ServerEntry("us_florida", "us-florida.privacy.network"),
            ),
            servers,
        )
    }

    @Test
    fun `fetchServers throws when the list holds no regions`() {
        val fakeHttp = CatalogFakeHttp(getReply = """{"groups":{},"regions":[]}""")
        val catalog = ServerCatalog(fakeHttp)

        assertFailsWith<PiaApiError> {
            catalog.fetchServers()
        }
    }

    @Test
    fun `pickById returns the matching entry and null when missing`() {
        val catalog = ServerCatalog(CatalogFakeHttp())
        val servers = listOf(
            ServerEntry("us_california", "us-california.privacy.network"),
            ServerEntry("us_florida", "us-florida.privacy.network"),
        )

        assertEquals(
            ServerEntry("us_florida", "us-florida.privacy.network"),
            catalog.pickById(servers, "us_florida"),
        )
        assertNull(catalog.pickById(servers, "xx_nowhere"))
    }

    @Test
    fun `pickBest takes the lowest known latency`() {
        val catalog = ServerCatalog(CatalogFakeHttp())
        val servers = listOf(
            ServerEntry("us_california", "us-california.privacy.network", latencyMs = 90),
            ServerEntry("us_florida", "us-florida.privacy.network", latencyMs = 20),
        )

        assertEquals("us_florida", catalog.pickBest(servers)?.id)
    }

    @Test
    fun `pickBest sorts unknown latency last`() {
        val catalog = ServerCatalog(CatalogFakeHttp())
        val servers = listOf(
            ServerEntry("ghost", "ghost.privacy.network", latencyMs = null),
            ServerEntry("us_florida", "us-florida.privacy.network", latencyMs = 300),
        )

        assertEquals("us_florida", catalog.pickBest(servers)?.id)
        assertNull(catalog.pickBest(emptyList()))
    }
}
