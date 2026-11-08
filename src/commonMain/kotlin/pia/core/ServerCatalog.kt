package pia.core

/**
 * Catalog entry for one PIA region: the region [id] plus the [hostname]
 * to dial with [PiaClient.addKey]. [displayName] is the human-readable
 * region name from the server list ("US Florida"), falling back to [id].
 * [country] is the payload ISO code ("DK" for denmark: region ids are not
 * ISO codes). [virtual] marks a virtual location (the payload's `geo`
 * flag is true: the exit IP geolocates there, the hardware sits
 * elsewhere). [streaming] marks a streaming-optimized region (id suffix
 * `-so`). [latencyMs] is attached by the caller (platform ping probe);
 * the server-list payload carries no latency.
 */
data class ServerEntry(
    val id: String,
    val hostname: String,
    val latencyMs: Long? = null,
    val displayName: String = "",
    val virtual: Boolean = false,
    val streaming: Boolean = false,
    val country: String = "",
) {
    /** Human-readable title, never blank: [displayName] or [id]. */
    fun displayTitle(): String = displayName.ifEmpty { id }
}

/**
 * Server list behind the [HttpPort] seam; no real network here.
 * Behaviour mirrors pia-foss manual-connections:
 * GET https://serverlist.piaservers.net/vpninfo/servers/v6 whose first
 * line is the JSON document {"groups": {...}, "regions": [...]}, where
 * each region carries id, dns, offline, geo plus per-protocol servers.
 * Trailing lines are the base64 backdrop signature (verified by the
 * platforms, ignored here). Offline regions are skipped: dialling one
 * would fail. Favour pure string search over JSON libraries to stay
 * stdlib only. No secrets flow through here, just the public list.
 */
class ServerCatalog(
    private val http: HttpPort,
) {
    companion object {
        const val SERVER_LIST_URL = "https://serverlist.piaservers.net/vpninfo/servers/v6"
    }

    /** GET the raw server list body (cacheable, parsed with [parseBody]). */
    fun fetchRaw(): String = http.get(SERVER_LIST_URL)

    /** Parse a server list body; throws when it holds no regions. */
    fun parseBody(body: String): List<ServerEntry> {
        val entries = parseRegions(body)
        if (entries.isEmpty()) {
            throw PiaApiError("server list held no regions")
        }
        return entries
    }

    /** GET the server list and parse it into region entries. */
    fun fetchServers(): List<ServerEntry> = parseBody(fetchRaw())

    /** Return the entry with [id], or null when the catalog holds none. */
    fun pickById(servers: List<ServerEntry>, id: String): ServerEntry? =
        servers.firstOrNull { it.id == id }

    /**
     * Return the lowest-latency entry, reusing [RegionPicker] semantics:
     * lowest known latency wins, unknown latency sorts last, ties break
     * on id. Null when the catalog is empty.
     */
    fun pickBest(servers: List<ServerEntry>): ServerEntry? {
        val best = RegionPicker.pickBest(
            servers.map { RegionEndpoint(it.id, it.hostname, latencyMs = it.latencyMs) },
        ) ?: return null
        return servers.firstOrNull { it.id == best.id }
    }

    private fun parseRegions(body: String): List<ServerEntry> {
        val entries = mutableListOf<ServerEntry>()
        val regionsIndex = body.indexOf("\"regions\"")
        if (regionsIndex < 0) {
            return entries
        }
        var cursor = skipWs(body, regionsIndex + "\"regions\"".length)
        if (cursor >= body.length || body[cursor] != ':') {
            return entries
        }
        cursor = skipWs(body, cursor + 1)
        if (cursor >= body.length || body[cursor] != '[') {
            return entries
        }
        cursor += 1
        while (true) {
            cursor = skipWs(body, cursor)
            if (cursor >= body.length || body[cursor] == ']') {
                break
            }
            if (body[cursor] == ',') {
                cursor += 1
                continue
            }
            if (body[cursor] != '{') {
                break
            }
            val region = extractObject(body, cursor) ?: break
            cursor = region.second
            val offline = findBoolField(region.first, "offline") ?: false
            if (!offline) {
                val id = findStringField(region.first, "id")
                val dns = findStringField(region.first, "dns")
                if (!id.isNullOrEmpty() && !dns.isNullOrEmpty()) {
                    val name = findStringField(region.first, "name").orEmpty()
                    val geo = findBoolField(region.first, "geo")
                    entries.add(
                        ServerEntry(
                            id = id,
                            hostname = dns,
                            displayName = name,
                            virtual = geo == true,
                            streaming = id.endsWith("-so"),
                            country = findStringField(region.first, "country").orEmpty(),
                        ),
                    )
                }
            }
        }
        return entries
    }

    /** Slice the balanced object starting at [start], strings respected. */
    private fun extractObject(body: String, start: Int): Pair<String, Int>? {
        var index = start
        var depth = 0
        var inString = false
        var escaped = false
        while (index < body.length) {
            val char = body[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) {
                            return body.substring(start, index + 1) to (index + 1)
                        }
                    }
                }
            }
            index += 1
        }
        return null
    }

    private fun findStringField(body: String, field: String): String? {
        val key = "\"$field\""
        var index = body.indexOf(key)
        while (index >= 0) {
            var cursor = skipWs(body, index + key.length)
            if (cursor < body.length && body[cursor] == ':') {
                cursor = skipWs(body, cursor + 1)
                if (cursor < body.length && body[cursor] == '"') {
                    val start = cursor + 1
                    val end = body.indexOf('"', start)
                    if (end > start) {
                        return body.substring(start, end)
                    }
                    return null
                }
                return null
            }
            index = body.indexOf(key, index + 1)
        }
        return null
    }

    private fun findBoolField(body: String, field: String): Boolean? {
        val key = "\"$field\""
        var index = body.indexOf(key)
        while (index >= 0) {
            var cursor = skipWs(body, index + key.length)
            if (cursor < body.length && body[cursor] == ':') {
                cursor = skipWs(body, cursor + 1)
                val rest = body.substring(cursor)
                return when {
                    rest.startsWith("true") -> true
                    rest.startsWith("false") -> false
                    else -> null
                }
            }
            index = body.indexOf(key, index + 1)
        }
        return null
    }

    private fun skipWs(body: String, from: Int): Int {
        var cursor = from
        while (cursor < body.length && body[cursor].isWhitespace()) {
            cursor += 1
        }
        return cursor
    }
}
