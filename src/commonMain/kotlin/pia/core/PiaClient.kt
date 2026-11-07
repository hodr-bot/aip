package pia.core

/** Authentication failure: missing credentials/token or rejected login. */
class PiaAuthError(message: String) : Exception(message)

/** API failure: addKey returned non-OK status or malformed payload. */
class PiaApiError(message: String) : Exception(message)

/** addKey outcome: tunnel lease plus resolvers advertised by the region. */
data class LeaseResult(
    val lease: WireGuardLease,
    val dnsServers: List<String>,
)

/**
 * Live PIA client behind the [HttpPort] seam; no real network here.
 * Behaviour mirrors pia-foss manual-connections:
 * get_token for the 24h token, then WireGuard addKey on port 1337.
 * Favour pure string search over JSON libraries to stay stdlib only.
 */
class PiaClient(
    private val http: HttpPort,
    private val clock: Clock,
) {
    companion object {
        const val TOKEN_URL = "https://www.privateinternetaccess.com/api/client/v2/token"
        const val ADD_KEY_PORT = 1337
        const val ADD_KEY_PATH = "addKey"
        const val HEX_DIGITS = "0123456789ABCDEF"
    }

    /** Exchange username/password for a fresh token stamped with [Clock]. */
    fun login(username: String, password: String): AuthToken {
        val body = http.postForm(
            TOKEN_URL,
            mapOf("username" to username, "password" to password),
        )
        val status = findStringField(body, "status")
        val token = findStringField(body, "token")
        if (status != null && status != "OK") {
            throw PiaAuthError("login rejected with status: $status")
        }
        if (token.isNullOrEmpty()) {
            throw PiaAuthError("login response held no token")
        }
        return AuthToken(value = token, issuedAtEpochSec = clock.nowEpochSec())
    }

    /**
     * Return the stored token when still valid, otherwise refresh it
     * through the stored credentials. Throws [PiaAuthError] when the
     * token or the credentials are missing.
     */
    fun ensureValidToken(store: TokenStore): AuthToken {
        val now = clock.nowEpochSec()
        val cached = store.loadToken()
        if (cached != null && !cached.shouldRefresh(now)) {
            return cached
        }
        val username = store.loadUsername()
        val password = store.loadPassword()
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            if (cached == null) {
                throw PiaAuthError("no stored token and no stored credentials")
            }
            throw PiaAuthError("stored token needs refresh but credentials are missing")
        }
        val refreshed = login(username, password)
        store.saveToken(refreshed)
        return refreshed
    }

    /** Negotiate a WireGuard lease on [hostname] for [publicKey]. */
    fun addKey(hostname: String, authToken: AuthToken, publicKey: String): LeaseResult =
        addKey(hostname, authToken.value, publicKey)

    /** String-token overload so callers need not wrap the pt value. */
    fun addKey(hostname: String, token: String, publicKey: String): LeaseResult {
        val url = "https://$hostname:$ADD_KEY_PORT/$ADD_KEY_PATH" +
            "?pt=${urlEncode(token)}&pubkey=${urlEncode(publicKey)}"
        val body = http.get(url)
        val status = findStringField(body, "status")
        if (status != "OK") {
            throw PiaApiError("addKey rejected with status: $status")
        }
        val peerIp = findStringField(body, "peer_ip")
        val serverKey = findStringField(body, "server_key")
        val serverPort = findIntField(body, "server_port")
        if (peerIp.isNullOrEmpty() || serverKey.isNullOrEmpty() || serverPort == null) {
            throw PiaApiError("addKey response missed peer_ip, server_key or server_port")
        }
        val dnsServers = findStringArray(body, "dns_servers")
        val lease = WireGuardLease(
            peerIp = peerIp,
            serverPublicKey = serverKey,
            endpoint = "$hostname:$serverPort",
        )
        return LeaseResult(lease = lease, dnsServers = dnsServers)
    }

    private fun findStringField(body: String, field: String): String? {
        val key = "\"$field\""
        var index = body.indexOf(key)
        while (index >= 0) {
            var cursor = index + key.length
            cursor = skipSeparators(body, cursor)
            if (cursor < body.length && body[cursor] == ':') {
                cursor = skipSeparators(body, cursor + 1)
                if (cursor < body.length && body[cursor] == '"') {
                    val start = cursor + 1
                    val end = body.indexOf('"', start)
                    if (end > start) {
                        return body.substring(start, end)
                    }
                    return null
                }
                // Field present but not a quoted string (number, null, ...).
                return null
            }
            index = body.indexOf(key, index + 1)
        }
        return null
    }

    private fun findIntField(body: String, field: String): Int? {
        val quoted = findStringField(body, field)
        if (quoted != null) {
            return quoted.toIntOrNull()
        }
        val key = "\"$field\""
        var index = body.indexOf(key)
        while (index >= 0) {
            var cursor = index + key.length
            cursor = skipSeparators(body, cursor)
            if (cursor < body.length && body[cursor] == ':') {
                cursor = skipSeparators(body, cursor + 1)
                val start = cursor
                while (cursor < body.length && (body[cursor].isDigit() || body[cursor] == '-')) {
                    cursor++
                }
                if (start < cursor) {
                    return body.substring(start, cursor).toIntOrNull()
                }
                return null
            }
            index = body.indexOf(key, index + 1)
        }
        return null
    }

    private fun findStringArray(body: String, field: String): List<String> {
        val key = "\"$field\""
        var index = body.indexOf(key)
        while (index >= 0) {
            var cursor = index + key.length
            cursor = skipSeparators(body, cursor)
            if (cursor < body.length && body[cursor] == ':') {
                cursor = skipSeparators(body, cursor + 1)
                if (cursor < body.length && body[cursor] == '[') {
                    val end = body.indexOf(']', cursor + 1)
                    if (end < 0) {
                        return emptyList()
                    }
                    val inner = body.substring(cursor + 1, end).trim()
                    if (inner.isEmpty()) {
                        return emptyList()
                    }
                    return inner.split(',').mapNotNull { entry ->
                        val cleaned = entry.trim().trim('"').trim()
                        if (cleaned.isEmpty()) null else cleaned
                    }
                }
                return emptyList()
            }
            index = body.indexOf(key, index + 1)
        }
        return emptyList()
    }

    private fun skipSeparators(body: String, from: Int): Int {
        var cursor = from
        while (cursor < body.length && (body[cursor].isWhitespace())) {
            cursor++
        }
        return cursor
    }

    internal fun urlEncode(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
                || ch == '-' || ch == '_' || ch == '.' || ch == '~'
            ) {
                out.append(ch)
            } else {
                val code = raw[i].code
                if (code < 0x80) {
                    out.append('%')
                    out.append(HEX_DIGITS[(code shr 4) and 0xF])
                    out.append(HEX_DIGITS[code and 0xF])
                } else {
                    var j = i
                    while (j < raw.length && raw[j].code >= 0x80) {
                        j++
                    }
                    val bytes = raw.substring(i, j).encodeToByteArray()
                    for (b in bytes) {
                        val v = b.toInt() and 0xFF
                        out.append('%')
                        out.append(HEX_DIGITS[(v shr 4) and 0xF])
                        out.append(HEX_DIGITS[v and 0xF])
                    }
                    i = j - 1
                }
            }
            i++
        }
        return out.toString()
    }
}
