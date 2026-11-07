package pia.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class FakeHttpPort(
    var postReply: String = "",
    var getReply: String = "",
) : HttpPort {
    var postCalls = 0
    var getCalls = 0
    var lastPostUrl: String? = null
    var lastGetUrl: String? = null

    override fun postForm(url: String, fields: Map<String, String>): String {
        postCalls += 1
        lastPostUrl = url
        return postReply
    }

    override fun get(url: String, headers: Map<String, String>): String {
        getCalls += 1
        lastGetUrl = url
        return getReply
    }
}

private class FakeClock(var fixedNow: Long) : Clock {
    override fun nowEpochSec(): Long = fixedNow
}

private class FakeTokenStore(
    var storedUsername: String? = null,
    var storedPassword: String? = null,
    var storedToken: AuthToken? = null,
) : TokenStore {
    var savedToken: AuthToken? = null

    override fun loadUsername(): String? = storedUsername

    override fun loadPassword(): String? = storedPassword

    override fun saveCredentials(username: String, password: String) {
        storedUsername = username
        storedPassword = password
    }

    override fun loadToken(): AuthToken? = storedToken

    override fun saveToken(token: AuthToken) {
        storedToken = token
        savedToken = token
    }

    override fun clearToken() {
        storedToken = null
    }
}

class PiaClientTest {
    @Test
    fun `login parses token payload fixture`() {
        val fixedNow = 1_700_000L
        val fakeHttp = FakeHttpPort(postReply = """{"status":"OK","token":"dummy-token-value"}""")
        val fakeClock = FakeClock(fixedNow)
        val client = PiaClient(fakeHttp, fakeClock)

        val token = client.login("dummy-user", "dummy-pass")

        assertEquals("dummy-token-value", token.value)
        assertEquals(fixedNow, token.issuedAtEpochSec)
        assertEquals(PiaClient.TOKEN_URL, fakeHttp.lastPostUrl)
        assertEquals(1, fakeHttp.postCalls)
    }

    @Test
    fun `login rejects empty token payload`() {
        val fakeHttp = FakeHttpPort(postReply = """{"status":"OK","token":""}""")
        val fakeClock = FakeClock(1_700_000L)
        val client = PiaClient(fakeHttp, fakeClock)

        assertFailsWith<PiaAuthError> {
            client.login("dummy-user", "dummy-pass")
        }
    }

    @Test
    fun `ensureValidToken returns fresh cached token without http`() {
        val fixedNow = 1_700_000L
        val cachedToken = AuthToken(value = "cached-token", issuedAtEpochSec = fixedNow)
        val fakeHttp = FakeHttpPort(postReply = """{"status":"OK","token":"must-not-be-used"}""")
        val fakeClock = FakeClock(fixedNow + 3600L)
        val fakeStore = FakeTokenStore(storedToken = cachedToken)
        val client = PiaClient(fakeHttp, fakeClock)

        val result = client.ensureValidToken(fakeStore)

        assertEquals("cached-token", result.value)
        assertEquals(0, fakeHttp.postCalls)
    }

    @Test
    fun `ensureValidToken refreshes thirteen hour old token through stored credentials`() {
        val issuedAt = 1_700_000L
        val fixedNow = issuedAt + 13 * 3600L
        val staleToken = AuthToken(value = "stale-token", issuedAtEpochSec = issuedAt)
        val fakeHttp = FakeHttpPort(postReply = """{"status":"OK","token":"fresh-token"}""")
        val fakeClock = FakeClock(fixedNow)
        val fakeStore = FakeTokenStore(
            storedUsername = "dummy-user",
            storedPassword = "dummy-pass",
            storedToken = staleToken,
        )
        val client = PiaClient(fakeHttp, fakeClock)

        val result = client.ensureValidToken(fakeStore)

        assertEquals("fresh-token", result.value)
        assertEquals(fixedNow, result.issuedAtEpochSec)
        assertEquals(1, fakeHttp.postCalls)
        assertEquals("fresh-token", fakeStore.savedToken?.value)
    }

    @Test
    fun `ensureValidToken throws when refresh is due but credentials are missing`() {
        val issuedAt = 1_700_000L
        val fixedNow = issuedAt + 13 * 3600L
        val staleToken = AuthToken(value = "stale-token", issuedAtEpochSec = issuedAt)
        val fakeHttp = FakeHttpPort(postReply = """{"status":"OK","token":"must-not-be-used"}""")
        val fakeClock = FakeClock(fixedNow)
        val fakeStore = FakeTokenStore(storedToken = staleToken)
        val client = PiaClient(fakeHttp, fakeClock)

        assertFailsWith<PiaAuthError> {
            client.ensureValidToken(fakeStore)
        }
        assertEquals(0, fakeHttp.postCalls)
    }

    @Test
    fun `addKey parses ok payload into lease and resolvers`() {
        val hostname = "uk-london.privacy.network"
        val fakeHttp = FakeHttpPort(
            getReply = """{"status":"OK","peer_ip":"10.4.0.2","server_key":"dummy-server-key=","server_port":1337,"dns_servers":["10.0.0.243","10.0.0.244"]}""",
        )
        val fakeClock = FakeClock(1_700_000L)
        val client = PiaClient(fakeHttp, fakeClock)

        val result = client.addKey(hostname, AuthToken("dummy-token-value", 1_700_000L), "dummy-pubkey")

        assertEquals("10.4.0.2", result.lease.peerIp)
        assertEquals("dummy-server-key=", result.lease.serverPublicKey)
        assertEquals("$hostname:1337", result.lease.endpoint)
        assertEquals(listOf("10.0.0.243", "10.0.0.244"), result.dnsServers)
        assertTrue(fakeHttp.lastGetUrl?.startsWith("https://$hostname:1337/addKey") == true)
        assertEquals(1, fakeHttp.getCalls)
    }

    @Test
    fun `addKey throws on non ok status`() {
        val fakeHttp = FakeHttpPort(getReply = """{"status":"ERROR"}""")
        val fakeClock = FakeClock(1_700_000L)
        val client = PiaClient(fakeHttp, fakeClock)

        assertFailsWith<PiaApiError> {
            client.addKey("uk-london.privacy.network", AuthToken("dummy-token-value", 1_700_000L), "dummy-pubkey")
        }
    }

    @Test
    fun `addKey percent-encodes token and pubkey in query`() {
        val fakeHttp = FakeHttpPort(
            getReply = """{"status":"OK","peer_ip":"10.4.0.2","server_key":"k=","server_port":1337,"dns_servers":[]}""",
        )
        val client = PiaClient(fakeHttp, FakeClock(1_700_000L))

        client.addKey("uk-london.privacy.network", "tok+en/with=", "pub+key/with=")

        val url = fakeHttp.lastGetUrl ?: ""
        assertTrue(url.contains("pt=tok%2Ben%2Fwith%3D"), url)
        assertTrue(url.contains("pubkey=pub%2Bkey%2Fwith%3D"), url)
    }
}
