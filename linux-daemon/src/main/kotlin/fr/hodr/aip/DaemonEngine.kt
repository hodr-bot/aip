package fr.hodr.aip

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.util.Base64
import java.util.Properties
import pia.core.Clock
import pia.core.LeaseResult
import pia.core.PiaApiError
import pia.core.PiaAuthError
import pia.core.PiaClient
import pia.core.ServerCatalog
import pia.core.WireGuard

/**
 * Daemon state machine, mirroring PiaVpnService: CONNECT clears the stop
 * flag and dials now, DISCONNECT persists the stop first so an Always-on
 * style restart stays down, boot/link-return restores only when allowed,
 * stall redials the same server then rotates cached leases, exhaustion
 * parks with a cooldown and the monitor stays silent until the link or
 * the user changes something.
 */
class DaemonEngine(
    private val client: PiaClient,
    private val catalog: ServerCatalog,
    private val store: FileTokenStore,
    private val tunnel: TunnelOps,
    private val state: EngineState,
    private val clock: Clock = SystemClock(),
    private val pf: PortForward? = null,
    /**
     * Where the kill-switch ruleset goes. Defaults to applying the
     * single-tunnel rules directly; a multi-tunnel manager passes a sink
     * that merges every live tunnel into one `nft -f` before applying.
     */
    private val ruleSink: (NftParams) -> Boolean =
        { params -> tunnel.applyRules(NftRules.build(params)) },
) {
    enum class Status { DOWN, CONNECTING, UP, PARKED }

    var status: Status = Status.DOWN
        private set
    var lastError: String? = null
        private set
    var activeServerId: String? = null
        private set
    var forwardedPort: Int? = null
        private set

    private var generation = 0L
    private var connectingSinceSec = 0L
    private var lastHandshakeSec = 0L
    private var stallWarned = false
    private var activeHostname: String? = null
    private var activeGatewayIp: String? = null
    private var lastPfSec = 0L

    fun connect(serverId: String? = null): Boolean {
        val myGen = ++generation
        state.userStopped = false
        state.giveUpUntilSec = 0
        state.lastServerId = serverId ?: state.lastServerId
        state.save()
        return dial(myGen)
    }

    fun disconnect() {
        generation++
        state.userStopped = true
        state.forwardedPort = null
        state.save()
        pf?.reset()
        forwardedPort = null
        tunnel.bringDown()
        ruleSink(openParams())
        status = Status.DOWN
        activeServerId = null
    }

    /** Boot and link-return entry point: stays down unless allowed. */
    fun restore(linkUp: Boolean): Boolean {
        if (state.userStopped) {
            status = Status.DOWN
            return false
        }
        if (!linkUp) {
            park()
            return false
        }
        if (state.giveUpActive(clock.nowEpochSec())) {
            status = Status.PARKED
            return false
        }
        return dial(++generation)
    }

    fun setAllowLan(on: Boolean): Boolean {
        state.allowLan = on
        state.save()
        if (status == Status.UP) return dial(++generation)
        return true
    }

    /**
     * Toggle port forwarding; enabling on a live tunnel binds immediately
     * without redial. [hook] is the Post-Port-Up shell command, run with
     * AIPD_PORT set on every (re)bind.
     */
    fun setPortForward(on: Boolean, hook: String?): Boolean {
        state.portForward = on
        if (hook != null) state.postPortUp = hook
        state.save()
        if (!on) {
            pf?.reset()
            forwardedPort = null
            state.forwardedPort = null
            state.save()
            return true
        }
        if (status == Status.UP) refreshPortForward()
        return true
    }

    fun serverList(): List<pia.core.ServerEntry> = freshServers()

    /**
     * Periodic tick from the link monitor. Returns true when a dial was
     * attempted. Pure policy: the caller supplies link/probe readings.
     */
    fun tick(linkUp: Boolean, handshakeSec: Long?): Boolean {
        val now = clock.nowEpochSec()
        if (!linkUp) {
            park()
            return false
        }
        if (state.userStopped || state.giveUpActive(now)) return false
        if (status != Status.UP) return restore(linkUp = true)
        val hs = handshakeSec ?: tunnel.latestHandshake()
        if (hs != null && hs > 0) {
            lastHandshakeSec = hs
            stallWarned = false
            maybeRefreshPf()
            return false
        }
        val stalledFor = now - lastHandshakeSec
        if (stalledFor >= STALL_REDIAL_AFTER_SEC) {
            if (!stallWarned) {
                lastError = "Traffic stalled, redialing"
                stallWarned = true
            }
            return dial(++generation)
        }
        return false
    }

    private fun park() {
        tunnel.bringDown()
        ruleSink(parkedParams())
        if (status == Status.UP) status = Status.PARKED
    }

    private fun dial(myGen: Long): Boolean {
        if (myGen != generation) return false
        status = Status.CONNECTING
        connectingSinceSec = clock.nowEpochSec()
        lastError = null
        try {
            val token = client.ensureValidToken(store)
            val servers = freshServers()
            val target = servers.firstOrNull { it.id == state.lastServerId } ?: servers.first()
            val keys = newKeypair()
            val result = client.addKey(target.hostname, token, keys.publicB64)
            if (myGen != generation) return false
            return bringUpLease(myGen, target.id, target.hostname, result, keys.privateB64)
        } catch (auth: PiaAuthError) {
            lastError = "Login rejected, check account"
            park()
            return false
        } catch (api: PiaApiError) {
            lastError = "Server unreachable, tap CONNECT to retry"
        } catch (io: java.io.IOException) {
            lastError = "Server unreachable, tap CONNECT to retry"
        }
        if (myGen != generation) return false
        return dialCached(myGen) || giveUp()
    }

    private fun bringUpLease(myGen: Long, serverId: String, hostname: String, result: LeaseResult, privateB64: String): Boolean {
        val dns = result.dnsServers.ifEmpty { listOf("10.0.0.243") }
        val endpointIp = result.lease.endpoint.substringBefore(":")
        val endpointPort = result.lease.endpoint.substringAfter(":").toIntOrNull() ?: PiaClient.ADD_KEY_PORT
        ruleSink(
            NftParams(
                endpointIp = endpointIp,
                endpointPort = endpointPort,
                dnsIps = dns,
                allowLan = state.allowLan,
                iface = tunnel.iface,
            ),
        )
        val ok = tunnel.bringUp(
            lease = result.lease,
            privateKey = privateB64,
            allowedIps = WireGuard.allowedIpCidrs(state.allowLan),
        )
        if (myGen != generation) {
            tunnel.bringDown()
            return false
        }
        if (!ok) {
            lastError = "Tunnel refused to come up"
            return dialCached(myGen) || giveUp()
        }
        state.lastServerId = serverId
        state.cachedLease = CachedLease(
            serverId = serverId,
            peerIp = result.lease.peerIp,
            serverKey = result.lease.serverPublicKey,
            endpoint = result.lease.endpoint,
            dnsCsv = dns.joinToString(","),
        )
        state.save()
        activeServerId = serverId
        activeHostname = hostname
        activeGatewayIp = endpointIp
        status = Status.UP
        lastHandshakeSec = clock.nowEpochSec()
        stallWarned = false
        refreshPortForward()
        return true
    }

    /**
     * Bind the forwarded port now; PF failures keep the tunnel up with a
     * note in lastError instead of tearing down working traffic.
     */
    private fun refreshPortForward() {
        if (!state.portForward) return
        val manager = pf ?: return
        val host = activeHostname ?: return
        val gw = activeGatewayIp ?: return
        try {
            val token = client.ensureValidToken(store)
            forwardedPort = manager.refresh(host, gw, token.value)
            state.forwardedPort = forwardedPort
            state.save()
            lastPfSec = clock.nowEpochSec()
        } catch (t: Throwable) {
            lastError = "Port forward failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun maybeRefreshPf() {
        if (!state.portForward || pf == null) return
        if (clock.nowEpochSec() - lastPfSec < PortForward.REFRESH_SEC) return
        refreshPortForward()
    }

    private fun dialCached(myGen: Long): Boolean {
        val cached = state.cachedLease ?: return false
        if (myGen != generation) return false
        return try {
            val token = client.ensureValidToken(store)
            val keys = newKeypair()
            // Cached lease is a fallback address book, not a replay: the
            // server name comes from the cache, the lease from a live addKey.
            val servers = freshServers()
            val target = servers.firstOrNull { it.id == cached.serverId } ?: return false
            val result = client.addKey(target.hostname, token, keys.publicB64)
            if (myGen != generation) return false
            bringUpLease(myGen, target.id, target.hostname, result, keys.privateB64)
        } catch (t: Throwable) {
            false
        }
    }

    private fun giveUp(): Boolean {
        state.giveUpUntilSec = clock.nowEpochSec() + GIVE_UP_COOLDOWN_SEC
        state.save()
        park()
        status = Status.PARKED
        lastError = "Server unreachable, tap CONNECT to retry"
        return false
    }

    private fun freshServers(): List<pia.core.ServerEntry> {
        return try {
            val raw = catalog.fetchRaw()
            val servers = catalog.parseBody(raw)
            // Never cache a body that is empty or carries trailing garbage:
            // a corrupt cache poisons every offline fallback after it.
            val trimmed = raw.trim()
            if (servers.isEmpty() || !trimmed.endsWith("}")) {
                throw PiaApiError("server list body failed strict validation")
            }
            state.serversRaw = raw
            state.save()
            servers
        } catch (t: Throwable) {
            val cached = state.serversRaw ?: throw t
            catalog.parseBody(cached)
        }
    }

    private fun parkedParams(): NftParams = NftParams(
        endpointIp = state.cachedLease?.endpoint?.substringBefore(":") ?: "127.0.0.1",
        endpointPort = state.cachedLease?.endpoint?.substringAfter(":")?.toIntOrNull()
            ?: PiaClient.ADD_KEY_PORT,
        dnsIps = emptyList(),
        allowLan = false,
        iface = tunnel.iface,
    )

    private fun openParams(): NftParams = parkedParams().copy(allowLan = true)

    private data class Keypair(val privateB64: String, val publicB64: String)

    private fun newKeypair(): Keypair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val kp = kpg.generateKeyPair()
        val priv = (kp.private as XECPrivateKey).scalar.orElse(null)
            ?.let { pad32(it) }
            ?: throw IllegalStateException("X25519 private key unavailable")
        // getU is big-endian two's complement: strip the sign byte, reverse
        // to little-endian, then fit 32 bytes.
        val pub = (kp.public as XECPublicKey).u.toByteArray()
            .dropWhile { it == 0.toByte() }
            .reversed()
            .let { pad32(it.toByteArray()) }
        val enc = Base64.getEncoder()
        return Keypair(enc.encodeToString(priv), enc.encodeToString(pub))
    }

    private fun pad32(raw: ByteArray): ByteArray {
        if (raw.size == 32) return raw
        val out = ByteArray(32)
        val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    companion object {
        const val STALL_REDIAL_AFTER_SEC = 180L
        const val GIVE_UP_COOLDOWN_SEC = 600L
    }
}

data class CachedLease(
    val serverId: String,
    val peerIp: String,
    val serverKey: String,
    val endpoint: String,
    val dnsCsv: String,
)

/**
 * Persisted daemon state: stop flag, cooldown, last server, cached lease,
 * offline server list. Secrets never land here, only the token store file
 * holds credentials and that file is 0600 root-only.
 */
class EngineState(private val path: Path) {
    var userStopped: Boolean = false
    var giveUpUntilSec: Long = 0
    var lastServerId: String? = null
    var allowLan: Boolean = false
    var cachedLease: CachedLease? = null
    var serversRaw: String? = null
    var portForward: Boolean = false
    var postPortUp: String? = null
    var forwardedPort: Int? = null

    init {
        load()
        serversRaw = loadServersRaw()
    }

    fun giveUpActive(nowSec: Long): Boolean = nowSec < giveUpUntilSec

    fun load() {
        if (!Files.exists(path)) return
        val props = Properties()
        Files.newInputStream(path).use { props.load(it) }
        userStopped = props.getProperty("userStopped") == "true"
        giveUpUntilSec = props.getProperty("giveUpUntilSec")?.toLongOrNull() ?: 0
        lastServerId = props.getProperty("lastServerId")
        allowLan = props.getProperty("allowLan") == "true"
        portForward = props.getProperty("portForward") == "true"
        postPortUp = props.getProperty("postPortUp")
        forwardedPort = props.getProperty("forwardedPort")?.toIntOrNull()
        val leaseServer = props.getProperty("leaseServer")
        cachedLease = if (leaseServer != null) {
            CachedLease(
                serverId = leaseServer,
                peerIp = props.getProperty("leasePeerIp", ""),
                serverKey = props.getProperty("leaseServerKey", ""),
                endpoint = props.getProperty("leaseEndpoint", ""),
                dnsCsv = props.getProperty("leaseDns", ""),
            )
        } else {
            null
        }
    }

    fun save() {
        saveServersRaw()
        path.parent?.let { Files.createDirectories(it) }
        val props = Properties()
        props.setProperty("userStopped", userStopped.toString())
        props.setProperty("giveUpUntilSec", giveUpUntilSec.toString())
        lastServerId?.let { props.setProperty("lastServerId", it) }
        props.setProperty("allowLan", allowLan.toString())
        props.setProperty("portForward", portForward.toString())
        postPortUp?.let { props.setProperty("postPortUp", it) }
        forwardedPort?.let { props.setProperty("forwardedPort", it.toString()) }
        cachedLease?.let {
            props.setProperty("leaseServer", it.serverId)
            props.setProperty("leasePeerIp", it.peerIp)
            props.setProperty("leaseServerKey", it.serverKey)
            props.setProperty("leaseEndpoint", it.endpoint)
            props.setProperty("leaseDns", it.dnsCsv)
        }
        Files.newOutputStream(path).use { props.store(it, null) }
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (ignored: UnsupportedOperationException) {
        }
    }

    private fun saveServersRaw() {
        val raw = serversRaw ?: return
        val dir = path.parent ?: return
        Files.createDirectories(dir)
        // Atomic tmp+move: concurrent serve/connect processes never leave
        // a torn file behind for the next offline fallback.
        val tmp = dir.resolve("servers.json.tmp")
        Files.writeString(tmp, raw, Charsets.UTF_8)
        Files.move(tmp, dir.resolve("servers.json"), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun loadServersRaw(): String? {
        val dir = path.parent ?: return null
        val file = dir.resolve("servers.json")
        return if (Files.exists(file)) Files.readString(file, Charsets.UTF_8) else null
    }
}
