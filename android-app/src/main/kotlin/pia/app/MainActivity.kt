package pia.app

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import pia.core.AuthToken
import pia.core.Clock
import pia.core.HttpPort
import pia.core.PiaClient
import pia.core.ServerCatalog
import pia.core.ServerEntry

/** OkHttp-free [HttpPort] backed by java.net.HttpURLConnection. No secrets logged. */
private class HttpUrlConnectionPort : HttpPort {
    override fun postForm(url: String, fields: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = fields.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            conn.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    override fun get(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            for ((key, value) in headers) {
                conn.setRequestProperty(key, value)
            }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            ?: throw java.io.IOException("HTTP $code with empty body")
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            val body = reader.readText()
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code")
            }
            return body
        }
    }
}

private class SystemClock : Clock {
    override fun nowEpochSec(): Long = System.currentTimeMillis() / 1000L
}

/**
 * Step 2 UI only, no tunnel yet.
 * Vertical LinearLayout built in code: username/password fields, Login button
 * (CORE [PiaClient.login]), server [ListView] (CORE [ServerCatalog.fetchServers]),
 * Connect button (CORE [PiaClient.addKey]). All core calls off the main thread.
 */
class MainActivity : Activity() {
    private val http: HttpPort = HttpUrlConnectionPort()
    private val clock: Clock = SystemClock()
    private val client = PiaClient(http, clock)
    private val catalog = ServerCatalog(http)

    @Volatile
    private var authToken: AuthToken? = null

    @Volatile
    private var servers: List<ServerEntry> = emptyList()

    @Volatile
    private var selected: ServerEntry? = null

    private lateinit var usernameField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginStatus: TextView
    private lateinit var serverAdapter: ArrayAdapter<String>
    private lateinit var serverList: ListView
    private lateinit var selectedStatus: TextView
    private lateinit var connectStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        usernameField = EditText(this).apply {
            hint = "Username"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        passwordField = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val loginButton = Button(this).apply { text = "Login" }
        loginStatus = TextView(this).apply { text = "Not logged in" }

        serverAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        serverList = ListView(this).apply {
            adapter = serverAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        selectedStatus = TextView(this).apply { text = "No server selected" }
        val connectButton = Button(this).apply { text = "Connect" }
        connectStatus = TextView(this).apply { text = "Not connected (UI only, no tunnel)" }

        layout.addView(usernameField)
        layout.addView(passwordField)
        layout.addView(loginButton)
        layout.addView(loginStatus)
        layout.addView(serverList)
        layout.addView(selectedStatus)
        layout.addView(connectButton)
        layout.addView(connectStatus)
        setContentView(layout)

        serverList.setOnItemClickListener { _, _, position, _ ->
            val entry = servers.getOrNull(position)
            selected = entry
            runOnUiThread {
                selectedStatus.text = if (entry == null) {
                    "No server selected"
                } else {
                    "Selected: ${entry.id}"
                }
            }
        }

        loginButton.setOnClickListener {
            val username = usernameField.text.toString()
            val password = passwordField.text.toString()
            loginStatus.text = "Logging in..."
            Thread {
                try {
                    val token = client.login(username, password)
                    authToken = token
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
            val token = authToken
            if (entry == null) {
                connectStatus.text = "Select a server first"
                return@setOnClickListener
            }
            if (token == null) {
                connectStatus.text = "Login first"
                return@setOnClickListener
            }
            connectStatus.text = "Connecting..."
            Thread {
                try {
                    val publicKey = generateEphemeralPublicKey()
                    val result = client.addKey(entry.hostname, token, publicKey)
                    runOnUiThread {
                        connectStatus.text =
                            "Peer IP: ${result.lease.peerIp} Endpoint: ${result.lease.endpoint}"
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

    /** Ephemeral placeholder public key for the UI-only addKey call; real keypair lands with the tunnel step. */
    private fun generateEphemeralPublicKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
