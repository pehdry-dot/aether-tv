package com.aether.tv.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.aether.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Built-in wallpapers shipped with the app (original artwork, generated for Aether). */
enum class BuiltinWallpaper(val id: String, val label: String, val res: Int) {
    AlpineDusk("alpine_dusk", "Alpine Dusk", R.drawable.wp_alpine_dusk),
    BlueHour("blue_hour", "Blue Hour", R.drawable.wp_blue_hour),
    Night("night", "Moonlit Night", R.drawable.wp_night),
}

/**
 * The composited wallpaper (image + legibility gradients + dim) at screen size, plus a small blurred copy used
 * by glass surfaces. Glass samples the blurred copy at its own screen position, which gives a convincing
 * frosted-glass effect for the cost of one clipped bitmap draw — no per-frame blur, works on API 25+.
 */
@Immutable
class Backdrop(
    val full: ImageBitmap,
    val blurred: ImageBitmap?,
    val rootSize: IntSize,
    /** Resting glass tint already painted into [blurred], so glass needs no separate tint pass at rest. */
    val blurredTintAlpha: Float = 0f,
)

/** Shared wallpaper drift so glass surfaces stay aligned when the animated wallpaper is enabled. */
@Stable
class WallpaperMotion(val offsetX: State<Float>, val zoom: Float) {
    companion object { val None = WallpaperMotion(mutableStateOf(0f), 1f) }
}

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }
val LocalWallpaperMotion = staticCompositionLocalOf { WallpaperMotion.None }

object WallpaperRenderer {
    /** Downscale factor for the blurred copy. 1920px wide screens produce a 240px blur source. */
    private const val BLUR_DOWNSCALE = 8

    fun decode(context: Context, spec: String, targetW: Int, targetH: Int): Bitmap? = runCatching {
        when {
            spec.startsWith("solid:") -> {
                val color = spec.removePrefix("solid:").toLong(16).toInt()
                Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            }
            spec.startsWith("file:") -> {
                val f = File(spec.removePrefix("file:"))
                if (!f.exists()) null else decodeSampled(targetW, targetH) { o -> BitmapFactory.decodeFile(f.path, o) }
            }
            else -> {
                val id = spec.removePrefix("builtin:")
                val res = (BuiltinWallpaper.entries.firstOrNull { it.id == id } ?: BuiltinWallpaper.AlpineDusk).res
                decodeSampled(targetW, targetH) { o -> BitmapFactory.decodeResource(context.resources, res, o) }
            }
        }
    }.getOrNull()

    private inline fun decodeSampled(w: Int, h: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        var sample = 1
        if (bounds.outWidth > 0) {
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        }
        return decode(BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    /** Draws [src] center-cropped into a [w]x[h] bitmap and applies the Aether legibility treatment. */
    fun composite(src: Bitmap, w: Int, h: Int, dim: Float, dark: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
        }
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        paintGradients(c, w, h, dim, dark)
        // Center-cropped, so every pixel is covered: marking it opaque lets the GPU copy it without blending.
        out.setHasAlpha(false)
        return out
    }

    private fun paintGradients(c: Canvas, w: Int, h: Int, dim: Float, dark: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = if (dark) 0x0E1620 else 0xE6ECF2
        fun argb(a: Float, rgb: Int) = ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or rgb
        // Global dim
        if (dim > 0f) { p.shader = null; p.color = argb(dim, if (dark) 0x000000 else 0xFFFFFF); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p) }
        // Left wash so the clock and page title stay legible over bright skies.
        p.shader = LinearGradient(0f, 0f, w * 0.5f, 0f, intArrayOf(argb(0.42f, base), argb(0f, base)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w * 0.5f, h.toFloat(), p)
        // Top band behind the status bar.
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.16f, intArrayOf(argb(0.30f, base), argb(0f, base)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.16f, p)
        // Bottom fade into the base color — where content rows sit.
        p.shader = LinearGradient(
            0f, h * 0.24f, 0f, h.toFloat(),
            intArrayOf(argb(0f, base), argb(0.55f, base), argb(0.86f, base), argb(0.94f, base)),
            floatArrayOf(0f, 0.18f, 0.42f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, h * 0.24f, w.toFloat(), h.toFloat(), p)
    }

    /**
     * Everything Home draws behind its glass for wallpaper [spec] at [w]x[h]: the center-cropped, dimmed composite and
     * its blurred copy with the resting glass tint baked in. Null when the image can't be decoded. Slow: call off the
     * main thread. The wallpaper preview uses this too, so it shows exactly the crop and dimming Home will use.
     */
    fun backdrop(context: Context, spec: String, w: Int, h: Int, dim: Float, dark: Boolean, blurRadiusPx: Float, tint: androidx.compose.ui.graphics.Color, tintAlpha: Float): Backdrop? {
        val src = decode(context, spec, w, h) ?: return null
        val comp = composite(src, w, h, dim, dark)
        if (src != comp) src.recycle()
        val blurred = if (blurRadiusPx > 0f) blurredCopy(comp, blurRadiusPx) else null
        // Bake the resting glass tint into the (tiny) blurred copy: one fill per glass surface instead of two.
        val tinted = blurred?.let { b ->
            val m = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }
            Canvas(m).drawColor(tint.copy(alpha = tintAlpha).toArgb())
            m.setHasAlpha(false)
            // Immutable on purpose: glass draws this through a BitmapShader, and Android copies a *mutable*
            // bitmap (and re-uploads its texture) every time the shader is rebuilt.
            m.copy(Bitmap.Config.ARGB_8888, false).also { m.recycle() }
        }
        return Backdrop(comp.asImageBitmap(), tinted?.asImageBitmap(), IntSize(w, h), blurredTintAlpha = if (tinted != null) tintAlpha else 0f)
    }

    /** Why [spec] can't be used as a wallpaper, or null when it decodes. Cheap: reads the image header only. */
    fun problem(context: Context, spec: String): String? = when {
        spec.startsWith("solid:") -> if (spec.removePrefix("solid:").toLongOrNull(16) == null) "That colour isn't valid." else null
        spec.startsWith("file:") -> {
            val f = File(spec.removePrefix("file:"))
            when {
                !f.exists() -> "The image file is missing."
                !f.canRead() -> "Aether can't read that file."
                else -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching { BitmapFactory.decodeFile(f.path, o) }
                    if (o.outWidth <= 0 || o.outHeight <= 0) "That file isn't a picture Android can open (JPEG, PNG or WebP)." else null
                }
            }
        }
        else -> null
    }

    fun blurredCopy(composite: Bitmap, radiusPx: Float): Bitmap {
        val sw = max(1, composite.width / BLUR_DOWNSCALE)
        val sh = max(1, composite.height / BLUR_DOWNSCALE)
        val small = Bitmap.createScaledBitmap(composite, sw, sh, true)
        val r = (radiusPx / BLUR_DOWNSCALE).roundToInt().coerceIn(1, 24)
        return BoxBlur.blur(small, r)
    }
}

/** Separable 3-pass box blur (≈ Gaussian). Runs on tiny bitmaps only, off the main thread. */
object BoxBlur {
    fun blur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            pass(px, tmp, w, h, radius, horizontal = true)
            pass(tmp, px, w, h, radius, horizontal = false)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pass(input: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int): Int { val k = i.coerceIn(0, len - 1); return if (horizontal) line * w + k else k * w + line }
            for (i in -r..r) { val c = input[idx(i)]; sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (i in 0 until len) {
                out[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = input[idx(i + r + 1)]; val rem = input[idx(i - r)]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }
}

/**
 * Root background. Renders the wallpaper once per (spec, size, dim, theme, blur) off the main thread and exposes
 * the result to glass surfaces through [LocalBackdrop].
 */
@Composable
fun WallpaperHost(
    spec: String,
    dim: Float,
    blurEnabled: Boolean,
    animated: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = Aether.colors
    val blurRadius = Aether.glass.blurRadius
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.coerceAtLeast(1)
        val h = constraints.maxHeight.coerceAtLeast(1)
        val radiusPx = with(density) { blurRadius.toPx() }
        val tint = colors.glassTint
        val tintAlpha = colors.glassAlpha
        val backdrop by produceState<Backdrop?>(null, spec, w, h, dim, colors.isDark, radiusPx, blurEnabled, tint, tintAlpha) {
            value = withContext(Dispatchers.Default) {
                WallpaperRenderer.backdrop(context, spec, w, h, dim, colors.isDark, if (blurEnabled) radiusPx else 0f, tint, tintAlpha)
                    ?: WallpaperRenderer.backdrop(context, "builtin:alpine_dusk", w, h, dim, colors.isDark, if (blurEnabled) radiusPx else 0f, tint, tintAlpha)
            }
        }
        val motion = if (animated && Aether.motion.enabled) {
            val t = rememberInfiniteTransition(label = "wallpaper")
            val x = t.animateFloat(0f, 1f, infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
            remember(x) { WallpaperMotion(x, 1.06f) }
        } else WallpaperMotion.None
        val base = colors.base
        ComposeCanvas(Modifier.fillMaxSize()) {
            val b = backdrop
            if (b == null) drawRect(base) else drawBackdropImage(b.full, b.rootSize, motion)
        }
        CompositionLocalProvider(LocalBackdrop provides backdrop, LocalWallpaperMotion provides motion) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** Draws an image that represents the whole root at root coordinates, honouring the wallpaper drift. */
internal fun DrawScope.drawBackdropImage(image: ImageBitmap, root: IntSize, motion: WallpaperMotion, alpha: Float = 1f) {
    val zoom = motion.zoom
    val dx = if (zoom > 1f) -(root.width * (zoom - 1f)) * motion.offsetX.value else 0f
    translate(dx, 0f) {
        scale(zoom, zoom, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawImage(image, dstSize = root, alpha = alpha, filterQuality = FilterQuality.Low)
        }
    }
}
