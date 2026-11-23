package com.aether.tv.data.media

import com.aether.tv.BuildConfig
import com.aether.tv.core.model.MediaItem
import com.aether.tv.core.model.MediaKind
import com.aether.tv.platform.AppLauncher
import com.aether.tv.platform.LaunchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Jellyfin via its REST API. Supports Quick Connect (code entry on another device) or username/password. */
@Singleton
class JellyfinProvider @Inject constructor(
    private val store: ProviderStore,
    private val launcher: AppLauncher,
) : MediaProvider {
    private val http = JsonHttp()
    override val id = ID
    override val displayName = "Jellyfin"
    override val description = "Resume items, latest media and search from your Jellyfin server."
    override val capabilities = setOf(MediaCapability.ContinueWatching, MediaCapability.RecentlyAdded, MediaCapability.Search, MediaCapability.Resume)
    override val appPackage = "org.jellyfin.androidtv"

    private val config get() = store.load(ID)

    private fun auth(token: String? = config.token): Map<String, String> {
        val v = "MediaBrowser Client=\"Aether TV\", Device=\"Android TV\", DeviceId=\"${store.clientId}\", Version=\"${BuildConfig.VERSION_NAME}\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")
        return mapOf("Authorization" to v, "X-Emby-Authorization" to v)
    }

    override suspend fun status(): ProviderStatus {
        val c = config
        if (!c.enabled || c.token == null || c.serverUrl == null) return ProviderStatus.NotConfigured
        return runCatching { http.get("${c.serverUrl}/Users/${c.userId}", auth()); ProviderStatus.Connected("${c.userName ?: "Signed in"} on ${c.serverName ?: c.serverUrl}") }
            .getOrElse { ProviderStatus.Error("Can't reach ${c.serverUrl}: ${it.message}") }
    }

    suspend fun startQuickConnect(server: String): LinkCode {
        val base = normalizeServerUrl(server, 8096)
        val info = runCatching { http.get("$base/System/Info/Public") }.getOrNull()
        store.save(ID, ProviderConfig(serverUrl = base, serverName = info["ServerName"].str))
        val r = http.post("$base/QuickConnect/Initiate", null, auth(null))
        return LinkCode(r["Code"].str!!, "In Jellyfin on another device, open your profile ▸ Quick Connect and enter this code.", r["Secret"].str!!)
    }

    suspend fun pollQuickConnect(secret: String): Boolean {
        val base = config.serverUrl ?: return false
        val state = http.get("$base/QuickConnect/Connect?secret=${URLEncoder.encode(secret, "UTF-8")}", auth(null))
        if (state["Authenticated"].bool != true) return false
        val r = http.post("$base/Users/AuthenticateWithQuickConnect", buildJsonObject { put("Secret", secret) }.toString(), auth(null))
        saveAuth(base, r); return true
    }

    suspend fun signIn(server: String, username: String, password: String) {
        val base = normalizeServerUrl(server, 8096)
        val info = runCatching { http.get("$base/System/Info/Public") }.getOrNull()
        val r = http.post("$base/Users/AuthenticateByName", buildJsonObject { put("Username", username); put("Pw", password) }.toString(), auth(null))
        store.save(ID, ProviderConfig(serverUrl = base, serverName = info["ServerName"].str))
        saveAuth(base, r)
    }

    private fun saveAuth(base: String, r: JsonElement) {
        store.save(ID, config.copy(enabled = true, serverUrl = base, token = r["AccessToken"].str, userId = r["User"]["Id"].str, userName = r["User"]["Name"].str))
    }

    fun disconnect() = store.clear(ID)

    override suspend fun continueWatching(limit: Int): List<MediaItem> {
        val c = config; if (!c.enabled || c.serverUrl == null || c.userId == null) return emptyList()
        val r = http.get("${c.serverUrl}/Users/${c.userId}/Items/Resume?Limit=$limit&MediaTypes=Video&Fields=PrimaryImageAspectRatio", auth())
        return r["Items"].arr.map { toItem(it, c.serverUrl) }
    }

    override suspend fun recentlyAdded(limit: Int): List<MediaItem> {
        val c = config; if (!c.enabled || c.serverUrl == null || c.userId == null) return emptyList()
        return http.get("${c.serverUrl}/Users/${c.userId}/Items/Latest?Limit=$limit", auth()).arr.map { toItem(it, c.serverUrl) }
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        val c = config; if (!c.enabled || c.serverUrl == null || c.userId == null) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        return http.get("${c.serverUrl}/Users/${c.userId}/Items?searchTerm=$q&Recursive=true&Limit=$limit&IncludeItemTypes=Movie,Series,Episode", auth())["Items"].arr.map { toItem(it, c.serverUrl) }
    }

    private fun toItem(m: JsonElement, base: String): MediaItem {
        val type = m["Type"].str
        val id = m["Id"].str.orEmpty()
        val isEp = type == "Episode"
        val backdropOwner = when {
            m["BackdropImageTags"].arr.isNotEmpty() -> id
            m["ParentBackdropItemId"].str != null -> m["ParentBackdropItemId"].str
            else -> null
        }
        val art = backdropOwner?.let { "$base/Items/$it/Images/Backdrop?maxWidth=640" } ?: "$base/Items/$id/Images/Primary?maxWidth=480"
        val pct = m["UserData"]["PlayedPercentage"].double
        return MediaItem(
            id = id, providerId = ID,
            title = if (isEp) m["SeriesName"].str ?: m["Name"].str.orEmpty() else m["Name"].str.orEmpty(),
            subtitle = if (isEp) "S${m["ParentIndexNumber"].long ?: 0} E${m["IndexNumber"].long ?: 0} • ${m["Name"].str.orEmpty()}" else m["ProductionYear"].long?.toString(),
            artworkUrl = art, progress = pct?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
            durationMs = m["RunTimeTicks"].long?.div(10_000), kind = if (isEp) MediaKind.Episode else MediaKind.Movie,
        )
    }

    override suspend fun resume(item: MediaItem): ResumeResult = withContext(Dispatchers.Main) {
        when (launcher.launchPackage(appPackage)) {
            is LaunchResult.Opened -> ResumeResult.Message("Opened Jellyfin — “${item.title}” is under Continue Watching.")
            is LaunchResult.Unavailable -> ResumeResult.Message("Install the Jellyfin app to play “${item.title}”.")
        }
    }

    companion object { const val ID = "jellyfin" }
}
