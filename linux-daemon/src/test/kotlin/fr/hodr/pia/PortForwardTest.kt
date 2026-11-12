package fr.hodr.pia

import pia.core.AuthToken
import pia.core.Clock
import pia.core.HttpPort
import pia.core.PiaApiError
import pia.core.PiaClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class PfFakeHttp(
    var signatureReply: String = PfFakeHttp.SIG_OK,
    var bindReply: String = """{"status":"OK"}""",
) : HttpPort {
    var getCount = 0
    var bindCount = 0
    var lastBindUrl = ""
    var lastBindJson = ""

    override fun postForm(url: String, fields: Map<String, String>): String =
        """{"status":"OK","token":"TOK123"}"""

    override fun get(url: String, headers: Map<String, String>): String {
        getCount++
        require("getSignature" in url) { "unexpected GET $url" }
        require("pt=TOK123" in url) { "token missing from $url" }
        return signatureReply
    }

    override fun postJson(url: String, json: String): String {
        bindCount++
        lastBindUrl = url
        lastBindJson = json
        return bindReply
    }

    companion object {
        const val SIG_OK =
            """{"status":"OK","signature":{"port":41234,"expires_at":"2030-01-01T00:00:00Z","payload":"PAY1","signature":"SIG1"}}"""
    }
}

private class PfFakeClock(var now: Long = 1_700_000_000L) : Clock {
    override fun nowEpochSec(): Long = now
}

class PortForwardTest {
    private fun pf(
        http: PfFakeHttp = PfFakeHttp(),
        clock: PfFakeClock = PfFakeClock(),
        onPort: (Int, Boolean) -> Unit = { _, _ -> },
    ) = PortForward(PiaClient(http, clock), clock, onPort)

    @Test
    fun refreshBindsSignatureOnGatewayPort19999() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val http = PfFakeHttp()
        val p = pf(http, onPort = { port, changed -> events.add(port to changed) })
        val port = p.refresh("host.example", "10.9.0.1", "TOK123")
        assertEquals(41234, port)
        assertEquals(41234, p.port)
        assertEquals("https://10.9.0.1:19999/bindPort", http.lastBindUrl)
        assertTrue(http.lastBindJson.contains("\"payload\":\"PAY1\""))
        assertTrue(http.lastBindJson.contains("\"signature\":\"SIG1\""))
        assertEquals(listOf(41234 to false), events)
    }

    @Test
    fun freshBindSkipsSignatureButRebinds() {
        val http = PfFakeHttp()
        val clock = PfFakeClock()
        var calls = 0
        val p = pf(http, clock) { _, _ -> calls++ }
        p.refresh("host.example", "10.9.0.1", "TOK123")
        p.refresh("host.example", "10.9.0.1", "TOK123")
        assertEquals(1, http.getCount)
        assertEquals(2, http.bindCount)
        assertEquals(1, calls)
    }

    @Test
    fun staleSignatureRefetchesAndReportsChange() {
        val http = PfFakeHttp()
        val clock = PfFakeClock()
        val events = mutableListOf<Pair<Int, Boolean>>()
        val p = pf(http, clock) { port, changed -> events.add(port to changed) }
        p.refresh("host.example", "10.9.0.1", "TOK123")
        clock.now += PortForward.REFRESH_SEC + 1
        http.signatureReply = PfFakeHttp.SIG_OK.replace("41234", "51234")
        val port = p.refresh("host.example", "10.9.0.1", "TOK123")
        assertEquals(51234, port)
        assertEquals(listOf(41234 to false, 51234 to true), events)
    }

    @Test
    fun rejectedSignatureThrowsApiError() {
        val http = PfFakeHttp(signatureReply = """{"status":"ERROR"}""")
        val p = pf(http)
        assertFailsWith<PiaApiError> {
            p.refresh("host.example", "10.9.0.1", "TOK123")
        }
    }

    @Test
    fun resetForcesRefetch() {
        val http = PfFakeHttp()
        val p = pf(http)
        p.refresh("host.example", "10.9.0.1", "TOK123")
        p.reset()
        assertEquals(null, p.port)
        p.refresh("host.example", "10.9.0.1", "TOK123")
        assertEquals(2, http.getCount)
    }

    @Test
    fun authTokenOverloadWorks() {
        val clock = PfFakeClock()
        val http = PfFakeHttp()
        val client = PiaClient(http, clock)
        val sig = client.getSignature("host.example", AuthToken("TOK123", 9_999_999_999L))
        assertEquals(41234, sig.port)
        assertEquals("PAY1", sig.payload)
        assertEquals("SIG1", sig.signature)
    }

}
