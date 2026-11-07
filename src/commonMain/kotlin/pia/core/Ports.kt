package pia.core

/** Outbound HTTP surface the core needs; platforms inject their own engine. */
interface HttpPort {
    fun postForm(url: String, fields: Map<String, String>): String
    fun get(url: String, headers: Map<String, String> = emptyMap()): String
}

/** Credential + token persistence; platforms inject browser.storage / DataStore. */
interface TokenStore {
    fun loadUsername(): String?
    fun loadPassword(): String?
    fun saveCredentials(username: String, password: String)
    fun loadToken(): AuthToken?
    fun saveToken(token: AuthToken)
    fun clearToken()
}

/** Clock abstraction so refresh logic is unit-testable. */
interface Clock {
    fun nowEpochSec(): Long
}

data class RegionEndpoint(
    val id: String,
    val host: String,
    val port: Int = 1337,
    val latencyMs: Long? = null,
)

object RegionPicker {
    /** Pure function: lowest known latency wins, unknown latency sorts last. */
    fun pickBest(candidates: List<RegionEndpoint>): RegionEndpoint? =
        candidates.minWithOrNull(
            compareBy<RegionEndpoint> { it.latencyMs ?: Long.MAX_VALUE }.thenBy { it.id },
        )
}
