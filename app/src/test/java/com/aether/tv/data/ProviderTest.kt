package com.aether.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.MemorySecrets
import com.aether.tv.TestEnv
import com.aether.tv.data.media.JellyfinProvider
import com.aether.tv.data.media.KodiProvider
import com.aether.tv.data.media.ProviderConfig
import com.aether.tv.data.media.ProviderStatus
import com.aether.tv.data.media.ProviderStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercises the real HTTP adapters against a local mock server (no external network). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ProviderTest {
    private val server = MockWebServer()
    private val env = TestEnv()
    @After fun tearDown() { server.shutdown(); env.close() }

    @Test fun jellyfinSignInAndResume() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/System/Info/Public") -> json("""{"ServerName":"Den"}""")
                request.path!!.startsWith("/Users/AuthenticateByName") -> {
                    assertTrue(request.getHeader("Authorization")!!.startsWith("MediaBrowser Client=\"Aether TV\""))
                    json("""{"AccessToken":"tok","User":{"Id":"u1","Name":"Sam"}}""")
                }
                request.path!!.startsWith("/Users/u1/Items/Resume") -> {
                    assertTrue(request.getHeader("Authorization")!!.contains("Token=\"tok\""))
                    json("""{"Items":[{"Id":"e1","Type":"Episode","Name":"Pilot","SeriesName":"Show","ParentIndexNumber":2,"IndexNumber":4,"UserData":{"PlayedPercentage":37.5},"ParentBackdropItemId":"s1"}]}""")
                }
                request.path!!.startsWith("/Users/u1") -> json("""{"Id":"u1"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val store = ProviderStore(MemorySecrets())
        val jf = JellyfinProvider(store, env.launcher)
        jf.signIn(server.url("/").toString(), "Sam", "pw")
        assertTrue(jf.status() is ProviderStatus.Connected)
        val items = jf.continueWatching(10)
        assertEquals(1, items.size)
        val it = items.single()
        assertEquals("Show", it.title); assertEquals("S2 E4 • Pilot", it.subtitle)
        assertEquals(0.375f, it.progress!!, 0.001f)
        assertTrue(it.artworkUrl!!.endsWith("/Items/s1/Images/Backdrop?maxWidth=640"))
    }

    @Test fun kodiInProgressUsesResumePoints() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return when {
                    "GetMovies" in body -> json("""{"result":{"movies":[{"movieid":7,"title":"Dunes","year":2024,"resume":{"position":600,"total":2400},"art":{"fanart":"image://x/"}}]}}""")
                    "GetEpisodes" in body -> json("""{"result":{"episodes":[]}}""")
                    else -> json("""{"result":"pong"}""")
                }
            }
        }
        server.start()
        val store = ProviderStore(MemorySecrets())
        val kodi = KodiProvider(store, env.launcher)
        kodi.configure(server.hostName + ":" + server.port, null, null)
        assertTrue(kodi.status() is ProviderStatus.Connected)
        val items = kodi.continueWatching(5)
        assertEquals("Dunes", items.single().title)
        assertEquals(0.25f, items.single().progress!!, 0.001f)
        assertTrue(items.single().artworkUrl!!.contains("/image/image%3A%2F%2Fx%2F"))
    }

    @Test fun unreachableServerReportsErrorNotFakeData() = runBlocking {
        val store = ProviderStore(MemorySecrets())
        store.save("kodi", ProviderConfig(enabled = true, serverUrl = "http://127.0.0.1:1"))
        val kodi = KodiProvider(store, env.launcher)
        assertTrue(kodi.status() is ProviderStatus.Error)
    }

    private fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
}
