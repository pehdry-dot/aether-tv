package com.aether.tv.data.media

import com.aether.tv.core.model.MediaItem

/**
 * Builds Home's Continue Watching from every source's latest good result. Pure logic, unit-tested.
 *
 * 1. **Merge first, limit last.** Every source contributes everything it has; Home applies the display limit to the
 *    merged list, so a source with twelve old entries can't crowd out a fresh one from another.
 * 2. **One card per episode.** Items are the same when they share their provider id, the app's resume link, or the
 *    same app + show + season + episode. Two episodes of one show stay two cards.
 * 3. **Most recently watched first,** by engagement time when a source provides one. Items without a time keep their
 *    publisher's order: each takes the time of the item above it in its own source (or sorts after every timed item).
 * 4. A live media session yields to a resumable listing of the same title from the same app. A session that is playing
 *    carries the current time, so it comes first.
 */
object ContinueWatchingRanker {
    /** A source's items in the order it published them; lower [priority] wins ties. */
    data class SourceItems(val providerId: String, val priority: Int, val items: List<MediaItem>)

    fun merge(sources: List<SourceItems>): List<MediaItem> {
        class Ranked(val item: MediaItem, val time: Long, val priority: Int, val rank: Int)
        val ranked = sources.flatMap { src ->
            var previous: Long? = null
            src.items.mapIndexed { i, item ->
                val t = item.engagedAt ?: previous?.let { it - 1 } ?: Long.MIN_VALUE / 2 + i
                previous = t
                Ranked(item, t, src.priority, i)
            }
        }
        val listingTitles = ranked.filter { !it.item.nowPlaying }.mapNotNull { r -> r.item.packageName?.let { it to norm(r.item.title) } }.toSet()
        val listingSubtitles = ranked.filter { !it.item.nowPlaying }.mapNotNull { r ->
            val p = r.item.packageName ?: return@mapNotNull null
            r.item.subtitle?.let { p to norm(it) }
        }
        val sorted = ranked
            .filterNot { r -> r.item.nowPlaying && r.item.packageName != null && duplicatesListing(r.item, listingTitles, listingSubtitles) }
            .sortedWith(compareByDescending<Ranked> { it.time }.thenBy { it.priority }.thenBy { it.rank })
        val seen = HashSet<String>()
        return sorted.mapNotNull { r -> if (keys(r.item).any { it in seen }) null else r.item.also { seen += keys(it) } }
    }

    /** Every identity an item can be matched by. */
    fun keys(m: MediaItem): List<String> = buildList {
        add("id|${m.providerId}|${m.id}")
        val app = m.packageName ?: m.providerId
        m.link?.takeIf { it.isNotBlank() }?.let { add("link|$app|$it") }
        if (m.season != null && m.episode != null) add("ep|$app|${norm(m.title)}|${m.season}|${m.episode}")
    }

    private fun duplicatesListing(session: MediaItem, titles: Set<Pair<String, String>>, subtitles: List<Pair<String, String>>): Boolean {
        val pkg = session.packageName ?: return false
        val t = norm(session.title)
        if (t.isEmpty()) return false
        return (pkg to t) in titles || subtitles.any { (p, s) -> p == pkg && s.contains(t) }
    }

    private fun norm(s: String) = s.trim().lowercase()
}
