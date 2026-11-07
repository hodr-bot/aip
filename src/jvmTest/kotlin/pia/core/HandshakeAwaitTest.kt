package pia.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandshakeAwaitTest {

    @Test
    fun returnsImmediatelyWhenRxAlreadyMoved() {
        var now = 0L
        val result = awaitHandshake(
            stats = { 148L to 200L },
            nowMs = { now },
            sleepMs = { now += it },
        )
        assertTrue(result.completed)
        assertEquals(148L, result.txBytes)
        assertEquals(200L, result.rxBytes)
        assertEquals(0L, now)
    }

    @Test
    fun waitsUntilRxMoves() {
        var now = 0L
        val result = awaitHandshake(
            stats = { 300L to if (now >= 1_500L) 176L else 0L },
            nowMs = { now },
            sleepMs = { now += it },
            timeoutMs = 10_000L,
            stepMs = 500L,
        )
        assertTrue(result.completed)
        assertEquals(1_500L, now)
    }

    @Test
    fun timesOutWithFinalCounters() {
        var now = 0L
        val result = awaitHandshake(
            stats = { 516L to 0L },
            nowMs = { now },
            sleepMs = { now += it },
            timeoutMs = 2_000L,
            stepMs = 500L,
        )
        assertFalse(result.completed)
        assertEquals(516L, result.txBytes)
        assertEquals(0L, result.rxBytes)
        assertTrue(now >= 2_000L)
    }
}
