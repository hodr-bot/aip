package fr.hodr.pia

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import pia.core.AuthToken
import pia.core.TokenStore

/**
 * [TokenStore] over a root-only properties file (0600). Credentials live
 * here, never in the lease cache and never on D-Bus. The path is injected
 * so tests can point at a temp dir; production uses /var/lib/piad/auth.
 */
class FileTokenStore(private val path: Path) : TokenStore {
    override fun loadUsername(): String? = props().getProperty("username")

    override fun loadPassword(): String? = props().getProperty("password")

    override fun saveCredentials(username: String, password: String) {
        val props = props()
        props.setProperty("username", username)
        props.setProperty("password", password)
        store(props)
    }

    override fun loadToken(): AuthToken? {
        val props = props()
        val value = props.getProperty("token") ?: return null
        val issued = props.getProperty("tokenIssuedAt")?.toLongOrNull() ?: return null
        val ttl = props.getProperty("tokenTtl")?.toLongOrNull() ?: AuthToken.TOKEN_TTL_SEC
        return AuthToken(value, issued, ttl)
    }

    override fun saveToken(token: AuthToken) {
        val props = props()
        props.setProperty("token", token.value)
        props.setProperty("tokenIssuedAt", token.issuedAtEpochSec.toString())
        props.setProperty("tokenTtl", token.ttlSec.toString())
        store(props)
    }

    override fun clearToken() {
        val props = props()
        props.remove("token")
        props.remove("tokenIssuedAt")
        props.remove("tokenTtl")
        store(props)
    }

    private fun props(): Properties {
        val props = Properties()
        if (Files.exists(path)) {
            Files.newInputStream(path).use { props.load(it) }
        }
        return props
    }

    private fun store(props: Properties) {
        path.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(path).use { props.store(it, null) }
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (ignored: UnsupportedOperationException) {
            // Non-POSIX fs (tests on odd mounts): best effort only.
        }
    }
}
