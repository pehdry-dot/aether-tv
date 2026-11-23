package com.aether.tv.unit

import com.aether.tv.data.store.AuroraRelease
import com.aether.tv.platform.HomeRedirectPolicy
import com.aether.tv.platform.HomeRedirectPolicy.Decision.Ignore
import com.aether.tv.platform.HomeRedirectPolicy.Decision.Redirect
import com.aether.tv.platform.HomeRedirectPolicy.KeyDecision.Consume
import com.aether.tv.platform.HomeRedirectPolicy.KeyDecision.OpenQuickSettings
import com.aether.tv.platform.HomeRedirectPolicy.KeyDecision.PassThrough
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TV's own quick panel (⚙) and the optional "hold ☰" shortcut, as the Home button service handles them. */
class RemoteShortcutTest {
    private val amazon = "com.amazon.tv.launcher"
    private val home = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
    private val menu = HomeRedirectPolicy.KEYCODE_MENU
    private val gear = HomeRedirectPolicy.KEYCODE_SETTINGS

    @Test fun theQuickPanelIsNeverCovered() {
        val p = HomeRedirectPolicy("com.aether.tv")
        p.onWindow("com.nuvio.tv", "com.nuvio.tv.MainActivity", 0)
        assertEquals(PassThrough, p.onKey(gear, true, 0, 1_000, menuShortcut = false)) // ⚙ reaches the system as usual
        // The panel itself (reported host) and, on builds that draw it from Amazon's launcher, a launcher window.
        assertEquals(Ignore, p.onWindow("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.hud.HudActivity", 1_200))
        assertEquals(Ignore, p.onWindow(amazon, home, 1_300))
        // Picture settings for an HDMI input (MediaTek TVs) and the input itself.
        assertEquals(Ignore, p.onWindow("com.amazon.tv.settings.mediatek", "x.PictureActivity", 1_500))
        // Panel closed, back to the app: Home works normally again.
        assertEquals(Ignore, p.onWindow("com.nuvio.tv", "com.nuvio.tv.MainActivity", 20_000))
        assertEquals(Redirect, p.onWindow(amazon, home, 30_000))
    }

    @Test fun panelSessionExpires() {
        val p = HomeRedirectPolicy("com.aether.tv")
        p.beginPanelSession(0)
        assertEquals(Ignore, p.onWindow(amazon, home, 10_000))
        assertEquals(Redirect, p.onWindow(amazon, home, HomeRedirectPolicy.PANEL_SESSION_MS + 5_000))
    }

    @Test fun aShortMenuPressStillReachesTheApp() {
        val p = HomeRedirectPolicy("com.aether.tv")
        assertEquals(PassThrough, p.onKey(menu, true, 0, 0, menuShortcut = true))
        assertEquals(PassThrough, p.onKey(menu, false, 0, 120, menuShortcut = true))
    }

    @Test fun holdingMenuOpensThePanelOnce() {
        val p = HomeRedirectPolicy("com.aether.tv")
        assertEquals(PassThrough, p.onKey(menu, true, 0, 0, true))
        assertEquals(PassThrough, p.onKey(menu, true, 1, 400, true)) // not held long enough yet
        assertEquals(OpenQuickSettings, p.onKey(menu, true, 2, 750, true))
        assertEquals(Consume, p.onKey(menu, true, 3, 800, true))
        assertEquals(Consume, p.onKey(menu, false, 0, 900, true)) // the app doesn't get the release of that hold
        assertTrue(p.inPanelSession)
        assertEquals(PassThrough, p.onKey(menu, true, 0, 2_000, true)) // next press is normal again
    }

    @Test fun otherKeysAreNeverTouched() {
        val p = HomeRedirectPolicy("com.aether.tv")
        val keys = listOf(3 /* Home */, 4 /* Back */, 24, 25, 164 /* volume, mute */, 85, 89, 90, 126, 127 /* playback */, 23, 19, 20, 21, 22)
        for (k in keys) for (repeat in 0..3) {
            assertEquals(PassThrough, p.onKey(k, true, repeat, repeat * 500L, menuShortcut = true))
            assertEquals(PassThrough, p.onKey(k, false, 0, 2_000, menuShortcut = true))
        }
        // Shortcut off: even a long ☰ goes to the app.
        p.onKey(menu, true, 0, 0, false)
        assertEquals(PassThrough, p.onKey(menu, true, 5, 2_000, false))
    }

    @Test fun auroraOfficialReleaseAndSigner() {
        val release = """{"name":"4.8.4","description":"### Downloads\n- [AuroraStore-4.8.4.apk](/uploads/0123456789abcdef0123456789abcdef/AuroraStore-4.8.4.apk)\n- [hw](/uploads/fedcba9876543210fedcba9876543210/AuroraStore-4.8.4-hw.apk)"}"""
        assertEquals("/uploads/0123456789abcdef0123456789abcdef/AuroraStore-4.8.4.apk", AuroraRelease.apkPath(release))
        assertNull(AuroraRelease.apkPath("""{"description":"no files"}"""))
        assertNull(AuroraRelease.problem("com.aurora.store", listOf(AuroraRelease.SIGNER_SHA256.uppercase())))
        assertTrue(AuroraRelease.problem("com.aurora.store", listOf("00"))!!.contains("isn't signed"))
        assertTrue(AuroraRelease.problem("com.evil", listOf(AuroraRelease.SIGNER_SHA256))!!.contains("not Aurora"))
    }
}
