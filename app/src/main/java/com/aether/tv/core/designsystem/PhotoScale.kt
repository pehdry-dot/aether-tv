package com.aether.tv.core.designsystem

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-quality shrinking for pictures shown much smaller than their source. The GPU's bilinear filter skips most source
 * pixels when shrinking more than 2x, which makes edges jagged; area averaging lets every source pixel count.
 * Pure functions (unit-tested).
 */
object PhotoScale {
    /** Size that fits [w] x [h] inside [maxW] x [maxH] without cropping, never larger than the source. */
    fun fitSize(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        val s = min(1f, min(maxW / w.toFloat(), maxH / h.toFloat()))
        return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
    }

    /**
     * Area-average resampling of (unpremultiplied) ARGB pixels to [ow] x [oh]. Colour is averaged premultiplied, so
     * transparent surroundings don't darken the edges of a cut-out photo.
     */
    fun areaAverage(px: IntArray, w: Int, h: Int, ow: Int, oh: Int): IntArray {
        val n = w * h
        val a = FloatArray(n); val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        for (i in 0 until n) {
            val c = px[i]; val al = (c ushr 24) / 255f
            a[i] = al; r[i] = (c shr 16 and 255) * al; g[i] = (c shr 8 and 255) * al; b[i] = (c and 255) * al
        }
        fun plane(v: FloatArray) = resample(resample(v, w, h, ow, horizontal = true), ow, h, oh, horizontal = false)
        val pa = plane(a); val pr = plane(r); val pg = plane(g); val pb = plane(b)
        return IntArray(ow * oh) { i ->
            val al = pa[i]
            if (al <= 0.0005f) 0 else {
                val ai = (al * 255f + 0.5f).toInt().coerceIn(0, 255)
                fun ch(v: Float) = (v / al + 0.5f).toInt().coerceIn(0, 255)
                (ai shl 24) or (ch(pr[i]) shl 16) or (ch(pg[i]) shl 8) or ch(pb[i])
            }
        }
    }

    /** Box-filter resampling along one axis to [n] samples; every source pixel contributes exactly its share. */
    fun resample(v: FloatArray, w: Int, h: Int, n: Int, horizontal: Boolean): FloatArray {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val outW = if (horizontal) n else w
        val out = FloatArray(if (horizontal) n * h else w * n)
        val step = len / n.toFloat()
        for (line in 0 until lines) for (o in 0 until n) {
            val a = o * step; val b = a + step
            var acc = 0f
            var k = a.toInt()
            while (k < b && k < len) {
                val cover = min(b, k + 1f) - max(a, k.toFloat())
                if (cover > 0f) acc += cover * (if (horizontal) v[line * w + k] else v[k * w + line])
                k++
            }
            val value = acc / step
            if (horizontal) out[line * outW + o] = value else out[o * outW + line] = value
        }
        return out
    }

    /** [src] shrunk to fit [maxW] x [maxH] (area averaging); returned as is when it already fits. */
    fun fit(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val (ow, oh) = fitSize(src.width, src.height, maxW, maxH)
        if (ow == src.width && oh == src.height) return src
        val px = IntArray(src.width * src.height).also { src.getPixels(it, 0, src.width, 0, 0, src.width, src.height) }
        return Bitmap.createBitmap(areaAverage(px, src.width, src.height, ow, oh), ow, oh, Bitmap.Config.ARGB_8888)
    }

    private val cache = object : LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Decodes with a coarse power-of-two subsample (never below 2x the target), then shrinks precisely. */
    fun decodeFitted(key: String, maxW: Int, maxH: Int, decode: (BitmapFactory.Options) -> Bitmap?): ImageBitmap? {
        val ck = "$key|$maxW|$maxH"
        cache.get(ck)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        var sample = 1
        if (bounds.outWidth > 0) while (bounds.outWidth / (sample * 2) >= maxW * 2 && bounds.outHeight / (sample * 2) >= maxH * 2) sample *= 2
        val bmp = decode(BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        return fit(bmp, maxW, maxH).asImageBitmap().also { cache.put(ck, it) }
    }
}

/**
 * A photo fitted inside its box without cropping, prepared at the exact pixel size it is shown at (see [PhotoScale]) and
 * drawn pixel for pixel. [key] identifies the source; [decode] reads it with the given options.
 */
@Composable
fun SharpPhoto(key: String, modifier: Modifier = Modifier, decode: (BitmapFactory.Options) -> Bitmap?) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth.coerceAtLeast(1); val h = constraints.maxHeight.coerceAtLeast(1)
        val img by produceState<ImageBitmap?>(null, key, w, h) {
            value = withContext(Dispatchers.Default) { runCatching { PhotoScale.decodeFitted(key, w, h, decode) }.getOrNull() }
        }
        img?.let { i ->
            // Prepared to fill one side exactly: draw 1:1. A source smaller than the box is enlarged to fit, as before.
            val fits = (kotlin.math.abs(i.width - w) <= 1 && i.height <= h) || (kotlin.math.abs(i.height - h) <= 1 && i.width <= w)
            Image(
                i, null,
                if (fits) Modifier.size(with(density) { i.width.toDp() }, with(density) { i.height.toDp() }) else Modifier,
                contentScale = if (fits) ContentScale.FillBounds else ContentScale.Fit, filterQuality = FilterQuality.High,
            )
        }
    }
}
