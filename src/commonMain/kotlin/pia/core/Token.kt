package pia.core

/**
 * PIA auth token issued for 24h (see manual-connections get_token.sh).
 * Core refreshes it every 12h so consumers never hit the expiry wall.
 */
data class AuthToken(
    val value: String,
    val issuedAtEpochSec: Long,
    val ttlSec: Long = TOKEN_TTL_SEC,
) {
    fun remainingSec(nowEpochSec: Long): Long =
        (issuedAtEpochSec + ttlSec) - nowEpochSec

    fun shouldRefresh(nowEpochSec: Long, refreshAfterSec: Long = REFRESH_AFTER_SEC): Boolean =
        nowEpochSec - issuedAtEpochSec >= refreshAfterSec || remainingSec(nowEpochSec) <= refreshAfterSec

    companion object {
        const val TOKEN_TTL_SEC: Long = 24 * 3600
        const val REFRESH_AFTER_SEC: Long = 12 * 3600
    }
}
