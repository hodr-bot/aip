package fr.hodr.aip

import com.wireguard.android.backend.Tunnel

/**
 * Minimal [Tunnel] implementation: stores the name and the last state reported
 * by the backend. No service wiring in this step.
 */
class PiaTunnel(private val tunnelName: String) : Tunnel {

    @Volatile
    var state: Tunnel.State = Tunnel.State.DOWN

    override fun getName(): String = tunnelName

    override fun onStateChange(newState: Tunnel.State) {
        state = newState
    }
}
