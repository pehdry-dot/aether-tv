package com.aether.tv.data.media

import com.aether.tv.core.model.MediaItem
import com.aether.tv.platform.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class ProviderState(val provider: MediaProvider, val status: ProviderStatus)

/**
 * What one source last gave Aether. [items] is the last *successful* read: a failed read keeps it (with [lastError]),
 * so an offline server shows what it last had, and Diagnostics shows how old it is.
 */
data class SourceState(
    val id: String,
    val status: ProviderStatus = ProviderStatus.Connecting,
    val items: List<MediaItem> = emptyList(),
    val recentlyAdded: List<MediaItem> = emptyList(),
    /** Epoch ms of the last successful read (0: never). A successful read may be empty. */
    val lastSuccessAt: Long = 0,
    val lastAttemptAt: Long = 0,
    val lastError: String? = null,
    val refreshing: Boolean = false,
)

data class MediaSnapshot(
    /** Every source merged, deduplicated and ranked; Home applies its display limit to this. */
    val continueWatching: List<MediaItem> = emptyList(),
    val recentlyAdded: List<MediaItem> = emptyList(),
    val providers: List<ProviderState> = emptyList(),
    val sources: Map<String, SourceState> = emptyMap(),
    val loading: Boolean = false,
    val lastRefresh: Long = 0,
)

/**
 * Aggregates all media sources. Each source refreshes on its own, so an offline server or a slow "Recently added"
 * never holds back a fresh Watch Next read.
 *
 * - **Apps on this TV** (Watch Next, channels, media sessions) are read whenever Aether comes to the front and, while
 *   it stays there, whenever the system reports a change (bursts are coalesced). A short follow-up read catches
 *   publishers that write just after they go to the background without notifying.
 * - **Servers** (Plex, Jellyfin, Kodi) are read when Aether comes to the front, at most once a minute unless asked.
 * Nothing is read while Aether is in the background: no polling.
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
    private val local: Set<String> get() = setOf(sessions.id, watchNext.id)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sources = MutableStateFlow(providers.associate { it.id to SourceState(it.id) })
    private val _state = MutableStateFlow(MediaSnapshot(providers = providers.map { ProviderState(it, ProviderStatus.Connecting) }))
    val state: StateFlow<MediaSnapshot> = _state.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    /** A source asked to refresh while it was already reading: read once more when that finishes. */
    private val again = ConcurrentHashMap.newKeySet<String>()

    /** Clock (tests). */
    internal var now: () -> Long = System::currentTimeMillis

    fun provider(id: String) = providers.firstOrNull { it.id == id }

    /** Reads every source: apps on this TV always, servers unless they were read in the last minute ([force] reads all). */
    fun refresh(force: Boolean = false) = providers.forEach { refreshSource(it.id, force) }

    /** Reads one source now (or right after its current read). */
    fun refreshSource(id: String, force: Boolean = true) {
        val p = provider(id) ?: return
        val st = sources.value[id]
        if (!force && id !in local && st != null && now() - st.lastAttemptAt < REMOTE_MIN_INTERVAL_MS) return
        if (jobs[id]?.isActive == true) { again += id; return }
        jobs[id] = scope.launch {
            do { again.remove(id); read(p) } while (id in again)
        }
    }

    /** Drops what a source showed (its account changed or it was disconnected), then reads it again. */
    fun invalidate(id: String) {
        update(id) { SourceState(id) }
        refreshSource(id, force = true)
    }

    private suspend fun read(p: MediaProvider) {
        val start = now()
        update(p.id) { it.copy(refreshing = true, lastAttemptAt = start) }
        val timeout = if (p.id in local) LOCAL_TIMEOUT_MS else REMOTE_TIMEOUT_MS
        val status = runCatching { withTimeoutOrNull(timeout) { p.status() } ?: ProviderStatus.Error("Timed out") }
            .getOrElse { ProviderStatus.Error(it.message ?: it.javaClass.simpleName) }
        when (status) {
            is ProviderStatus.Connected -> {
                val result = runCatching {
                    withTimeout(timeout) {
                        val cw = async { p.continueWatching(SOURCE_LIMIT) }
                        val ra = async { if (MediaCapability.RecentlyAdded in p.capabilities) runCatching { p.recentlyAdded(RECENT_LIMIT) }.getOrNull() else emptyList() }
                        cw.await() to ra.await()
                    }
                }
                result.onSuccess { (cw, ra) ->
                    update(p.id) { it.copy(status = status, items = cw, recentlyAdded = ra ?: it.recentlyAdded, lastSuccessAt = now(), lastError = null, refreshing = false) }
                }.onFailure { e ->
                    val msg = (e.message ?: e.javaClass.simpleName).let { if (e is kotlinx.coroutines.TimeoutCancellationException) "Timed out" else it }
                    EventLog.log("Continue Watching: ${p.displayName} read failed ($msg); keeping its last result")
                    update(p.id) { it.copy(status = status, lastError = msg, refreshing = false) }
                }
            }
            // Not set up or not allowed: nothing to show (an empty answer, not a failure).
            is ProviderStatus.NotConfigured, is ProviderStatus.NeedsPermission ->
                update(p.id) { it.copy(status = status, items = emptyList(), recentlyAdded = emptyList(), lastError = null, refreshing = false) }
            // Unreachable: keep the last good result and say so.
            is ProviderStatus.Error -> update(p.id) { it.copy(status = status, lastError = status.message, refreshing = false) }
            ProviderStatus.Connecting -> update(p.id) { it.copy(refreshing = false) }
        }
    }

    private fun update(id: String, f: (SourceState) -> SourceState) {
        sources.update { m -> m + (id to f(m[id] ?: SourceState(id))) }
        publish()
    }

    private fun publish() {
        val m = sources.value
        val order = providers.mapIndexed { i, p -> p.id to i }.toMap()
        val merged = ContinueWatchingRanker.merge(
            providers.map { p -> ContinueWatchingRanker.SourceItems(p.id, order.getValue(p.id), m[p.id]?.items.orEmpty()) },
        )
        _state.value = MediaSnapshot(
            continueWatching = merged,
            recentlyAdded = providers.flatMap { m[it.id]?.recentlyAdded.orEmpty() },
            providers = providers.map { ProviderState(it, m[it.id]?.status ?: ProviderStatus.Connecting) },
            sources = m,
            loading = m.values.any { it.refreshing },
            lastRefresh = m.values.maxOfOrNull { it.lastSuccessAt } ?: 0,
        )
    }

    // ---------------------------------------------------------------- while Aether is in front
    private var watchers: List<Closeable> = emptyList()
    private var followUps: Job? = null
    private var debounce: Job? = null
    private var firstChangeAt = 0L

    /**
     * Aether came to the front: watch the TV's listings and media sessions (registered *before* the first read, so a
     * write in between isn't lost), read everything, and read the local listings again shortly after, for apps that
     * write as they go to the background without notifying.
     */
    fun startWatching() {
        if (watchers.isEmpty()) {
            val tick = scope.launch {
                // A media notification posted or removed: sessions may have changed (also where callbacks aren't delivered).
                var first = true
                com.aether.tv.platform.NotificationHub.mediaTick.collect { if (first) first = false else localChanged(sessions.id) }
            }
            watchers = listOf(watchNext.observe { localChanged(watchNext.id) }, sessions.observe { localChanged(sessions.id) }, Closeable { tick.cancel() })
        }
        refresh(force = false)
        followUps?.cancel()
        followUps = scope.launch {
            for (d in FOLLOW_UP_READS_MS) { delay(d); refreshSource(watchNext.id) }
        }
    }

    /** Aether left the front: stop watching (nothing is read in the background). */
    fun stopWatching() {
        watchers.forEach { runCatching { it.close() } }
        watchers = emptyList()
        followUps?.cancel(); followUps = null
        debounce?.cancel(); debounce = null
    }

    /** A burst of changes (an app rewriting its whole list) becomes one read: after a quiet moment, or at most a little later. */
    private fun localChanged(id: String) {
        val t = now()
        if (debounce?.isActive != true) firstChangeAt = t
        debounce?.cancel()
        val wait = (DEBOUNCE_MS).coerceAtMost((firstChangeAt + MAX_DEBOUNCE_MS - t).coerceAtLeast(0))
        debounce = scope.launch { delay(wait); refreshSource(id) }
    }

    suspend fun search(query: String): List<MediaItem> = providers.filter { MediaCapability.Search in it.capabilities }.map { p ->
        scope.async { runCatching { withTimeoutOrNull(8_000) { if (p.status() is ProviderStatus.Connected) p.search(query, 8) else emptyList() } }.getOrNull().orEmpty() }
    }.awaitAll().flatten()

    suspend fun resume(item: MediaItem): ResumeResult = provider(item.providerId)?.resume(item) ?: ResumeResult.Message("Provider unavailable")

    companion object {
        const val REMOTE_MIN_INTERVAL_MS = 60_000L
        const val LOCAL_TIMEOUT_MS = 5_000L
        const val REMOTE_TIMEOUT_MS = 12_000L
        const val SOURCE_LIMIT = 60
        const val RECENT_LIMIT = 12
        const val DEBOUNCE_MS = 350L
        const val MAX_DEBOUNCE_MS = 1_500L
        /** Reads after coming to the front (Nuvio writes Watch Next as playback stops and again in onStop). */
        val FOLLOW_UP_READS_MS = listOf(2_000L, 4_000L)
    }
}
