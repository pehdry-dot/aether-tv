package com.aether.tv.update

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.TestEnv
import com.aether.tv.data.update.Installed
import com.aether.tv.data.update.Release
import com.aether.tv.data.update.ReleaseAsset
import com.aether.tv.data.update.Selection
import com.aether.tv.data.update.UpdateManager
import com.aether.tv.data.update.UpdateMetadata
import com.aether.tv.data.update.UpdateRules
import com.aether.tv.data.update.UpdateState
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

private const val KEY = "1ce2ca82ff95fba23ef62d97a3d2ba95746ced3a278dc55f5ca74966c0ce683e"
private const val OTHER_KEY = "aa11bb22cc33dd44ee55ff6600112233445566778899aabbccddeeff00112233"
private val TV = Installed("com.aether.tv", 3, "1.2.0", 34, listOf("armeabi-v7a", "armeabi"), listOf(KEY))

private fun meta(code: Long, minSdk: Int = 25, abis: List<String> = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"), signer: String = KEY, size: Long = 1000, sha: String = "00", app: String = "com.aether.tv") =
    UpdateMetadata(app, code, "1.$code.0", minSdk, abis, "AetherTV-$code.apk", size, sha, listOf(signer))

private fun release(m: UpdateMetadata, draft: Boolean = false, pre: Boolean = false) = Release(
    "v${m.versionName}", m.versionName, "2026-12-01T10:00:00Z", "## New\n- **Faster** things", draft, pre,
    listOf(ReleaseAsset(m.apk, "https://x/${m.apk}", m.size), ReleaseAsset(Release.METADATA_NAME, "https://x/meta", 300)), m,
)

/** Which release may update this TV, and what a downloaded file must prove before Android's installer sees it. */
class UpdateRulesTest {
    @Test fun picksTheNewestCompatibleRelease() {
        val sel = UpdateRules.select(listOf(release(meta(4)), release(meta(5)), release(meta(2))), TV)
        assertEquals(5L, (sel as Selection.Available).metadata.versionCode)
    }

    @Test fun skipsDraftsPrereleasesAndOtherApps() {
        val sel = UpdateRules.select(listOf(release(meta(9), pre = true), release(meta(8), draft = true), release(meta(7, app = "com.aether.tv.debug"))), TV)
        assertTrue(sel is Selection.UpToDate)
    }

    @Test fun thirtyTwoBitTvNeedsArmV7Code() {
        val sel = UpdateRules.select(listOf(release(meta(5, abis = listOf("arm64-v8a", "x86_64")))), TV)
        assertTrue((sel as Selection.Incompatible).reason.contains("armeabi-v7a"))
        // No native code at all runs everywhere.
        assertTrue(UpdateRules.select(listOf(release(meta(5, abis = emptyList()))), TV) is Selection.Available)
    }

    @Test fun androidVersionAndSigner() {
        assertTrue((UpdateRules.select(listOf(release(meta(5, minSdk = 35))), TV) as Selection.Incompatible).reason.contains("API 35"))
        val other = UpdateRules.select(listOf(release(meta(5, signer = OTHER_KEY))), TV) as Selection.Incompatible
        assertTrue(other.reason.contains("different key"))
        // A compatible older release is still offered when the newest one isn't.
        val mixed = UpdateRules.select(listOf(release(meta(6, signer = OTHER_KEY)), release(meta(5))), TV)
        assertEquals(5L, (mixed as Selection.Available).metadata.versionCode)
    }

    @Test fun numericVersionCodesNotNames() {
        val m = meta(10).copy(versionName = "1.2.0-final")
        assertTrue(UpdateRules.select(listOf(release(m)), TV) is Selection.Available)
        assertTrue(UpdateRules.select(listOf(release(meta(3))), TV) is Selection.UpToDate)
    }

    @Test fun verificationRejectsTheWrongFile() {
        val m = meta(5, size = 10, sha = "ab")
        fun a(pkg: String? = "com.aether.tv", code: Long = 5, signers: List<String> = listOf(KEY), sha: String = "ab", size: Long = 10) = UpdateRules.Archive(pkg, code, signers, sha, size)
        assertNull(UpdateRules.verify(a(), m, TV))
        assertTrue(UpdateRules.verify(a(pkg = "com.evil.app"), m, TV)!!.contains("not Aether"))
        assertTrue(UpdateRules.verify(a(pkg = null), m, TV)!!.contains("isn't an Android app"))
        assertTrue(UpdateRules.verify(a(signers = listOf(OTHER_KEY)), m, TV)!!.contains("different key"))
        assertTrue(UpdateRules.verify(a(sha = "cd"), m, TV)!!.contains("checksum"))
        assertTrue(UpdateRules.verify(a(size = 9), m, TV)!!.contains("incomplete"))
        assertTrue(UpdateRules.verify(a(code = 4), m, TV)!!.contains("isn't the one offered"))
        assertTrue(UpdateRules.verify(a(code = 2), meta(2, size = 10, sha = "ab"), TV)!!.contains("downgrade"))
    }

    @Test fun githubAnswerAndNotes() {
        val body = """[{"tag_name":"v1.3.0","name":"Aether 1.3.0","draft":false,"prerelease":false,"published_at":"2026-12-01T10:00:00Z","body":"## Fixes\n- [Docs](https://x) **now**",
            "assets":[{"name":"AetherTV-1.3.0.apk","browser_download_url":"https://g/a.apk","size":3600000},{"name":"aether-update.json","browser_download_url":"https://g/m.json","size":400}]},
            {"tag_name":"v1.1.0","name":"1.1.0","draft":false,"prerelease":false,"assets":[{"name":"AetherTV-1.1.0.apk","browser_download_url":"https://g/o.apk","size":3}]}]"""
        val rs = UpdateRules.parseReleases(body)
        assertEquals(2, rs.size)
        assertEquals(listOf("v1.3.0"), UpdateRules.candidates(rs).map { it.tag }) // 1.1.0 has no metadata: not compared
        assertEquals("Fixes\n• Docs now", UpdateRules.plainNotes(rs[0].notes))
    }
}

/** The whole update flow against a local stand-in for GitHub. Android's installer itself is stubbed (needs a TV). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class UpdateManagerTest {
    private val server = MockWebServer()
    private val env = TestEnv()
    private lateinit var u: UpdateManager
    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private var signer = KEY
    private var installs = 0
    private var permission = true
    private var dropAfter = -1
    private var rateLimited = false
    private var apkRequests = mutableListOf<RecordedRequest>()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                val base = server.url("/").toString().trimEnd('/')
                return when {
                    rateLimited -> MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0").setHeader("X-RateLimit-Reset", "1900000000")
                    r.path!!.startsWith("/repos/") -> MockResponse().setBody("""[{"tag_name":"v1.3.0","name":"Aether 1.3.0","draft":false,"prerelease":false,"published_at":"2026-12-01T10:00:00Z","body":"Notes",
                        "assets":[{"name":"AetherTV-1.3.0.apk","browser_download_url":"$base/a.apk","size":${apk.size}},{"name":"aether-update.json","browser_download_url":"$base/m.json","size":400}]}]""")
                    r.path == "/m.json" -> MockResponse().setBody("""{"applicationId":"com.aether.tv","versionCode":4,"versionName":"1.3.0","minSdk":25,"abis":["armeabi-v7a"],"apk":"AetherTV-1.3.0.apk","size":${apk.size},"sha256":"$sha","signerSha256":["$signer"]}""")
                    r.path == "/a.apk" -> {
                        apkRequests += r
                        val from = r.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                        val body = Buffer().write(apk, from, apk.size - from)
                        if (dropAfter in 1 until apk.size && from == 0) MockResponse().setBody(Buffer().write(apk, 0, dropAfter)).setHeader("Content-Length", apk.size).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        else if (from > 0) MockResponse().setResponseCode(206).setBody(body) else MockResponse().setBody(body)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        u = UpdateManager(env.context, env.settings, env.installer)
        // Robolectric reports no free space unless told: 400 MB free.
        org.robolectric.shadows.ShadowStatFs.registerStats(u.updatesDir, 100_000, 100_000, 100_000)
        u.apiBase = server.url("/").toString().trimEnd('/')
        u.installedInfo = { TV }
        u.readArchive = { f -> UpdateRules.Archive("com.aether.tv", 4, listOf(signer), com.aether.tv.platform.ApkInstaller.fileSha256(f), f.length()) }
        u.canInstall = { permission }
        u.startInstall = { installs++ }
    }

    @After fun tearDown() { server.shutdown(); env.close() }

    private fun waitFor(ms: Long = 8_000, cond: (UpdateState) -> Boolean): UpdateState {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); val s = u.state.value; if (cond(s)) return s; Thread.sleep(20) }
        throw AssertionError("State: ${u.state.value}")
    }

    @Test fun checkDownloadVerifyInstall() {
        u.check()
        val a = waitFor { it is UpdateState.Available } as UpdateState.Available
        assertEquals("1.3.0", a.meta.versionName)
        assertEquals("Aether 1.3.0 is available", waitForNotice())
        u.download()
        val ready = waitFor { it is UpdateState.Ready } as UpdateState.Ready
        assertTrue("stored in app-private storage", ready.file.canonicalPath.startsWith(env.context.filesDir.canonicalPath))
        u.install()
        waitFor { installs == 1 }
        // Dismissing hides the Home notice for this version.
        u.dismissNotice()
        waitFor(2_000) { u.notice.value == null }
    }

    private fun waitForNotice(): String? { val end = System.currentTimeMillis() + 3_000; while (u.notice.value == null && System.currentTimeMillis() < end) Thread.sleep(20); return u.notice.value }

    @Test fun permissionDetourResumesWhenYouComeBack() {
        permission = false
        u.check(); waitFor { it is UpdateState.Available }
        u.download(); waitFor { it is UpdateState.Ready }
        u.install()
        waitFor { it is UpdateState.NeedsPermission }
        assertEquals(0, installs)
        permission = true
        u.onResume() // back from "Install unknown apps"
        waitFor { installs == 1 }
    }

    @Test fun signerMismatchIsRefusedBeforeDownloading() {
        signer = OTHER_KEY
        u.check()
        val s = waitFor { it is UpdateState.Incompatible } as UpdateState.Incompatible
        assertTrue(s.reason.contains("different key"))
        assertTrue(apkRequests.isEmpty())
    }

    @Test fun aTamperedDownloadIsDeleted() {
        u.check(); waitFor { it is UpdateState.Available }
        u.readArchive = { f -> UpdateRules.Archive("com.other.app", 4, listOf(KEY), com.aether.tv.platform.ApkInstaller.fileSha256(f), f.length()) }
        u.download()
        val f = waitFor { it is UpdateState.Failed } as UpdateState.Failed
        assertTrue(f.message.contains("not Aether"))
        assertTrue(u.updatesDir.listFiles().orEmpty().none { it.name.endsWith(".apk") })
    }

    @Test fun interruptedDownloadResumesWithRange() {
        dropAfter = 100_000
        u.check(); waitFor { it is UpdateState.Available }
        u.download()
        val f = waitFor { it is UpdateState.Failed } as UpdateState.Failed
        assertEquals(UpdateState.Retry.Download, f.retry)
        dropAfter = -1
        u.download()
        waitFor { it is UpdateState.Ready }
        assertNotNull("second request continued where the first stopped", apkRequests.last().getHeader("Range"))
    }

    @Test fun cancelRemovesThePartialDownload() {
        server.dispatcher.let { d -> server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse = d.dispatch(r).let { if (r.path == "/a.apk") it.throttleBody(20_000, 1, java.util.concurrent.TimeUnit.SECONDS) else it }
        } }
        u.check(); waitFor { it is UpdateState.Available }
        u.download()
        waitFor { it is UpdateState.Downloading && it.done > 0 }
        u.cancel()
        waitFor { it is UpdateState.Available }
        Thread.sleep(300)
        assertFalse(u.updatesDir.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test fun rateLimitAndNoConnectionAreExplained() {
        rateLimited = true
        u.check()
        assertTrue((waitFor { it is UpdateState.Failed } as UpdateState.Failed).message.contains("limit"))
        u.apiBase = "http://unresolvable.invalid"
        u.check()
        val f = waitFor { it is UpdateState.Failed && !it.message.contains("limit") } as UpdateState.Failed
        assertTrue(f.message, f.message.contains("connection") || f.message.contains("GitHub"))
    }

    @Test fun notEnoughSpace() {
        server.dispatcher.let { d -> server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse = if (r.path == "/m.json")
                MockResponse().setBody("""{"applicationId":"com.aether.tv","versionCode":4,"versionName":"1.3.0","minSdk":25,"abis":[],"apk":"AetherTV-1.3.0.apk","size":${Long.MAX_VALUE / 4},"sha256":"$sha","signerSha256":["$signer"]}""")
            else d.dispatch(r)
        } }
        u.check(); waitFor { it is UpdateState.Available }
        u.download()
        assertTrue((waitFor { it is UpdateState.Failed } as UpdateState.Failed).message.contains("Not enough free space"))
    }
}
