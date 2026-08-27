package com.hatsyrei.maidnative.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Raised when the Android Keystore cannot protect a secret. Callers must let it
 * abort the write: the alternative — persisting the plaintext — would quietly
 * void the guarantee the rest of the app is written against.
 */
class SecretUnavailableException(cause: Throwable) : Exception(
    "This device's secure keystore is unavailable, so the API key cannot be stored safely.",
    cause,
)

/**
 * Encrypts the API keys that [SettingsRepository] persists, using an AES/GCM key
 * held in the Android Keystore. The key is generated on the device and can never
 * be exported, so the preferences file on disk — and any copy of it lifted off a
 * rooted device — carries nothing usable.
 */
internal object SecretCipher {

    /**
     * @throws SecretUnavailableException if the Keystore cannot encrypt. Failing
     * here is deliberate: AES/GCM Keystore support is universal above the app's
     * minSdk, so this only fires on a broken keystore, and on such a device the
     * user needs to be told rather than handed a silently unprotected key.
     */
    fun encode(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        }.getOrElse { throw SecretUnavailableException(it) }
    }

    /**
     * Null when an encrypted value cannot be read back — the reachable case is a
     * Keystore key that was invalidated or never restored (its material is not
     * backed up), which leaves undecryptable ciphertext behind. That is reported
     * rather than folded into an empty key, which would look like "no key set"
     * and send unauthenticated requests instead.
     *
     * Values without the [PREFIX] predate encryption and are read back verbatim.
     */
    fun decode(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    @Volatile
    private var cached: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cached?.let { return it }
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val existing = (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        return (existing ?: generate()).also { cached = it }
    }

    private fun generate(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "maid-native-credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
}
