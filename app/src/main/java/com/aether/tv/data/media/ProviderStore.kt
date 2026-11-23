package com.aether.tv.data.media

import com.aether.tv.data.security.SecretStorage
import com.aether.tv.data.settings.AetherJson
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypted storage for provider configuration including tokens. */
@Singleton
class ProviderStore @Inject constructor(private val secure: SecretStorage) {
    fun load(id: String): ProviderConfig = secure.get("provider_$id")?.let {
        runCatching { AetherJson.decodeFromString(ProviderConfig.serializer(), it) }.getOrNull()
    } ?: ProviderConfig()

    fun save(id: String, c: ProviderConfig) = secure.put("provider_$id", AetherJson.encodeToString(ProviderConfig.serializer(), c))
    fun clear(id: String) = secure.put("provider_$id", null)

    /** Stable per-install client id (required by Plex and Jellyfin device registration). */
    val clientId: String get() = secure.get("client_id") ?: UUID.randomUUID().toString().also { secure.put("client_id", it) }
}
