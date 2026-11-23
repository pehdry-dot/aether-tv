package com.aether.tv.data.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import java.io.Closeable
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
 * 1. the system **Watch Next** list;
 * 2. apps' own home-screen channels of things in progress (Nuvio publishes a "Continue Watching" channel, with a
 *    localized name, as well as Watch Next), and any program that carries a playback position.
 * Needs the TV listings permission. Only what the apps publish is shown: no history is kept by Aether.
 *
 * While Aether is in front, [observe] watches all three tables, so a publisher that writes its entry just after it goes
 * to the background (Nuvio does so when playback stops and again in onStop) shows up on Home without a refresh.
 */
@Singleton
class TvWatchNextProvider @Inject constructor(@ApplicationContext private val context: Context) : MediaProvider {
    override val id = ID
    override val displayName = "Apps on this TV (Watch Next)"
    override val description = "Continue Watching that apps such as Nuvio publish to Android TV: the Watch Next list and their “Continue watching” channels. Needs TV listings access."
    override val capabilities = setOf(MediaCapability.ContinueWatching, MediaCapability.Resume)
    override val appPackage: String? = null

    /** Resume links (link, publishing package) by item id, from the last read. Replaced whole, never cleared midway. */
    @Volatile private var links: Map<String, Pair<String?, String?>> = emptyMap()

    fun hasAccess() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    override suspend fun status(): ProviderStatus =
        if (hasAccess()) ProviderStatus.Connected("TV listings access allowed")
        else ProviderStatus.NeedsPermission("Allow TV listings access to show what apps like Nuvio publish")

    /**
     * Calls [onChange] whenever an app changes its Watch Next entries, channels or channel programs. Register before
     * the first read, so a write that lands between the two isn't missed. Close the result when Aether leaves the front.
     */
    fun observe(onChange: () -> Unit): Closeable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange()
        }
        val ok = listOf(WATCH_NEXT_URI, PREVIEW_URI, CHANNELS_URI).count { u ->
            runCatching { context.contentResolver.registerContentObserver(Uri.parse(u), true, observer) }
                .onFailure { Log.w(TAG, "can't observe $u: ${it.message}") }.isSuccess
        }
        Log.i(TAG, "observing $ok TV provider tables")
        return Closeable { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }

    override suspend fun continueWatching(limit: Int): List<MediaItem> = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext emptyList()
        val watchNext = query(Uri.parse(WATCH_NEXT_URI), WATCH_NEXT_COLUMNS, COMMON + "watch_next_type" + "last_engagement_time_utc_millis")
            .filter { it.watchNextType != TYPE_WATCHLIST }
            .map { it.toItem("wn") }
            .sortedByDescending { it.engagedAt ?: 0L }
        val channels = readChannels()
        val continueIds = channels.filter { ContinueChannels.isContinue(it.pkg, it.name, it.providerId) }.map { it.id }.toSet()
        val byId = channels.associateBy { it.id }
        // One query for every channel's programs, filtered here: Fire OS rejects selection clauses on preview_program.
        val programs = query(Uri.parse(PREVIEW_URI), PREVIEW_COLUMNS, COMMON + "weight")
            .filter { p -> p.channelId in continueIds || (p.channelId in byId && p.position >= 1000 && p.duration > 0) }
            .sortedWith(compareBy<Row> { if (it.channelId in continueIds) 0 else 1 }.thenByDescending { it.weight })
            .map { it.toItem("ch") }
        Log.i(TAG, "continue watching: ${watchNext.size} from Watch Next, ${programs.size} from ${continueIds.size} continue channels")
        val all = (watchNext + programs).take(limit)
        links = all.associate { it.id to (it.link to it.packageName) }
        all
    }

    private data class Channel(val id: Long, val pkg: String, val name: String?, val providerId: String?)

    private fun readChannels(): List<Channel> = runCatching {
        val out = ArrayList<Channel>()
        val cols = arrayOf("_id", "package_name", "display_name", "browsable", "internal_provider_id")
        val c = runCatching { context.contentResolver.query(Uri.parse(CHANNELS_URI), cols, null, null, null) }.getOrNull()
            ?: context.contentResolver.query(Uri.parse(CHANNELS_URI), cols.copyOf(4).requireNoNulls(), null, null, null)
        c?.use {
            while (it.moveToNext()) {
                val pkg = it.str("package_name") ?: continue
                if (pkg == context.packageName || it.str("browsable") == "0") continue
                out += Channel(it.getLong(it.getColumnIndexOrThrow("_id")), pkg, it.str("display_name"), it.str("internal_provider_id"))
            }
        }
        out
    }.onFailure { Log.w(TAG, "can't read channels: ${it.message}") }.getOrDefault(emptyList())

    private class Row(
        val title: String, val pkg: String?, val duration: Long, val position: Long, val type: Int?,
        val season: String?, val episode: String?, val episodeTitle: String?, val art: String?, val link: String?,
        val engaged: Long, val weight: Long, val watchNextType: Int, val providerId: String?, val channelId: Long, val rowId: Long,
    )

    private fun Row.toItem(prefix: String): MediaItem {
        val kind = when (type) {
            0 -> MediaKind.Movie
            1, 2, 3 -> MediaKind.Episode
            7, 8, 9, 10, 11 -> MediaKind.Music
            else -> MediaKind.Video
        }
        val ep = WatchNextText.episode(season, episode, episodeTitle)
        val sub = listOfNotNull(ep, WatchNextText.timeLeft(duration, position), appLabel(pkg)).joinToString(" • ").ifBlank { null }
        return MediaItem(
            id = WatchNextText.identity(prefix, pkg, providerId, link, title, season, episode, episodeTitle, rowId),
            providerId = ID, title = title, subtitle = sub, artworkUrl = art,
            progress = WatchNextText.progress(duration, position), durationMs = duration.takeIf { it > 0 }, kind = kind,
            packageName = pkg, engagedAt = engaged.takeIf { it > 0 },
            season = season?.trim()?.toIntOrNull(), episode = episode?.trim()?.toIntOrNull(), link = link,
        )
    }

    /**
     * Reads only the named columns: a null projection fails on TVs whose provider lacks newer columns. If [columns]
     * includes one this TV's provider doesn't have, [basic] is used instead.
     */
    private fun query(uri: Uri, columns: Array<String>, basic: Array<String>): List<Row> = runCatching {
        val out = ArrayList<Row>()
        val cursor = runCatching { context.contentResolver.query(uri, columns, null, null, null) }
            .recoverCatching { context.contentResolver.query(uri, basic, null, null, null) }.getOrThrow()
        cursor?.use { c ->
            while (c.moveToNext()) {
                if (c.str("browsable") == "0") continue
                val title = c.str("title") ?: continue
                out += Row(
                    title = title, pkg = c.str("package_name"), duration = c.num("duration_millis"), position = c.num("last_playback_position_millis"),
                    type = c.str("type")?.toIntOrNull(), season = c.str("season_display_number"), episode = c.str("episode_display_number"),
                    episodeTitle = c.str("episode_title"), art = c.str("thumbnail_uri") ?: c.str("poster_art_uri"), link = c.str("intent_uri"),
                    engaged = c.num("last_engagement_time_utc_millis"), weight = c.num("weight"),
                    watchNextType = c.str("watch_next_type")?.toIntOrNull() ?: -1, providerId = c.str("internal_provider_id"),
                    channelId = c.num("channel_id"), rowId = c.num("_id"),
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
        val (link, pkg) = links[item.id] ?: (item.link to item.packageName)
        if (link == null && pkg == null) return@withContext ResumeResult.Message("That item is no longer listed.")
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
        const val WATCH_NEXT_URI = "content://android.media.tv/watch_next_program"
        const val PREVIEW_URI = "content://android.media.tv/preview_program"
        const val CHANNELS_URI = "content://android.media.tv/channel"
        private const val TYPE_WATCHLIST = 3
        private val COMMON = arrayOf(
            "_id", "browsable", "title", "episode_title", "package_name", "season_display_number", "episode_display_number",
            "duration_millis", "last_playback_position_millis", "thumbnail_uri", "poster_art_uri", "type", "intent_uri",
        )
        private val WATCH_NEXT_COLUMNS = COMMON + arrayOf("watch_next_type", "last_engagement_time_utc_millis", "internal_provider_id")
        private val PREVIEW_COLUMNS = COMMON + arrayOf("weight", "channel_id", "internal_provider_id")
    }
}

/** Which apps' home-screen channels hold things in progress. Pure (unit-tested). */
object ContinueChannels {
    /** Nuvio's TV and Play builds; its channel's internal id is its package name, whatever the channel is called. */
    val NUVIO_PACKAGES = setOf("com.nuvio.tv", "com.nuvio.app")

    /** "Continue watching" as apps name their channel (Nuvio's 33 translations and common variants). */
    val NAMES = setOf(
        "continue watching", "continue", "keep watching", "resume watching", "up next",
        "continua a guardare", "continuar assistindo", "continuar a ver", "continuar viendo", "continuer à regarder",
        "fortsæt med at se", "kontynuuj oglądanie", "lanjutkan menonton", "megtekintés folytatása", "nadaljuj z ogledom",
        "nastavi gledanje", "nastavi gledati", "pokračovat ve sledování", "pokračovať v sledovaní", "seguir viendo",
        "tiếp tục xem", "tęsti žiūrėjimą", "vazhdo shikimin", "verder kijken", "i̇zlemeye devam et", "izlemeye devam et",
        "συνέχεια παρακολούθησης", "продовжити перегляд", "продолжить просмотр", "продължи гледането", "המשך צפייה",
        "متابعة المشاهدة", "দেখা চালিয়ে যান", "தொடர்ந்து பார்க்கவும்", "繼續觀看", "继续观看", "視聴を続ける", "weiterschauen", "weiter ansehen",
    )

    fun isContinue(pkg: String, displayName: String?, internalProviderId: String?): Boolean {
        if (pkg in NUVIO_PACKAGES && internalProviderId == pkg) return true
        val n = displayName?.trim()?.lowercase() ?: return false
        return n in NAMES || n.startsWith("continue")
    }
}

/** Pure helpers (unit-tested). */
object WatchNextText {
    /**
     * A listing's identity, stable across rewrites of its row: the publisher's own id for it when there is one (Nuvio's
     * are per episode, "wn_tt123_s1e2"), else its resume link, else show + season + episode + episode title.
     */
    fun identity(prefix: String, pkg: String?, providerId: String?, link: String?, title: String, season: String?, episode: String?, episodeTitle: String?, rowId: Long): String {
        val app = pkg ?: "?"
        return when {
            !providerId.isNullOrBlank() -> "$prefix:$app:id:$providerId"
            !link.isNullOrBlank() -> "$prefix:$app:link:${link.hashCode().toUInt().toString(36)}"
            !season.isNullOrBlank() || !episode.isNullOrBlank() || !episodeTitle.isNullOrBlank() ->
                "$prefix:$app:ep:${title.lowercase()}|${season?.trim()}|${episode?.trim()}|${episodeTitle?.trim()?.lowercase()}"
            else -> "$prefix:$app:row:$rowId"
        }
    }

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
