package com.aether.tv.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import android.media.session.PlaybackState
import com.aether.tv.core.model.MediaItem
import com.aether.tv.core.model.MediaKind
import com.aether.tv.platform.NotificationHub
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitmaps from media sessions can't be addressed by URL; cards look them up here by "session:<key>". Bounded: a TV
 * playing music for hours changes artwork with every track, and only the newest few are ever on screen.
 */
object SessionArtCache {
    private const val MAX_BYTES = 12 * 1024 * 1024
    private val cache = object : android.util.LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount.coerceAtLeast(1)
    }
    fun put(id: String, b: Bitmap?) { if (b == null) cache.remove(id) else cache.put(id, b) }
    fun get(url: String?): Bitmap? = url?.takeIf { it.startsWith("session:") }?.let { cache.get(it.removePrefix("session:")) }
    /** Number of pictures held. */
    fun count(): Int = cache.snapshot().size
    fun clear() = cache.evictAll()
}

/**
 * Android media sessions published by any app (YouTube, Netflix, Spotify, Plex …). Requires notification access.
 * Only reports what a session exposes: title, artwork, playback state and position while the session is alive. It is
 * not a viewing history: the card is labelled "Now Playing" and disappears when the app ends its session.
 *
 * While Aether is in front, [observe] follows sessions through callbacks (new and ended sessions, metadata, playback
 * state), so the card updates as the app changes episode or pauses.
 */
@Singleton
class MediaSessionProvider @Inject constructor(@ApplicationContext private val context: Context) : MediaProvider {
    override val id = ID
    override val displayName = "Now playing (media sessions)"
    override val description = "Shows what any app is playing or has paused, using Android media sessions. Needs notification access. It is not a viewing history."
    override val capabilities = setOf(MediaCapability.NowPlaying, MediaCapability.Resume)
    override val appPackage: String? = null

    private val controllers = ConcurrentHashMap<String, MediaController>()
    /** Clock for tests. */
    internal var now: () -> Long = System::currentTimeMillis
    internal var elapsed: () -> Long = android.os.SystemClock::elapsedRealtime

    override suspend fun status(): ProviderStatus =
        if (NotificationHub.isAccessGranted(context)) ProviderStatus.Connected("Notification access granted")
        else ProviderStatus.NeedsPermission("Grant notification access to see what's playing")

    /**
     * Follows active sessions until closed: [onChange] runs when a session starts or ends, or one changes what it plays
     * or its playback state. Without notification access this does nothing (and returns a no-op).
     */
    fun observe(onChange: () -> Unit): Closeable {
        if (!NotificationHub.isAccessGranted(context)) return Closeable { }
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val handler = Handler(Looper.getMainLooper())
        val watched = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
        fun follow(list: List<MediaController>?) {
            val current = list.orEmpty().filter { it.packageName != context.packageName }
            val tokens = current.map { it.sessionToken }.toSet()
            watched.keys.filter { it !in tokens }.forEach { t -> watched.remove(t)?.let { (c, cb) -> runCatching { c.unregisterCallback(cb) } } }
            current.filter { it.sessionToken !in watched }.forEach { c ->
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = onChange()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = onChange()
                    override fun onSessionDestroyed() = onChange()
                }
                runCatching { c.registerCallback(cb, handler) }.onSuccess { watched[c.sessionToken] = c to cb }
            }
        }
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> follow(list); onChange() }
        val ok = runCatching { msm.addOnActiveSessionsChangedListener(listener, NotificationHub.listenerComponent(context), handler) }.isSuccess
        if (ok) follow(NotificationHub.activeSessions(context))
        return Closeable {
            runCatching { msm.removeOnActiveSessionsChangedListener(listener) }
            watched.values.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
            watched.clear()
        }
    }

    override suspend fun continueWatching(limit: Int): List<MediaItem> = withContext(Dispatchers.Main) {
        val sessions = NotificationHub.activeSessions(context) ?: return@withContext emptyList()
        val fresh = HashMap<String, MediaController>()
        val items = sessions.filter { it.packageName != context.packageName }.mapNotNull { mc ->
            val md = mc.metadata ?: return@mapNotNull null
            val state = mc.playbackState
            sessionItem(
                pkg = mc.packageName, appLabel = appLabel(mc.packageName),
                title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE),
                subtitle = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                state = state?.state, position = state?.position ?: -1, speed = state?.playbackSpeed ?: 1f,
                updatedAtElapsed = state?.lastPositionUpdateTime ?: 0L,
                art = { md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) },
            )?.also { fresh[it.id] = mc }
        }.take(limit)
        controllers.clear(); controllers.putAll(fresh)
        items
    }

    /**
     * One card per app's session ("session:<package>"), so the card updates in place, keeping focus, when the app
     * moves to the next episode. Its artwork key changes with the title so the picture follows.
     */
    internal fun sessionItem(
        pkg: String, appLabel: String?, title: String?, subtitle: String?, durationMs: Long, state: Int?, position: Long,
        speed: Float, updatedAtElapsed: Long, art: () -> Bitmap?,
    ): MediaItem? {
        if (title.isNullOrBlank()) return null
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR) return null
        val playing = state == PlaybackState.STATE_PLAYING
        val duration = durationMs.takeIf { it > 0 }
        // The position the session reported, moved on by the time since it reported it when playing.
        val pos = position.takeIf { it >= 0 }?.let { p -> if (playing && updatedAtElapsed > 0) p + ((elapsed() - updatedAtElapsed) * speed).toLong() else p }
        val engaged = if (playing || updatedAtElapsed <= 0) now() else now() - (elapsed() - updatedAtElapsed).coerceAtLeast(0)
        val artKey = "$pkg:${title.hashCode().toUInt().toString(36)}"
        art()?.let { SessionArtCache.put(artKey, it) }
        return MediaItem(
            id = "session:$pkg", providerId = ID, title = title,
            subtitle = listOfNotNull("Now Playing", if (playing) null else "Paused", subtitle?.takeIf { it.isNotBlank() }, appLabel).joinToString(" • "),
            artworkUrl = "session:$artKey",
            progress = if (duration != null && pos != null) (pos.toFloat() / duration).coerceIn(0f, 1f) else null,
            durationMs = duration, kind = if (duration == null) MediaKind.Live else MediaKind.Video,
            packageName = pkg, engagedAt = engaged, nowPlaying = true,
        )
    }

    private fun appLabel(pkg: String) = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    override suspend fun resume(item: MediaItem): ResumeResult = withContext(Dispatchers.Main) {
        val mc = controllers[item.id] ?: return@withContext ResumeResult.Message("That session has ended.")
        val activity = mc.sessionActivity
        runCatching { mc.transportControls.play() }
        if (activity != null && runCatching { activity.send(); true }.getOrDefault(false)) ResumeResult.Started
        else {
            val launch = context.packageManager.getLeanbackLaunchIntentForPackage(mc.packageName) ?: context.packageManager.getLaunchIntentForPackage(mc.packageName)
            if (launch != null) { context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); ResumeResult.Started }
            else ResumeResult.Message("Resumed playback in the background.")
        }
    }

    companion object { const val ID = "sessions" }
}
