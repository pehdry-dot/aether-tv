package com.aether.tv.unit

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.data.media.WatchNextText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Continue Watching published by apps such as Nuvio to Android TV's Watch Next list and channels. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class WatchNextTest {
    @Test fun episodeLine() {
        assertEquals("S2 E5 · The Bridge", WatchNextText.episode("2", "5", "The Bridge"))
        assertEquals("S1 E1", WatchNextText.episode("1", "1", null))
        assertNull(WatchNextText.episode(null, "3", ""))
    }

    @Test fun progressAndTimeLeft() {
        assertEquals(0.5f, WatchNextText.progress(3_600_000, 1_800_000)!!, 0.001f)
        assertNull(WatchNextText.progress(3_600_000, 500))      // not really started
        assertNull(WatchNextText.progress(0, 10_000))           // unknown length
        assertEquals("30 min left", WatchNextText.timeLeft(3_600_000, 1_800_000))
        assertEquals("1 h 10 min left", WatchNextText.timeLeft(5_400_000, 1_200_000))
        assertNull(WatchNextText.timeLeft(3_600_000, 3_600_000))
    }

    @Test fun resumeLinkOpensOnlyThePublishingApp() {
        val deep = WatchNextText.resumeIntent("nuvio://play/tt123?t=1200", "com.nuvio.tv")!!
        assertEquals(Intent.ACTION_VIEW, deep.action)
        assertEquals("com.nuvio.tv", deep.`package`)

        val own = WatchNextText.resumeIntent("intent:#Intent;component=com.nuvio.tv/.MainActivity;S.id=tt123;end", "com.nuvio.tv")!!
        assertEquals("com.nuvio.tv", own.component!!.packageName)

        // A link that points at another app is refused.
        assertNull(WatchNextText.resumeIntent("intent:#Intent;component=com.other/.Evil;end", "com.nuvio.tv"))

        // URI-permission grants are stripped.
        val granted = WatchNextText.resumeIntent("intent:#Intent;package=com.nuvio.tv;launchFlags=0x1;end", "com.nuvio.tv")!!
        assertEquals(0, granted.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNull(WatchNextText.resumeIntent(null, "com.nuvio.tv"))
    }
}
