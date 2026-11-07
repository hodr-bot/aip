package fr.hodr.aip

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger
import pia.core.Clock
import pia.core.HttpPort
import pia.core.LeaseResult
import pia.core.PiaClient
import pia.core.ServerCatalog
import pia.core.ServerEntry

/**
 * Login gating + server pick + connect UI. All core calls run off the main thread.
 *
 * The login view is shown only when no password is stored, when the launch
 * intent carries [EXTRA_AUTH_FAILED], or after tapping "Switch account".
 * Otherwise the main view is shown directly. VPN state (connected/serverId)
 * lives in SharedPreferences file "pia-vpn-state" so the connect button and
 * the status text stay accurate across rotations and restarts.
 */
class MainActivity : Activity() {
    private val http: HttpPort = HttpUrlConnectionPort()
    private val clock: Clock = SystemClock()
    private val client = PiaClient(http, clock)
    private val catalog = ServerCatalog(http)
    private lateinit var tokenStore: PrefsTokenStore

    @Volatile
    private var servers: List<ServerEntry> = emptyList()

    @Volatile
    private var selected: ServerEntry? = null

    private lateinit var loginContainer: View
    private lateinit var mainContainer: View
    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginStatus: TextView
    private lateinit var selectedStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var connectStatus: TextView
    private lateinit var errorDetail: TextView
    private lateinit var connectProgress: ProgressBar
    private lateinit var allowLanBox: CheckBox

    @Volatile
    private var connecting: Boolean = false

    @Volatile
    private var pendingLease: LeaseResult? = null

    @Volatile
    private var pendingServer: ServerEntry? = null

    private var stateListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TlsTrust.init(this)
        tokenStore = PrefsTokenStore(this)
        setContentView(R.layout.activity_main)

        loginContainer = findViewById(R.id.loginContainer)
        mainContainer = findViewById(R.id.mainContainer)
        usernameField = findViewById(R.id.username)
        passwordField = findViewById(R.id.password)
        val loginButton: View = findViewById(R.id.login)
        loginStatus = findViewById(R.id.loginStatus)

        val pickServerButton: View = findViewById(R.id.pickServer)
        selectedStatus = findViewById(R.id.selectedStatus)
        connectButton = findViewById(R.id.connect)
        val refreshButton: View = findViewById(R.id.refresh)
        val switchAccountButton: View = findViewById(R.id.switchAccount)
        val vpnSettingsButton: View = findViewById(R.id.vpnSettings)
        vpnSettingsButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
            } catch (error: Exception) {
                connectStatus.text =
                    "VPN settings unavailable: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        connectStatus = findViewById(R.id.connStatus)
        errorDetail = findViewById(R.id.errorDetail)
        connectProgress = findViewById(R.id.connectProgress)
        allowLanBox = findViewById(R.id.allowLan)
        findViewById<TextView>(R.id.appVersion).text =
            "v" + try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (error: Exception) {
                "?"
            }

        allowLanBox.isChecked = piaPrefs().getBoolean(KEY_ALLOW_LAN, false)
        allowLanBox.setOnCheckedChangeListener { _, checked ->
            piaPrefs().edit().putBoolean(KEY_ALLOW_LAN, checked).apply()
        }

        ensureAlertChannel()
        requestNotificationPermission()

        refreshButton.setOnClickListener { refreshServers() }

        pickServerButton.setOnClickListener {
            startActivityForResult(
                Intent(this, ServerListActivity::class.java),
                REQUEST_PICK_SERVER,
            )
        }

        switchAccountButton.setOnClickListener {
            showLoginView(true)
        }

        loginButton.setOnClickListener {
            val username = usernameField.text.toString()
            val password = passwordField.text.toString()
            loginStatus.text = "Logging in..."
            Thread {
                try {
                    val token = client.login(username, password)
                    tokenStore.saveCredentials(username, password)
                    tokenStore.saveToken(token)
                    val remainingHours = token.remainingSec(clock.nowEpochSec()) / 3600
                    runOnUiThread {
                        loginStatus.text = "Token valid, remaining ~${remainingHours}h"
                        showLoginView(false)
                    }
                } catch (error: Exception) {
                    val message = error.message ?: error.javaClass.simpleName
                    tokenStore.clearCredentials()
                    runOnUiThread {
                        loginStatus.text = "Login failed: $message"
                        showLoginView(true)
                    }
                    notifyAlert("Login failed", message)
                }
            }.start()
        }

        connectButton.setOnClickListener {
            // Hero toggles, and switches straight to a newly picked server:
            // same server -> disconnect, other server picked -> move over.
            val (live, liveServer, liveAt) = readVpnState()
            val liveConnected = live && (System.currentTimeMillis() - liveAt < PiaVpnService.ALIVE_FRESH_MS)
            val entry = selected
            if (entry == null) {
                connectStatus.text = "Select a server first"
                return@setOnClickListener
            }
            if (liveConnected && !liveServer.isNullOrEmpty()) {
                if (entry.id == liveServer) {
                    doDisconnect()
                    return@setOnClickListener
                }
                switchServer(entry)
                return@setOnClickListener
            }
            connectTo(entry)
        }

        val authFailed = intent?.getBooleanExtra(EXTRA_AUTH_FAILED, false) ?: false
        showLoginView(tokenStore.loadPassword() == null || authFailed)
        refreshServers()
        restoreSession()
        // Live state: the service writes pia-vpn-state from a worker thread the
        // moment it proves or fails traffic, while the old UI only refreshed on
        // onResume or after a fixed 30s delay. That gap is exactly why the
        // notification (posted instantly) arrived before the app caught up.
        // Listen for every state write and refresh immediately.
        val prefs = getSharedPreferences(PREFS_STATE, MODE_PRIVATE)
        stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            runOnUiThread {
                val (stored, _, aliveAt) = readVpnState()
                val fresh = stored &&
                    (System.currentTimeMillis() - aliveAt < PiaVpnService.ALIVE_FRESH_MS)
                if (fresh || readLastError() != null) {
                    setConnecting(false)
                }
                updateConnectState()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(stateListener)
        updateConnectState()
    }

    /** Shared connect path: token, addKey, permission, launch. */
    private fun connectTo(entry: ServerEntry) {
            setConnecting(true)
            connectStatus.text = "Contacting PIA..."
            Thread {
                try {
                    val token = try {
                        client.ensureValidToken(tokenStore)
                    } catch (authError: Exception) {
                        val message = authError.message ?: authError.javaClass.simpleName
                        runOnUiThread {
                            setConnecting(false)
                            pendingLease = null
                            pendingServer = null
                            connectStatus.text = "Login failed: $message"
                            tokenStore.clearCredentials()
                            showLoginView(true)
                        }
                        notifyAlert("Login failed", message)
                        return@Thread
                    }
                    val remainingHours = token.remainingSec(clock.nowEpochSec()) / 3600
                    runOnUiThread {
                        loginStatus.text = "Token valid, remaining ~${remainingHours}h"
                    }
                    val keyPair = WgKeys.loadOrGenerate(this@MainActivity)
                    val publicKey = keyPair.publicKey.toBase64()
                    runOnUiThread { connectStatus.text = "Requesting key..." }
                    val result = client.addKey(entry.hostname, token, publicKey)
                    pendingLease = result
                    pendingServer = entry
                    tokenStore.saveLastHost(entry.hostname)
                    tokenStore.saveLastServerId(entry.id)
                    runOnUiThread {
                        val prepareIntent = VpnService.prepare(this@MainActivity)
                        if (prepareIntent != null) {
                            connectStatus.text = "Waiting for VPN permission..."
                            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
                        } else {
                            launchVpn(result, entry)
                        }
                    }
                } catch (error: Exception) {
                    val message = error.message ?: error.javaClass.simpleName
                    runOnUiThread {
                        setConnecting(false)
                        pendingLease = null
                        pendingServer = null
                        connectStatus.text = "Connect failed: $message"
                    }
                    notifyAlert("Connect failed", message)
                }
            }.start()
    }

    /** Switch server in one tap: tear the old tunnel down, connect the new. */
    private fun switchServer(entry: ServerEntry) {
        try {
            val intent = Intent(this, PiaVpnService::class.java).apply {
                action = PiaVpnService.ACTION_DISCONNECT
            }
            startService(intent)
        } catch (ignored: Exception) {
        }
        pendingLease = null
        pendingServer = null
        connectTo(entry)
    }

    override fun onDestroy() {
        try {
            stateListener?.let {
                getSharedPreferences(PREFS_STATE, MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(it)
            }
        } catch (ignored: Exception) {
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        updateConnectState()
    }

    private fun showLoginView(show: Boolean) {
        loginContainer.visibility = if (show) View.VISIBLE else View.GONE
        mainContainer.visibility = if (show) View.GONE else View.VISIBLE
        if (!show) {
            updateConnectState()
        }
    }

    private fun restoreSession() {
        val savedUsername = tokenStore.loadUsername()
        if (savedUsername != null) {
            usernameField.setText(savedUsername)
        }
        val cached = tokenStore.loadToken() ?: return
        val remainingHours = cached.remainingSec(clock.nowEpochSec()) / 3600
        if (remainingHours > 0) {
            loginStatus.text = "Token restored, remaining ~${remainingHours}h"
        }
    }

    private fun refreshServers() {
        runOnUiThread {
            if (selected == null) {
                selectedStatus.text = "Loading servers..."
            }
        }
        Thread {
            try {
                val fetched = catalog.fetchServers()
                servers = fetched
                runOnUiThread {
                    if (selected == null) {
                        // Preselect the last used server so one tap connects.
                        val lastId = tokenStore.loadLastServerId()
                        val last = fetched.firstOrNull { it.id == lastId }
                        if (last != null) {
                            selected = last
                            updateConnectState()
                        }
                    }
                    selectedStatus.text = when {
                        fetched.isEmpty() -> "No servers"
                        selected != null -> "Selected: ${ServerLabels.label(selected!!)}"
                        else -> "Tap Choose server to select"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (selected == null) {
                        selectedStatus.text =
                            "Server list failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_PICK_SERVER -> {
                if (resultCode != RESULT_OK || data == null) {
                    return
                }
                val id = data.getStringExtra(ServerListActivity.EXTRA_SERVER_ID) ?: return
                val hostname = data.getStringExtra(ServerListActivity.EXTRA_SERVER_HOSTNAME)
                    ?: return
                val title = data.getStringExtra(ServerListActivity.EXTRA_SERVER_TITLE).orEmpty()
                val virtual = data.getBooleanExtra(ServerListActivity.EXTRA_SERVER_VIRTUAL, false)
                val streaming =
                    data.getBooleanExtra(ServerListActivity.EXTRA_SERVER_STREAMING, false)
                selected = ServerEntry(
                    id = id,
                    hostname = hostname,
                    displayName = title,
                    virtual = virtual,
                    streaming = streaming,
                )
                selectedStatus.text = "Selected: ${ServerLabels.label(selected!!)}"
                updateConnectState()
            }
            REQUEST_VPN_PERMISSION -> {
                if (resultCode != RESULT_OK) {
                    setConnecting(false)
                    connectStatus.text = "VPN permission denied"
                    pendingLease = null
                    pendingServer = null
                    return
                }
                val lease = pendingLease
                val server = pendingServer
                if (lease == null || server == null) {
                    setConnecting(false)
                    connectStatus.text = "No pending lease"
                    return
                }
                launchVpn(lease, server)
            }
        }
    }

    private fun launchVpn(result: LeaseResult, server: ServerEntry) {
        val split = splitEndpoint(result.lease.endpoint)
        if (split == null) {
            setConnecting(false)
            connectStatus.text = "Connect failed: bad endpoint"
            notifyAlert("Connect failed", "bad endpoint for ${server.id}")
            return
        }
        val (host, port) = split
        val allowLan = allowLanBox.isChecked
        // Resolve the probe host NOW on the plain network, before the tunnel
        // claims DNS. These IPs ride inside the tunnel (TCP+TLS with SNI),
        // so a dead tunnel-DNS can no longer fake a dead tunnel. This is the
        // IP cache: fresh every connect, never stale across server changes.
        connectStatus.text = "Connecting... verifying traffic"
        Thread {
            val probeIps = try {
                java.net.InetAddress.getAllByName("www.google.com")
                    .filterIsInstance<java.net.Inet4Address>()
                    .take(4).joinToString(",") { it.hostAddress ?: "" }
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .joinToString(",")
            } catch (error: Exception) {
                ""
            }
            runOnUiThread {
                PiaVpnService.startAction(
                    this,
                    peerIp = result.lease.peerIp,
                    endpoint = host,
                    serverPort = port,
                    serverKey = result.lease.serverPublicKey,
                    dnsServersCsv = result.dnsServers.joinToString(","),
                    allowLan = allowLan,
                    serverId = server.id,
                    probeIpsCsv = probeIps,
                )
            }
        }.apply { isDaemon = true }.start()
        pendingLease = null
        pendingServer = null
        // The service writes pia-vpn-state itself once real traffic is proven.
        // Never claim "connected" before that: a tunnel without traffic is a failure.
        getSharedPreferences(PREFS_STATE, MODE_PRIVATE).edit().remove(KEY_LAST_ERROR).apply()
        connectStatus.text = "Connecting... verifying traffic"
        // Safety net behind the prefs listener above: poll every second so a
        // missed write still surfaces within ~1s, never 30s. Stops early on a
        // terminal state (connected fresh or lastError set) or after 30s.
        Thread {
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && connecting) {
                try {
                    Thread.sleep(1_000)
                } catch (interrupted: InterruptedException) {
                    break
                }
                val (stored, _, aliveAt) = readVpnState()
                val fresh = stored &&
                    (System.currentTimeMillis() - aliveAt < PiaVpnService.ALIVE_FRESH_MS)
                val failed = readLastError() != null
                runOnUiThread { updateConnectState() }
                if (fresh || failed) {
                    runOnUiThread {
                        setConnecting(false)
                        updateConnectState()
                    }
                    break
                }
            }
            runOnUiThread {
                setConnecting(false)
                updateConnectState()
            }
        }.apply { isDaemon = true }.start()
        updateConnectState()
    }

    private fun setConnecting(active: Boolean) {
        connecting = active
        connectProgress.visibility = if (active) View.VISIBLE else View.GONE
    }

    /** Hero button + status pill follow the persisted vpn state. */
    private fun updateConnectState() {
        val (stored, serverId, aliveAt) = readVpnState()
        // The service heartbeats every 5s. A "connected" flag with a stale
        // heartbeat means the process died without cleanup: say so honestly
        // instead of showing a fake "Connecté".
        val connected = stored && (System.currentTimeMillis() - aliveAt < PiaVpnService.ALIVE_FRESH_MS)
        val current = selected
        val connectedTitle = serverId?.let { id ->
            servers.firstOrNull { it.id == id }?.let { ServerLabels.label(it) }
        } ?: serverId
        when {
            !connected || serverId.isNullOrEmpty() -> {
                if (!connecting) {
                    connectStatus.text = "Disconnected"
                    errorDetail.text = readLastError().orEmpty()
                } else {
                    connectStatus.text = "Connecting…"
                    errorDetail.text = ""
                }
                connectStatus.setBackgroundResource(R.drawable.pill_busy.takeIf { connecting } ?: R.drawable.pill_off)
                connectButton.isEnabled = current != null && !connecting
                connectButton.text = "CONNECT"
                connectButton.setBackgroundResource(R.drawable.hero_off)
                connectButton.setTextColor(getColor(R.color.pia_accent))
            }
            current?.id == serverId -> {
                if (!connecting) {
                    connectStatus.text = "Connected"
                    errorDetail.text = readLastError().orEmpty()
                } else {
                    connectStatus.text = "Connecting…"
                    errorDetail.text = ""
                }
                connectStatus.setBackgroundResource(R.drawable.pill_busy.takeIf { connecting } ?: R.drawable.pill_on)
                connectButton.isEnabled = true
                connectButton.text = "DISCONNECT"
                connectButton.setBackgroundResource(R.drawable.hero_on)
                connectButton.setTextColor(getColor(R.color.pia_background))
            }
            else -> {
                if (!connecting) {
                    connectStatus.text = "Connected"
                    errorDetail.text = "$connectedTitle (other server picked — tap to switch)"
                } else {
                    connectStatus.text = "Connecting…"
                    errorDetail.text = ""
                }
                connectStatus.setBackgroundResource(R.drawable.pill_busy.takeIf { connecting } ?: R.drawable.pill_on)
                connectButton.isEnabled = current != null && !connecting
                connectButton.text = "CONNECT"
                connectButton.setBackgroundResource(R.drawable.hero_off)
                connectButton.setTextColor(getColor(R.color.pia_accent))
            }
        }
    }

    /** Single disconnect path: hero toggle and options button share it. */
    private fun doDisconnect() {
        val intent = Intent(this, PiaVpnService::class.java).apply {
            action = PiaVpnService.ACTION_DISCONNECT
        }
        startService(intent)
        pendingLease = null
        pendingServer = null
        setConnecting(false)
        val (_, lastServerId) = readVpnState()
        writeVpnState(false, lastServerId)
        connectStatus.text = "Disconnected"
        errorDetail.text = ""
        updateConnectState()
    }

    private fun piaPrefs() = getSharedPreferences(PREFS_UI, MODE_PRIVATE)

    private fun readVpnState(): Triple<Boolean, String?, Long> {
        val prefs = getSharedPreferences(PREFS_STATE, MODE_PRIVATE)
        return Triple(
            prefs.getBoolean(KEY_CONNECTED, false),
            prefs.getString(KEY_SERVER_ID, null),
            prefs.getLong(KEY_ALIVE_AT, 0L),
        )
    }

    private fun readLastError(): String? =
        getSharedPreferences(PREFS_STATE, MODE_PRIVATE).getString(KEY_LAST_ERROR, null)

    private fun writeVpnState(connected: Boolean, serverId: String?) {
        getSharedPreferences(PREFS_STATE, MODE_PRIVATE).edit()
            .putBoolean(KEY_CONNECTED, connected)
            .putString(KEY_SERVER_ID, serverId)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    private fun ensureAlertChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "PIA alerts",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    /** Alert notifications are posted only on problems (login/connect failures). */
    private fun notifyAlert(title: String, message: String) {
        ensureAlertChannel()
        val notification: Notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
        getSystemService(NotificationManager::class.java)
            ?.notify(alertId.incrementAndGet(), notification)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT > 32) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATIONS,
                )
            }
        }
    }

    private fun splitEndpoint(endpoint: String): Pair<String, Int>? {
        val slash = endpoint.lastIndexOf(':')
        if (slash <= 0 || slash == endpoint.length - 1) {
            return null
        }
        val host = endpoint.substring(0, slash)
        val port = endpoint.substring(slash + 1).toIntOrNull() ?: return null
        if (port <= 0 || port > 65535) {
            return null
        }
        return host to port
    }

    companion object {
        /** Launch MainActivity with this extra true to force the login view. */
        const val EXTRA_AUTH_FAILED = "authFailed"

        private const val REQUEST_VPN_PERMISSION = 100
        private const val REQUEST_PICK_SERVER = 101
        private const val REQUEST_NOTIFICATIONS = 102

        private const val PREFS_STATE = "pia-vpn-state"
        private const val KEY_CONNECTED = "connected"
        private const val KEY_SERVER_ID = "serverId"
        private const val KEY_LAST_ERROR = "lastError"
        private const val KEY_ALIVE_AT = "aliveAt"

        private const val PREFS_UI = "pia-prefs"
        private const val KEY_ALLOW_LAN = "allowLan"

        private const val ALERT_CHANNEL_ID = "aip-alerts"

        private val alertId = AtomicInteger(100)
    }
}
