package fr.hodr.pia

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import pia.core.HttpPort
import pia.core.PiaClient
import pia.core.ServerCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PiadServerTest {
    private companion object {
        val serversBody = """
        {"regions":[
          {"id":"testland","dns":"test.example.com","name":"Testland",
           "geo":false,"offline":false,"country":"TL"}
        ]}
    """.trimIndent()
    }

    private class FakeHttp : HttpPort {
        override fun postForm(url: String, fields: Map<String, String>): String =
            """{"status":"OK","token":"TOK123"}"""
        override fun get(url: String, headers: Map<String, String>): String =
            if ("addKey" in url) {
                """{"status":"OK","peer_ip":"10.4.128.2","server_key":"K",
                    "server_port":1337,"server_ip":"154.16.20.1",
                    "dns_servers":["10.0.0.243"]}"""
            } else {
                serversBody
            }
    }

    private class Client(val socketPath: java.nio.file.Path) {
        fun send(command: String): String {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                ch.connect(UnixDomainSocketAddress.of(socketPath))
                val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
                ch.write(java.nio.ByteBuffer.wrap(bytes))
                val buf = java.nio.ByteBuffer.allocate(4096)
                val out = StringBuilder()
                while (true) {
                    val n = ch.read(buf)
                    if (n < 0) break
                    out.append(String(buf.array(), 0, buf.position(), Charsets.UTF_8))
                    buf.clear()
                    if (out.contains("\n")) break
                }
                return out.toString().trim()
            }
        }
    }

    private fun server(): Triple<PiadServer, Client, DaemonEngine> {
        val dir = Files.createTempDirectory("piad-srv")
        val http = FakeHttp()
        val store = FileTokenStore(dir.resolve("auth"))
        store.saveCredentials("user", "pass")
        val engine = DaemonEngine(
            client = PiaClient(http, SystemClock()),
            catalog = ServerCatalog(http),
            store = store,
            tunnel = TunnelOps(FakeRunner()),
            state = EngineState(dir.resolve("state.properties")),
        )
        val server = PiadServer(engine, store, dir.resolve("piad.sock"))
        server.start()
        return Triple(server, Client(dir.resolve("piad.sock")), engine)
    }

    @Test
    fun socketProtocolRoundTrip() {
        val (server, client, _) = server()
        try {
            assertTrue(client.send("STATUS").startsWith("status=DOWN"))
            assertEquals("OK", client.send("LOGIN alice s3cret"))
            assertEquals("OK", client.send("CONNECT testland"))
            assertTrue(client.send("STATUS").startsWith("status=UP server=testland"))
            assertTrue(client.send("SERVERS").contains("testland=Testland"))
            assertEquals("OK", client.send("ALLOWLAN on"))
            assertEquals("OK", client.send("DISCONNECT"))
            assertTrue(client.send("STATUS").startsWith("status=DOWN"))
            assertTrue(client.send("BOGUS").startsWith("FAIL"))
        } finally {
            server.stop()
        }
    }
}
