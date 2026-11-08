package fr.hodr.aip

import android.content.Context

/** Last-known-good tunnel lease for one server: enough to connect with zero pre-tunnel network. */
data class CachedLease(
    val hostname: String,
    val peerIp: String,
    val endpointHost: String,
    val endpointPort: Int,
    val serverKey: String,
    val dnsCsv: String,
    val probeIpsCsv: String,
    val savedAtMs: Long,
)

/**
 * Offline caches in plain prefs ("pia-lease-cache", no secrets: endpoint IPs,
 * public keys and the public server list only). Auth tokens stay in the
 * encrypted store. Refreshed while the tunnel runs, consumed when the
 * kill switch blocks everything before the tunnel is up.
 */
class OfflineCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveLease(serverId: String, lease: CachedLease) {
        prefs.edit().putString(LEASE_PREFIX + serverId, encode(lease)).apply()
    }

    fun loadLease(serverId: String?): CachedLease? {
        if (serverId.isNullOrEmpty()) return null
        val raw = prefs.getString(LEASE_PREFIX + serverId, null) ?: return null
        return decode(raw)
    }

    fun updateProbes(serverId: String, probeIpsCsv: String) {
        val current = loadLease(serverId) ?: return
        if (probeIpsCsv.isBlank()) return
        saveLease(serverId, current.copy(probeIpsCsv = probeIpsCsv, savedAtMs = System.currentTimeMillis()))
    }

    fun saveServerList(rawBody: String) {
        prefs.edit()
            .putString(KEY_SERVER_LIST, rawBody)
            .putLong(KEY_SERVER_LIST_AT, System.currentTimeMillis())
            .apply()
    }

    /** Raw server list body plus fetch time, null when never cached. */
    fun loadServerList(): Pair<String, Long>? {
        val raw = prefs.getString(KEY_SERVER_LIST, null) ?: return null
        return raw to prefs.getLong(KEY_SERVER_LIST_AT, 0L)
    }

    private fun encode(lease: CachedLease): String = listOf(
        lease.hostname,
        lease.peerIp,
        lease.endpointHost,
        lease.endpointPort.toString(),
        lease.serverKey,
        lease.dnsCsv,
        lease.probeIpsCsv,
        lease.savedAtMs.toString(),
    ).joinToString(SEP)

    private fun decode(raw: String): CachedLease? {
        val parts = raw.split(SEP)
        if (parts.size != 8) return null
        val port = parts[3].toIntOrNull() ?: return null
        val at = parts[7].toLongOrNull() ?: return null
        return CachedLease(parts[0], parts[1], parts[2], port, parts[4], parts[5], parts[6], at)
    }

    companion object {
        private const val PREFS = "pia-lease-cache"
        private const val LEASE_PREFIX = "lease_"
        private const val KEY_SERVER_LIST = "server_list_body"
        private const val KEY_SERVER_LIST_AT = "server_list_at"
        private const val SEP = "|"
    }
}
