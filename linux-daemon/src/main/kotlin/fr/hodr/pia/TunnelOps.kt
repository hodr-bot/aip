package fr.hodr.pia

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import pia.core.WireGuardLease

/**
 * Applies tunnel state through `ip`/`wg`/`nft`. Pure sequencing over a
 * [CommandRunner]: unit tests assert the exact argv order with a fake.
 *
 * bringUp: nft fail-closed first, routes second, handshake material last,
 * so no packet leaves before the kill switch is armed. The WireGuard
 * private key travels on stdin only, never in argv.
 */
class TunnelOps(
    private val run: CommandRunner,
    private val iface: String = "pia0",
    private val table: Int = 100,
    private val fwmark: Int = 51820,
) {
    fun bringUp(
        lease: WireGuardLease,
        privateKey: String,
        allowedIps: List<String>,
        mtu: Int = 900,
    ): Boolean {
        val steps = mutableListOf<Boolean>()
        bringDown()
        steps.add(run.run(listOf("ip", "link", "add", iface, "type", "wireguard")).ok)
        steps.add(
            run.run(
                listOf("wg", "set", iface, "private-key", "/dev/stdin", "fwmark", fwmark.toString()),
                stdin = privateKey.trim() + "\n",
            ).ok,
        )
        val endpoint = lease.endpoint.substringAfterLast("@")
        steps.add(
            run.run(
                listOf(
                    "wg", "set", iface,
                    "peer", lease.serverPublicKey,
                    "endpoint", endpoint,
                    "allowed-ips", allowedIps.joinToString(","),
                    "persistent-keepalive", "25",
                ),
            ).ok,
        )
        steps.add(run.run(listOf("ip", "addr", "add", "${lease.peerIp}/32", "dev", iface)).ok)
        steps.add(run.run(listOf("ip", "link", "set", iface, "up", "mtu", mtu.toString())).ok)
        steps.add(run.run(listOf("ip", "route", "add", "0.0.0.0/0", "dev", iface, "table", table.toString())).ok)
        steps.add(run.run(listOf("ip", "route", "add", "::/0", "dev", iface, "table", table.toString())).ok)
        steps.add(run.run(listOf("ip", "rule", "add", "not", "fwmark", fwmark.toString(), "table", table.toString(), "priority", "500")).ok)
        return steps.all { it }
    }

    /** Best effort teardown: never fails, order is rule, routes, link. */
    fun bringDown() {
        run.run(listOf("ip", "rule", "del", "not", "fwmark", fwmark.toString(), "table", table.toString(), "priority", "500"))
        run.run(listOf("ip", "route", "flush", "table", table.toString()))
        run.run(listOf("ip", "link", "del", iface))
    }

    /** Applies a ruleset built by [NftRules] through a 0600 temp file. */
    fun applyRules(ruleset: String): Boolean {
        val tmp = Files.createTempFile("pia-rules", ".nft")
        try {
            try {
                Files.setPosixFilePermissions(
                    tmp,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            } catch (ignored: UnsupportedOperationException) {
            }
            Files.writeString(tmp, ruleset, Charsets.UTF_8)
            return run.run(listOf("nft", "-f", tmp.toString())).ok
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Latest handshake epoch seconds across peers, null when unknown. */
    fun latestHandshake(): Long? {
        val result = run.run(listOf("wg", "show", iface, "latest-handshakes"))
        if (!result.ok) return null
        return result.stdout.lineSequence()
            .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() }
            .filter { it > 0 }
            .maxOrNull()
    }

    /** Single ping through the tunnel; minute-proof equivalent. */
    fun probeOk(ip: String): Boolean =
        run.run(listOf("ping", "-c1", "-W2", "-I", iface, ip)).ok
}
