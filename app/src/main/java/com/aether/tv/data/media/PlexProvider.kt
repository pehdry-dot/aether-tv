package com.aether.tv.data.media

import com.aether.tv.BuildConfig
import com.aether.tv.core.model.MediaItem
import com.aether.tv.core.model.MediaKind
import com.aether.tv.platform.AppLauncher
import com.aether.tv.platform.LaunchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plex Media Server via the documented plex.tv device-link (PIN) flow and the server's JSON API.
 * Continue Watching data comes from the server's own "continueWatching" hub (falls back to On Deck).
 */
@Singleton
class PlexProvider @Inject constructor(
    private val store: ProviderStore,
    private val launcher: AppLauncher,
) : MediaProvider {
    private val http = JsonHttp()
    override val id = ID
    override val displayName = "Plex"
    override val description = "Continue Watching, recently added and search from your Plex Media Server."
    override val capabilities = setOf(MediaCapability.ContinueWatching, MediaCapability.RecentlyAdded, MediaCapability.Search, MediaCapability.Resume)
    override val appPackage = "com.plexapp.android"

    private val config get() = store.load(ID)

    private fun headers(token: String? = config.token) = buildMap {
        put("X-Plex-Product", "Aether TV"); put("X-Plex-Version", BuildConfig.VERSION_NAME)
        put("X-Plex-Client-Identifier", store.clientId); put("X-Plex-Platform", "Android"); put("X-Plex-Device", "Android TV")
        if (token != null) put("X-Plex-Token", token)
    }

    override suspend fun status(): ProviderStatus {
        val c = config
        if (!c.enabled || c.token == null) return ProviderStatus.NotConfigured
        if (c.serverUrl == null) return ProviderStatus.Error("Signed in, but no server selected")
        return runCatching { http.get("${c.serverUrl}/identity", headers()); ProviderStatus.Connected(c.serverName ?: c.serverUrl) }
            .getOrElse { ProviderStatus.Error("Can't reach ${c.serverName ?: c.serverUrl}: ${it.message}") }
    }

    /** Step 1 of linking: returns the 4-character code the user enters at plex.tv/link. */
    suspend fun startLink(): LinkCode {
        val r = http.post("https://plex.tv/api/v2/pins?strong=false", null, headers(null), "application/x-www-form-urlencoded")
        return LinkCode(r["code"].str!!, "On a phone or computer, visit plex.tv/link and enter this code.", r["id"].long.toString())
    }

    /** Step 2: poll until the user approves. Returns true once a token was stored and a server selected. */
    suspend fun pollLink(pollToken: String): Boolean {
        val r = http.get("https://plex.tv/api/v2/pins/$pollToken", headers(null))
        val token = r["authToken"].str?.takeIf { it.isNotBlank() } ?: return false
        store.save(ID, config.copy(enabled = true, token = token))
        selectServer(token)
        return true
    }

    fun setManual(serverUrl: String, token: String) {
        store.save(ID, ProviderConfig(enabled = true, serverUrl = normalizeServerUrl(serverUrl, 32400), token = token.trim(), serverName = serverUrl))
    }

    private suspend fun selectServer(userToken: String) {
        val resources = runCatching { http.get("https://plex.tv/api/v2/resources?includeHttps=1", headers(userToken)).arr }.getOrDefault(emptyList())
        val server = resources.firstOrNull { it["provides"].str?.contains("server") == true && it["owned"].bool != false }
            ?: resources.firstOrNull { it["provides"].str?.contains("server") == true } ?: return
        val conns = server["connections"].arr
        val conn = conns.firstOrNull { it["local"].bool == true && it["relay"].bool != true } ?: conns.firstOrNull { it["relay"].bool != true } ?: conns.firstOrNull()
        store.save(ID, config.copy(serverUrl = conn?.get("uri").str, serverName = server["name"].str, machineId = server["clientIdentifier"].str, token = server["accessToken"].str ?: userToken))
    }

    fun disconnect() = store.clear(ID)

    override suspend fun continueWatching(limit: Int): List<MediaItem> {
        val c = config; val base = c.serverUrl ?: return emptyList(); if (!c.enabled) return emptyList()
        val r = runCatching { http.get("$base/hubs/continueWatching/items?X-Plex-Container-Size=$limit", headers()) }
            .getOrElse { http.get("$base/library/onDeck?X-Plex-Container-Size=$limit", headers()) }
        return r["MediaContainer"]["Metadata"].arr.take(limit).map { toItem(it, base, c.token) }
    }

    override suspend fun recentlyAdded(limit: Int): List<MediaItem> {
        val c = config; val base = c.serverUrl ?: return emptyList(); if (!c.enabled) return emptyList()
        return http.get("$base/library/recentlyAdded?X-Plex-Container-Size=$limit", headers())["MediaContainer"]["Metadata"].arr.take(limit).map { toItem(it, base, c.token) }
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        val c = config; val base = c.serverUrl ?: return emptyList(); if (!c.enabled) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val hubs = http.get("$base/hubs/search?query=$q&limit=$limit", headers())["MediaContainer"]["Hub"].arr
        return hubs.flatMap { it["Metadata"].arr }.filter { it["type"].str in setOf("movie", "show", "episode") }.take(limit).map { toItem(it, base, c.token) }
    }

    private fun toItem(m: JsonElement, base: String, token: String?): MediaItem {
        val type = m["type"].str
        val title = if (type == "episode") m["grandparentTitle"].str ?: m["title"].str.orEmpty() else m["title"].str.orEmpty()
        val subtitle = if (type == "episode") "S${m["parentIndex"].long ?: 0} E${m["index"].long ?: 0} • ${m["title"].str.orEmpty()}" else m["year"].long?.toString()
        val art = m["art"].str ?: m["grandparentArt"].str ?: m["thumb"].str
        val duration = m["duration"].long
        val offset = m["viewOffset"].long
        return MediaItem(
            id = m["ratingKey"].str ?: m["key"].str.orEmpty(), providerId = ID, title = title, subtitle = subtitle,
            artworkUrl = art?.let { "$base/photo/:/transcode?width=640&height=360&minSize=1&upscale=1&url=${URLEncoder.encode(it, "UTF-8")}&X-Plex-Token=${token.orEmpty()}" },
            progress = if (duration != null && offset != null && duration > 0) (offset.toFloat() / duration).coerceIn(0f, 1f) else null,
            durationMs = duration, kind = if (type == "episode") MediaKind.Episode else MediaKind.Movie,
            engagedAt = m["lastViewedAt"].long?.times(1000),
            season = if (type == "episode") m["parentIndex"].long?.toInt() else null,
            episode = if (type == "episode") m["index"].long?.toInt() else null,
        )
    }

    override suspend fun resume(item: MediaItem): ResumeResult = withContext(Dispatchers.Main) {
        val machine = config.machineId
        val deep = if (machine != null) "plex://server://$machine/com.plexapp.plugins.library/library/metadata/${item.id}" else null
        val r = if (deep != null) launcher.openUri(deep, appPackage) else launcher.launchPackage(appPackage)
        when (r) {
            is LaunchResult.Opened -> ResumeResult.Started
            is LaunchResult.Unavailable -> ResumeResult.Message("Install the Plex app to play “${item.title}”.")
        }
    }

    companion object { const val ID = "plex" }
}
