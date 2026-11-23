package com.aether.tv.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.data.media.TvWatchNextProvider
import com.aether.tv.media.FakeTvProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Home's Continue Watching row with real Watch Next reads: late writes, reordering under focus, resuming. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class HomeContinueWatchingTest : LauncherUiTest() {
    @Test fun newEpisodeAppearsFirstFocusStaysOnItsCardAndOkResumesIt() {
        shadowOf(env.context).grantPermissions(TvWatchNextProvider.PERMISSION)
        val tv = Robolectric.setupContentProvider(FakeTvProvider::class.java, FakeTvProvider.AUTHORITY)
        val now = System.currentTimeMillis()
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("a", "Arcadia", 1, 1, "One", 600_000, 2_400_000, now - 1_000))
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("b", "Blue Shore", 1, 1, "One", 600_000, 2_400_000, now - 2_000))
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("c", "Cold Harbor", 1, 1, "One", 600_000, 2_400_000, now - 3_000))
        vm.testStateTransform.value = null // real media, not the screenshot demo
        rule.runOnUiThread { vm.onResume() }
        settleUntil { vm.state.value.media.continueWatching.size == 3 }
        down() // from the first app to the Continue Watching row
        assertEquals("Arcadia", focusedLabel())
        right()
        assertEquals("Blue Shore", focusedLabel())

        // Nuvio publishes a newer episode while Home is showing.
        tv.insert(FakeTvProvider.WATCH_NEXT, FakeTvProvider.nuvioWatchNext("n", "Night Train", 2, 4, "Endure", 1_200_000, 2_400_000, now))
        settleUntil { vm.state.value.media.continueWatching.firstOrNull()?.title == "Night Train" }
        settleUntil { true }
        assertEquals("Focus stays on the card it was on when the row reorders", "Blue Shore", focusedLabel())
        left(2)
        assertEquals("Night Train", focusedLabel())
        lastStartedPackage()
        ok()
        settleUntil { lastStartedPackage() == "com.nuvio.tv" }
        rule.runOnUiThread { vm.onPause() }
    }
}
