package fr.hodr.pia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TunnelConfigsTest {
    @Test
    fun parsesTwoStanzasWithDefaults() {
        val specs = TunnelConfigs.parse(
            """
            [tunnel.boulot]
            server = nl_amsterdam
            port-forward = true
            allow-lan = yes
            post-port-up = /usr/local/bin/qbit-port.sh

            [tunnel.perso]
            server = ch_zurich
            auto-connect = off
            """.trimIndent(),
        )
        assertEquals(2, specs.size)
        val work = specs[0]
        assertEquals("boulot", work.name)
        assertEquals("nl_amsterdam", work.server)
        assertTrue(work.portForward)
        assertTrue(work.allowLan)
        assertTrue(work.autoConnect)
        assertEquals("/usr/local/bin/qbit-port.sh", work.postPortUp)
        val home = specs[1]
        assertEquals("perso", home.name)
        assertEquals(false, home.autoConnect)
        assertEquals(null, home.postPortUp)
    }

    @Test
    fun unknownKeyIsHardError() {
        assertFailsWith<IllegalArgumentException> {
            TunnelConfigs.parse("[tunnel.a]\nallowlan = true")
        }
    }

    @Test
    fun badNameIsHardError() {
        assertFailsWith<IllegalArgumentException> {
            TunnelConfigs.parse("[tunnel.Boulot_Bureau_123456789]")
        }
    }

    @Test
    fun duplicateTunnelIsHardError() {
        assertFailsWith<IllegalArgumentException> {
            TunnelConfigs.parse("[tunnel.a]\n[tunnel.a]")
        }
    }

    @Test
    fun keyOutsideSectionIsHardError() {
        assertFailsWith<IllegalArgumentException> {
            TunnelConfigs.parse("server = nl_amsterdam")
        }
    }
}
