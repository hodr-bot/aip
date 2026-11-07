package fr.hodr.aip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
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

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        // Drop the legacy "PIA VPN" channel name; the mandatory sticky
        // notification now reads "AIP". Android forces a foreground service
        // to show one while the tunnel runs, so it stays minimal and silent.
        manager?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AIP",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AIP")
            .setContentText("VPN active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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
            writeState(false, store.loadLastServerId())
            postAlert("AIP tunnel down", "Restore failed: ${error.message ?: error.javaClass.simpleName}")
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
                .parseAddresses("$peerIp/32")
            if (dnsServersCsv.isNotBlank()) {
                ifaceBuilder.parseDnsServers(dnsServersCsv)
            }
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
            if (verifyTraffic()) {
                writeState(true, serverId)
            } else {
                try {
                    activeBackend.setState(activeTunnel, Tunnel.State.DOWN, null)
                } catch (ignored: Exception) {
                    // Best-effort teardown; nothing secret to report.
                }
                backend = null
                tunnel = null
                writeState(false, serverId)
                postAlert(
                    "AIP tunnel down",
                    "Connected but no traffic: the tunnel came up yet nothing flows through it",
                )
                stopSelf()
            }
        } catch (error: Exception) {
            try {
                activeBackend.setState(activeTunnel, Tunnel.State.DOWN, null)
            } catch (ignored: Exception) {
                // Best-effort teardown; nothing secret to report.
            }
            backend = null
            tunnel = null
            writeState(false, serverId)
            postAlert("AIP tunnel down", "Unexpected teardown: ${error.message ?: error.javaClass.simpleName}")
            stopSelf()
        }
    }

    /**
     * Prove real traffic flows through the tunnel: a small HEAD request to
     * the PIA site (DNS + TCP + TLS through the TUN interface). Two tries,
     * short timeouts. False means "tunnel up, internet dead".
     */
    private fun verifyTraffic(): Boolean {
        repeat(2) {
            var connection: HttpsURLConnection? = null
            try {
                val url = java.net.URL("https://www.privateinternetaccess.com/")
                connection = url.openConnection() as HttpsURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.instanceFollowRedirects = false
                if (connection.responseCode in 200..399) {
                    return true
                }
            } catch (ignored: Exception) {
                // Try once more, then report no traffic.
            } finally {
                connection?.disconnect()
            }
        }
        return false
    }

    private fun writeState(connected: Boolean, serverId: String?) {
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putBoolean(STATE_CONNECTED, connected)
            .putString(STATE_SERVER_ID, serverId)
            .apply()
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
                    "AIP alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val notification: Notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
        try {
            manager.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (security: SecurityException) {
            // POST_NOTIFICATIONS not granted (Android 13+): alert dropped,
            // tunnel state handling above still applies.
        }
    }

    private fun bringDown() {
        val activeBackend = backend
        val activeTunnel = tunnel
        backend = null
        tunnel = null
        writeState(false, activeServerId)
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
