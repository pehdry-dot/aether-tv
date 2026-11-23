package com.aether.tv.data.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
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

/** Bitmaps from media sessions can't be addressed by URL; cards look them up here by "session:<id>". */
object SessionArtCache {
    private val map = ConcurrentHashMap<String, Bitmap>()
    fun put(id: String, b: Bitmap?) { if (b == null) map.remove(id) else map[id] = b }
    fun get(url: String?): Bitmap? = url?.takeIf { it.startsWith("session:") }?.let { map[it.removePrefix("session:")] }
}

/**
 * Android media sessions published by any app (Netflix, YouTube, Spotify, Plex …). Requires notification access.
 * Only reports what a session exposes: title, artwork, and position while the session is alive.
 * It is not a viewing history — when the app ends its session, the item disappears.
 */
@Singleton
class MediaSessionProvider @Inject constructor(@ApplicationContext private val context: Context) : MediaProvider {
    override val id = ID
    override val displayName = "Now playing (media sessions)"
    override val description = "Shows what any app is playing or has paused, using Android media sessions. Needs notification access."
    override val capabilities = setOf(MediaCapability.NowPlaying, MediaCapability.Resume)
    override val appPackage: String? = null

    private val controllers = ConcurrentHashMap<String, MediaController>()

    override suspend fun status(): ProviderStatus =
        if (NotificationHub.isAccessGranted(context)) ProviderStatus.Connected("Notification access granted")
        else ProviderStatus.NeedsPermission("Grant notification access to see what's playing")

    override suspend fun continueWatching(limit: Int): List<MediaItem> = withContext(Dispatchers.Main) {
        val sessions = NotificationHub.activeSessions(context) ?: return@withContext emptyList()
        controllers.clear()
        sessions.filter { it.packageName != context.packageName }.mapNotNull { mc ->
            val md = mc.metadata ?: return@mapNotNull null
            val title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return@mapNotNull null
            val sub = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
            val pos = mc.playbackState?.position?.takeIf { it >= 0 }
            val id = "${mc.packageName}:${title.hashCode()}"
            controllers[id] = mc
            SessionArtCache.put(id, md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))
            val playing = mc.playbackState?.state == PlaybackState.STATE_PLAYING
            MediaItem(
                id = id, providerId = ID, title = title,
                subtitle = listOfNotNull(sub, appLabel(mc.packageName), if (playing) "Playing" else "Paused").joinToString(" • "),
                artworkUrl = "session:$id",
                progress = if (duration != null && pos != null) (pos.toFloat() / duration).coerceIn(0f, 1f) else null,
                durationMs = duration, kind = if (duration == null) MediaKind.Live else MediaKind.Video,
            )
        }.take(limit)
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
