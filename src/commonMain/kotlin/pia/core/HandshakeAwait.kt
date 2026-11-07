package pia.core

/**
 * Wait-for-handshake policy: after the interface comes up, give WireGuard
 * time to complete its first handshake before any TCP probe runs. Pure
 * logic (injected stats supplier + clock + sleep) so it is unit-testable
 * on JVM without Android or a real tunnel.
 *
 * @param stats returns (txBytes, rxBytes) cumulative counters.
 * @param nowMs current monotonic time in ms.
 * @param sleepMs blocks that long; interruptible in production.
 * @return [AwaitResult] telling whether rx moved, with final counters.
 */
fun awaitHandshake(
    stats: () -> Pair<Long, Long>,
    nowMs: () -> Long,
    sleepMs: (Long) -> Unit,
    timeoutMs: Long = 10_000L,
    stepMs: Long = 500L,
): AwaitResult {
    val deadline = nowMs() + timeoutMs
    var tx = 0L
    var rx = 0L
    while (true) {
        try {
            val (t, r) = stats()
            tx = t
            rx = r
        } catch (error: Exception) {
            return AwaitResult(completed = false, txBytes = tx, rxBytes = rx)
        }
        if (rx > 0L) {
            return AwaitResult(completed = true, txBytes = tx, rxBytes = rx)
        }
        if (nowMs() >= deadline) {
            return AwaitResult(completed = false, txBytes = tx, rxBytes = rx)
        }
        // Portable: on JVM this also catches InterruptedException (the
        // Android sleep ignores interrupts anyway); on JS any throw aborts
        // the wait as incomplete instead of killing the caller.
        try {
            sleepMs(stepMs)
        } catch (error: Exception) {
            return AwaitResult(completed = false, txBytes = tx, rxBytes = rx)
        }
    }
}

/** Outcome of [awaitHandshake]: handshake seen plus final counters. */
data class AwaitResult(
    val completed: Boolean,
    val txBytes: Long,
    val rxBytes: Long,
)
