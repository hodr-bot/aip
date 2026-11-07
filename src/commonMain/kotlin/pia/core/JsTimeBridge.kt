@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package pia.core

import kotlin.js.JsExport

/**
 * JS-compatible bridge over [AuthToken] time logic.
 *
 * Kotlin Long is not JsExport-compatible, so this facade takes Doubles
 * (exact for epoch-second magnitudes) and delegates to [AuthToken].
 * Stdlib only, no platform imports.
 */
@JsExport
object JsTimeBridge {
    fun tokenRemainingSec(issuedAtEpochSec: Double, ttlSec: Double, nowEpochSec: Double): Double =
        AuthToken(
            value = "js-bridge",
            issuedAtEpochSec = issuedAtEpochSec.toLong(),
            ttlSec = ttlSec.toLong(),
        ).remainingSec(nowEpochSec.toLong()).toDouble()

    fun tokenShouldRefresh(
        issuedAtEpochSec: Double,
        nowEpochSec: Double,
        refreshAfterSec: Double,
    ): Boolean =
        AuthToken(
            value = "js-bridge",
            issuedAtEpochSec = issuedAtEpochSec.toLong(),
        ).shouldRefresh(
            nowEpochSec = nowEpochSec.toLong(),
            refreshAfterSec = refreshAfterSec.toLong(),
        )
}

/** JS-compatible view of [AuthToken] timing constants as Doubles. */
@JsExport
object JsConstants {
    val TOKEN_TTL_SEC: Double = AuthToken.TOKEN_TTL_SEC.toDouble()
    val REFRESH_AFTER_SEC: Double = AuthToken.REFRESH_AFTER_SEC.toDouble()
}
