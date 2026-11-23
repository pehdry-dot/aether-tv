package com.aether.tv.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.TestEnv
import com.aether.tv.data.assets.AssetKind
import com.aether.tv.data.assets.ImportResult
import com.aether.tv.ui.WallpaperCandidate
import com.aether.tv.ui.WallpaperSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.File

/** Wallpapers from a web address or the TV folder: useful errors, nothing kept unless applied, originals untouched. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class WallpaperImportTest {
    private val env = TestEnv()
    private val server = MockWebServer()
    @After fun tearDown() { server.shutdown(); env.close() }

    private fun png(): ByteArray {
        val b = android.graphics.Bitmap.createBitmap(64, 36, android.graphics.Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun downloadsExplainWhatWentWrong() = runBlocking {
        val pic = png()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest) = when (r.path) {
                "/ok.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(pic))
                "/page" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html>")
                "/fake.png" -> MockResponse().setHeader("Content-Type", "image/png").setBody("not a picture")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        fun get(path: String) = runBlocking { env.assets.download(server.url(path).toString(), env.assets.newPreviewFile()) }
        assertTrue(get("/ok.png") is ImportResult.Ok)
        assertTrue((get("/page") as ImportResult.Failed).reason.contains("web page"))
        assertTrue((get("/missing.png") as ImportResult.Failed).reason.contains("404"))
        assertTrue((get("/fake.png") as ImportResult.Failed).reason.contains("isn't a picture"))
        assertTrue((env.assets.download("ftp://x", env.assets.newPreviewFile()) as ImportResult.Failed).reason.contains("https://"))
        // Failed downloads leave nothing behind; only the good one is in the preview folder.
        assertEquals(1, env.assets.previewDir.listFiles()!!.size)
    }

    @Test fun applyKeepsAPreviewCancelRemovesItAndTheTvFolderIsNeverTouched() {
        val vm = env.viewModel()
        runBlocking { env.profiles.ensureDefault() }
        val preview = env.assets.newPreviewFile().apply { writeBytes(png()) }
        val original = File(env.assets.tvFolder(AssetKind.Wallpaper), "beach.png").apply { writeBytes(png()) }
        // Cancel: the downloaded preview goes, the profile is unchanged.
        val before = runBlocking { env.settings.get("default").appearance.wallpaper }
        vm.discardWallpaperPreview(WallpaperCandidate("file:${preview.path}", "x", WallpaperSource.Web))
        assertFalse(preview.exists())
        assertEquals(before, runBlocking { env.settings.get("default").appearance.wallpaper })
        // A TV-folder picture discarded: the original stays.
        vm.discardWallpaperPreview(WallpaperCandidate("file:${original.path}", "beach", WallpaperSource.TvFolder))
        assertTrue(original.exists())
        // Apply: Aether keeps its own copy; the original in the TV folder is untouched.
        vm.applyWallpaper(WallpaperCandidate("file:${original.path}", "beach", WallpaperSource.TvFolder))
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end && runBlocking { env.settings.get("default").appearance.wallpaper } == before) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        val applied = File(runBlocking { env.settings.get("default").appearance.wallpaper }.removePrefix("file:"))
        assertTrue(env.assets.isPrivate(applied))
        assertTrue(original.exists())
        // Previews left over from a crash are removed at the next start.
        env.assets.newPreviewFile().writeBytes(ByteArray(10))
        assertTrue(env.assets.clearPreviews() > 0)
    }
}
