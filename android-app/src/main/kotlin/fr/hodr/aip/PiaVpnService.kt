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
        const val ACTION_DISCONNECT = "pia.app.action.DISCONNECT"

        const val EXTRA_PEER_IP = "peerIp"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_SERVER_PORT = "serverPort"
        const val EXTRA_SERVER_KEY = "serverKey"
        const val EXTRA_DNS_SERVERS = "dnsServersCsv"
        const val EXTRA_ALLOW_LAN = "allowLan"

        private const val CHANNEL_ID = "pia-vpn"
        private const val NOTIFICATION_ID = 1
        private const val TUNNEL_NAME = "pia"

        fun startAction(
            context: Context,
            peerIp: String,
            endpoint: String,
            serverPort: Int,
            serverKey: String,
            dnsServersCsv: String,
            allowLan: Boolean = false,
        ) {
            val intent = Intent(context, PiaVpnService::class.java).apply {
                putExtra(EXTRA_PEER_IP, peerIp)
                putExtra(EXTRA_ENDPOINT, endpoint)
                putExtra(EXTRA_SERVER_PORT, serverPort)
                putExtra(EXTRA_SERVER_KEY, serverKey)
                putExtra(EXTRA_DNS_SERVERS, dnsServersCsv)
                putExtra(EXTRA_ALLOW_LAN, allowLan)
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
        if (peerIp.isNullOrEmpty() || endpoint.isNullOrEmpty() ||
            serverKey.isNullOrEmpty() || serverPort <= 0 || serverPort > 65535
        ) {
            // No lease in the intent (boot with Always-on, system restart):
            // rebuild it from the stored account and last server.
            goForeground()
            Thread { restoreLease() }.start()
            return START_STICKY
        }
        goForeground()
        Thread {
            bringUp(peerIp, endpoint, serverPort, serverKey, dnsServersCsv)
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bringDown()
        super.onDestroy()
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "PIA VPN",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PIA VPN")
            .setContentText("Tunnel active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
    private fun restoreLease() {
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
            )
        } catch (error: Exception) {
            // Offline at boot or stale account: stop, the system retries.
            stopSelf()
        }
    }

    private fun bringUp(
        peerIp: String,
        endpoint: String,
        serverPort: Int,
        serverKey: String,
        dnsServersCsv: String,
    ) {
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
            val config = Config.Builder()
                .setInterface(ifaceBuilder.build())
                .addPeer(
                    Peer.Builder()
                        .setPublicKey(Key.fromBase64(serverKey))
                        .setEndpoint(InetEndpoint.parse("$endpoint:$serverPort"))
                        .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                        .addAllowedIp(InetNetwork.parse("::/0"))
                        .build(),
                )
                .build()
            activeBackend.setState(activeTunnel, Tunnel.State.UP, config)
        } catch (error: Exception) {
            try {
                activeBackend.setState(activeTunnel, Tunnel.State.DOWN, null)
            } catch (ignored: Exception) {
                // Best-effort teardown; nothing secret to report.
            }
            backend = null
            tunnel = null
            stopSelf()
        }
    }

    private fun bringDown() {
        val activeBackend = backend
        val activeTunnel = tunnel
        backend = null
        tunnel = null
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
