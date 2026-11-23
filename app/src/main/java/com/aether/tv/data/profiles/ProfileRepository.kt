package com.aether.tv.data.profiles

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aether.tv.data.db.AetherDatabase
import com.aether.tv.data.db.ProfileEntity
import com.aether.tv.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val db: AetherDatabase,
    private val store: DataStore<Preferences>,
    private val settings: SettingsRepository,
) {
    private val activeKey = stringPreferencesKey("active_profile")

    val profiles: Flow<List<ProfileEntity>> = db.profiles().observeAll()
    val activeProfileId: Flow<String> = store.data.map { it[activeKey] ?: DEFAULT_ID }

    /** Creates the default profile on first launch. */
    suspend fun ensureDefault() {
        if (db.profiles().get(DEFAULT_ID) == null) db.profiles().upsert(ProfileEntity(DEFAULT_ID, "Main", 0xFFBFDFFF))
        val active = store.data.first()[activeKey]
        if (active == null || db.profiles().get(active) == null) store.edit { it[activeKey] = DEFAULT_ID }
    }

    suspend fun activeId(): String = activeProfileId.first()

    suspend fun create(name: String, colorArgb: Long, copySettingsFrom: String?): ProfileEntity {
        val p = ProfileEntity(UUID.randomUUID().toString(), name.trim().ifEmpty { "Profile" }, colorArgb)
        db.profiles().upsert(p)
        if (copySettingsFrom != null) settings.replace(p.id, settings.get(copySettingsFrom))
        return p
    }

    suspend fun rename(id: String, name: String) { db.profiles().get(id)?.let { db.profiles().upsert(it.copy(name = name.trim().ifEmpty { it.name })) } }

    /** Switches the active profile. Returns false when a PIN is set and [pin] doesn't match. */
    suspend fun switchTo(id: String, pin: String?): Boolean {
        val p = db.profiles().get(id) ?: return false
        if (p.pinHash != null && (pin == null || !verifyPin(pin, p.pinHash))) return false
        store.edit { it[activeKey] = id }
        return true
    }

    suspend fun setPin(id: String, pin: String?) {
        val p = db.profiles().get(id) ?: return
        db.profiles().upsert(p.copy(pinHash = pin?.takeIf { it.isNotBlank() }?.let { hashPin(it) }))
    }

    suspend fun delete(id: String) {
        if (id == DEFAULT_ID) return
        db.profiles().delete(id)
        db.favorites().clear(id); db.appMeta().clear(id); db.categories().clear(id); db.games().clear(id)
        settings.remove(id)
        if (activeId() == id) store.edit { it[activeKey] = DEFAULT_ID }
    }

    companion object {
        const val DEFAULT_ID = "default"
        private const val ITERATIONS = 20_000

        fun hashPin(pin: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }, iterations: Int = ITERATIONS): String {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            return "$iterations:${Base64.encodeToString(salt, Base64.NO_WRAP)}:${Base64.encodeToString(hash, Base64.NO_WRAP)}"
        }

        fun verifyPin(pin: String, stored: String): Boolean {
            val parts = stored.split(":"); if (parts.size != 3) return false
            val salt = Base64.decode(parts[1], Base64.NO_WRAP)
            val expected = hashPin(pin, salt, parts[0].toInt())
            return java.security.MessageDigest.isEqual(expected.toByteArray(), stored.toByteArray())
        }
    }
}
