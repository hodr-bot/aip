package fr.hodr.pia

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import pia.core.AuthToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileTokenStoreTest {
    @Test
    fun credentialsAndTokenRoundTrip() {
        val dir = Files.createTempDirectory("piad-test")
        val store = FileTokenStore(dir.resolve("auth"))
        assertNull(store.loadUsername())
        assertNull(store.loadToken())
        store.saveCredentials("user", "pass")
        assertEquals("user", store.loadUsername())
        assertEquals("pass", store.loadPassword())
        store.saveToken(AuthToken("tok", 1000L, 2000L))
        assertEquals(AuthToken("tok", 1000L, 2000L), store.loadToken())
        store.clearToken()
        assertNull(store.loadToken())
        assertEquals("user", store.loadUsername())
    }

    @Test
    fun storeFileIsOwnerOnly() {
        val dir = Files.createTempDirectory("piad-test")
        val file = dir.resolve("auth")
        val store = FileTokenStore(file)
        store.saveCredentials("user", "pass")
        val perms = try {
            Files.getPosixFilePermissions(file)
        } catch (ignored: UnsupportedOperationException) {
            return
        }
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms,
        )
    }
}
