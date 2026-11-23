package com.aether.tv.data.media

import com.aether.tv.core.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class ProviderState(val provider: MediaProvider, val status: ProviderStatus)

data class MediaSnapshot(
    val continueWatching: List<MediaItem> = emptyList(),
    val recentlyAdded: List<MediaItem> = emptyList(),
    val providers: List<ProviderState> = emptyList(),
    val loading: Boolean = false,
    val lastRefresh: Long = 0,
)

/**
 * Aggregates all adapters. Refreshes happen on demand (app resume, page visit) and are throttled — there is no
 * background polling.
 */
@Singleton
class MediaRepository @Inject constructor(
    val sessions: MediaSessionProvider,
    val watchNext: TvWatchNextProvider,
    val plex: PlexProvider,
    val jellyfin: JellyfinProvider,
    val kodi: KodiProvider,
) {
    val providers: List<MediaProvider> get() = listOf(sessions, watchNext, plex, jellyfin, kodi)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MediaSnapshot())
    val state: StateFlow<MediaSnapshot> = _state.asStateFlow()
    private val lock = Mutex()

    fun provider(id: String) = providers.firstOrNull { it.id == id }

    fun refresh(force: Boolean = false, limit: Int = 12) {
        scope.launch {
            lock.withLock {
                val now = System.currentTimeMillis()
                if (!force && now - _state.value.lastRefresh < 60_000) return@withLock
                _state.value = _state.value.copy(loading = true)
                val statuses = providers.map { p -> async { ProviderState(p, runCatching { withTimeoutOrNull(12_000) { p.status() } ?: ProviderStatus.Error("Timed out") }.getOrElse { ProviderStatus.Error(it.message ?: "Error") }) } }.awaitAll()
                val active = statuses.filter { it.status is ProviderStatus.Connected }.map { it.provider }
                val cw = active.map { p -> async { runCatching { withTimeoutOrNull(12_000) { p.continueWatching(limit) } }.getOrNull().orEmpty() } }.awaitAll().flatten()
                val ra = active.map { p -> async { runCatching { withTimeoutOrNull(12_000) { p.recentlyAdded(limit) } }.getOrNull().orEmpty() } }.awaitAll().flatten()
                _state.value = MediaSnapshot(cw, ra, statuses, loading = false, lastRefresh = now)
            }
        }
    }

    suspend fun search(query: String): List<MediaItem> = providers.filter { MediaCapability.Search in it.capabilities }.map { p ->
        scope.async { runCatching { withTimeoutOrNull(8_000) { if (p.status() is ProviderStatus.Connected) p.search(query, 8) else emptyList() } }.getOrNull().orEmpty() }
    }.awaitAll().flatten()

    suspend fun resume(item: MediaItem): ResumeResult = provider(item.providerId)?.resume(item) ?: ResumeResult.Message("Provider unavailable")
}
