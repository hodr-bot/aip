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
 * ALLOWLAN on|off | SERVERS | PORT [name] | PORTFORWARD [name] on [hook]|off |
 * UP name [serverId] | DOWN name | LIST
 *
 * Single-tunnel commands hit the default tunnel; UP/DOWN/LIST and the
 * optional [name] address one tunnel of a multi-tunnel manager.
 */
class PiadServer(
    private val engine: DaemonEngine,
    private val store: FileTokenStore,
    private val socketPath: java.nio.file.Path,
    private val manager: TunnelManager? = null,
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
            "PORT" -> {
                val target = named(parts.getOrNull(1)) ?: return "FAIL unknown tunnel"
                "port=${target.forwardedPort ?: "-"}"
            }
            "PORTFORWARD" -> {
                // PORTFORWARD on [hook] | PORTFORWARD name on [hook]|off
                val (target, sub, hook) = if (parts.getOrNull(1)?.lowercase() in listOf("on", "off")) {
                    Triple(engine, parts.getOrNull(1), parts.drop(2).joinToString(" ").ifEmpty { null })
                } else {
                    val target = named(parts.getOrNull(1)) ?: return "FAIL unknown tunnel"
                    Triple(target, parts.getOrNull(2), parts.drop(3).joinToString(" ").ifEmpty { null })
                }
                when (sub?.lowercase()) {
                    "on" -> {
                        target.setPortForward(true, hook)
                        "OK port=${target.forwardedPort ?: "-"}"
                    }
                    "off" -> {
                        target.setPortForward(false, null)
                        "OK"
                    }
                    else -> "FAIL usage: PORTFORWARD [name] on [hook]|off"
                }
            }
            "UP" -> {
                val mgr = manager ?: return "FAIL single-tunnel daemon"
                val name = parts.getOrNull(1) ?: return "FAIL usage: UP name [serverId]"
                if (mgr.up(name, parts.getOrNull(2))) "OK" else "FAIL ${mgr.tunnels[name]?.engine?.lastError ?: "unknown tunnel"}"
            }
            "DOWN" -> {
                val mgr = manager ?: return "FAIL single-tunnel daemon"
                val name = parts.getOrNull(1) ?: return "FAIL usage: DOWN name"
                if (mgr.down(name)) "OK" else "FAIL unknown tunnel"
            }
            "LIST" -> {
                val mgr = manager ?: return "FAIL single-tunnel daemon"
                mgr.tunnels.values.joinToString(";") {
                    "${it.spec.name}=${it.engine.status}:${it.engine.activeServerId ?: "-"}:${it.engine.forwardedPort ?: "-"}"
                }
            }
            else -> "FAIL unknown command"
        }
    }

    /** Default engine when no name given, the named tunnel engine otherwise. */
    private fun named(name: String?): DaemonEngine? {
        if (name.isNullOrEmpty()) return engine
        return manager?.tunnels?.get(name)?.engine
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
