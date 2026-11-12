package fr.hodr.pia

import java.nio.file.Files
import pia.core.HttpPort
import pia.core.PiaClient
import pia.core.ServerCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonEngineTest {
    private companion object {
        val addKeyBody = """
        {"status":"OK","peer_ip":"10.4.128.2","server_key":"SERVERPUBKEY",
         "server_port":1337,"server_ip":"154.16.20.1",
         "dns_servers":["10.0.0.243"]}
    """.trimIndent()

        val serversBody = """
        {"regions":[
          {"id":"testland","dns":"test.example.com","name":"Testland",
           "geo":false,"offline":false,"country":"TL"}
        ]}
    """.trimIndent()

        val sigBody = """{"status":"OK","signature":{"port":41234,"expires_at":"2030-01-01T00:00:00Z","payload":"PAY1","signature":"SIG1"}}"""
    }

    private class FakeHttp : HttpPort {
        var addKeyFails = false
        var lastBindJson = ""
        override fun postForm(url: String, fields: Map<String, String>): String =
            """{"status":"OK","token":"TOK123"}"""
        override fun postJson(url: String, json: String): String {
            lastBindJson = json
            return """{"status":"OK"}"""
        }
        override fun get(url: String, headers: Map<String, String>): String =
            when {
                "addKey" in url && addKeyFails -> """{"status":"ERROR"}"""
                "addKey" in url -> addKeyBody
                "getSignature" in url -> sigBody
                else -> serversBody
            }
    }

    private class FakeClock(var now: Long = 1_700_000_000L) : pia.core.Clock {
        override fun nowEpochSec(): Long = now
    }

    private fun engine(
        http: FakeHttp = FakeHttp(),
        runner: FakeRunner = FakeRunner(),
        dir: java.nio.file.Path = Files.createTempDirectory("piad-eng"),
        clock: FakeClock = FakeClock(),
    ): EngineHarness {
        val state = EngineState(dir.resolve("state.properties"))
        val store = FileTokenStore(dir.resolve("auth"))
        store.saveCredentials("user", "pass")
        val eng = DaemonEngine(
            client = PiaClient(http, clock),
            catalog = ServerCatalog(http),
            store = store,
            tunnel = TunnelOps(runner),
            state = state,
            clock = clock,
        )
        return EngineHarness(eng, runner, state, clock)
    }

    private data class EngineHarness(
        val eng: DaemonEngine,
        val runner: FakeRunner,
        val state: EngineState,
        val clock: FakeClock,
    )

    private fun FakeRunner.wgSets(): Int =
        commands.count { it.first.take(2) == listOf("wg", "set") }

    private fun engineWithPf(
        http: FakeHttp = FakeHttp(),
        clock: FakeClock = FakeClock(),
        hooks: MutableList<String> = mutableListOf(),
    ): EngineHarness {
        val dir = Files.createTempDirectory("piad-eng")
        val state = EngineState(dir.resolve("state.properties"))
        val store = FileTokenStore(dir.resolve("auth"))
        store.saveCredentials("user", "pass")
        val client = PiaClient(http, clock)
        val runner = FakeRunner()
        val pf = PortForward(client, clock) { port, _ -> hooks.add("hook:$port") }
        val eng = DaemonEngine(
            client = client,
            catalog = ServerCatalog(http),
            store = store,
            tunnel = TunnelOps(runner),
            state = state,
            clock = clock,
            pf = pf,
        )
        return EngineHarness(eng, runner, state, clock)
    }

    @Test
    fun portForwardBindsOnConnectAndReportsPort() {
        val hooks = mutableListOf<String>()
        val (eng, _, state) = engineWithPf(hooks = hooks)
        eng.setPortForward(true, null)
        assertTrue(eng.connect("testland"))
        assertEquals(DaemonEngine.Status.UP, eng.status)
        assertEquals(41234, eng.forwardedPort)
        assertEquals(41234, state.forwardedPort)
        assertEquals(listOf("hook:41234"), hooks)
    }

    @Test
    fun portForwardOffClearsPortWithoutRedial() {
        val (eng, runner, _) = engineWithPf()
        eng.setPortForward(true, null)
        assertTrue(eng.connect("testland"))
        val sets = runner.wgSets()
        eng.setPortForward(false, null)
        assertEquals(null, eng.forwardedPort)
        assertEquals(sets, runner.wgSets())
    }

    @Test
    fun connectDialsAndBringsTunnelUp() {
        val (eng, runner, _) = engine()
        assertTrue(eng.connect("testland"))
        assertEquals(DaemonEngine.Status.UP, eng.status)
        assertEquals("testland", eng.activeServerId)
        val argv = runner.commands.map { it.first }
        assertTrue(argv.any { it.take(2) == listOf("wg", "set") && "154.16.20.1:1337" in it })
        assertTrue(argv.any { it.take(2) == listOf("nft", "-f") })
    }

    @Test
    fun disconnectPersistsStopAndRestoreStaysDown() {
        val (eng, runner, state) = engine()
        assertTrue(eng.connect("testland"))
        eng.disconnect()
        assertTrue(state.userStopped)
        assertEquals(DaemonEngine.Status.DOWN, eng.status)
        assertFalse(eng.restore(linkUp = true))
        assertEquals(DaemonEngine.Status.DOWN, eng.status)
        // One dial means two wg set calls (keys, then peer); the stop
        // must not trigger any further dial.
        assertEquals(2, runner.wgSets())
    }

    @Test
    fun linkLossParksAndReturnRestores() {
        val (eng, _, _) = engine()
        assertTrue(eng.connect("testland"))
        assertFalse(eng.tick(linkUp = false, handshakeSec = null))
        assertEquals(DaemonEngine.Status.PARKED, eng.status)
        assertTrue(eng.tick(linkUp = true, handshakeSec = null))
        assertEquals(DaemonEngine.Status.UP, eng.status)
    }

    @Test
    fun stallRedialsAndExhaustionParksWithCooldown() {
        val http = FakeHttp()
        val (eng, runner, state, clock) = engine(http = http)
        assertTrue(eng.connect("testland"))
        val dialsBefore = runner.wgSets()
        // No handshake progress for over the redial delay: tick redials.
        clock.now += DaemonEngine.STALL_REDIAL_AFTER_SEC + 1
        assertTrue(eng.tick(linkUp = true, handshakeSec = 0L))
        assertTrue(runner.wgSets() > dialsBefore)
        // Now the API is dead: tick must give up and park with cooldown.
        http.addKeyFails = true
        clock.now += DaemonEngine.STALL_REDIAL_AFTER_SEC + 1
        assertFalse(eng.tick(linkUp = true, handshakeSec = 0L))
        assertEquals(DaemonEngine.Status.PARKED, eng.status)
        assertTrue(state.giveUpActive(clock.now))
        assertFalse(eng.restore(linkUp = true))
    }
}
