package com.aether.tv.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores connected-service credentials (Plex/Jellyfin tokens, Kodi passwords) encrypted with an AES-256-GCM key
 * held in the Android Keystore. Values never leave the device and are excluded from backups.
 */
/** Secret key/value storage abstraction (tests substitute an in-memory implementation). */
interface SecretStorage {
    fun put(name: String, value: String?)
    fun get(name: String): String?
}

@Singleton
class SecureStore @Inject constructor(@ApplicationContext private val context: Context) : SecretStorage {
    private val prefs by lazy { context.getSharedPreferences("aether_secure", Context.MODE_PRIVATE) }

    private fun key(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }.getOrNull()

    override fun put(name: String, value: String?) {
        if (value == null) { prefs.edit().remove(name).apply(); return }
        val k = key() ?: return // Keystore unavailable: refuse to store in plaintext.
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, k) }
        val ct = cipher.doFinal(value.toByteArray())
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(name, packed).apply()
    }

    override fun get(name: String): String? = runCatching {
        val packed = prefs.getString(name, null) ?: return null
        val (iv, ct) = packed.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(ct))
    }.getOrNull()

    fun clearAll() = prefs.edit().clear().apply()

    val isAvailable: Boolean get() = key() != null

    private companion object {
        const val ALIAS = "aether_credentials_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
