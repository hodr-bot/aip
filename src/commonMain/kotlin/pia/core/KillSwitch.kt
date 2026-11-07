package pia.core

enum class ConnectionState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    DEGRADED,
}

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
object KillSwitch {
    fun decide(state: ConnectionState, killSwitchEnabled: Boolean): TrafficVerdict =
        when {
            state == ConnectionState.CONNECTED -> TrafficVerdict.ALLOW_ALL
            killSwitchEnabled -> TrafficVerdict.BLOCK_ALL
            else -> TrafficVerdict.ALLOW_LAN_ONLY
        }
}
