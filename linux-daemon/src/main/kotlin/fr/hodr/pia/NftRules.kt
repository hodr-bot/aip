package fr.hodr.pia

/**
 * Parameters for the fail-closed nftables ruleset. Pure data in, ruleset
 * text out: no process, no network, fully unit-testable. The daemon applies
 * the text with `nft -f` as root; verification happens on target with
 * `nft list table inet pia` and leak probes.
 */
data class NftParams(
    val endpointIp: String,
    val endpointPort: Int,
    val dnsIps: List<String>,
    val allowLan: Boolean,
    val extraLanNets: List<String> = emptyList(),
)

object NftRules {
    private val RFC1918 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")

    fun build(params: NftParams): String {
        val lines = mutableListOf<String>()
        lines.add("table inet pia {")
        lines.add("    chain input {")
        lines.add("        type filter hook input priority 0; policy drop;")
        lines.add("        iif \"lo\" accept")
        lines.add("        ct state established,related accept")
        lines.add("        iifname \"pia0\" accept")
        lines.add("    }")
        lines.add("")
        lines.add("    chain output {")
        lines.add("        type filter hook output priority 0; policy drop;")
        lines.add("        oif \"lo\" accept")
        lines.add("        udp dport 67 accept")
        val family = if (':' in params.endpointIp) "ip6" else "ip"
        lines.add("        $family daddr ${params.endpointIp} udp dport ${params.endpointPort} accept")
        if (params.allowLan) {
            val nets = (RFC1918 + params.extraLanNets).joinToString(", ")
            lines.add("        ip daddr { $nets } accept")
        }
        lines.add("        oifname != \"pia0\" udp dport { 53, 853 } drop")
        lines.add("        oifname != \"pia0\" tcp dport { 53, 853 } drop")
        lines.add("        meta nfproto ipv6 oifname != \"pia0\" drop")
        if (params.dnsIps.isNotEmpty()) {
            val dns = params.dnsIps.joinToString(", ")
            lines.add("        oifname \"pia0\" udp dport { 53, 853 } ip daddr { $dns } accept")
        }
        lines.add("        oifname \"pia0\" accept")
        lines.add("    }")
        lines.add("")
        lines.add("    chain forward {")
        lines.add("        type filter hook forward priority 0; policy drop;")
        lines.add("    }")
        lines.add("}")
        return lines.joinToString("\n") + "\n"
    }
}
