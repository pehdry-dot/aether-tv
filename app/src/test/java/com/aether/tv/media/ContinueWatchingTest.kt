package com.aether.tv.media

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.MemorySecrets
import com.aether.tv.core.model.MediaItem
import com.aether.tv.data.media.ContinueChannels
import com.aether.tv.data.media.ContinueWatchingRanker
import com.aether.tv.data.media.JellyfinProvider
import com.aether.tv.data.media.KodiProvider
import com.aether.tv.data.media.MediaRepository
import com.aether.tv.data.media.MediaSessionProvider
import com.aether.tv.data.media.PlexProvider
import com.aether.tv.data.media.ProviderConfig
import com.aether.tv.data.media.ProviderStatus
import com.aether.tv.data.media.ProviderStore
import com.aether.tv.data.media.SessionArtCache
import com.aether.tv.data.media.TvWatchNextProvider
import com.aether.tv.platform.AppLauncher
import com.aether.tv.platform.PlatformInfo
import com.aether.tv.platform.SystemShortcuts
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.util.concurrent.TimeUnit

/** Continue Watching end to end with a fake Android TV listings provider: late writes, ranking, offline servers. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContinueWatchingTest {
    private val app: android.app.Application = ApplicationProvider.getApplicationContext()
    private lateinit var tv: FakeTvProvider
    private lateinit var repo: MediaRepository
    private val store = ProviderStore(MemorySecrets())
    private val server = MockWebServer()
    private val now = System.currentTimeMillis()

    @Before fun setUp() {
        shadowOf(app).grantPermissions(TvWatchNextProvider.PERMISSION)
        tv = Robolectric.setupContentProvider(FakeTvProvider::class.java, FakeTvProvider.AUTHORITY)
        val launcher = AppLauncher(app, SystemShortcuts(app, PlatformInfo(app)))
        repo = MediaRepository(MediaSessionProvider(app), TvWatchNextProvider(app), PlexProvider(store, launcher), JellyfinProvider(store, launcher), KodiProvider(store, launcher))
    }

    @After fun tearDown() { repo.stopWatching(); runCatching { server.shutdown() } }

    /** Pumps the main looper (observers are delivered there) until [cond], or fails after [ms]. */
    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); if (cond()) return; Thread.sleep(25) }
        throw AssertionError("Not met in $ms ms: ${repo.state.value.continueWatching.map { it.title + " " + it.subtitle }}")
    }

    private fun cw() = repo.state.value.continueWatching

    @Test fun aNewNuvioEpisodeWrittenAfterAetherIsInFrontAppearsFirstWithoutARefresh() {
        // Twelve older Watch Next entries from another app, plus Nuvio's previous episode.
        repeat(12) { i -> tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("old$i", "Old show $i", 1, 1, "Ep", 600_000, 2_400_000, now - 86_400_000L * (i + 2), pkg = "com.other.app")) }
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("tt1", "The Long Road", 2, 3, "Ghosts", 900_000, 2_700_000, now - 3_600_000))
        repo.startWatching() // Home: observers first, then the first read
        waitFor { cw().size == 13 }
        assertEquals("The Long Road", cw().first().title)
        val queriesBefore = tv.queries

        // Nuvio: pause the next episode, Home pressed; Nuvio writes Watch Next only as playback stops / in onStop.
        Thread.sleep(300)
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("tt1", "The Long Road", 2, 4, "Endure and Survive", 1_620_000, 2_700_000, now))
        waitFor { cw().first().subtitle?.startsWith("S2 E4") == true }
        assertTrue("read again because of the change notification, not a manual refresh", tv.queries > queriesBefore)
        val first = cw().first()
        assertEquals(0.6f, first.progress!!, 0.001f)
        // Merged before the display limit: the fresh episode leads even with 12 older entries listed.
        assertEquals(14, cw().size)
        assertTrue(cw().take(8).any { it.subtitle!!.startsWith("S2 E3") })
        // OK reopens that episode: Nuvio's own link, to Nuvio only.
        var result: com.aether.tv.data.media.ResumeResult? = null
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch { result = repo.resume(first) }
        waitFor { result != null }
        assertEquals(com.aether.tv.data.media.ResumeResult.Started, result)
        val started = shadowOf(app).nextStartedActivity
        assertEquals("com.nuvio.tv", started.component!!.packageName)
        assertEquals(4, started.getIntExtra("episode", -1))
    }

    @Test fun burstsOfChangesAreCoalesced() {
        repo.startWatching()
        waitFor { repo.state.value.sources[TvWatchNextProvider.ID]?.lastSuccessAt ?: 0 > 0 }
        Thread.sleep(200)
        val before = tv.queries
        // Nuvio's reconcile rewrites its whole list at once.
        repeat(10) { i -> tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("b$i", "Show $i", 1, i + 1, "E", 60_000, 1_200_000, now - i)) }
        waitFor { cw().size == 10 }
        Thread.sleep(800); shadowOf(Looper.getMainLooper()).idle()
        // One read of each table (Watch Next, channels, programs), not ten.
        assertTrue("queries: ${tv.queries - before}", tv.queries - before <= 6)
    }

    @Test fun observersAreRemovedWhenAetherLeavesTheFront() {
        repo.startWatching()
        waitFor { repo.state.value.sources[TvWatchNextProvider.ID]?.lastSuccessAt ?: 0 > 0 }
        repo.stopWatching()
        Thread.sleep(4_500) // past the follow-up reads
        val before = tv.queries
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("late", "Late", 1, 1, "E", 60_000, 1_200_000, now))
        Thread.sleep(800); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("nothing is read in the background", before, tv.queries)
    }

    @Test fun nuvioChannelIsFoundByPublisherIdWhateverItsLocalizedName() {
        val ch = tv.insert(FakeTvProvider.CHANNELS, ContentValues().apply {
            put("package_name", "com.nuvio.tv"); put("display_name", "Продолжить просмотр"); put("internal_provider_id", "com.nuvio.tv"); put("browsable", 1)
        })
        val other = tv.insert(FakeTvProvider.CHANNELS, ContentValues().apply { put("package_name", "com.news.app"); put("display_name", "Top stories"); put("browsable", 1) })
        tv.insert(FakeTvProvider.PREVIEW, ContentValues().apply {
            put("channel_id", android.content.ContentUris.parseId(ch)); put("package_name", "com.nuvio.tv"); put("title", "Dune Sea"); put("type", 0)
            put("duration_millis", 6_000_000); put("last_playback_position_millis", 1_500_000); put("weight", 10); put("internal_provider_id", "tt9"); put("browsable", 1)
            put("intent_uri", "intent:#Intent;component=com.nuvio.tv/com.nuvio.tv.MainActivity;S.contentId=tt9;end")
        })
        tv.insert(FakeTvProvider.PREVIEW, ContentValues().apply {
            put("channel_id", android.content.ContentUris.parseId(other)); put("package_name", "com.news.app"); put("title", "Headlines"); put("browsable", 1)
        })
        repo.startWatching()
        waitFor { cw().isNotEmpty() }
        assertEquals(listOf("Dune Sea"), cw().map { it.title })
        assertEquals(0.25f, cw().single().progress!!, 0.001f)
    }

    @Test fun twoEpisodesOfOneShowStayTwoCards() {
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("tt1", "Arcadia", 2, 3, "One", 600_000, 2_400_000, now - 10))
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("tt1", "Arcadia", 2, 4, "Two", 300_000, 2_400_000, now))
        repo.startWatching()
        waitFor { cw().size == 2 }
        assertEquals(listOf("S2 E4", "S2 E3"), cw().map { it.subtitle!!.substring(0, 5) })
        assertEquals(2, cw().map { it.id }.toSet().size)
    }

    @Test fun anOfflineServerNeverDelaysTheAppsOnThisTvAndKeepsItsLastResult() {
        var online = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!online) return MockResponse().setBodyDelay(16, TimeUnit.SECONDS).setBody("{}")
                return when {
                    request.path!!.startsWith("/Users/u1/Items/Resume") -> json("""{"Items":[{"Id":"e7","Type":"Episode","Name":"Pilot","SeriesName":"Server Show","ParentIndexNumber":1,"IndexNumber":1,"UserData":{"PlayedPercentage":40,"LastPlayedDate":"2020-01-01T10:00:00.0000000Z"}}]}""")
                    request.path!!.startsWith("/Users/u1/Items/Latest") -> json("[]")
                    else -> json("""{"Id":"u1"}""")
                }
            }
        }
        server.start()
        store.save(JellyfinProvider.ID, ProviderConfig(enabled = true, serverUrl = server.url("/").toString().trimEnd('/'), token = "t", userId = "u1"))
        repo.refresh(force = true)
        waitFor { cw().any { it.title == "Server Show" } }
        val goodAt = repo.state.value.sources.getValue(JellyfinProvider.ID).lastSuccessAt

        online = false // the server stops answering
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("tt1", "Fresh", 1, 2, "E", 60_000, 1_200_000, now))
        val t0 = System.currentTimeMillis()
        repo.refresh(force = true)
        waitFor(3_000) { cw().firstOrNull()?.title == "Fresh" }
        assertTrue("local result not held back by the server", System.currentTimeMillis() - t0 < 3_000)
        assertTrue("the server's last result is kept meanwhile", cw().any { it.title == "Server Show" })
        waitFor(20_000) { repo.state.value.sources.getValue(JellyfinProvider.ID).lastError != null }
        val jf = repo.state.value.sources.getValue(JellyfinProvider.ID)
        assertEquals(goodAt, jf.lastSuccessAt)
        assertTrue(cw().any { it.title == "Server Show" })
    }

    @Test fun anEmptyAnswerIsNotAFailure() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/Users/u1/Items/Resume") -> json("""{"Items":[]}""")
                request.path!!.startsWith("/Users/u1/Items/Latest") -> json("[]")
                else -> json("""{"Id":"u1"}""")
            }
        }
        server.start()
        store.save(JellyfinProvider.ID, ProviderConfig(enabled = true, serverUrl = server.url("/").toString().trimEnd('/'), token = "t", userId = "u1"))
        repo.refreshSource(JellyfinProvider.ID)
        waitFor { repo.state.value.sources.getValue(JellyfinProvider.ID).lastSuccessAt > 0 }
        val jf = repo.state.value.sources.getValue(JellyfinProvider.ID)
        assertTrue(jf.status is ProviderStatus.Connected)
        assertNull(jf.lastError)
        assertTrue(jf.items.isEmpty())
    }

    @Test fun accountChangeDropsTheOldCards() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.path!!.startsWith("/Users/u1/Items/Resume") -> json("""{"Items":[{"Id":"a","Type":"Movie","Name":"Old account film","UserData":{"PlayedPercentage":10}}]}""")
                request.path!!.startsWith("/Users/u2/Items/Resume") -> json("""{"Items":[]}""")
                request.path!!.contains("/Items/Latest") -> json("[]")
                else -> json("""{"Id":"x"}""")
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        store.save(JellyfinProvider.ID, ProviderConfig(enabled = true, serverUrl = base, token = "t", userId = "u1"))
        repo.refreshSource(JellyfinProvider.ID)
        waitFor { cw().any { it.title == "Old account film" } }
        store.save(JellyfinProvider.ID, ProviderConfig(enabled = true, serverUrl = base, token = "t2", userId = "u2"))
        repo.invalidate(JellyfinProvider.ID)
        assertFalse("cleared at once, before the new account answers", cw().any { it.title == "Old account film" })
        waitFor { repo.state.value.sources.getValue(JellyfinProvider.ID).lastSuccessAt > 0 }
        assertFalse(cw().any { it.title == "Old account film" })
    }

    @Test fun nowPlayingSessionCard() {
        val p = MediaSessionProvider(app)
        p.now = { 1_000_000 }; p.elapsed = { 50_000 }
        val playing = p.sessionItem("com.amazon.firetv.youtube", "YouTube", "Lo-fi beats", "Chill channel", 600_000, android.media.session.PlaybackState.STATE_PLAYING, 100_000, 1f, 40_000) { null }!!
        assertTrue(playing.nowPlaying)
        assertEquals("Now Playing • Chill channel • YouTube", playing.subtitle)
        assertEquals("session:com.amazon.firetv.youtube", playing.id) // one card per app: it updates in place
        assertEquals(110_000f / 600_000f, playing.progress!!, 0.001f) // position moved on while playing
        assertEquals(1_000_000L, playing.engagedAt)
        val paused = p.sessionItem("com.amazon.firetv.youtube", "YouTube", "Lo-fi beats", null, 600_000, android.media.session.PlaybackState.STATE_PAUSED, 100_000, 1f, 40_000) { null }!!
        assertEquals("Now Playing • Paused • YouTube", paused.subtitle)
        assertEquals(990_000L, paused.engagedAt)
        assertNull("an ended session has no card", p.sessionItem("com.amazon.firetv.youtube", "YouTube", "x", null, 0, android.media.session.PlaybackState.STATE_STOPPED, 0, 1f, 0) { null })
    }

    @Test fun sessionArtworkCacheIsBounded() {
        SessionArtCache.clear()
        repeat(40) { i -> SessionArtCache.put("k$i", android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)) }
        assertTrue("entries: ${SessionArtCache.count()}", SessionArtCache.count() in 1..13)
        assertNotNull(SessionArtCache.get("session:k39"))
        assertNull(SessionArtCache.get("session:k0"))
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
}

/** Ranking rules, without Android. */
class ContinueWatchingRankerTest {
    private fun item(id: String, title: String, at: Long?, provider: String = "tvwatchnext", pkg: String? = "com.nuvio.tv", s: Int? = null, e: Int? = null, link: String? = null, now: Boolean = false) =
        MediaItem(id, provider, title, null, null, null, packageName = pkg, engagedAt = at, season = s, episode = e, link = link, nowPlaying = now)

    @Test fun mergeBeforeLimitByEngagement() {
        val old = (1..12).map { item("o$it", "Old $it", 1_000L - it) }
        val fresh = item("f", "Fresh", 5_000L, provider = "jellyfin", pkg = null)
        val merged = ContinueWatchingRanker.merge(listOf(
            ContinueWatchingRanker.SourceItems("tvwatchnext", 1, old),
            ContinueWatchingRanker.SourceItems("jellyfin", 3, listOf(fresh)),
        ))
        assertEquals("Fresh", merged.first().title)
        assertEquals(13, merged.size)
    }

    @Test fun untimedItemsKeepPublisherOrder() {
        val a = item("a", "A", 3_000L); val b = item("b", "B", null); val c = item("c", "C", null)
        val d = item("d", "D", 2_000L, provider = "plex", pkg = null)
        val merged = ContinueWatchingRanker.merge(listOf(
            ContinueWatchingRanker.SourceItems("tvwatchnext", 1, listOf(a, b, c)),
            ContinueWatchingRanker.SourceItems("plex", 2, listOf(d)),
        ))
        assertEquals(listOf("A", "B", "C", "D"), merged.map { it.title })
    }

    @Test fun sameEpisodeFromWatchNextAndChannelIsOneCard() {
        val wn = item("wn:1", "Show", 9_000L, s = 1, e = 2)
        val ch = item("ch:1", "Show", null, s = 1, e = 2)
        val other = item("ch:2", "Show", null, s = 1, e = 3)
        val merged = ContinueWatchingRanker.merge(listOf(ContinueWatchingRanker.SourceItems("tvwatchnext", 1, listOf(wn, ch, other))))
        assertEquals(listOf("wn:1", "ch:2"), merged.map { it.id })
    }

    @Test fun sessionYieldsToAResumableListing() {
        val listing = item("wn:1", "Arcadia", 1_000L, s = 2, e = 3)
        val session = item("session:com.nuvio.tv", "Arcadia", 9_000L, provider = "sessions", now = true)
        val yt = item("session:com.amazon.firetv.youtube", "Lo-fi", 8_000L, provider = "sessions", pkg = "com.amazon.firetv.youtube", now = true)
        val merged = ContinueWatchingRanker.merge(listOf(
            ContinueWatchingRanker.SourceItems("sessions", 0, listOf(session, yt)),
            ContinueWatchingRanker.SourceItems("tvwatchnext", 1, listOf(listing)),
        ))
        assertEquals(listOf("session:com.amazon.firetv.youtube", "wn:1"), merged.map { it.id })
    }

    @Test fun localizedChannelNames() {
        assertTrue(ContinueChannels.isContinue("com.nuvio.tv", "继续观看", "com.nuvio.tv"))
        assertTrue(ContinueChannels.isContinue("com.some.app", "Continuer à regarder", null))
        assertTrue(ContinueChannels.isContinue("com.some.app", "Continue Watching", null))
        assertFalse(ContinueChannels.isContinue("com.some.app", "Trending", null))
    }
}
