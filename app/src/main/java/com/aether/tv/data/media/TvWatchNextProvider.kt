package com.aether.tv.data.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.aether.tv.core.model.MediaItem
import com.aether.tv.core.model.MediaKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Continue Watching that apps publish to Android TV itself (Nuvio, Netflix, Prime Video, Plex …), read the way the
 * Android TV home screen reads it:
 * 1. the system **Watch Next** list, newest engagement first;
 * 2. apps' own home-screen channels named "Continue …" (Stremio-style apps such as Nuvio publish one of these, with
 *    progress and a resume link, instead of or as well as Watch Next).
 * Needs the TV listings permission. Only what the apps publish is shown: no history is kept by Aether.
 */
@Singleton
class TvWatchNextProvider @Inject constructor(@ApplicationContext private val context: Context) : MediaProvider {
    override val id = ID
    override val displayName = "Apps on this TV (Watch Next)"
    override val description = "Continue Watching that apps such as Nuvio publish to Android TV: the Watch Next list and their “Continue watching” channels. Needs TV listings access."
    override val capabilities = setOf(MediaCapability.ContinueWatching, MediaCapability.Resume)
    override val appPackage: String? = null

    /** Resume links by item id, from the last read. */
    private val links = ConcurrentHashMap<String, Pair<String?, String?>>()

    fun hasAccess() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    override suspend fun status(): ProviderStatus =
        if (hasAccess()) ProviderStatus.Connected("TV listings access allowed")
        else ProviderStatus.NeedsPermission("Allow TV listings access to show what apps like Nuvio publish")

    override suspend fun continueWatching(limit: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext emptyList()
        val watchNext = read(Uri.parse(WATCH_NEXT_URI), WATCH_NEXT_COLUMNS, "wn")
            .filter { it.watchNextType != TYPE_WATCHLIST }
            .sortedByDescending { it.engaged }
        val channels = continueChannels()
        val fromChannels = channels.flatMap { (cid, _) ->
            read(Uri.parse("$PREVIEW_URI?channel=$cid"), PREVIEW_COLUMNS, "ch$cid").sortedByDescending { it.weight }
        }
        Log.i(TAG, "continue watching: ${watchNext.size} from Watch Next, ${fromChannels.size} from ${channels.size} channels")
        val all = (watchNext + fromChannels).distinctBy { it.link ?: (it.pkg + it.item.title) }.take(limit)
        links.clear()
        all.forEach { links[it.item.id] = it.link to it.pkg }
        all.map { it.item }
    }

    /** (id, package) of other apps' channels whose name starts with "Continue" (Continue Watching, Continue watching …). */
    private fun continueChannels(): List<Pair<Long, String>> = runCatching {
        val out = ArrayList<Pair<Long, String>>()
        context.contentResolver.query(Uri.parse(CHANNELS_URI), arrayOf("_id", "package_name", "display_name", "browsable"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val pkg = c.str("package_name") ?: continue
                val name = c.str("display_name") ?: continue
                if (pkg == context.packageName || c.str("browsable") == "0") continue
                if (name.trim().startsWith("continue", ignoreCase = true)) out += c.getLong(c.getColumnIndexOrThrow("_id")) to pkg
            }
        }
        out
    }.getOrDefault(emptyList())

    private class Row(val item: MediaItem, val link: String?, val pkg: String?, val engaged: Long, val weight: Long, val watchNextType: Int)

    /** Reads only the named columns: a null projection fails on TVs whose provider lacks newer columns. */
    private fun read(uri: Uri, columns: Array<String>, prefix: String): List<Row> = runCatching {
        val out = ArrayList<Row>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.str("browsable") == "0") continue
                val title = c.str("title") ?: continue
                val pkg = c.str("package_name")
                val duration = c.num("duration_millis")
                val position = c.num("last_playback_position_millis")
                val kind = when (c.str("type")?.toIntOrNull()) {
                    0 -> MediaKind.Movie
                    1, 2, 3 -> MediaKind.Episode
                    7, 8, 9, 10, 11 -> MediaKind.Music
                    else -> MediaKind.Video
                }
                val episode = WatchNextText.episode(c.str("season_display_number"), c.str("episode_display_number"), c.str("episode_title"))
                val sub = listOfNotNull(episode, WatchNextText.timeLeft(duration, position), appLabel(pkg)).joinToString(" • ").ifBlank { null }
                out += Row(
                    MediaItem(
                        id = "$prefix:${c.num("_id")}", providerId = ID, title = title, subtitle = sub,
                        artworkUrl = c.str("thumbnail_uri") ?: c.str("poster_art_uri"),
                        progress = WatchNextText.progress(duration, position),
                        durationMs = duration.takeIf { it > 0 }, kind = kind,
                    ),
                    c.str("intent_uri"), pkg, c.num("last_engagement_time_utc_millis"), c.num("weight"),
                    c.str("watch_next_type")?.toIntOrNull() ?: -1,
                )
            }
        }
        out
    }.onFailure { Log.w(TAG, "can't read $uri: ${it.message}") }.getOrDefault(emptyList())

    private val labels = ConcurrentHashMap<String, String>()
    private fun appLabel(pkg: String?): String? = pkg?.let { p ->
        labels[p] ?: runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }
            .getOrNull()?.also { labels[p] = it }
    }

    override suspend fun resume(item: MediaItem): ResumeResult = withContext(Dispatchers.Main) {
        val (link, pkg) = links[item.id] ?: return@withContext ResumeResult.Message("That item is no longer listed.")
        val intent = WatchNextText.resumeIntent(link, pkg)
        if (intent != null && runCatching { context.startActivity(intent); true }.getOrDefault(false)) return@withContext ResumeResult.Started
        val launch = pkg?.let { context.packageManager.getLeanbackLaunchIntentForPackage(it) ?: context.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ResumeResult.Started }
        else ResumeResult.Message("The app that published this isn't installed.")
    }

    private fun Cursor.str(col: String) = getColumnIndex(col).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.num(col: String) = getColumnIndex(col).let { if (it < 0 || isNull(it)) 0L else getLong(it) }

    companion object {
        const val ID = "tvwatchnext"
        const val PERMISSION = "android.permission.READ_TV_LISTINGS"
        private const val TAG = "AetherWatchNext"
        private const val WATCH_NEXT_URI = "content://android.media.tv/watch_next_program"
        private const val PREVIEW_URI = "content://android.media.tv/preview_program"
        private const val CHANNELS_URI = "content://android.media.tv/channel"
        private const val TYPE_WATCHLIST = 3
        private val COMMON = arrayOf(
            "_id", "browsable", "title", "episode_title", "package_name", "season_display_number", "episode_display_number",
            "duration_millis", "last_playback_position_millis", "thumbnail_uri", "poster_art_uri", "type", "intent_uri",
        )
        private val WATCH_NEXT_COLUMNS = COMMON + arrayOf("watch_next_type", "last_engagement_time_utc_millis")
        private val PREVIEW_COLUMNS = COMMON + "weight"
    }
}

/** Pure helpers (unit-tested). */
object WatchNextText {
    /** "S2 E5 · The Title", "S2 E5", or just the episode title. */
    fun episode(season: String?, episode: String?, title: String?): String? {
        val se = if (!season.isNullOrBlank() && !episode.isNullOrBlank()) "S${season.trim()} E${episode.trim()}" else null
        return listOfNotNull(se, title?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }
    }

    /** Under a second in means "not really started": no progress bar. */
    fun progress(duration: Long, position: Long): Float? =
        if (duration > 0 && position >= 1000) (position.toFloat() / duration).coerceIn(0f, 1f) else null

    fun timeLeft(duration: Long, position: Long): String? {
        if (duration <= 0 || position < 1000 || position >= duration) return null
        val min = ((duration - position) / 60_000).coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${min % 60} min left" else "$min min left"
    }

    /**
     * The app's own resume link, limited to that app: an `intent:` URI may only open the publishing app's activities
     * (no selector, no URI-permission grants), and a plain URI opens with the publishing app.
     */
    fun resumeIntent(link: String?, pkg: String?): Intent? {
        if (link.isNullOrBlank()) return null
        val intent = runCatching {
            if (link.startsWith("intent:") || link.startsWith("#Intent")) Intent.parseUri(link, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(link))
        }.getOrNull() ?: return null
        intent.selector = null
        intent.flags = intent.flags and GRANT_FLAGS.inv()
        val target = intent.component?.packageName ?: intent.`package`
        if (pkg != null) {
            if (target != null && target != pkg) return null
            if (target == null) intent.setPackage(pkg)
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
}
