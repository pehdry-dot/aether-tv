package com.aether.tv.unit

import com.aether.tv.platform.HomeRedirectPolicy
import com.aether.tv.platform.HomeRedirectPolicy.Decision.Ignore
import com.aether.tv.platform.HomeRedirectPolicy.Decision.Redirect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event sequences as recorded on the Toshiba 43M450SU (Fire OS 14) with `uiautomator events`. */
class HomeRedirectPolicyTest {
    private val amazon = "com.amazon.tv.launcher"
    private val home = "com.amazon.tv.launcher.ui.HomeActivity_vNext"
    private fun policy() = HomeRedirectPolicy("com.aether.tv")

    @Test fun homeFromAnAppRedirectsOnceForTheTwoEventsAmazonSends() {
        val p = policy()
        assertEquals(Ignore, p.onWindow("com.netflix.ninja", "com.netflix.ninja.MainActivity", 0))
        assertEquals(Redirect, p.onWindow(amazon, home, 1_000))
        assertEquals(Ignore, p.onWindow(amazon, home, 1_170)) // second event of the same press
    }

    @Test fun otherLauncherWindowsAreNotHome() {
        val p = policy()
        assertEquals(Ignore, p.onWindow(amazon, "com.amazon.tv.launcher.ui.SettingsActivity", 0))
        assertEquals(Ignore, p.onWindow(amazon, null, 0))
        assertFalse(HomeRedirectPolicy.isAmazonHome("com.example", home))
    }

    @Test fun settingsSessionLeavesAmazonsSettingsAlone() {
        val p = policy()
        p.beginSettingsSession(0)
        assertEquals(Ignore, p.onWindow(amazon, home, 100)) // Settings is drawn inside Amazon's HomeActivity
        assertEquals(Ignore, p.onWindow("com.amazon.tv.settings.v2", "x.NetworkActivity", 2_000))
        assertEquals(Ignore, p.onWindow(amazon, home, 4_000)) // Back from Network to the Settings grid
        assertTrue(p.inSettingsSession)
    }

    @Test fun homeTabFocusDuringSettingsMeansHomeWasPressed() {
        val p = policy()
        p.beginSettingsSession(0)
        assertEquals(Ignore, p.onFocus(amazon, "Find", 5_000))
        assertEquals(Redirect, p.onFocus(amazon, "Home", 5_003))
        assertFalse(p.inSettingsSession)
    }

    @Test fun focusEventsOutsideASessionAreIgnored() {
        val p = policy()
        assertEquals(Ignore, p.onFocus(amazon, "Home", 0))
        p.beginSettingsSession(0)
        assertEquals(Ignore, p.onFocus("com.netflix.ninja", "Home", 10))
    }

    @Test fun openingAnAppOrAetherEndsTheSession() {
        val p = policy()
        p.beginSettingsSession(0)
        p.onWindow("com.netflix.ninja", "m", 1_000)
        assertFalse(p.inSettingsSession)
        assertEquals(Redirect, p.onWindow(amazon, home, 5_000))
        p.beginSettingsSession(6_000)
        p.onWindow("com.aether.tv", "com.aether.tv.MainActivity", 7_000)
        assertFalse(p.inSettingsSession)
    }

    @Test fun systemOverlaysDoNotEndTheSession() {
        val p = policy()
        p.beginSettingsSession(0)
        p.onWindow("com.android.systemui", null, 10)
        p.onWindow("com.amazon.tv.parentalcontrols", "PINManagementActivity", 20)
        assertTrue(p.inSettingsSession)
    }

    @Test fun sessionExpiresAsAFailSafe() {
        val p = policy()
        p.beginSettingsSession(0)
        assertEquals(Redirect, p.onWindow(amazon, home, HomeRedirectPolicy.SETTINGS_SESSION_MAX_MS + 1))
    }

    @Test fun loopGuardStopsFightingAndRecovers() {
        val p = policy()
        var t = 0L
        repeat(HomeRedirectPolicy.LOOP_MAX) { assertEquals(Redirect, p.onWindow(amazon, home, t)); t += 1_500 }
        assertEquals(Ignore, p.onWindow(amazon, home, t))
        assertTrue(p.takeLoopTripped())
        assertFalse(p.takeLoopTripped())
        assertEquals(Ignore, p.onWindow(amazon, home, t + 30_000)) // still cooling down
        assertEquals(Redirect, p.onWindow(amazon, home, t + HomeRedirectPolicy.COOLDOWN_MS + 1))
    }
}
