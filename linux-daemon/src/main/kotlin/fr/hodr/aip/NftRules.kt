package fr.hodr.aip

/**
 * Parameters for the fail-closed nftables ruleset. Pure data in, ruleset
 * text out: no process, no network, fully unit-testable. The daemon applies
 * the text with `nft -f` as root; verification happens on target with
 * `nft list table inet aip` and leak probes.
 */
data class NftParams(
    val endpointIp: String,
    val endpointPort: Int,
    val dnsIps: List<String>,
    val allowLan: Boolean,
    val extraLanNets: List<String> = emptyList(),
    val iface: String = "aip0",
)

object NftRules {
    private val RFC1918 = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")

    /** Single tunnel — the classic fail-closed ruleset. */
    fun build(params: NftParams): String = buildMulti(listOf(params))

    /**
     * Merged ruleset for every live tunnel, applied once with `nft -f`.
     * An empty list yields a fully closed table (lo + established only),
     * so the last tunnel going down never opens a hole.
     */
    fun buildMulti(all: List<NftParams>): String {
        val lines = mutableListOf<String>()
        lines.add("table inet aip {")
        lines.add("    chain input {")
        lines.add("        type filter hook input priority 0; policy drop;")
        lines.add("        iif \"lo\" accept")
        lines.add("        ct state established,related accept")
        if (all.isNotEmpty()) {
            lines.add("        iifname ${ifaceSel(all)} accept")
        }
        lines.add("    }")
        lines.add("")
        lines.add("    chain output {")
        lines.add("        type filter hook output priority 0; policy drop;")
        lines.add("        oif \"lo\" accept")
        lines.add("        udp dport 67 accept")
        for (p in all) {
            val family = if (':' in p.endpointIp) "ip6" else "ip"
            lines.add("        $family daddr ${p.endpointIp} udp dport ${p.endpointPort} accept")
        }
        if (all.any { it.allowLan }) {
            val nets = (RFC1918 + all.flatMap { it.extraLanNets }.distinct()).joinToString(", ")
            lines.add("        ip daddr { $nets } accept")
        }
        if (all.isNotEmpty()) {
            lines.add("        oifname != ${ifaceSel(all)} udp dport { 53, 853 } drop")
            lines.add("        oifname != ${ifaceSel(all)} tcp dport { 53, 853 } drop")
            lines.add("        meta nfproto ipv6 oifname != ${ifaceSel(all)} drop")
            val dns = all.flatMap { it.dnsIps }.distinct()
            if (dns.isNotEmpty()) {
                lines.add("        oifname ${ifaceSel(all)} udp dport { 53, 853 } ip daddr { ${dns.joinToString(", ")} } accept")
            }
            lines.add("        oifname ${ifaceSel(all)} accept")
        }
        lines.add("    }")
        lines.add("")
        lines.add("    chain forward {")
        lines.add("        type filter hook forward priority 0; policy drop;")
        lines.add("    }")
        lines.add("}")
        return lines.joinToString("\n") + "\n"
    }

    /** One quoted iface, or an nft set when several tunnels share the table. */
    private fun ifaceSel(all: List<NftParams>): String {
        val ifaces = all.map { it.iface }.distinct().sorted()
        if (ifaces.size == 1) return "\"${ifaces.single()}\""
        return "{ ${ifaces.joinToString(", ") { "\"$it\"" }} }"
    }
}
