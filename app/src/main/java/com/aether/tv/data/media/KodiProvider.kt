package com.aether.tv.data.media

import android.util.Base64
import com.aether.tv.core.model.MediaItem
import com.aether.tv.core.model.MediaKind
import com.aether.tv.platform.AppLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kodi via its JSON-RPC web interface ("Allow remote control via HTTP" must be enabled in Kodi).
 * In-progress movies/episodes come from Kodi's own resume points.
 */
@Singleton
class KodiProvider @Inject constructor(
    private val store: ProviderStore,
    private val launcher: AppLauncher,
) : MediaProvider {
    private val http = JsonHttp()
    override val id = ID
    override val displayName = "Kodi"
    override val description = "In-progress movies and episodes from Kodi on this TV or your network (JSON-RPC)."
    override val capabilities = setOf(MediaCapability.ContinueWatching, MediaCapability.Search, MediaCapability.Resume)
    override val appPackage = "org.xbmc.kodi"
    private val config get() = store.load(ID)

    private fun headers(): Map<String, String> {
        val c = config
        return if (c.username.isNullOrBlank()) emptyMap()
        else mapOf("Authorization" to "Basic " + Base64.encodeToString("${c.username}:${c.password.orEmpty()}".toByteArray(), Base64.NO_WRAP))
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonElement {
        val base = config.serverUrl ?: error("Kodi not configured")
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }
        val r = http.post("$base/jsonrpc", body.toString(), headers())
        r["error"]?.let { error(it["message"].str ?: "Kodi error") }
        return r["result"] ?: JsonObject(emptyMap())
    }

    fun configure(host: String, username: String?, password: String?) {
        store.save(ID, ProviderConfig(enabled = true, serverUrl = normalizeServerUrl(host, 8080), username = username?.ifBlank { null }, password = password?.ifBlank { null }, serverName = host))
    }

    fun disconnect() = store.clear(ID)

    override suspend fun status(): ProviderStatus {
        val c = config
        if (!c.enabled || c.serverUrl == null) return ProviderStatus.NotConfigured
        return runCatching { rpc("JSONRPC.Ping", buildJsonObject { }); ProviderStatus.Connected(c.serverName ?: c.serverUrl) }
            .getOrElse { ProviderStatus.Error("Can't reach Kodi at ${c.serverUrl}: ${it.message}") }
    }

    private fun inProgressParams(props: List<String>, limit: Int) = buildJsonObject {
        putJsonObject("filter") { put("field", "inprogress"); put("operator", "true"); put("value", "") }
        putJsonArray("properties") { props.forEach { add(it) } }
        putJsonObject("limits") { put("start", 0); put("end", limit) }
        putJsonObject("sort") { put("method", "lastplayed"); put("order", "descending") }
    }

    override suspend fun continueWatching(limit: Int): List<MediaItem> {
        if (!config.enabled) return emptyList()
        val movies = rpc("VideoLibrary.GetMovies", inProgressParams(listOf("title", "resume", "art", "year", "lastplayed"), limit))["movies"].arr.map { movie(it) }
        val eps = rpc("VideoLibrary.GetEpisodes", inProgressParams(listOf("title", "showtitle", "season", "episode", "resume", "art", "lastplayed"), limit))["episodes"].arr.map { episode(it) }
        return (movies + eps).sortedByDescending { it.engagedAt ?: 0L }.take(limit)
    }

    override suspend fun search(query: String, limit: Int): List<MediaItem> {
        if (!config.enabled) return emptyList()
        val p = buildJsonObject {
            putJsonObject("filter") { put("field", "title"); put("operator", "contains"); put("value", query) }
            putJsonArray("properties") { add("title"); add("resume"); add("art"); add("year") }
            putJsonObject("limits") { put("start", 0); put("end", limit) }
        }
        return rpc("VideoLibrary.GetMovies", p)["movies"].arr.map { movie(it) }
    }

    private fun art(e: JsonElement): String? {
        val raw = e["art"]["fanart"].str ?: e["art"]["tvshow.fanart"].str ?: e["art"]["thumb"].str ?: return null
        return "${config.serverUrl}/image/${URLEncoder.encode(raw, "UTF-8")}"
    }

    private fun progress(e: JsonElement): Float? {
        val pos = e["resume"]["position"].double ?: return null; val total = e["resume"]["total"].double ?: return null
        return if (total > 0) (pos / total).toFloat().coerceIn(0f, 1f) else null
    }

    private fun movie(e: JsonElement) = MediaItem("movie:${e["movieid"].long}", ID, e["title"].str.orEmpty(), e["year"].long?.toString(), art(e), progress(e), kind = MediaKind.Movie,
        engagedAt = MediaTimes.kodi(e["lastplayed"].str))
    private fun episode(e: JsonElement) = MediaItem(
        "episode:${e["episodeid"].long}", ID, e["showtitle"].str ?: e["title"].str.orEmpty(),
        "S${e["season"].long ?: 0} E${e["episode"].long ?: 0} • ${e["title"].str.orEmpty()}", art(e), progress(e), kind = MediaKind.Episode,
        engagedAt = MediaTimes.kodi(e["lastplayed"].str), season = e["season"].long?.toInt(), episode = e["episode"].long?.toInt(),
    )

    override suspend fun resume(item: MediaItem): ResumeResult {
        val (type, idStr) = item.id.split(":").let { it[0] to it.getOrNull(1) }
        val idNum = idStr?.toLongOrNull() ?: return ResumeResult.Message("Unknown Kodi item")
        return runCatching {
            rpc("Player.Open", buildJsonObject {
                putJsonObject("item") { put(if (type == "movie") "movieid" else "episodeid", idNum) }
                putJsonObject("options") { put("resume", true) }
            })
            val local = config.serverUrl?.let { it.contains("127.0.0.1") || it.contains("localhost") } == true
            if (local) withContext(Dispatchers.Main) { launcher.launchPackage(appPackage) }
            if (local) ResumeResult.Started else ResumeResult.Message("Playback started on Kodi (${config.serverName}).")
        }.getOrElse { ResumeResult.Message("Kodi couldn't start playback: ${it.message}") }
    }

    companion object { const val ID = "kodi" }
}
