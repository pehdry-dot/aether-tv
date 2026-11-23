package com.aether.tv.core.designsystem

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns an app icon or banner into a single-colour logo: the shape of the logo as an alpha mask, cropped to the logo.
 * Works on plain ARGB pixels so it can be unit-tested without Android.
 *
 * The background is estimated from the image's border (or, for a shaped icon such as a circle on transparency, from the
 * edge of the shape). Pixels that differ clearly from it become the logo. Parts of the logo drawn in the background
 * colour (YouTube's white triangle, Spotify's black stripes) stay cut out, as they are in the single-colour versions of
 * those logos. An image that is only a logo on transparency (adaptive-icon foreground or monochrome layer) uses its
 * alpha directly.
 *
 * Returns null when there is no clear logo: nothing differs from the background, or the "logo" fills almost the whole
 * image (a photo or key-art banner). Callers then show the app's own colours.
 */
object LogoMask {
    class Result(val width: Int, val height: Int, /** ARGB, white with the mask as alpha. */ val pixels: IntArray)

    private const val RING = 2
    /** Colour distance (0..1 of the RGB cube diagonal) a logo must reach somewhere nearby to count as logo at all. */
    private const val NEAR = 0.10f
    private const val MAX_COVERAGE = 0.80f
    /** A shape on transparency covering more than this is a filled icon shape, not a bare logo. */
    private const val SHAPE_COVERAGE = 0.45f
    private const val MIN_COVERAGE = 0.004f
    /** Distance a logo's own colour must reach: anything fainter than this nearby is not part of the logo. */
    private const val STRONG = 0.25f
    /** Colour noise (JPEG artefacts, slight gradients) below this distance is ignored entirely. */
    private const val NOISE_FLOOR = 0.03f

    fun extract(px: IntArray, w: Int, h: Int): Result? {
        if (w < 8 || h < 8 || px.size < w * h) return null
        val border = ArrayList<Int>(4 * RING * (w + h)); var ringCount = 0; var clear = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x >= RING && y >= RING && x < w - RING && y < h - RING) continue
            val c = px[y * w + x]; ringCount++
            if ((c ushr 24) < 128) clear++ else border += c
        }
        val mask = if (clear * 2 > ringCount) {
            // Transparent surroundings: either a bare logo (use its alpha) or a filled shape (circle, squircle) whose
            // own background colour has to be removed like a square icon's.
            val alphaOnly = maskFrom(px, w, h, null)
            if (alphaOnly.coverage <= SHAPE_COVERAGE) alphaOnly
            else shapeEdge(px, w, h).takeIf { it.isNotEmpty() }?.let { maskFrom(px, w, h, it) } ?: return null
        } else maskFrom(px, w, h, border)
        if (mask.maxX < 0 || mask.coverage < MIN_COVERAGE || mask.coverage > MAX_COVERAGE) return null
        // Crop to the logo with one pixel of margin for the anti-aliased edge.
        val x0 = max(0, mask.minX - 1); val y0 = max(0, mask.minY - 1)
        val x1 = min(w - 1, mask.maxX + 1); val y1 = min(h - 1, mask.maxY + 1)
        val ow = x1 - x0 + 1; val oh = y1 - y0 + 1
        val out = IntArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            val a = (mask.values[(y + y0) * w + (x + x0)] * 255f + 0.5f).toInt().coerceIn(0, 255)
            out[y * ow + x] = (a shl 24) or 0xFFFFFF
        }
        return Result(ow, oh, out)
    }

    private class Mask(val values: FloatArray, val coverage: Float, val minX: Int, val minY: Int, val maxX: Int, val maxY: Int)

    /** [background]: sample colours of the background, or null to use the image's alpha alone. */
    private fun maskFrom(px: IntArray, w: Int, h: Int, background: List<Int>?): Mask {
        var r = 0f; var g = 0f; var b = 0f; var near = 0f
        if (background != null && background.isNotEmpty()) {
            for (c in background) { r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 }
            r /= background.size; g /= background.size; b /= background.size
            // A graded or noisy background (gradients) raises the threshold so it doesn't leak into the logo.
            var dev = 0f
            for (c in background) dev += dist(c, r, g, b)
            near = max(NEAR, dev / background.size * 2.5f)
        }
        val values = FloatArray(w * h)
        if (background == null) {
            for (i in 0 until w * h) values[i] = (px[i] ushr 24) / 255f
        } else {
            // How far each pixel is from the background colour. An anti-aliased edge pixel that is 40% logo sits about
            // 40% of the way from the background to the logo's own colour, so dividing by the strongest distance nearby
            // (the solid logo next to it) recovers the original edge smoothness instead of hardening it.
            val d = FloatArray(w * h) { i -> (px[i] ushr 24) / 255f * dist(px[i], r, g, b) }
            val local = maxFilter(d, w, h, 2)
            // Faint details (glows, shadows, low-contrast taglines) never reach STRONG and are left out, rather than
            // being stretched to full white by the division.
            val floor = max(NOISE_FLOOR, near * 0.3f)
            val gate = max(near, STRONG)
            for (i in 0 until w * h) {
                val solid = max(local[i] * 0.85f, STRONG)
                values[i] = if (local[i] < gate) 0f else ((d[i] - floor) / (solid - floor)).coerceIn(0f, 1f)
            }
        }
        var minX = w; var minY = h; var maxX = -1; var maxY = -1; var sum = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val m = values[y * w + x]
            if (m > 0.08f) { minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y) }
            sum += m
        }
        return Mask(values, (sum / (w * h)).toFloat(), minX, minY, maxX, maxY)
    }

    /** Maximum over a (2r+1)² square, done as two 1-D passes. */
    private fun maxFilter(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (k in max(0, x - r)..min(w - 1, x + r)) m = max(m, v[y * w + k])
            tmp[y * w + x] = m
        }
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
            for (k in max(0, y - r)..min(h - 1, y + r)) m = max(m, tmp[k * w + x])
            out[y * w + x] = m
        }
        return out
    }

    /**
     * Shrinks a logo to fit inside [maxW] x [maxH] pixels by area averaging (every source pixel contributes exactly its
     * share), so it can be drawn 1:1 with no further scaling. Logos smaller than the box are returned unchanged.
     */
    fun scaleToFit(src: Result, maxW: Int, maxH: Int): Result {
        val s = min(maxW / src.width.toFloat(), maxH / src.height.toFloat())
        if (s >= 1f) return src
        val ow = max(1, (src.width * s).roundToInt()); val oh = max(1, (src.height * s).roundToInt())
        val alpha = FloatArray(src.width * src.height) { (src.pixels[it] ushr 24) / 255f }
        val rows = PhotoScale.resample(alpha, src.width, src.height, ow, horizontal = true)   // ow x src.height
        val both = PhotoScale.resample(rows, ow, src.height, oh, horizontal = false)          // ow x oh
        return Result(ow, oh, IntArray(ow * oh) { ((both[it] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF })
    }

    /** Opaque pixels just inside the edge of a shape drawn on transparency (its background colour). */
    private fun shapeEdge(px: IntArray, w: Int, h: Int): List<Int> {
        val out = ArrayList<Int>()
        fun clearAt(x: Int, y: Int) = x < 0 || y < 0 || x >= w || y >= h || (px[y * w + x] ushr 24) < 128
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            if ((c ushr 24) < 230) continue
            // Two pixels in from the edge, so the anti-aliased rim isn't sampled.
            if (!clearAt(x - 1, y) && !clearAt(x + 1, y) && !clearAt(x, y - 1) && !clearAt(x, y + 1) &&
                (clearAt(x - 3, y) || clearAt(x + 3, y) || clearAt(x, y - 3) || clearAt(x, y + 3))) out += c
        }
        return out
    }

    private fun dist(c: Int, r: Float, g: Float, b: Float): Float {
        val dr = (c shr 16 and 255) - r; val dg = (c shr 8 and 255) - g; val db = (c and 255) - b
        return sqrt(dr * dr + dg * dg + db * db) / 441.67f
    }
}
