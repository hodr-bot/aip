package pia.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsTimeBridgeTest {
    @Test
    fun `bridge fresh token needs no refresh`() {
        val issuedAt = 1_000_000.0
        assertEquals(23 * 3600.0, JsTimeBridge.tokenRemainingSec(issuedAt, 24 * 3600.0, issuedAt + 3600.0))
        assertFalse(JsTimeBridge.tokenShouldRefresh(issuedAt, issuedAt + 3600.0, 12 * 3600.0))
    }

    @Test
    fun `bridge refreshes at 12h boundary`() {
        val issuedAt = 1_000_000.0
        assertTrue(JsTimeBridge.tokenShouldRefresh(issuedAt, issuedAt + 12 * 3600.0 + 1, 12 * 3600.0))
    }

    @Test
    fun `bridge refreshes before 24h expiry`() {
        val issuedAt = 1_000_000.0
        // 20h old: only 4h left, must already refresh
        assertEquals(4 * 3600.0, JsTimeBridge.tokenRemainingSec(issuedAt, 24 * 3600.0, issuedAt + 20 * 3600.0))
        assertTrue(JsTimeBridge.tokenShouldRefresh(issuedAt, issuedAt + 20 * 3600.0, 12 * 3600.0))
    }

    @Test
    fun `bridge constants mirror AuthToken`() {
        assertEquals(AuthToken.TOKEN_TTL_SEC.toDouble(), JsConstants.TOKEN_TTL_SEC)
        assertEquals(AuthToken.REFRESH_AFTER_SEC.toDouble(), JsConstants.REFRESH_AFTER_SEC)
    }
}
