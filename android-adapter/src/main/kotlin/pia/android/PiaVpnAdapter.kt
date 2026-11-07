package pia.android

import pia.core.AuthToken
import pia.core.Clock
import pia.core.ConnectionState
import pia.core.HttpPort
import pia.core.KillSwitch
import pia.core.PiaAuthError
import pia.core.PiaClient
import pia.core.TokenStore
import pia.core.TrafficVerdict
import pia.core.WireGuard

/**
 * Platform VPN surface. The JVM keeps the behaviour pure while the real
 * Android service honours the same calls (tunnel set-up, fail-closed block).
 */
interface VpnBackend {
    fun installConf(conf: String)
    fun setBlocked(blocked: Boolean)
}

/** Minimal credential source; the platform keeps the secrets themselves. */
interface CredentialVault {
    val username: String?
    val password: String?
}

/**
 * Totally in-memory [TokenStore] for tests and for the adapter itself.
 * Favours clarity over persistence; platforms add their own store later.
 */
class InMemoryTokenStore : TokenStore {
    private var cachedUsername: String? = null
    private var cachedPassword: String? = null
    private var cachedToken: AuthToken? = null

    override fun loadUsername(): String? = cachedUsername

    override fun loadPassword(): String? = cachedPassword

    override fun saveCredentials(username: String, password: String) {
        cachedUsername = username
        cachedPassword = password
    }

    override fun loadToken(): AuthToken? = cachedToken

    override fun saveToken(token: AuthToken) {
        cachedToken = token
    }

    override fun clearToken() {
        cachedToken = null
    }
}

/** Successful connect outcome: rendered conf, fail-closed verdict, endpoint. */
data class ConnectPlan(
    val conf: String,
    val verdict: TrafficVerdict,
    val endpoint: String,
)

/**
 * Pure-JVM orchestrator over [PiaClient]. No real network here: everything
 * flows through [HttpPort], and the tunnel behaviour stays testable with
 * fakes. The caller supplies the WireGuard public key whose private half
 * never leaves the device; it is only echoed into the conf so tests can
 * trace it without handling real secrets.
 */
class PiaVpnAdapter(
    private val http: HttpPort,
    private val clock: Clock,
    private val backend: VpnBackend,
    private val vault: CredentialVault,
) {
    private val client = PiaClient(http, clock)
    private val store = InMemoryTokenStore()

    /**
     * Log in, save the token, negotiate the lease, render the conf,
     * install it and unblock. On [PiaAuthError] the backend is blocked
     * again before the error is rethrown, keeping the behaviour fail-closed.
     */
    fun connect(publicKey: String, hostname: String, excludeLan: Boolean = true): ConnectPlan {
        try {
            val username = vault.username
            val password = vault.password
            if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                throw PiaAuthError("stored credentials are missing")
            }
            val token = client.login(username, password)
            store.saveCredentials(username, password)
            store.saveToken(token)
            val leaseResult = client.addKey(hostname, token, publicKey)
            val lease = leaseResult.lease
            val dns = leaseResult.dnsServers.firstOrNull() ?: "10.0.0.243"
            val address = "${lease.peerIp}/32"
            val conf = WireGuard.buildConf(
                privateKey = publicKey,
                address = address,
                dns = dns,
                lease = lease,
                excludeLan = excludeLan,
            )
            val verdict = KillSwitch.decide(ConnectionState.CONNECTED, killSwitchEnabled = true)
            backend.installConf(conf)
            backend.setBlocked(false)
            return ConnectPlan(conf = conf, verdict = verdict, endpoint = lease.endpoint)
        } catch (error: PiaAuthError) {
            backend.setBlocked(true)
            throw error
        }
    }

    /** Fail closed: block all traffic. */
    fun disconnect() {
        backend.setBlocked(true)
    }
}
