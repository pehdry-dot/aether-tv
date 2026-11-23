package com.aether.tv.core.designsystem

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aether.tv.core.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads installed-app icons/banners from PackageManager (or a user override) into a small LRU cache.
 * Icons are rasterised once at a fixed size and reused; no network access is involved.
 */
object AppIconLoader {
    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Optional icon-pack resolver installed by the app layer. */
    @Volatile var iconPackResolver: ((Context, ComponentName) -> Drawable?)? = null

    fun invalidate() = cache.evictAll()

    suspend fun load(context: Context, app: AppInfo, px: Int, banner: Boolean): ImageBitmap? {
        val key = "${app.key}|${app.customIconUri}|$px|$banner|${iconPackResolver != null}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bmp = runCatching { loadBitmap(context, app, px, banner) }.getOrNull()
            bmp?.asImageBitmap()?.also { cache.put(key, it) }
        }
    }

    private fun loadBitmap(context: Context, app: AppInfo, px: Int, banner: Boolean): Bitmap? {
        app.customIconUri?.let { uri ->
            val f = File(uri.removePrefix("file:"))
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return it }
        }
        val pm = context.packageManager
        val cn = app.component
        val drawable: Drawable? = if (banner) {
            runCatching { pm.getActivityBanner(cn) }.getOrNull()
                ?: runCatching { pm.getApplicationBanner(app.packageName) }.getOrNull()
        } else {
            iconPackResolver?.invoke(context, cn)
                ?: runCatching { pm.getActivityIcon(cn) }.getOrNull()
                ?: runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
        }
        drawable ?: return null
        return drawable.toBitmap(if (banner) px * 16 / 9 else px, px)
    }

    fun Drawable.toBitmap(w: Int, h: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null && bitmap.width == w && bitmap.height == h) return bitmap
        val iw = intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = intrinsicHeight.takeIf { it > 0 } ?: h
        // Fit inside w x h preserving aspect ratio.
        val scale = minOf(w / iw.toFloat(), h / ih.toFloat())
        val bw = (iw * scale).toInt().coerceAtLeast(1); val bh = (ih * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        setBounds(0, 0, bw, bh)
        draw(c)
        return bmp
    }

    /**
     * The app's logo as a white mask (see [LogoMask]) at exactly the pixel size it is drawn at (fitted inside
     * [maxW] x [maxH]), or null when no clean logo can be found or the user picked a custom picture for this app.
     *
     * For sharpness, the source is the app's highest-resolution artwork: resources are read at 4x density (xxxhdpi,
     * the largest Android apps ship), vector artwork is drawn large, and the result is shrunk once by area averaging,
     * so nothing is scaled again on screen. Sources, best first: the TV banner (usually a wordmark), the adaptive
     * icon's monochrome layer (Android 13+), its foreground layer, then the whole icon.
     */
    suspend fun loadLogo(context: Context, app: AppInfo, maxW: Int, maxH: Int): ImageBitmap? {
        if (app.customIconUri != null) return null
        val key = "${app.key}|logo|$maxW|$maxH"
        cache.get(key)?.let { return it }
        if (app.key in noLogo) return null
        return withContext(Dispatchers.Default) {
            val full = fullLogos[app.key] ?: runCatching { logoSource(context, app) }.getOrNull()?.also { fullLogos.put(app.key, it) }
            if (full == null) { noLogo += app.key; return@withContext null }
            val fit = LogoMask.scaleToFit(full, maxW, maxH)
            Bitmap.createBitmap(fit.pixels, fit.width, fit.height, Bitmap.Config.ARGB_8888).asImageBitmap().also { cache.put(key, it) }
        }
    }

    private val noLogo = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** Full-resolution masks, kept so the same logo can be fitted to several sizes without re-reading the app. */
    private val fullLogos = object : LruCache<String, LogoMask.Result>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: LogoMask.Result) = value.pixels.size * 4
    }

    private fun logoSource(context: Context, app: AppInfo): LogoMask.Result? {
        val pm = context.packageManager
        val cn = app.component
        val info = runCatching { pm.getActivityInfo(cn, 0) }.getOrNull()
        val res = runCatching { pm.getResourcesForApplication(app.packageName) }.getOrNull()
        fun hiRes(id: Int): Drawable? =
            if (id == 0 || res == null) null else runCatching { res.getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null) }.getOrNull()
        val banner = hiRes(info?.bannerResource ?: 0)
            ?: runCatching { pm.getActivityBanner(cn) ?: pm.getApplicationBanner(app.packageName) }.getOrNull()
        val icon = hiRes(info?.iconResource ?: 0)
            ?: runCatching { pm.getActivityIcon(cn) }.getOrNull()
        val candidates = sequence {
            banner?.let { yield("banner" to it) }
            if (icon is android.graphics.drawable.AdaptiveIconDrawable) {
                if (android.os.Build.VERSION.SDK_INT >= 33) icon.monochrome?.let { yield("monochrome" to it) }
                icon.foreground?.let { yield("foreground" to it) }
            }
            icon?.let { yield("icon" to it) }
        }
        for ((name, d) in candidates) {
            val src = runCatching { d.toLogoSource() }.getOrNull() ?: continue
            val w = src.width; val h = src.height
            val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
            val r = LogoMask.extract(px, w, h) ?: continue
            android.util.Log.i("AetherLogo", "${app.packageName}: $name ${w}x$h -> logo ${r.width}x${r.height}")
            return r
        }
        android.util.Log.i("AetherLogo", "${app.packageName}: no clean logo, showing its icon")
        return null
    }

    /**
     * Pixels of a drawable at its best resolution: a bitmap as it is (no resampling), anything else (vectors, adaptive
     * layers) drawn with its longest side at [LOGO_VECTOR_PX].
     */
    private fun Drawable.toLogoSource(): Bitmap {
        if (this is BitmapDrawable) bitmap?.let { b ->
            return if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
        }
        val iw = intrinsicWidth.takeIf { it > 0 } ?: LOGO_VECTOR_PX
        val ih = intrinsicHeight.takeIf { it > 0 } ?: LOGO_VECTOR_PX
        val scale = LOGO_VECTOR_PX / maxOf(iw, ih).toFloat()
        val bw = (iw * scale).toInt().coerceAtLeast(1); val bh = (ih * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, bw, bh)
        draw(Canvas(bmp))
        return bmp
    }

    /** Vector and adaptive artwork is drawn this large (longest side) before the logo is cut out and shrunk. */
    private const val LOGO_VECTOR_PX = 1024

    fun packageInstalled(pm: PackageManager, pkg: String) = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}

@Composable
fun AppIcon(app: AppInfo, size: Dp, modifier: Modifier = Modifier, banner: Boolean = false) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = with(density) { size.roundToPx() }.coerceAtLeast(16)
    val image by produceState<ImageBitmap?>(null, app.key, app.customIconUri, px, banner) {
        value = AppIconLoader.load(context, app, px, banner)
    }
    Box(modifier.size(if (banner) size * 16f / 9f else size, size), contentAlignment = Alignment.Center) {
        image?.let { Image(it, null, Modifier.size(if (banner) size * 16f / 9f else size, size), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium) }
            ?: AText(app.label.take(1).uppercase(), Aether.type.panelTitle, color = Aether.colors.textSecondary)
    }
}

/**
 * The app's logo in a single colour (white on the dark theme), fitted inside [maxWidth] x [height] and centred, drawn
 * pixel for pixel at its prepared size. Falls back to the app's own icon when no clean logo can be cut out of it.
 */
@Composable
fun AppLogo(app: AppInfo, maxWidth: Dp, height: Dp, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Aether.colors.textPrimary) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The space actually given (a tile may be narrower than maxWidth), so the prepared logo is never scaled again.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.size(maxWidth, height), contentAlignment = Alignment.Center) {
        val maxWpx = constraints.maxWidth.coerceAtLeast(8)
        val maxHpx = constraints.maxHeight.coerceAtLeast(8)
        // (loaded, logo): nothing is drawn until the first load finishes, so the colour icon doesn't flash first.
        val state by produceState<Pair<Boolean, ImageBitmap?>>(false to null, app.key, app.customIconUri, maxWpx, maxHpx) {
            value = true to AppIconLoader.loadLogo(context, app, maxWpx, maxHpx)
        }
        val (loaded, logo) = state
        when {
            logo != null -> Image(
                logo, null,
                Modifier.size(with(density) { logo.width.toDp() }, with(density) { logo.height.toDp() }),
                contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.High,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
            )
            loaded -> AppIcon(app, height)
        }
    }
}

@Suppress("unused") private val placeholderSize = 1.dp
