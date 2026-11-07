@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package pia.core

import kotlin.js.JsExport

@JsExport
enum class ConnectionState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    DEGRADED,
}

@JsExport
enum class TrafficVerdict {
    ALLOW_ALL,
    ALLOW_LAN_ONLY,
    BLOCK_ALL,
}

/**
 * Fail-closed decision shared by both consumers.
 * The Firefox extension enforces it for browser traffic only;
 * the Android app enforces it system-wide via Always-on VPN.
 */
@JsExport
object KillSwitch {
    fun decide(state: ConnectionState, killSwitchEnabled: Boolean): TrafficVerdict =
        when {
            state == ConnectionState.CONNECTED -> TrafficVerdict.ALLOW_ALL
            killSwitchEnabled -> TrafficVerdict.BLOCK_ALL
            else -> TrafficVerdict.ALLOW_LAN_ONLY
        }
}
