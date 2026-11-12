package fr.hodr.pia

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Local control plane: line protocol over a root-owned Unix socket.
 * One command per connection, one line back. The socket lives in a
 * 0710 root:pia directory so only root and GUI users in group `pia`
 * can talk to the daemon; no secrets travel here except LOGIN, which
 * writes straight to the 0600 token store and is never echoed.
 *
 * Commands: STATUS | CONNECT [serverId] | DISCONNECT | LOGIN user pass |
 * ALLOWLAN on|off | SERVERS
 */
class PiadServer(
    private val engine: DaemonEngine,
    private val store: FileTokenStore,
    private val socketPath: java.nio.file.Path,
) {

    @Volatile
    private var running = false
    private var channel: ServerSocketChannel? = null

    fun start() {
        socketPath.parent?.let { Files.createDirectories(it) }
        Files.deleteIfExists(socketPath)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socketPath))
        try {
            Files.setPosixFilePermissions(
                socketPath,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                ),
            )
        } catch (ignored: UnsupportedOperationException) {
        }
        channel = server
        running = true
        Thread({ acceptLoop(server) }, "piad-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try {
            channel?.close()
        } catch (ignored: Exception) {
        }
        Files.deleteIfExists(socketPath)
    }

    private fun acceptLoop(server: ServerSocketChannel) {
        while (running) {
            val client: SocketChannel = try {
                server.accept()
            } catch (e: Exception) {
                if (running) Thread.sleep(200)
                continue
            }
            try {
                handle(client)
            } catch (e: Exception) {
            } finally {
                try {
                    client.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun handle(client: SocketChannel) {
        val line = client.readLine(4096) ?: return
        client.writeLine(handleCommand(line.trim()))
    }

    fun handleCommand(line: String): String {
        val parts = line.split(" ")
        return when (parts.firstOrNull()?.uppercase()) {
            "STATUS" -> "status=${engine.status} server=${engine.activeServerId ?: "-"} " +
                "port=${engine.forwardedPort ?: "-"} " +
                "error=${engine.lastError ?: "-"}"
            "CONNECT" -> {
                val ok = engine.connect(parts.getOrNull(1))
                if (ok) "OK" else "FAIL ${engine.lastError ?: "dial failed"}"
            }
            "DISCONNECT" -> {
                engine.disconnect()
                "OK"
            }
            "LOGIN" -> {
                if (parts.size < 3) return "FAIL usage: LOGIN user pass"
                store.saveCredentials(parts[1], parts[2])
                "OK"
            }
            "ALLOWLAN" -> {
                when (parts.getOrNull(1)?.lowercase()) {
                    "on" -> if (engine.setAllowLan(true)) "OK" else "FAIL ${engine.lastError ?: "redial failed"}"
                    "off" -> if (engine.setAllowLan(false)) "OK" else "FAIL ${engine.lastError ?: "redial failed"}"
                    else -> "FAIL usage: ALLOWLAN on|off"
                }
            }
            "SERVERS" -> try {
                engine.serverList().joinToString(";") { "${it.id}=${it.displayTitle()}" }
            } catch (t: Throwable) {
                "FAIL ${engine.lastError ?: "no server list"}"
            }
            "PORT" -> "port=${engine.forwardedPort ?: "-"}"
            "PORTFORWARD" -> {
                when (parts.getOrNull(1)?.lowercase()) {
                    "on" -> {
                        engine.setPortForward(true, parts.drop(2).joinToString(" ").ifEmpty { null })
                        "OK port=${engine.forwardedPort ?: "-"}"
                    }
                    "off" -> {
                        engine.setPortForward(false, null)
                        "OK"
                    }
                    else -> "FAIL usage: PORTFORWARD on [hook]|off"
                }
            }
            else -> "FAIL unknown command"
        }
    }
}

private fun SocketChannel.readLine(max: Int): String? {
    val buf = java.nio.ByteBuffer.allocate(max)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        val text = String(buf.array(), 0, buf.position(), Charsets.UTF_8)
        val nl = text.indexOf('\n')
        if (nl >= 0) return text.substring(0, nl)
        if (buf.position() >= max) return text
    }
    return if (buf.position() > 0) {
        String(buf.array(), 0, buf.position(), Charsets.UTF_8)
    } else {
        null
    }
}

private fun SocketChannel.writeLine(text: String) {
    val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
    write(java.nio.ByteBuffer.wrap(bytes))
}
