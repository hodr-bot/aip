package fr.hodr.aip

import pia.core.Clock
import pia.core.PfSignature
import pia.core.PiaClient

/**
 * PIA port-forward lifecycle: fetch the signature when missing or stale,
 * bind it on the connected gateway, and report the port. The caller
 * decides what to do with it (state, Post-Port-Up hook).
 *
 * PIA rotates the port if the bind lapses, so refresh at most every
 * [REFRESH_SEC]: a fresh getSignature keeps returning the same port.
 */
class PortForward(
    private val client: PiaClient,
    private val clock: Clock,
    private val onPort: (port: Int, changed: Boolean) -> Unit = { _, _ -> },
) {
    var port: Int? = null
        private set

    private var sig: PfSignature? = null
    private var boundAtSec = 0L

    companion object {
        const val REFRESH_SEC = 15 * 60L
    }

    /**
     * Guarantee a bound port; no-op when the current bind is fresh.
     * Returns the active port. Throws on API failure (caller keeps the
     * tunnel up and retries on the next tick).
     */
    fun refresh(hostname: String, gatewayIp: String, token: String): Int {
        val now = clock.nowEpochSec()
        val current = sig
        if (current == null || now - boundAtSec >= REFRESH_SEC) {
            val fresh = client.getSignature(hostname, token)
            sig = fresh
            boundAtSec = now
        }
        val active = sig!!
        client.bindPort(gatewayIp, active.payload, active.signature)
        boundAtSec = now
        val previous = port
        port = active.port
        if (previous != active.port) onPort(active.port, previous != null)
        return active.port
    }

    /** Forget the bind (tunnel down, logout): next refresh starts over. */
    fun reset() {
        port = null
        sig = null
        boundAtSec = 0L
    }
}
