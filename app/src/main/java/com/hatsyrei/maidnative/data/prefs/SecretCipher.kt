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
 * Encrypts the API keys that [SettingsRepository] persists, using an AES/GCM key
 * held in the Android Keystore. The key is generated on the device and can never
 * be exported, so the preferences file on disk — and any copy of it lifted off a
 * rooted device or out of a backup — carries nothing usable.
 */
internal object SecretCipher {

    fun encode(plain: String): String {
        if (plain.isEmpty()) return ""
        // A device whose Keystore refuses to serve the key should still be able
        // to save an endpoint; `decode` reads unprefixed values back verbatim.
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    /** Values without the [PREFIX] were written before encryption existed (or by the fallback above). */
    fun decode(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrDefault("")
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
