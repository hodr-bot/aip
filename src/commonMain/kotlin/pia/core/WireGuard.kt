@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package pia.core

import kotlin.js.JsExport

@JsExport
data class WireGuardLease(
    val peerIp: String,
    val serverPublicKey: String,
    val endpoint: String,
)

@JsExport
object WireGuard {
    fun allowedIpCidrs(allowLan: Boolean): List<String> =
        if (!allowLan) {
            listOf("0.0.0.0/0", "::/0")
        } else {
            // Public IPv4 space only: everything except 10.0.0.0/8,
            // 172.16.0.0/12 and 192.168.0.0/16. IPv6 stays fully inside
            // the tunnel ("::/0") so it can never leak outside.
            listOf(
                "0.0.0.0/5",
                "8.0.0.0/7",
                "11.0.0.0/8",
                "12.0.0.0/6",
                "16.0.0.0/4",
                "32.0.0.0/3",
                "64.0.0.0/2",
                "128.0.0.0/3",
                "160.0.0.0/5",
                "168.0.0.0/6",
                "172.0.0.0/12",
                "172.32.0.0/11",
                "172.64.0.0/10",
                "172.128.0.0/9",
                "173.0.0.0/8",
                "174.0.0.0/7",
                "176.0.0.0/4",
                "192.0.0.0/9",
                "192.128.0.0/11",
                "192.160.0.0/13",
                "192.169.0.0/16",
                "192.170.0.0/15",
                "192.172.0.0/14",
                "192.176.0.0/12",
                "192.192.0.0/10",
                "193.0.0.0/8",
                "194.0.0.0/7",
                "196.0.0.0/6",
                "200.0.0.0/5",
                "208.0.0.0/4",
                "224.0.0.0/3",
                "::/0",
            )
        }

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
