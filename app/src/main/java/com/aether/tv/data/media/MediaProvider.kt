package com.aether.tv.data.media

import com.aether.tv.core.model.MediaItem
import kotlinx.serialization.Serializable

enum class MediaCapability(val label: String) {
    ContinueWatching("Continue watching with progress"),
    RecentlyAdded("Recently added"),
    Search("Search"),
    NowPlaying("Now playing (live session)"),
    Resume("Resume playback"),
}

sealed interface ProviderStatus {
    data object NotConfigured : ProviderStatus
    data object Connecting : ProviderStatus
    data class Connected(val detail: String) : ProviderStatus
    data class NeedsPermission(val detail: String) : ProviderStatus
    data class Error(val message: String) : ProviderStatus
}

/** Persisted (encrypted) per-provider configuration. */
@Serializable
data class ProviderConfig(
    val enabled: Boolean = false,
    val serverUrl: String? = null,
    val token: String? = null,
    val userId: String? = null,
    val userName: String? = null,
    val serverName: String? = null,
    val machineId: String? = null,
    val username: String? = null,
    val password: String? = null,
)

/** Result of starting a provider's device-link flow. */
data class LinkCode(val code: String, val instructions: String, val pollToken: String)

sealed interface ResumeResult {
    data object Started : ResumeResult
    data class Message(val text: String) : ResumeResult
}

/**
 * A media source adapter. Implementations only report data the source actually provides — no fabricated
 * progress or history.
 */
interface MediaProvider {
    val id: String
    val displayName: String
    val description: String
    val capabilities: Set<MediaCapability>
    /** Package of the companion app used to play items, if any. */
    val appPackage: String?

    suspend fun status(): ProviderStatus
    suspend fun continueWatching(limit: Int): List<MediaItem> = emptyList()
    suspend fun recentlyAdded(limit: Int): List<MediaItem> = emptyList()
    suspend fun search(query: String, limit: Int): List<MediaItem> = emptyList()
    suspend fun resume(item: MediaItem): ResumeResult
}

/** Timestamps as media servers write them, to epoch ms (null when absent or unreadable). */
object MediaTimes {
    /** Jellyfin: "2026-11-20T21:13:44.1234567Z" (UTC). */
    fun iso(s: String?): Long? = s?.takeIf { it.length >= 19 }?.let {
        runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(it.substring(0, 19))?.time }.getOrNull()
    }
    /** Kodi: "2026-11-20 21:13:44" in the TV's time zone; empty when never played. */
    fun kodi(s: String?): Long? = s?.takeIf { it.length >= 19 }?.let {
        runCatching { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(it.substring(0, 19))?.time }.getOrNull()
    }
}
