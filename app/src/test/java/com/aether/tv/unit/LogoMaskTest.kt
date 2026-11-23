package com.aether.tv.unit

import com.aether.tv.core.designsystem.LogoMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** White single-colour logos cut out of app icons (synthetic icons shaped like the ones on the TV). */
class LogoMaskTest {
    private val size = 100
    private fun image(f: (x: Int, y: Int) -> Int) = IntArray(size * size) { f(it % size, it / size) }
    private fun alphaAt(r: LogoMask.Result, x: Int, y: Int) = r.pixels[y * r.width + x] ushr 24

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()

    @Test fun logoOnWhiteSquareKeepsItsCutOut() {
        // Like YouTube's icon: a red block on white with a white "play" triangle inside it.
        val px = image { x, y ->
            val inBlock = x in 20..79 && y in 30..69
            val inTriangle = x in 42..58 && abs(y - 50) <= (x - 42) / 2 + 1 && x - 42 >= 0
            if (inBlock && !inTriangle) red else white
        }
        val r = LogoMask.extract(px, size, size)!!
        assertTrue("cropped to the block, got ${r.width}x${r.height}", abs(r.width - 62) <= 2 && abs(r.height - 42) <= 2)
        assertEquals(255, alphaAt(r, 4, 4))              // inside the red block
        assertEquals(0, alphaAt(r, 50 - 19, 50 - 29))    // inside the triangle: stays a hole
        assertEquals(0xFFFFFF, r.pixels[0] and 0xFFFFFF)  // the logo is white
    }

    @Test fun logoOnDarkSquare() {
        // Like Spotify's icon: a green disc on black.
        val px = image { x, y -> if (hypot(x - 50.0, y - 50.0) <= 30) 0xFF1ED760.toInt() else black }
        val r = LogoMask.extract(px, size, size)!!
        assertTrue(abs(r.width - 63) <= 2)
        assertEquals(255, alphaAt(r, r.width / 2, r.height / 2))
        assertEquals(0, alphaAt(r, 0, 0))
    }

    @Test fun circleIconOnTransparencyGivesTheMarkNotTheDisc() {
        // A circular icon (navy disc, transparent corners) with a pink mark in the middle.
        val px = image { x, y ->
            val d = hypot(x - 49.5, y - 49.5)
            when {
                d > 49 -> 0
                x in 40..60 && y in 38..62 -> 0xFFFF5FA2.toInt()
                else -> 0xFF14142B.toInt()
            }
        }
        val r = LogoMask.extract(px, size, size)!!
        assertTrue("expected the mark (~23x27), got ${r.width}x${r.height}", r.width in 20..26 && r.height in 24..30)
    }

    @Test fun bareLogoOnTransparencyUsesItsAlpha() {
        val px = image { x, y -> if (x in 30..69 && y in 45..54) white else 0 }
        val r = LogoMask.extract(px, size, size)!!
        assertTrue(abs(r.width - 42) <= 2 && abs(r.height - 12) <= 2)
    }

    @Test fun photoLikeImageHasNoLogo() {
        var seed = 7
        val px = image { _, _ -> seed = seed * 1103515245 + 12345; 0xFF000000.toInt() or (seed ushr 8 and 0xFFFFFF) }
        assertNull(LogoMask.extract(px, size, size))
    }

    @Test fun antiAliasedEdgeKeepsItsSoftness() {
        // A black bar on white whose bottom row is half-covered (mid grey), as drawn with anti-aliasing.
        val grey = 0xFF808080.toInt()
        val px = image { x, y -> if (x !in 20..79) white else if (y in 30..69) black else if (y == 70) grey else white }
        val r = LogoMask.extract(px, size, size)!!
        val edge = alphaAt(r, r.width / 2, r.height - 2)   // the grey row (one row of margin below it)
        assertTrue("edge alpha $edge should stay partial", edge in 110..180)
        assertEquals(255, alphaAt(r, r.width / 2, r.height / 2))
    }

    @Test fun shrinkingAveragesWholePixels() {
        // Alternating opaque and clear columns average to half alpha when halved.
        val src = LogoMask.Result(8, 4, IntArray(32) { i -> if ((i % 8) % 2 == 0) -1 else 0x00FFFFFF })
        val out = LogoMask.scaleToFit(src, 4, 4)
        assertEquals(4, out.width); assertEquals(2, out.height)
        for (p in out.pixels) assertTrue("alpha ${p ushr 24}", (p ushr 24) in 126..129)
        assertEquals(src, LogoMask.scaleToFit(src, 100, 100))   // never enlarged
    }

    @Test fun blankImageHasNoLogo() {
        assertNull(LogoMask.extract(IntArray(size * size) { white }, size, size))
    }
}
