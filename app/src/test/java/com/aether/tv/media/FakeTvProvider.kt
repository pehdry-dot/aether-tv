package com.aether.tv.media

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Android TV's listings provider ("android.media.tv") as far as Aether reads it: channels, preview programs and Watch
 * Next, with change notifications. Like the real provider it rejects unknown projection columns, and like Fire OS it
 * rejects selection clauses on preview programs.
 */
class FakeTvProvider : ContentProvider() {
    val tables = mapOf("channel" to ArrayList<MutableMap<String, Any?>>(), "preview_program" to ArrayList(), "watch_next_program" to ArrayList())
    private var nextId = 1L
    var queries = 0

    override fun onCreate() = true
    private fun table(uri: Uri) = tables[uri.pathSegments.first()] ?: error("unknown table $uri")

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queries++
        if (uri.pathSegments.first() == "preview_program" && selection != null) throw SecurityException("Selection not allowed for preview programs")
        val rows = synchronized(this) { table(uri).map { HashMap(it) } }
        val cols = projection ?: error("Aether always names its columns")
        val known = KNOWN.getValue(uri.pathSegments.first())
        cols.firstOrNull { it !in known }?.let { throw IllegalArgumentException("Invalid column $it") }
        val c = MatrixCursor(cols)
        rows.forEach { r -> c.addRow(cols.map { r[it] }) }
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = synchronized(this) { nextId++ }
        val row = HashMap<String, Any?>(); values?.keySet()?.forEach { row[it] = values.get(it) }; row["_id"] = id
        synchronized(this) { table(uri).add(row) }
        val out = ContentUris.withAppendedId(uri, id)
        context!!.contentResolver.notifyChange(out, null)
        return out
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val id = ContentUris.parseId(uri)
        val n = synchronized(this) { table(uri.buildUpon().path(uri.pathSegments.first()).build()).filter { it["_id"] == id }.onEach { r -> values?.keySet()?.forEach { r[it] = values.get(it) } }.size }
        context!!.contentResolver.notifyChange(uri, null)
        return n
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val id = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1)
        val n = synchronized(this) { val t = table(uri); val before = t.size; t.removeAll { id < 0 || it["_id"] == id }; before - t.size }
        context!!.contentResolver.notifyChange(uri, null)
        return n
    }

    override fun getType(uri: Uri): String? = null

    companion object {
        const val AUTHORITY = "android.media.tv"
        val CHANNELS: Uri = Uri.parse("content://$AUTHORITY/channel")
        val PREVIEW: Uri = Uri.parse("content://$AUTHORITY/preview_program")
        val WATCH_NEXT: Uri = Uri.parse("content://$AUTHORITY/watch_next_program")
        private val PROGRAM = setOf("_id", "browsable", "title", "episode_title", "package_name", "season_display_number", "episode_display_number",
            "duration_millis", "last_playback_position_millis", "thumbnail_uri", "poster_art_uri", "type", "intent_uri", "internal_provider_id")
        val KNOWN = mapOf(
            "channel" to setOf("_id", "package_name", "display_name", "browsable", "internal_provider_id"),
            "preview_program" to PROGRAM + setOf("weight", "channel_id"),
            "watch_next_program" to PROGRAM + setOf("watch_next_type", "last_engagement_time_utc_millis"),
        )

        /** A Watch Next "continue" row as Nuvio writes it (androidx.tvprovider WatchNextProgram). */
        fun nuvioWatchNext(contentId: String, show: String, season: Int, episode: Int, episodeTitle: String, positionMs: Long, durationMs: Long, engagedAt: Long, pkg: String = "com.nuvio.tv") = ContentValues().apply {
            put("package_name", pkg); put("title", show); put("type", 3); put("watch_next_type", 0)
            put("season_display_number", season.toString()); put("episode_display_number", episode.toString()); put("episode_title", episodeTitle)
            put("last_playback_position_millis", positionMs); put("duration_millis", durationMs); put("last_engagement_time_utc_millis", engagedAt)
            put("internal_provider_id", "wn_${contentId}_s${season}e$episode")
            put("intent_uri", "intent:#Intent;action=android.intent.action.VIEW;component=$pkg/com.nuvio.tv.MainActivity;S.contentId=$contentId;i.season=$season;i.episode=$episode;end")
            put("poster_art_uri", "https://img.example/$contentId.jpg"); put("browsable", 1)
        }
    }
}
