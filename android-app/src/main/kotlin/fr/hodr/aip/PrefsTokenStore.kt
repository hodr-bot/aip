package fr.hodr.aip

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import pia.core.AuthToken
import pia.core.TokenStore

/**
 * Persistent [TokenStore] backed by EncryptedSharedPreferences file
 * "pia-auth" (AndroidKeyStore). Holds the username/password for the
 * automatic 12h refresh plus the latest token. No secrets logged.
 */
class PrefsTokenStore(context: Context) : TokenStore {

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun loadUsername(): String? = prefs.getString(KEY_USERNAME, null)

    override fun loadPassword(): String? = prefs.getString(KEY_PASSWORD, null)

    override fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    override fun loadToken(): AuthToken? {
        val value = prefs.getString(KEY_TOKEN, null) ?: return null
        val issuedAt = prefs.getLong(KEY_ISSUED_AT, -1L)
        if (issuedAt < 0L) {
            return null
        }
        return AuthToken(value = value, issuedAtEpochSec = issuedAt)
    }

    override fun saveToken(token: AuthToken) {
        prefs.edit()
            .putString(KEY_TOKEN, token.value)
            .putLong(KEY_ISSUED_AT, token.issuedAtEpochSec)
            .apply()
    }

    override fun clearToken() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_ISSUED_AT)
            .apply()
    }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_TOKEN)
            .remove(KEY_ISSUED_AT)
            .apply()
    }

    fun saveLastHost(hostname: String) {
        prefs.edit().putString(KEY_LAST_HOST, hostname).apply()
    }

    fun loadLastHost(): String? = prefs.getString(KEY_LAST_HOST, null)

    companion object {
        private const val PREFS_NAME = "pia-auth"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOKEN = "token"
        private const val KEY_ISSUED_AT = "issuedAt"
        private const val KEY_LAST_HOST = "lastHost"
    }
}
