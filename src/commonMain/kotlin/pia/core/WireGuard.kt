package pia.core

data class WireGuardLease(
    val peerIp: String,
    val serverPublicKey: String,
    val endpoint: String,
)

object WireGuard {
    /**
     * Full-tunnel defaults. When [excludeLan] is set the consumer keeps
     * RFC1918 reachable locally instead of pushing it through the tunnel.
     */
    fun allowedIps(excludeLan: Boolean): List<String> =
        if (!excludeLan) {
            listOf("0.0.0.0/0", "::/0")
        } else {
            // 0/1 + 128/1 covers all IPv4 except nothing; LAN is handled
            // by a platform route outside the tunnel (Android bypass-LAN,
            // Firefox: proxy bypass list). Kept explicit so both UIs match.
            listOf("0.0.0.0/1", "128.0.0.0/1", "::/0")
        }

    fun lanBypassList(): List<String> =
        listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")

    fun buildConf(
        privateKey: String,
        address: String,
        dns: String,
        lease: WireGuardLease,
        excludeLan: Boolean = true,
    ): String {
        val allowed = allowedIps(excludeLan).joinToString(", ")
        return """
            |[Interface]
            |Address = $address
            |PrivateKey = $privateKey
            |DNS = $dns
            |
            |[Peer]
            |PublicKey = ${lease.serverPublicKey}
            |Endpoint = ${lease.endpoint}
            |AllowedIPs = $allowed
            |PersistentKeepalive = 25
            |
        """.trimMargin()
    }
}
