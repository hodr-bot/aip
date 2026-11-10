package fr.hodr.aip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import pia.core.PiaClient
import pia.core.WireGuard
import javax.net.ssl.HttpsURLConnection

/**
 * Foreground [VpnService] hosting the WireGuard tunnel through [GoBackend].
 *
 * The connect flow hands over a CORE-negotiated lease via [startAction];
 * the service builds a [Config] from the persistent [WgKeys] identity and
 * brings a [PiaTunnel] named "pia" up. All backend work runs off the main
 * thread. No secrets are logged.
 */
class PiaVpnService : VpnService() {

    companion object {
        const val ACTION_DISCONNECT = "fr.hodr.aip.action.DISCONNECT"

        const val EXTRA_PEER_IP = "peerIp"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_SERVER_PORT = "serverPort"
        const val EXTRA_SERVER_KEY = "serverKey"
        const val EXTRA_DNS_SERVERS = "dnsServersCsv"
        const val EXTRA_ALLOW_LAN = "allowLan"
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_SERVER_NAME = "serverName"
        const val EXTRA_PROBE_IPS = "probeIps"

        private const val CHANNEL_ID = "aip-vpn"
        private const val SILENT_CHANNEL_ID = "aip-vpn-active"
        private const val LEGACY_CHANNEL_ID = "pia-vpn"
        private const val ALERT_CHANNEL_ID = "aip-alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val TUNNEL_NAME = "aip"

        private const val STATE_PREFS = "pia-vpn-state"
        private const val STATE_CONNECTED = "connected"
        private const val STATE_SERVER_ID = "serverId"
        private const val STATE_LAST_ERROR = "lastError"
        private const val STATE_ERROR_AT = "errorAt"
        private const val STATE_ALIVE_AT = "aliveAt"
        private const val STATE_USER_STOPPED = "userStopped"
        private const val STATE_GIVE_UP_AT = "giveUpAt"

        /** Freshness window: the service heartbeats every 5s. */
        const val ALIVE_FRESH_MS = 20_000L
        const val FALLBACK_DNS = "10.0.0.243"

        /**
         * Failed minute-proofs before stall recovery re-dials: about 3 min
         * of dead traffic while the tunnel object is up.
         */
        private const val STALL_REDIAL_AFTER = 3

        /**
         * After recovery gives up on every cached server, auto-restarts
         * (Always-on, boot, link flap) stay down this long. A user tap on
         * CONNECT always retries at once.
         */
        private const val GIVE_UP_COOLDOWN_MS = 10 * 60_000L

        /**
         * ULA address giving the TUN interface an IPv6 identity. PIA is
         * IPv4-only, so no IPv6 lease exists — but without ANY IPv6 address
         * on the interface Android drops the ::/0 route and public IPv6
         * leaks around the tunnel. This address is never routed anywhere
         * (ULA, device-local): v6 packets enter the TUN, WireGuard seals
         * them, PIA drops them. Blocked, never leaked. IPv4 LAN bypass is
         * unaffected.
         */
        const val TUN_IPV6_ADDRESS = "fd8e:2a41:9c7d::1"

        /**
         * TUN MTU. The path drops packets over 1000 bytes, so the interface
         * must stay small: outer packet = MTU + 60 (IP + UDP + WireGuard
         * overhead). 900 keeps everything on the wire under 1000.
         */
        const val TUNNEL_MTU = 900

        fun startAction(
            context: Context,
            peerIp: String,
            endpoint: String,
            serverPort: Int,
            serverKey: String,
            dnsServersCsv: String,
            allowLan: Boolean,
            serverId: String?,
            serverName: String = "",
            probeIpsCsv: String = "",
        ) {
            val intent = Intent(context, PiaVpnService::class.java).apply {
                putExtra(EXTRA_PEER_IP, peerIp)
                putExtra(EXTRA_ENDPOINT, endpoint)
                putExtra(EXTRA_SERVER_PORT, serverPort)
                putExtra(EXTRA_SERVER_KEY, serverKey)
                putExtra(EXTRA_DNS_SERVERS, dnsServersCsv)
                putExtra(EXTRA_ALLOW_LAN, allowLan)
                putExtra(EXTRA_SERVER_ID, serverId)
                putExtra(EXTRA_SERVER_NAME, serverName)
                putExtra(EXTRA_PROBE_IPS, probeIpsCsv)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Volatile
    private var backend: GoBackend? = null

    @Volatile
    private var tunnel: PiaTunnel? = null

    @Volatile
    private var activeServerId: String? = null

    @Volatile
    private var activeServerName: String = ""

    /**
     * Whether RFC1918 bypasses the tunnel right now. Shown in the expanded
     * notice so a dead LAN box is never a guessing game: if this says
     * "direct" and 192.168.x still fails, Android itself (lockdown mode) or
     * the Wi-Fi is eating it, not the tunnel config.
     */
    @Volatile
    private var activeAllowLan: Boolean = false

    /** Uptime anchor for the session duration shown in the status notice. */
    @Volatile
    private var connectedSinceMs: Long = 0L

    @Volatile
    private var monitor: Thread? = null

    @Volatile
    private var trafficStalled = false

    @Volatile
    private var probeIpsCsv = ""

    /**
     * Link state from ConnectivityManager. linkUsable = an underlying
     * (non-VPN) network with INTERNET capability exists; linkValidated =
     * it actually reaches the internet (VALIDATED). While !linkUsable the
     * service parks: no handshake, no probes, no alerts — zero radio burn —
     * and resumes on the callback. linkLostAt stamps a live tunnel's grace
     * period (60s for WiFi->mobile handover) before teardown.
     */
    @Volatile
    private var linkUsable = true

    @Volatile
    private var linkValidated = true

    @Volatile
    private var linkLostAt = 0L

    @Volatile
    private var parkedForNoNetwork = false

    @Volatile
    private var connecting = false

    @Volatile
    private var connectingSince = 0L

    /**
     * Bumped on every user CONNECT tap. A dial captures the value at start;
     * a mismatch at any later boundary means a newer tap took over: abort
     * quietly so the old attempt never resurrects over the new one.
     */
    @Volatile
    private var dialGeneration = 0

    /**
     * Set by ACTION_DISCONNECT. A disconnect must win over everything:
     * in-flight handshakes, stall recovery, auto-restarts. Cleared only by
     * a user tap on CONNECT (lease-carrying intent).
     */
    @Volatile
    private var stopRequested = false

    /** Consecutive failed minute-proofs while the tunnel object is up. */
    @Volatile
    private var stallFails = 0

    /** Stall recovery (re-dial + rotation) already running. */
    @Volatile
    private var recovering = false

    // Last dial parameters, stashed by bringUp so stall recovery can re-dial
    // without any network (kill switch blocks everything pre-tunnel).
    private var lastPeerIp = ""
    private var lastEndpoint = ""
    private var lastEndpointPort = 0
    private var lastServerKey = ""
    private var lastDnsCsv = ""

    /** Set by the link callback: the watchdog re-proves traffic at once. */
    @Volatile
    private var forceVerify = false

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** Checkbox value of the last start, so auto-resume keeps LAN mode. */
    @Volatile
    private var lastAllowLan = false

    /**
     * True while the TUN carries the ULA IPv6 identity (v6 blocked via
     * tunnel). Set false when refused, falling
     * back to the v4-only config: v6 capture is then best-effort. Shown in
     * the expanded notice so a leak report can be correlated.
     */
    @Volatile
    private var activeV6Identity = true

    override fun onCreate() {
        super.onCreate()
        // A fresh process means no live tunnel: a previous process may have
        // been killed without onDestroy, leaving a stale "connected" behind.
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean(STATE_CONNECTED, false)
            // Fresh process = no live tunnel: old failures are history, never
            // news. Without this the UI replays the previous session's error
            // on every launch ("toujours cette erreur" while doing nothing).
            .remove(STATE_LAST_ERROR)
            .remove(STATE_ERROR_AT)
            .apply()
        watchConnectivity()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            // The user said stop: persist it BEFORE tearing down so an
            // Always-on auto-restart racing this intent still sees it.
            stopRequested = true
            setUserStopped(true)
            connecting = false
            clearAlert()
            val lastServer = activeServerId
            bringDown()
            // User disconnect: the error belonged to the dead session.
            writeState(false, lastServer, null)
            stopSelf()
            return START_NOT_STICKY
        }
        lastAllowLan = intent?.getBooleanExtra(EXTRA_ALLOW_LAN, false) ?: false
        val peerIp = intent?.getStringExtra(EXTRA_PEER_IP)
        val endpoint = intent?.getStringExtra(EXTRA_ENDPOINT)
        val serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, -1) ?: -1
        val serverKey = intent?.getStringExtra(EXTRA_SERVER_KEY)
        val dnsServersCsv = intent?.getStringExtra(EXTRA_DNS_SERVERS).orEmpty()
        val allowLan = intent?.getBooleanExtra(EXTRA_ALLOW_LAN, false) ?: false
        val serverId = intent?.getStringExtra(EXTRA_SERVER_ID)
        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
        val probeIps = intent?.getStringExtra(EXTRA_PROBE_IPS).orEmpty()
        if (peerIp.isNullOrEmpty() || endpoint.isNullOrEmpty() ||
            serverKey.isNullOrEmpty() || serverPort <= 0 || serverPort > 65535
        ) {
            // No lease in the intent (boot with Always-on, system restart):
            // rebuild it from the stored account and last server. A user
            // stop or a fresh give-up survives the restart: stay down.
            if (isUserStopped() || giveUpActive()) {
                stopSelf()
                return START_NOT_STICKY
            }
            goForeground()
            Thread { restoreLease(allowLan) }.start()
            return START_STICKY
        }
        // Full lease in the intent: a user tap on CONNECT. It clears any
        // previous stop and any recovery cooldown. Every tap bumps the
        // generation: an older dial still hanging in a blocking step sees
        // the mismatch at its next boundary and tears itself down instead
        // of resurrecting over the new dial. The tap always wins.
        stopRequested = false
        setUserStopped(false)
        clearGiveUp()
        dialGeneration++
        connecting = false
        goForeground()
        Thread {
            bringUp(peerIp, endpoint, serverPort, serverKey, dnsServersCsv, allowLan, serverId, serverName, probeIps)
        }.start()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // Another app took the VPN slot (single TUN on Android): our
        // tunnel is dead. Say so loudly, never fake "connected".
        bringDown()
        val msg = "Another VPN took over."
        writeState(false, null, msg)
        postAlert("AIP VPN: VPN revoked", msg)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        try {
            netCallback?.let { cb ->
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        } catch (ignored: Exception) {
        }
        netCallback = null
        bringDown()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        // Android mandates an ongoing notification while a VpnService runs:
        // it cannot be removed, only made as quiet as the system allows.
        // No success alert is ever posted; problems alone use the alerts
        // channel. Migrate the old LOW channel to a MIN one.
        manager?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        manager?.deleteNotificationChannel(CHANNEL_ID)
        if (manager?.getNotificationChannel(SILENT_CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    SILENT_CHANNEL_ID,
                    "AIP VPN",
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        val notification = buildStatusNotification(0L, 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The mandatory ongoing notice, made useful: current server plus live
     * up/down counters, pretty-printed. Collapsed shows one line, expanded
     * (BigTextStyle) shows server, traffic and session duration.
     */
    private fun buildStatusNotification(txBytes: Long, rxBytes: Long): Notification {
        val server = activeServerName.ifEmpty { activeServerId ?: "…" }
        val counters = "▲ ${fmtBytes(txBytes)} · ▼ ${fmtBytes(rxBytes)}"
        val lan = if (activeAllowLan) "LAN bypass" else "LAN via tunnel"
        if (parkedForNoNetwork && backend == null) {
            return Notification.Builder(this, SILENT_CHANNEL_ID)
                .setContentTitle("AIP VPN · paused")
                .setContentText("No network, auto-resume")
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText("Server: $server\nNo network, paused. Auto-resume when back."),
                )
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
        val link = if (!linkUsable) " (network lost, paused)" else ""
        val netLine = if (!linkUsable) "\nNetwork lost, paused. Auto-resume" else ""
        val v6line = "\nIPv6: " + if (activeV6Identity) "blocked via TUN" else "v4 fallback (may leak)"
        val title = "AIP VPN · $server"
        val detail = if (trafficStalled) {
            "$counters · reconnecting…$link"
        } else if (connectedSinceMs > 0L) {
            "$counters · ${fmtDuration(System.currentTimeMillis() - connectedSinceMs)}$link"
        } else {
            "$counters · connecting…$link"
        }
        return Notification.Builder(this, SILENT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText("Server: $server\nTraffic: $counters\nSession: ${fmtDuration(System.currentTimeMillis() - connectedSinceMs)}\n$lan$netLine$v6line"),
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Refresh the ongoing notice with live counters; silent by design. */
    private fun refreshStatus() {
        val activeBackend = backend
        val activeTunnel = tunnel
        if (activeBackend == null || activeTunnel == null) return
        try {
            val stats = activeBackend.getStatistics(activeTunnel)
            val notification = buildStatusNotification(stats.totalTx(), stats.totalRx())
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        } catch (ignored: Exception) {
        }
    }

    private fun fmtBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun fmtDuration(ms: Long): String {
        if (ms < 0) return "0 min"
        val min = ms / 60_000
        if (min < 60) return "$min min"
        val h = min / 60
        return "$h h ${min % 60} min"
    }

    /** Drop the mandatory foreground notice the moment no tunnel runs. */
    private fun stopForegroundNotice() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (ignored: Exception) {
        }
    }

    /**
     * Rebuild a lease after a system restart (boot with Always-on):
     * stored credentials plus last server hostname give a fresh token
     * and a fresh addKey lease. Stops the service when nothing stored.
     */
    private fun restoreLease(intentAllowLan: Boolean) {
        // Auto-restart path (boot, Always-on, link flap): a user stop or a
        // fresh give-up stays down. Only a CONNECT tap dials again.
        if (isUserStopped() || giveUpActive()) {
            stopSelf()
            return
        }
        val store = PrefsTokenStore(this)
        val hostname = store.loadLastHost()
        if (hostname.isNullOrEmpty() || store.loadUsername() == null) {
            stopSelf()
            return
        }
        lastAllowLan = intentAllowLan
        // A restart intent carries no checkbox: the persisted UI preference is
        // the truth. Otherwise a reboot silently drops the LAN bypass.
        val allowLan = try {
            getSharedPreferences("pia-prefs", MODE_PRIVATE).getBoolean("allowLan", intentAllowLan)
        } catch (ignored: Exception) {
            intentAllowLan
        }
        // Link down: a cached IP lease still dials (lockdown, tunnel carries
        // its own path, no DNS needed). Anything else parks silently.
        if (!linkUsableNow()) {
            val cached = OfflineCache(this).loadLease(store.loadLastServerId())
            if (cached != null && isLiteralIp(cached.endpointHost)) {
                bringUp(
                    cached.peerIp, cached.endpointHost, cached.endpointPort,
                    cached.serverKey, cached.dnsCsv.ifEmpty { FALLBACK_DNS },
                    allowLan, store.loadLastServerId(), probeIps = cached.probeIpsCsv,
                )
            } else {
                parkForNoNetwork()
            }
            return
        }
        try {
            val client = PiaClient(HttpUrlConnectionPort(), SystemClock())
            val token = client.ensureValidToken(store)
            val publicKey = WgKeys.loadOrGenerate(this).publicKey.toBase64()
            val result = client.addKey(hostname, token, publicKey)
            bringUp(
                result.lease.peerIp,
                result.lease.endpoint.substringBeforeLast(':'),
                result.lease.endpoint.substringAfterLast(':').toInt(),
                result.lease.serverPublicKey,
                result.dnsServers.joinToString(","),
                allowLan,
                store.loadLastServerId(),
            )
        } catch (error: Exception) {
            if (error !is pia.core.PiaAuthError && error !is pia.core.PiaApiError) {
                val cached = OfflineCache(this).loadLease(store.loadLastServerId())
                if (cached != null) {
                    bringUp(
                        cached.peerIp, cached.endpointHost, cached.endpointPort,
                        cached.serverKey, cached.dnsCsv.ifEmpty { FALLBACK_DNS },
                        allowLan, store.loadLastServerId(), probeIps = cached.probeIpsCsv,
                    )
                    return
                }
            }
            // Offline at boot or stale account: a dead link parks silently
            // (the callback resumes us); anything else alerts, then the
            // system retries.
            if (shouldParkInstead()) {
                parkForNoNetwork()
                return
            }
            writeState(false, store.loadLastServerId(), error.javaClass.simpleName + ": " + (error.message ?: "?"))
            postAlert("AIP VPN tunnel down", "Restore failed: ${error.javaClass.simpleName}: ${error.message ?: "?"}")
            stopSelf()
        }
    }

    /**
     * A DISCONNECT landed mid-dial, or a newer CONNECT tap took over: tear
     * the half-built tunnel down and report nothing. The disconnect (or the
     * newer dial) already owns the UI state.
     */
    private fun abortIfStopped(
        activeBackend: GoBackend,
        activeTunnel: PiaTunnel,
        myGen: Int,
    ): Boolean {
        if (!stopRequested && !isUserStopped() && myGen == dialGeneration) return false
        connecting = false
        try {
            activeBackend.setState(activeTunnel, Tunnel.State.DOWN, null)
        } catch (ignored: Exception) {
            // Best-effort teardown; nothing secret to report.
        }
        backend = null
        tunnel = null
        return true
    }

    /**
     * WireGuard config for one connect attempt. [addressesCsv] is normally
     * v4 lease and ULA identity; v4-only fallback if refused.
     */
    private fun buildConfig(
        addressesCsv: String,
        dnsServersCsv: String,
        serverKey: String,
        endpoint: String,
        serverPort: Int,
        allowLan: Boolean,
    ): Config {
        val ifaceBuilder = Interface.Builder()
            .setKeyPair(WgKeys.loadOrGenerate(this))
            .setMtu(TUNNEL_MTU)
            .parseAddresses(addressesCsv)
        val dnsCsv = dnsServersCsv.ifBlank { FALLBACK_DNS }
        val dnsAll = (dnsCsv.split(",").map { it.trim() }
            .filter { it.isNotEmpty() } +
            listOf("1.1.1.1", "8.8.8.8")).distinct().joinToString(",")
        ifaceBuilder.parseDnsServers(dnsAll)
        val peerBuilder = Peer.Builder()
            .setPublicKey(Key.fromBase64(serverKey))
            .setEndpoint(InetEndpoint.parse("$endpoint:$serverPort"))
        for (cidr in WireGuard.allowedIpCidrs(allowLan)) {
            peerBuilder.addAllowedIp(InetNetwork.parse(cidr))
        }
        return Config.Builder()
            .setInterface(ifaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()
    }

    private fun bringUp(
        peerIp: String,
        endpoint: String,
        serverPort: Int,
        serverKey: String,
        dnsServersCsv: String,
        allowLan: Boolean,
        serverId: String?,
        serverName: String = "",
        probeIps: String = "",
    ) {
        // Single-flight: an in-flight attempt wins. Stale guard (>90s) in
        // case a worker thread ever died without resetting the flag.
        if (connecting && SystemClock.elapsedRealtime() - connectingSince < 90_000) return
        connecting = true
        connectingSince = SystemClock.elapsedRealtime()
        // This dial's generation: a newer user tap bumps the counter, and
        // every boundary below aborts this attempt instead of letting it
        // resurrect over the new dial.
        val myGen = dialGeneration
        // No link, no attempt unless the endpoint is a literal IP: a cached
        // IP lease carries its own path (lockdown), no DNS needed. Anything
        // else would burn radio on doomed DNS.
        if (!linkUsableNow() && !isLiteralIp(endpoint)) {
            connecting = false
            parkForNoNetwork()
            return
        }
        parkedForNoNetwork = false
        if (otherVpnActive()) {
            connecting = false
            val msg = "Another VPN is active, disable it first."
            writeState(false, serverId, msg)
            postAlert("AIP VPN: VPN conflict", msg)
            stopSelf()
            return
        }
        activeServerId = serverId
        activeServerName = serverName
        activeAllowLan = allowLan
        probeIpsCsv = probeIps
        lastPeerIp = peerIp
        lastEndpoint = endpoint
        lastEndpointPort = serverPort
        lastServerKey = serverKey
        lastDnsCsv = dnsServersCsv
        val activeTunnel = PiaTunnel(TUNNEL_NAME)
        val activeBackend = GoBackend(this)
        tunnel = activeTunnel
        backend = activeBackend
        try {
            var useV6Identity = true
            activeV6Identity = true
            val config = try {
                buildConfig(
                    "$peerIp/32, $TUN_IPV6_ADDRESS/128",
                    dnsServersCsv, serverKey, endpoint, serverPort, allowLan,
                )
            } catch (error: Exception) {
                useV6Identity = false
                activeV6Identity = false
                buildConfig(
                    "$peerIp/32",
                    dnsServersCsv, serverKey, endpoint, serverPort, allowLan,
                )
            }
            try {
                activeBackend.setState(activeTunnel, Tunnel.State.UP, config)
            } catch (error: Exception) {
                if (useV6Identity && error.message?.contains("address", true) == true) {
                    useV6Identity = false
                    activeV6Identity = false
                    val v4 = buildConfig(
                        "$peerIp/32",
                        dnsServersCsv, serverKey, endpoint, serverPort, allowLan,
                    )
                    activeBackend.setState(activeTunnel, Tunnel.State.UP, v4)
                } else {
                    throw error
                }
            }
            // A DISCONNECT landed mid-dial: drop the half-built tunnel, the
            // disconnect already wrote the UI state.
            if (abortIfStopped(activeBackend, activeTunnel, myGen)) return
            // The tunnel is up, but that proves nothing about traffic: some
            // networks blackhole UDP or DNS. Give WireGuard up to ~10s to
            // complete its first handshake (rx>0) BEFORE any TCP runs: probing
            // instantly tears down tunnels whose handshake just needed a
            // second. Only claim "connected" once real traffic flows.
            val handshake = pia.core.awaitHandshake(
                stats = {
                    val s = activeBackend.getStatistics(activeTunnel)
                    s.totalTx() to s.totalRx()
                },
                nowMs = { SystemClock.elapsedRealtime() },
                sleepMs = { SystemClock.sleep(it) },
            )
            if (!handshake.completed) {
                connecting = false
                // Dead link (or wifi without internet): park silently instead
                // of the alert + stopSelf loop that Always-on would restart
                // forever. A real tunnel failure still alerts below.
                if (shouldParkInstead()) {
                    parkForNoNetwork()
                    return
                }
                failTraffic(
                    activeBackend, activeTunnel, serverId,
                    "handshake unanswered (tx=${handshake.txBytes} rx=0)",
                )
                return
            }
            val (flows, trace) = verifyTraffic()
            // Late DISCONNECT (during handshake or verify): drop everything,
            // report nothing, the disconnect owns the UI state now.
            if (abortIfStopped(activeBackend, activeTunnel, myGen)) return
            if (flows) {
                trafficStalled = false
                stallFails = 0
                connecting = false
                connectedSinceMs = System.currentTimeMillis()
                clearGiveUp()
                writeState(true, serverId, null)
                clearAlert()
                refreshStatus()
                refreshCacheInTunnel()
                startMonitor(activeBackend, activeTunnel)
            } else {
                // Handshake counters BEFORE teardown: tx=0 nothing left the
                // device, tx>0+rx=0 the handshake went unanswered (wrong
                // endpoint/key), rx>0 the handshake lived and TCP/HTTP died.
                val counters = try {
                    val stats = activeBackend.getStatistics(activeTunnel)
                    "tx=${stats.totalTx()} rx=${stats.totalRx()}"
                } catch (error: Exception) {
                    "counters n/a"
                }
                // Verify failed AFTER a live handshake: if the link itself
                // is dead or unvalidated (wifi without internet), park
                // silently. Only a live link + dead tunnel alerts.
                connecting = false
                if (shouldParkInstead()) {
                    parkForNoNetwork()
                    return
                }
                failTraffic(activeBackend, activeTunnel, serverId, "no traffic flows ($trace; $counters)")
            }
        } catch (error: Exception) {
            connecting = false
            // Same park rule on unexpected teardown: a dead link parks
            // silently, a live link with a broken tunnel alerts.
            if (shouldParkInstead()) {
                parkForNoNetwork()
                return
            }
            try {
                activeBackend.setState(activeTunnel, Tunnel.State.DOWN, null)
            } catch (ignored: Exception) {
                // Best-effort teardown; nothing secret to report.
            }
            backend = null
            tunnel = null
            val rawMsg = error.message ?: "?"
            val reason = if (rawMsg.contains("address", true)) {
                "Connection refused ($rawMsg). Another VPN is probably active, disable it first."
            } else {
                error.javaClass.simpleName + ": " + rawMsg
            }
            writeState(false, serverId, reason)
            postAlert("AIP VPN tunnel down", "Disconnected: $reason")
            stopSelf()
        }
    }

    /**
     * Prove real traffic flows through the tunnel. The probe IPs are resolved
     * on the plain network BEFORE the tunnel comes up (passed via
     * EXTRA_PROBE_IPS), so a dead tunnel-DNS can never fake a dead tunnel:
     * TCP+TLS to a cached IP either flows or it does not. Tunnel DNS is then
     * tested separately as a warning, not a verdict.
     */
    private fun verifyTraffic(): Pair<Boolean, String> {
        val trace = StringBuilder()
        val host = "www.google.com"
        val cached = intentProbeIps()
        if (cached.isNotEmpty()) {
            trace.append("dns cached ${cached.size}v4; ")
            val (ok, stage) = probeIpsTls(cached, host, trace)
            if (ok) {
                probeTunnelDns(host, trace)
                return true to trace.toString()
            }
            trace.append(stage)
            return false to trace.toString()
        }
        // Legacy path: no cached IPs (old app version). System DNS first.
        val dnsStart = SystemClock.elapsedRealtime()
        val addrs = try {
            java.net.InetAddress.getAllByName(host)
        } catch (error: Exception) {
            trace.append("dns ${error.javaClass.simpleName}")
            return false to trace.toString()
        }
        val v4 = addrs.filterIsInstance<java.net.Inet4Address>()
        trace.append("dns ${v4.size}v4 ${SystemClock.elapsedRealtime() - dnsStart}ms; ")
        if (v4.isEmpty()) {
            trace.append("tcp4 no-v4")
            return false to trace.toString()
        }
        val (ok, stage) = probeIpsTls(v4.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() }, host, trace)
        if (ok) {
            return true to trace.toString()
        }
        trace.append(stage)
        return false to trace.toString()
    }

    /** IPs passed by the activity, resolved before the tunnel went up. */
    private fun intentProbeIps(): List<String> =
        probeIpsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * TCP + TLS-with-SNI + HTTP GET against raw IPs. Returns (true, "") on
     * the first IP that yields >=1KB, else (false, stage-trace).
     */
    private fun probeIpsTls(ips: List<String>, sniHost: String, trace: StringBuilder): Pair<Boolean, String> {
        val stage = StringBuilder()
        for (ip in ips.take(4)) {
            try {
                val tcpStart = SystemClock.elapsedRealtime()
                val raw = java.net.Socket()
                raw.connect(java.net.InetSocketAddress(ip, 443), 5_000)
                stage.append("tcp4 ok; ")
                val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
                val tls = factory.createSocket(raw, sniHost, 443, true) as javax.net.ssl.SSLSocket
                try {
                    val params = tls.sslParameters
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(sniHost))
                    tls.sslParameters = params
                    tls.soTimeout = 12_000
                    tls.startHandshake()
                    val out = tls.outputStream
                    out.write("GET / HTTP/1.1\r\nHost: $sniHost\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    val input = tls.inputStream
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (total < 8192) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                    tls.close()
                    if (total >= 1024) {
                        trace.append("tcp4 ok ${SystemClock.elapsedRealtime() - tcpStart}ms; https ok ${total}b; ")
                        return true to ""
                    }
                    stage.append("https empty(${total}b); ")
                } finally {
                    try { tls.close() } catch (ignored: Exception) { }
                }
            } catch (error: Exception) {
                stage.append("ip $ip ${error.javaClass.simpleName}; ")
            }
        }
        return false to stage.toString()
    }

    /** Informational only: does tunnel DNS resolve? Never fails the proof. */
    private fun probeTunnelDns(host: String, trace: StringBuilder) {
        try {
            val addrs = java.net.InetAddress.getAllByName(host)
            trace.append("tun-dns ${addrs.size}ok; ")
        } catch (error: Exception) {
            trace.append("tun-dns ${error.javaClass.simpleName}(warn); ")
        }
    }

    /**
     * Connectivity-driven pause/resume. The official-app battery bug was a
     * hot loop: VPN on + no usable network = attempts that can never succeed
     * (handshake sleeps, TCP timeouts, Always-on restarts), forever.
     * Here a dead link PARKS the service — no handshake, no probes, no
     * alerts — and the system callback resumes it when a validated link
     * returns. Wifi connected-but-useless (no VALIDATED) is treated the
     * same after one failed attempt: the attempt is the test, parking is
     * the patience.
     *
     * Only UNDERLYING networks count: once our tunnel is up it would
     * otherwise be mistaken for the link. VPN transports are excluded.
     */
    private fun underlyingLink(): Pair<Boolean, Boolean> {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true to true
        var usable = false
        var validated = false
        val networks = try { cm.allNetworks } catch (ignored: Exception) { return true to true }
        for (net in networks) {
            val caps = try {
                cm.getNetworkCapabilities(net)
            } catch (ignored: Exception) {
                null
            } ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                usable = true
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    validated = true
                }
            }
        }
        return usable to validated
    }

    private fun linkUsableNow(): Boolean = underlyingLink().first

    private fun linkValidatedNow(): Boolean = underlyingLink().second

    /** Literal IP needs no DNS: safe to dial with the link down (lockdown). */
    private fun isLiteralIp(host: String): Boolean {
        if (host.contains(':')) return true
        if (!host.matches(Regex("[0-9.]+"))) return false
        return try {
            java.net.InetAddress.getByName(host).hostAddress == host
        } catch (error: Exception) {
            false
        }
    }

    /** Park instead of alerting when the link itself is the problem. */
    private fun shouldParkInstead(): Boolean = !linkUsableNow() || !linkValidatedNow()

    private fun snapshotLink() {
        val (usable, validated) = underlyingLink()
        if (!usable && linkUsable) linkLostAt = SystemClock.elapsedRealtime()
        if (usable) linkLostAt = 0L
        linkUsable = usable
        linkValidated = validated
    }

    private fun watchConnectivity() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        snapshotLink()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onLinkChanged() }
            override fun onLost(network: Network) { onLinkChanged() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { onLinkChanged() }
        }
        netCallback = callback
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (ignored: Exception) {
            netCallback = null
        }
    }

    private fun onLinkChanged() {
        val wasUsable = linkUsable
        val wasValidated = linkValidated
        snapshotLink()
        if (!linkUsable) return
        if (backend != null) {
            // Tunnel still up through a flap: re-prove at once instead of
            // waiting for the next minute tick.
            if (!wasUsable || !wasValidated) forceVerify = true
            return
        }
        if (parkedForNoNetwork && !connecting && (!wasUsable || !wasValidated)) {
            // A user stop or a fresh give-up stays down even when the link
            // flaps. Only a CONNECT tap dials again.
            if (isUserStopped() || giveUpActive()) return
            parkedForNoNetwork = false
            val resumeAllowLan = lastAllowLan
            Thread { restoreLease(resumeAllowLan) }.start()
        }
    }

    /**
     * Tear down and park SILENTLY: no alert, no stopSelf (stopping would let
     * Always-on restart us into the same dead link). Zero work happens while
     * parked; [onLinkChanged] resumes. The paused state is visible in the
     * mandatory notice and in pia-vpn-state for the UI.
     */
    private fun parkForNoNetwork() {
        val server = activeServerId
        bringDown()
        parkedForNoNetwork = true
        // A new link is a new chance: a recovery cooldown must not block
        // the resume that follows.
        clearGiveUp()
        writeState(false, server, "Paused, no network")
        goForeground()
    }

    /** Another app's VPN (never ours): Android allows one TUN at a time. */
    private fun otherVpnActive(): Boolean {
        if (tunnel != null || backend != null) return false
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val networks = try { cm.allNetworks } catch (error: Exception) { return false }
        for (net in networks) {
            val caps = try { cm.getNetworkCapabilities(net) } catch (error: Exception) { null } ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        }
        return false
    }

    /** A user tap on DISCONNECT persists across auto-restarts. */
    private fun isUserStopped(): Boolean =
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).getBoolean(STATE_USER_STOPPED, false)

    private fun setUserStopped(stopped: Boolean) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean(STATE_USER_STOPPED, stopped).apply()
    }

    /** Recovery gave up recently: auto-restarts back off, user taps do not. */
    private fun giveUpActive(): Boolean {
        val at = getSharedPreferences(STATE_PREFS, MODE_PRIVATE).getLong(STATE_GIVE_UP_AT, 0L)
        return at > 0 && System.currentTimeMillis() - at < GIVE_UP_COOLDOWN_MS
    }

    private fun persistGiveUp() {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putLong(STATE_GIVE_UP_AT, System.currentTimeMillis()).apply()
    }

    private fun clearGiveUp() {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .remove(STATE_GIVE_UP_AT).apply()
    }

    private fun writeState(connected: Boolean, serverId: String?, lastError: String?) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean(STATE_CONNECTED, connected)
            .putString(STATE_SERVER_ID, serverId)
            .putString(STATE_LAST_ERROR, lastError)
            .putLong(STATE_ALIVE_AT, System.currentTimeMillis())
            .apply()
        if (lastError != null) {
            getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
                .putLong(STATE_ERROR_AT, System.currentTimeMillis())
                .apply()
        }
    }

    private fun writeAlive() {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putLong(STATE_ALIVE_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Watchdog: heartbeat every 5s so the UI can tell a live tunnel from a
     * stale "connected" flag, plus a full traffic re-proof every minute.
     * A failed re-proof NEVER tears the tunnel down: WireGuard roams and
     * re-handshakes on its own (Doze, NAT expiry, brief stalls), and killing
     * the tunnel under Always-on block mode means total blackout. The
     * watchdog reports the stall once, then clears itself on recovery.
     */
    @Volatile
    private var lastCacheRefreshMs: Long = 0L

    /**
     * Refresh the offline caches while the tunnel carries traffic: fresh
     * lease, fresh server list, fresh probe IPs. Silent by design: the
     * caches are best-effort, the tunnel state is untouched.
     */
    private fun refreshCacheInTunnel() {
        lastCacheRefreshMs = System.currentTimeMillis()
        val serverId = activeServerId
        Thread {
            try {
                val store = PrefsTokenStore(this)
                val client = PiaClient(HttpUrlConnectionPort(), SystemClock())
                val token = client.ensureValidToken(store)
                val hostname = store.loadLastHost() ?: return@Thread
                val id = serverId ?: store.loadLastServerId() ?: return@Thread
                val publicKey = WgKeys.loadOrGenerate(this).publicKey.toBase64()
                val result = client.addKey(hostname, token, publicKey)
                val endpoint = result.lease.endpoint
                val slash = endpoint.lastIndexOf(':')
                if (slash > 0 && slash < endpoint.length - 1) {
                    val port = endpoint.substring(slash + 1).toIntOrNull()
                    if (port != null) {
                        val probes = try {
                            java.net.InetAddress.getAllByName("www.google.com")
                                .filterIsInstance<java.net.Inet4Address>()
                                .take(4)
                                .mapNotNull { it.hostAddress }
                                .joinToString(",")
                        } catch (ignored: Exception) {
                            ""
                        }
                        OfflineCache(this).saveLease(
                            id,
                            CachedLease(
                                hostname, result.lease.peerIp,
                                endpoint.substring(0, slash), port,
                                result.lease.serverPublicKey,
                                result.dnsServers.joinToString(","),
                                probes, System.currentTimeMillis(),
                            ),
                        )
                    }
                }
                try {
                    OfflineCache(this).saveServerList(
                        pia.core.ServerCatalog(HttpUrlConnectionPort()).fetchRaw(),
                    )
                } catch (ignored: Exception) {
                    // Server list refresh is optional; the lease is the priority.
                }
            } catch (ignored: Exception) {
                // Offline caches are best-effort; never touch the live tunnel.
            }
        }.apply { isDaemon = true }.start()
    }

    private fun startMonitor(activeBackend: GoBackend, activeTunnel: PiaTunnel) {
        stopMonitor()
        val watchedServer = activeServerId
        val thread = Thread {
            var lastVerify = System.currentTimeMillis()
            var lastStatus = 0L
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(5_000)
                } catch (interrupted: InterruptedException) {
                    break
                }
                try {
                    writeAlive()
                    val now = System.currentTimeMillis()
                    // Live counters in the ongoing notice, refreshed silently.
                    if (now - lastStatus >= 10_000) {
                        lastStatus = now
                        refreshStatus()
                    }
                    if (!linkUsable) {
                        // Link dead or gone: NO probes (zero radio burn), just
                        // show the pause. Past 60s of dead link a live tunnel
                        // is useless — WireGuard would re-handshake forever —
                        // so tear it down and park; the callback resumes.
                        // WiFi->mobile handovers pass through here unharmed.
                        trafficStalled = false
                        if (linkLostAt > 0 && SystemClock.elapsedRealtime() - linkLostAt > 60_000) {
                            bringDown()
                            parkedForNoNetwork = true
                            writeState(false, watchedServer, "Paused, no network")
                            goForeground()
                        }
                    } else if (now - lastVerify >= 60_000 || forceVerify) {
                        forceVerify = false
                        lastVerify = now
                        if (now - lastCacheRefreshMs >= 12 * 3_600_000L) {
                            refreshCacheInTunnel()
                        }
                        if (!verifyTraffic().first) {
                            if (!trafficStalled) {
                                trafficStalled = true
                                stallFails = 1
                                writeState(
                                    true,
                                    watchedServer,
                                    "traffic stalled, redialing",
                                )
                                postAlert(
                                    "AIP VPN tunnel stalled",
                                    "Traffic stalled, redialing",
                                )
                            } else {
                                stallFails++
                                writeState(
                                    true,
                                    watchedServer,
                                    "traffic stalled, redialing",
                                )
                                // About 3 min of dead traffic on a live
                                // tunnel object: WireGuard will not fix this
                                // on its own, re-dial (same server, then
                                // other cached servers) instead of hoping.
                                if (stallFails >= STALL_REDIAL_AFTER && !recovering && !connecting) {
                                    recoverTunnel()
                                    return@Thread
                                }
                            }
                        } else if (trafficStalled) {
                            trafficStalled = false
                            stallFails = 0
                            writeState(true, watchedServer, null)
                            clearAlert()
                        }
                    }
                } catch (ignored: Exception) {
                    // Keep watching; next tick retries.
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        monitor = thread
    }

    private fun stopMonitor() {
        monitor?.interrupt()
        monitor = null
    }

    /**
     * The tunnel object is up but no traffic flows for minutes: re-dial the
     * same server (fresh session fixes transient blackholes), then rotate
     * over other cached servers (newest first). Runs on the monitor thread;
     * bringUp starts the next monitor, this thread exits after handing off.
     * When nothing cached answers, say so honestly and back off: the user
     * taps CONNECT to retry, auto-restarts stay down for a cooldown.
     */
    private fun recoverTunnel() {
        if (recovering) return
        recovering = true
        // Clear any self-interrupt from bringDown: this thread keeps working.
        Thread.interrupted()
        try {
            persistGiveUp()
            // A newer user tap bumps the generation: this recovery is
            // obsolete, hand over to the new dial.
            val recGen = dialGeneration
            val stalledServer = activeServerId
            val stalledName = activeServerName
            if (!stopRequested && !isUserStopped() && tryRedial(
                    lastPeerIp, lastEndpoint, lastEndpointPort, lastServerKey,
                    lastDnsCsv, lastAllowLan, stalledServer, stalledName, probeIpsCsv,
                )
            ) {
                return
            }
            if (parkedForNoNetwork || stopRequested || isUserStopped() || recGen != dialGeneration) return
            val others = try {
                OfflineCache(this).allLeases()
            } catch (error: Exception) {
                emptyMap()
            }.filterKeys { it != stalledServer }
                .entries.sortedByDescending { it.value.savedAtMs }
            for ((id, lease) in others) {
                if (stopRequested || isUserStopped() || recGen != dialGeneration) return
                if (tryRedial(
                        lease.peerIp, lease.endpointHost, lease.endpointPort,
                        lease.serverKey, lease.dnsCsv.ifEmpty { FALLBACK_DNS },
                        lastAllowLan, id, "", lease.probeIpsCsv,
                    )
                ) {
                    return
                }
                if (parkedForNoNetwork || stopRequested || isUserStopped() || recGen != dialGeneration) return
            }
            giveUp(stalledServer)
        } finally {
            recovering = false
        }
    }

    /** One re-dial attempt with fixed parameters, no network needed. */
    private fun tryRedial(
        peerIp: String,
        endpoint: String,
        port: Int,
        key: String,
        dnsCsv: String,
        allowLan: Boolean,
        serverId: String?,
        serverName: String,
        probes: String,
    ): Boolean {
        if (peerIp.isEmpty() || endpoint.isEmpty() || key.isEmpty() || port <= 0) return false
        bringDown()
        // Clear the self-interrupt so the dial below sleeps normally.
        Thread.interrupted()
        if (stopRequested || isUserStopped()) return false
        SystemClock.sleep(1500)
        if (stopRequested || isUserStopped()) return false
        bringUp(peerIp, endpoint, port, key, dnsCsv, allowLan, serverId, serverName, probes)
        return backend != null && tunnel != null && !trafficStalled && !parkedForNoNetwork
    }

    /** Nothing cached answers: honest state, one alert, back off. */
    private fun giveUp(serverId: String?) {
        bringDown()
        val msg = "Server unreachable, tap CONNECT to retry"
        writeState(false, serverId, msg)
        postAlert("AIP VPN tunnel down", "Disconnected: $msg")
        stopSelf()
    }

    private fun failTraffic(
        activeBackend: GoBackend,
        activeTunnel: PiaTunnel,
        serverId: String?,
        reason: String,
    ) {
        stopMonitor()
        try {
            activeBackend.setState(activeTunnel, Tunnel.State.DOWN, null)
        } catch (ignored: Exception) {
            // Best-effort teardown; nothing secret to report.
        }
        backend = null
        tunnel = null
        writeState(false, serverId, reason)
        stopForegroundNotice()
        postAlert("AIP VPN tunnel down", "Disconnected: $reason")
        stopSelf()
    }

    private fun clearAlert() {
        try {
            getSystemService(NotificationManager::class.java)?.cancel(ALERT_NOTIFICATION_ID)
        } catch (ignored: Exception) {
            // Best effort: a stale alert is cosmetic, never fatal.
        }
    }

    /**
     * Failure alert on channel "aip-alerts" (created if missing,
     * IMPORTANCE_HIGH). Used on restoreLease failure and unexpected
     * teardown. Never includes secrets, only the error summary.
     */
    private fun postAlert(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "AIP VPN alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val full = text
        // Collapsed notifications ellipsize after ~2 lines, which is exactly
        // what hid the rx= counters. Keep the collapsed line short, expandable
        // to the full trace on tap/expand.
        val short = if (full.length > 120) full.take(117) + "..." else full
        val notification: Notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(short)
            .setStyle(Notification.BigTextStyle().bigText(full))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(openAppIntent())
            .build()
        try {
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (security: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+): alert dropped,
            // tunnel state handling above still applies.
        }
    }

    private fun bringDown() {
        stopMonitor()
        val activeBackend = backend
        val activeTunnel = tunnel
        backend = null
        tunnel = null
        // Preserve the stored failure reason: bringDown also runs on the way
        // out after a failure (stopSelf -> onDestroy), and must not wipe it.
        val prefs = getSharedPreferences(STATE_PREFS, MODE_PRIVATE)
        val keepServer = activeServerId ?: prefs.getString(STATE_SERVER_ID, null)
        val keepError = prefs.getString(STATE_LAST_ERROR, null)
        writeState(false, keepServer, keepError)
        activeServerId = null
        activeServerName = ""
        activeAllowLan = false
        connectedSinceMs = 0L
        // User disconnect: zero residue. Drop the mandatory notice now and
        // clear any stale problem alert; success itself stays silent.
        stopForegroundNotice()
        clearAlert()
        if (activeBackend != null && activeTunnel != null) {
            Thread {
                try {
                    activeBackend.setState(activeTunnel, Tunnel.State.DOWN, null)
                } catch (ignored: Exception) {
                    // Best-effort teardown; nothing secret to report.
                }
            }.start()
        }
    }
}
