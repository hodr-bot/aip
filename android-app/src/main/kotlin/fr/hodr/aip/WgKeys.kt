package fr.hodr.aip

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair

/**
 * Persistent WireGuard identity key, stored base64-encoded under "pia-private"
 * in EncryptedSharedPreferences file "pia-wg" (backed by AndroidKeyStore).
 * Generates a fresh [KeyPair] via the WireGuard tunnel library when absent.
 */
object WgKeys {

    private const val PREFS_NAME = "pia-wg"
    private const val PRIVATE_KEY = "pia-private"

    fun loadOrGenerate(context: Context): KeyPair {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        val stored = prefs.getString(PRIVATE_KEY, null)
        if (stored != null) {
            return KeyPair(Key.fromBase64(stored))
        }
        val generated = KeyPair()
        prefs.edit().putString(PRIVATE_KEY, generated.privateKey.toBase64()).apply()
        return generated
    }
}
