package fr.hodr.pia

import java.nio.file.Path
import pia.core.Clock
import pia.core.HttpPort
import pia.core.PiaClient
import pia.core.ServerCatalog

/**
 * Owns one [DaemonEngine] per tunnel stanza and merges every live tunnel
 * into a single fail-closed nftables ruleset. Each tunnel gets its own
 * interface (pia-<name>), routing table and fwmark, plus its own state
 * directory, so keys, leases and the forwarded port never cross.
 *
 * The kill switch stays merged: every engine reports its params through
 * the rule sink, the manager rebuilds [NftRules.buildMulti] over all of
 * them and applies once. The last tunnel going down yields the closed
 * table, never an open one.
 */
class TunnelManager(
    private val specs: List<TunnelSpec>,
    private val dir: Path,
    private val http: () -> HttpPort = { LinuxHttpPort() },
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val clock: Clock = SystemClock(),
) {
    data class Tunnel(
        val spec: TunnelSpec,
        val engine: DaemonEngine,
        val ops: TunnelOps,
        val state: EngineState,
    )

    val tunnels: Map<String, Tunnel> = specs.mapIndexed { index, spec ->
        val tdir = dir.resolve(spec.name)
        val httpPort = http()
        val client = PiaClient(httpPort, clock)
        val state = EngineState(tdir.resolve("state.properties"))
        if (spec.portForward) state.portForward = true
        if (spec.postPortUp != null) state.postPortUp = spec.postPortUp
        if (spec.allowLan) state.allowLan = true
        val ops = TunnelOps(runner, iface = "pia-${spec.name}", table = 100 + index, fwmark = 51820 + index)
        val pf = PortForward(client, clock) { port, _ ->
            state.postPortUp?.let { hook -> runner.runShell(hook, mapOf("PIAD_PORT" to port.toString())) }
        }
        val engine = DaemonEngine(
            client = client,
            catalog = ServerCatalog(httpPort),
            store = FileTokenStore(dir.resolve("auth")),
            tunnel = ops,
            state = state,
            clock = clock,
            pf = pf,
            ruleSink = { params -> reportParams(spec.name, params) },
        )
        spec.name to Tunnel(spec, engine, ops, state)
    }.toMap()

    private val liveParams = mutableMapOf<String, NftParams>()
    private var applier: (String) -> Boolean = { rules -> tunnels.values.firstOrNull()?.ops?.applyRules(rules) ?: false }

    /** Default tunnel: the first stanza, for single-tunnel CLI/socket compat. */
    val default: Tunnel get() = tunnels.values.first()

    fun up(name: String, server: String? = null): Boolean {
        val t = tunnels[name] ?: return false
        val target = server ?: t.spec.server
        val ok = if (target != null) t.engine.connect(target) else t.engine.connect()
        applyMerged()
        return ok
    }

    fun down(name: String): Boolean {
        val t = tunnels[name] ?: return false
        t.engine.disconnect()
        liveParams.remove(name)
        applyMerged()
        return true
    }

    fun tick(linkUp: Boolean) {
        for (t in tunnels.values) t.engine.tick(linkUp = linkUp, handshakeSec = null)
        applyMerged()
    }

    fun restore(linkUpByTunnel: Map<String, Boolean> = emptyMap()) {
        for (t in tunnels.values) {
            val link = linkUpByTunnel[t.spec.name] ?: true
            t.engine.restore(link && t.spec.autoConnect)
        }
        applyMerged()
    }

    fun statusLines(): List<String> =
        tunnels.values.map { t ->
            val e = t.engine
            "tunnel=${t.spec.name} status=${e.status} server=${e.activeServerId ?: "-"} " +
                "port=${e.forwardedPort ?: "-"} error=${e.lastError ?: "-"}"
        }

    private fun reportParams(name: String, params: NftParams): Boolean {
        liveParams[name] = params
        return applyMerged()
    }

    fun applyMerged(): Boolean = applier(NftRules.buildMulti(liveParams.values.toList()))
}
