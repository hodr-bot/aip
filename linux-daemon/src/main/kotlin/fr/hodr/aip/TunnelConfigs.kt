package fr.hodr.aip

/**
 * One tunnel stanza from tunnels.conf, ini style:
 *
 *   [tunnel.boulot]
 *   server = nl_amsterdam
 *   port-forward = true
 *   allow-lan = true
 *   auto-connect = true
 *   post-port-up = /usr/local/bin/qbit-port.sh
 *
 * Keys are fixed; an unknown key is a hard error so typos never
 * silently boot a tunnel with the wrong shape.
 */
data class TunnelSpec(
    val name: String,
    val server: String? = null,
    val portForward: Boolean = false,
    val allowLan: Boolean = false,
    val autoConnect: Boolean = true,
    val postPortUp: String? = null,
)

object TunnelConfigs {
    private val NAME = Regex("[a-z0-9-]{1,11}")
    private val KNOWN_KEYS = setOf("server", "port-forward", "allow-lan", "auto-connect", "post-port-up")

    fun parse(text: String): List<TunnelSpec> {
        val specs = mutableListOf<TunnelSpec>()
        var current: String? = null
        var server: String? = null
        var pf = false
        var lan = false
        var auto = true
        var hook: String? = null
        fun flush() {
            val name = current ?: return
            specs.add(TunnelSpec(name, server, pf, lan, auto, hook))
        }
        for ((number, raw) in text.lines().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                flush()
                val section = line.substring(1, line.length - 1).trim()
                if (!section.startsWith("tunnel.")) {
                    throw IllegalArgumentException("line ${number + 1}: expected [tunnel.<name>], got [$section]")
                }
                current = section.removePrefix("tunnel.")
                if (!NAME.matches(current!!)) {
                    throw IllegalArgumentException("line ${number + 1}: bad tunnel name '$current' (a-z, 0-9, -, max 11)")
                }
                server = null; pf = false; lan = false; auto = true; hook = null
                continue
            }
            if (current == null) {
                throw IllegalArgumentException("line ${number + 1}: key outside any [tunnel.*] section")
            }
            val eq = line.indexOf('=')
            if (eq < 0) throw IllegalArgumentException("line ${number + 1}: expected key = value")
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            if (key !in KNOWN_KEYS) {
                throw IllegalArgumentException("line ${number + 1}: unknown key '$key'")
            }
            when (key) {
                "server" -> server = value.ifEmpty { null }
                "port-forward" -> pf = bool(key, value, number)
                "allow-lan" -> lan = bool(key, value, number)
                "auto-connect" -> auto = bool(key, value, number)
                "post-port-up" -> hook = value.ifEmpty { null }
            }
        }
        flush()
        val dupes = specs.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        if (dupes.isNotEmpty()) throw IllegalArgumentException("duplicate tunnel: ${dupes.sorted().joinToString(", ")}")
        return specs
    }

    private fun bool(key: String, value: String, number: Int): Boolean = when (value.lowercase()) {
        "true", "yes", "1", "on" -> true
        "false", "no", "0", "off" -> false
        else -> throw IllegalArgumentException("line ${number + 1}: $key wants true/false, got '$value'")
    }
}
