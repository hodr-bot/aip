package fr.hodr.aip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

        private const val CHANNEL_ID = "aip-vpn"
        private const val LEGACY_CHANNEL_ID = "pia-vpn"
        private const val ALERT_CHANNEL_ID = "aip-alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val TUNNEL_NAME = "aip"

        private const val STATE_PREFS = "pia-vpn-state"
        private const val STATE_CONNECTED = "connected"
        private const val STATE_SERVER_ID = "serverId"
        private const val STATE_LAST_ERROR = "lastError"
        private const val STATE_ALIVE_AT = "aliveAt"

        /** Freshness window: the service heartbeats every 5s. */
        const val ALIVE_FRESH_MS = 20_000L
        const val FALLBACK_DNS = "10.0.0.243"

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
            allowLan: Boolean = false,
            serverId: String? = null,
        ) {
            val intent = Intent(context, PiaVpnService::class.java).apply {
                putExtra(EXTRA_PEER_IP, peerIp)
                putExtra(EXTRA_ENDPOINT, endpoint)
                putExtra(EXTRA_SERVER_PORT, serverPort)
                putExtra(EXTRA_SERVER_KEY, serverKey)
                putExtra(EXTRA_DNS_SERVERS, dnsServersCsv)
                putExtra(EXTRA_ALLOW_LAN, allowLan)
                putExtra(EXTRA_SERVER_ID, serverId)
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
    private var monitor: Thread? = null

    @Volatile
    private var trafficStalled = false

    override fun onCreate() {
        super.onCreate()
        // A fresh process means no live tunnel: a previous process may have
        // been killed without onDestroy, leaving a stale "connected" behind.
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean(STATE_CONNECTED, false)
            .apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            clearAlert()
            bringDown()
            stopSelf()
            return START_NOT_STICKY
        }
        val peerIp = intent?.getStringExtra(EXTRA_PEER_IP)
        val endpoint = intent?.getStringExtra(EXTRA_ENDPOINT)
        val serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, -1) ?: -1
        val serverKey = intent?.getStringExtra(EXTRA_SERVER_KEY)
        val dnsServersCsv = intent?.getStringExtra(EXTRA_DNS_SERVERS).orEmpty()
        val allowLan = intent?.getBooleanExtra(EXTRA_ALLOW_LAN, false) ?: false
        val serverId = intent?.getStringExtra(EXTRA_SERVER_ID)
        if (peerIp.isNullOrEmpty() || endpoint.isNullOrEmpty() ||
            serverKey.isNullOrEmpty() || serverPort <= 0 || serverPort > 65535
        ) {
            // No lease in the intent (boot with Always-on, system restart):
            // rebuild it from the stored account and last server.
            goForeground()
            Thread { restoreLease(allowLan) }.start()
            return START_STICKY
        }
        goForeground()
        Thread {
            bringUp(peerIp, endpoint, serverPort, serverKey, dnsServersCsv, allowLan, serverId)
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
        // Drop the legacy "PIA VPN" channel name; the mandatory sticky
        // notification now reads "AIP VPN". Android forces a foreground service
        // to show one while the tunnel runs, so it stays minimal and silent.
        manager?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AIP VPN",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val openApp = openAppIntent()
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AIP VPN")
            .setContentText("VPN active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .build()
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
     * Rebuild a lease after a system restart (boot with Always-on):
     * stored credentials plus last server hostname give a fresh token
     * and a fresh addKey lease. Stops the service when nothing stored.
     */
    private fun restoreLease(allowLan: Boolean) {
        val store = PrefsTokenStore(this)
        val hostname = store.loadLastHost()
        if (hostname.isNullOrEmpty() || store.loadUsername() == null) {
            stopSelf()
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
            // Offline at boot or stale account: alert, then stop; the system retries.
            writeState(false, store.loadLastServerId(), error.message ?: error.javaClass.simpleName)
            postAlert("AIP VPN tunnel down", "Restore failed: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
        }
    }

    private fun bringUp(
        peerIp: String,
        endpoint: String,
        serverPort: Int,
        serverKey: String,
        dnsServersCsv: String,
        allowLan: Boolean,
        serverId: String?,
    ) {
        activeServerId = serverId
        val activeTunnel = PiaTunnel(TUNNEL_NAME)
        val activeBackend = GoBackend(this)
        tunnel = activeTunnel
        backend = activeBackend
        try {
            val ifaceBuilder = Interface.Builder()
                .setKeyPair(WgKeys.loadOrGenerate(this))
                .setMtu(TUNNEL_MTU)
                .parseAddresses("$peerIp/32")
            val dnsCsv = dnsServersCsv.ifBlank { FALLBACK_DNS }
            ifaceBuilder.parseDnsServers(dnsCsv)
            val peerBuilder = Peer.Builder()
                .setPublicKey(Key.fromBase64(serverKey))
                .setEndpoint(InetEndpoint.parse("$endpoint:$serverPort"))
            for (cidr in WireGuard.allowedIpCidrs(allowLan)) {
                peerBuilder.addAllowedIp(InetNetwork.parse(cidr))
            }
            val config = Config.Builder()
                .setInterface(ifaceBuilder.build())
                .addPeer(peerBuilder.build())
                .build()
            activeBackend.setState(activeTunnel, Tunnel.State.UP, config)
            // The tunnel is up, but that proves nothing about traffic: some
            // networks blackhole UDP or DNS. Only claim "connected" once real
            // traffic flows through the tunnel; otherwise tear down loudly.
            val (flows, trace) = verifyTraffic()
            if (flows) {
                trafficStalled = false
                writeState(true, serverId, null)
                clearAlert()
                startMonitor(activeBackend, activeTunnel)
            } else {
                failTraffic(activeBackend, activeTunnel, serverId, "no traffic flows ($trace)")
            }
        } catch (error: Exception) {
            try {
                activeBackend.setState(activeTunnel, Tunnel.State.DOWN, null)
            } catch (ignored: Exception) {
                // Best-effort teardown; nothing secret to report.
            }
            backend = null
            tunnel = null
            val reason = error.message ?: error.javaClass.simpleName
            writeState(false, serverId, reason)
            postAlert("AIP VPN tunnel down", "Disconnected: $reason")
            stopSelf()
        }
    }

    /**
     * Prove real traffic flows through the tunnel, with a stage trace so a
     * failure says WHERE it died (DNS, TCP, TLS/HTTP) instead of a bare
     * "no traffic". Returns success plus the trace; the caller stores the
     * trace as the error detail. Stages: explicit DNS, raw TCP to the first
     * IPv4:443 (no TLS/HTTP stack involved), then the full HTTPS GET.
     */
    private fun verifyTraffic(): Pair<Boolean, String> {
        val trace = StringBuilder()
        val host = "www.privateinternetaccess.com"
        // Stage 1: DNS through the tunnel.
        val dnsStart = SystemClock.elapsedRealtime()
        val addrs = try {
            java.net.InetAddress.getAllByName(host)
        } catch (error: Exception) {
            trace.append("dns FAIL(${error.javaClass.simpleName})")
            return false to trace.toString()
        }
        val v4count = addrs.count { it is java.net.Inet4Address }
        trace.append("dns ${v4count}v4+${addrs.size - v4count}v6 ${SystemClock.elapsedRealtime() - dnsStart}ms; ")
        // Stage 2: raw TCP to the first IPv4:443.
        val first4 = addrs.firstOrNull { it is java.net.Inet4Address }
        if (first4 == null) {
            trace.append("tcp4 no-v4-addr; ")
        } else {
            try {
                val tcpStart = SystemClock.elapsedRealtime()
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(first4, 443), 5_000)
                }
                trace.append("tcp4 ok ${SystemClock.elapsedRealtime() - tcpStart}ms; ")
            } catch (error: Exception) {
                trace.append("tcp4 FAIL(${error.javaClass.simpleName})")
                return false to trace.toString()
            }
        }
        // Stage 3: full HTTPS GET (TLS + HTTP + body through the TUN).
        var connection: HttpsURLConnection? = null
        try {
            val url = java.net.URL("https://$host/")
            connection = url.openConnection() as HttpsURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.responseCode
            val buffer = ByteArray(8192)
            var total = 0
            val input = connection.inputStream
            // 8KB cap: several full-size packets prove real browsing while
            // finishing fast on slow links; 64KB only made slow nets slower.
            while (total < 8192) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                total += read
            }
            if (total >= 1024) {
                trace.append("https ok ${total}b")
                return true to trace.toString()
            }
            trace.append("https empty-body(${total}b)")
        } catch (error: Exception) {
            trace.append("https FAIL(${error.javaClass.simpleName})")
        } finally {
            connection?.disconnect()
        }
        return false to trace.toString()
    }

    private fun writeState(connected: Boolean, serverId: String?, lastError: String?) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean(STATE_CONNECTED, connected)
            .putString(STATE_SERVER_ID, serverId)
            .putString(STATE_LAST_ERROR, lastError)
            .putLong(STATE_ALIVE_AT, System.currentTimeMillis())
            .apply()
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
    private fun startMonitor(activeBackend: GoBackend, activeTunnel: PiaTunnel) {
        stopMonitor()
        val watchedServer = activeServerId
        val thread = Thread {
            var lastVerify = System.currentTimeMillis()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(5_000)
                } catch (interrupted: InterruptedException) {
                    break
                }
                try {
                    writeAlive()
                    val now = System.currentTimeMillis()
                    if (now - lastVerify >= 60_000) {
                        lastVerify = now
                        if (!verifyTraffic().first) {
                            if (!trafficStalled) {
                                trafficStalled = true
                                writeState(
                                    true,
                                    watchedServer,
                                    "traffic stalled, WireGuard recovering",
                                )
                                postAlert(
                                    "AIP VPN tunnel stalled",
                                    "Traffic stalled, WireGuard recovering on its own",
                                )
                            }
                        } else if (trafficStalled) {
                            trafficStalled = false
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
        val notification: Notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (text.length > 140) text.take(137) + "..." else text)
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
