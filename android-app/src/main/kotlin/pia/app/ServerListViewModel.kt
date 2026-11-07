package pia.app

import pia.android.ConnectPlan
import pia.android.PiaVpnAdapter
import pia.core.Clock
import pia.core.ServerCatalog
import pia.core.ServerEntry

/** Selection failure: unknown id or connect with nothing selected. */
class PiaSelectionError(message: String) : Exception(message)

/**
 * Minimal app state: fetched [servers] plus the [selectedId].
 * [refreshedAtEpochSec] records the last refresh, stamped with [Clock].
 */
data class AppState(
    val servers: List<ServerEntry> = emptyList(),
    val selectedId: String? = null,
    val refreshedAtEpochSec: Long? = null,
)

/**
 * Pure-JVM view model proving server choice end to end. No Android
 * imports here; behaviour stays testable with fakes and the real
 * platform shell honours the same calls. Favour clarity over
 * persistence; the favourite server is simply the selected one.
 */
class ServerListViewModel(
    private val catalog: ServerCatalog,
    private val adapter: PiaVpnAdapter,
    private val clock: Clock,
) {
    var state: AppState = AppState()
        private set

    /**
     * Fetch the server list, attach the caller-measured [latencyById]
     * (the list payload carries no latency), then auto-select the best
     * entry: lowest known latency wins, unknown sorts last, ties break
     * on id. Behaviour mirrors [ServerCatalog.pickBest].
     */
    fun refreshServers(latencyById: Map<String, Long> = emptyMap()): AppState {
        val fetched = catalog.fetchServers()
        val withLatency = if (latencyById.isEmpty()) {
            fetched
        } else {
            fetched.map { entry ->
                entry.copy(latencyMs = latencyById[entry.id] ?: entry.latencyMs)
            }
        }
        val best = catalog.pickBest(withLatency)
        state = AppState(
            servers = withLatency,
            selectedId = best?.id,
            refreshedAtEpochSec = clock.nowEpochSec(),
        )
        return state
    }

    /** Pick [id] or throw [PiaSelectionError] when the id is unknown. */
    fun selectServer(id: String): AppState {
        val entry = catalog.pickById(state.servers, id)
            ?: throw PiaSelectionError("unknown server id: $id")
        state = state.copy(selectedId = entry.id)
        return state
    }

    /**
     * Dial the selected hostname and return the [ConnectPlan].
     * Throws [PiaSelectionError] when nothing is selected.
     */
    fun connectSelected(publicKey: String): ConnectPlan {
        val selectedId = state.selectedId
            ?: throw PiaSelectionError("no server selected")
        val entry = catalog.pickById(state.servers, selectedId)
            ?: throw PiaSelectionError("selected server is unknown: $selectedId")
        return adapter.connect(publicKey = publicKey, hostname = entry.hostname)
    }
}
