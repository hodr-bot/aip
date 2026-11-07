package pia.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import pia.core.Clock
import pia.core.HttpPort
import pia.core.LeaseResult
import pia.core.PiaClient
import pia.core.ServerCatalog
import pia.core.ServerEntry

/** Login + server list + connect UI. All core calls run off the main thread. */
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

    private lateinit var usernameField: TextInputEditText
    private lateinit var passwordField: TextInputEditText
    private lateinit var loginStatus: TextView
    private lateinit var serverAdapter: ArrayAdapter<String>
    private lateinit var serverSpinner: Spinner
    private lateinit var selectedStatus: TextView
    private lateinit var connectStatus: TextView

    @Volatile
    private var pendingLease: LeaseResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStore = PrefsTokenStore(this)
        setContentView(R.layout.activity_main)

        usernameField = findViewById(R.id.username)
        passwordField = findViewById(R.id.password)
        val loginButton: View = findViewById(R.id.login)
        loginStatus = findViewById(R.id.loginStatus)

        serverAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        serverAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        serverSpinner = findViewById(R.id.servers)
        serverSpinner.adapter = serverAdapter
        selectedStatus = findViewById(R.id.selectedStatus)
        val connectButton: View = findViewById(R.id.connect)
        val disconnectButton: View = findViewById(R.id.disconnect)
        val refreshButton: View = findViewById(R.id.refresh)
        connectStatus = findViewById(R.id.connStatus)

        refreshButton.setOnClickListener { refreshServers() }

        disconnectButton.setOnClickListener {
            val intent = Intent(this, PiaVpnService::class.java).apply {
                action = PiaVpnService.ACTION_DISCONNECT
            }
            startService(intent)
            pendingLease = null
            connectStatus.text = "Disconnected"
        }

        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val entry = servers.getOrNull(position)
                selected = entry
                selectedStatus.text = if (entry == null) {
                    "No server selected"
                } else {
                    "Selected: ${entry.id}"
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selected = null
                selectedStatus.text = "No server selected"
            }
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
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        loginStatus.text = "Login failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }.start()
        }

        connectButton.setOnClickListener {
            val entry = selected
            if (entry == null) {
                connectStatus.text = "Select a server first"
                return@setOnClickListener
            }
            connectStatus.text = "Connecting..."
            Thread {
                try {
                    val token = client.ensureValidToken(tokenStore)
                    val remainingHours = token.remainingSec(clock.nowEpochSec()) / 3600
                    runOnUiThread {
                        loginStatus.text = "Token valid, remaining ~${remainingHours}h"
                    }
                    val keyPair = WgKeys.loadOrGenerate(this@MainActivity)
                    val publicKey = keyPair.publicKey.toBase64()
                    val result = client.addKey(entry.hostname, token, publicKey)
                    pendingLease = result
                    tokenStore.saveLastHost(entry.hostname)
                    runOnUiThread {
                        connectStatus.text =
                            "Peer IP: ${result.lease.peerIp} Endpoint: ${result.lease.endpoint}"
                        val prepareIntent = VpnService.prepare(this@MainActivity)
                        if (prepareIntent != null) {
                            startActivityForResult(prepareIntent, REQUEST_VPN_PERMISSION)
                        } else {
                            launchVpn(result)
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        connectStatus.text =
                            "Connect failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }.start()
        }

        refreshServers()
        restoreSession()
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
            selectedStatus.text = "Loading servers..."
        }
        Thread {
            try {
                val fetched = catalog.fetchServers()
                servers = fetched
                val labels = fetched.map { "${it.id} (${it.hostname})" }
                runOnUiThread {
                    serverAdapter.clear()
                    serverAdapter.addAll(labels)
                    serverAdapter.notifyDataSetChanged()
                    selectedStatus.text = if (fetched.isEmpty()) {
                        "No servers"
                    } else {
                        "Tap a server to select"
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    selectedStatus.text =
                        "Server list failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_PERMISSION) {
            return
        }
        if (resultCode != RESULT_OK) {
            connectStatus.text = "VPN permission denied"
            pendingLease = null
            return
        }
        val lease = pendingLease
        if (lease == null) {
            connectStatus.text = "No pending lease"
            return
        }
        launchVpn(lease)
    }

    private fun launchVpn(result: LeaseResult) {
        val split = splitEndpoint(result.lease.endpoint)
        if (split == null) {
            connectStatus.text = "Connect failed: bad endpoint"
            return
        }
        val (host, port) = split
        PiaVpnService.startAction(
            this,
            peerIp = result.lease.peerIp,
            endpoint = host,
            serverPort = port,
            serverKey = result.lease.serverPublicKey,
            dnsServersCsv = result.dnsServers.joinToString(","),
        )
        pendingLease = null
        connectStatus.text =
            "Peer IP: ${result.lease.peerIp} Endpoint: ${result.lease.endpoint}"
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
        private const val REQUEST_VPN_PERMISSION = 100
    }
}
