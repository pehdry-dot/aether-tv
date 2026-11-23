package com.aether.tv.unit

import com.aether.tv.core.designsystem.PhotoScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** High-quality shrinking of device photos (cut-out product shots on transparency). */
class PhotoScaleTest {
    @Test fun fitKeepsAspectAndNeverEnlarges() {
        assertEquals(144 to 134, PhotoScale.fitSize(671, 623, 144, 140))
        assertEquals(100 to 50, PhotoScale.fitSize(100, 50, 400, 400))
    }

    @Test fun transparentEdgesDontDarken() {
        // Opaque red next to fully transparent black, halved: half-transparent pure red, not dark red.
        val px = intArrayOf(0xFFFF0000.toInt(), 0x00000000, 0xFFFF0000.toInt(), 0x00000000)
        val out = PhotoScale.areaAverage(px, 2, 2, 1, 1)[0]
        assertTrue("alpha ${out ushr 24}", (out ushr 24) in 126..129)
        assertEquals(255, out shr 16 and 255)
        assertEquals(0, out shr 8 and 255)
    }

    @Test fun everyPixelCounts() {
        // A one-pixel line in an 8x8 block survives a 4x shrink as a faint line instead of vanishing or aliasing.
        val px = IntArray(64) { i -> if (i % 8 == 3) -1 else 0xFF000000.toInt() }
        val out = PhotoScale.areaAverage(px, 8, 8, 2, 2)
        val grey = out[0] and 255
        assertTrue("grey $grey", grey in 60..68)   // 1/4 of 255
        assertEquals(0, out[1] and 255)
    }
}
