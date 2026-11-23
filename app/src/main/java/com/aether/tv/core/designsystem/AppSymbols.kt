package com.aether.tv.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.PathParser
import com.aether.tv.core.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * CleanFrame's optical sizing (app/main.js `markHtml`), as pure functions. A mark's visible ink is measured on a square
 * grid: its bounding box and how much of that box it fills. The mark is then scaled so its ink fits the slot, and
 * weighted by fill (a dense YouTube block draws smaller than a thin Prime smile), so all marks look the same size.
 */
object SymbolLayout {
    data class Metrics(val n: Int, val x: Float, val y: Float, val w: Float, val h: Float, val fill: Float)
    data class Placement(val scale: Float, val left: Float, val top: Float)

    fun place(m: Metrics, boxW: Float, boxH: Float, size: Float): Placement {
        val weight = (0.45f / m.fill.coerceAtLeast(0.01f)).pow(0.3f).coerceIn(0.84f, 1.25f)
        val k = minOf(boxW / m.w, boxH / m.h, size * weight / sqrt(m.w * m.h))
        return Placement(k, boxW / 2 - (m.x + m.w / 2) * k, boxH / 2 - (m.y + m.h / 2) * k)
    }

    /** Ink bounding box and fill of an [n]x[n] alpha raster (alpha > 50 %). */
    fun measure(alpha: ByteArray, n: Int): Metrics? {
        var x0 = n; var y0 = n; var x1 = -1; var y1 = -1; var ink = 0
        for (y in 0 until n) for (x in 0 until n) {
            if ((alpha[y * n + x].toInt() and 0xFF) > 127) { ink++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        }
        if (x1 < 0) return null
        val w = (x1 - x0 + 1).toFloat(); val h = (y1 - y0 + 1).toFloat()
        return Metrics(n, x0.toFloat(), y0.toFloat(), w, h, ink / (w * h))
    }

    /** CleanFrame's fallback: the first letters of the first two words ("Prime Video" → "PV", "Nuvio" → "N"). */
    fun monogram(label: String): String {
        val words = label.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "?"
        val a = words[0].firstOrNull { it.isLetterOrDigit() } ?: words[0].first()
        val b = words.getOrNull(1)?.firstOrNull { it.isLetterOrDigit() }
        return (a.toString() + (b?.toString() ?: "")).uppercase()
    }
}

/** Bundled brand marks (see licenses/BRAND-SYMBOLS.md), rasterised once per slot size, pixel for pixel. */
object BrandSymbols {
    private const val GRID = 240
    private val metrics = ConcurrentHashMap<String, SymbolLayout.Metrics>()

    fun idFor(pkg: String): String? = BrandSymbolData.byPackage[pkg]
    fun title(id: String): String? = BrandSymbolData.marks[id]?.title

    private fun gridMatrix(m: BrandSymbolData.Mark): Matrix {
        val (vx, vy, vw, vh) = m.viewBox.let { listOf(it[0], it[1], it[2], it[3]) }
        val s = minOf(GRID / vw, GRID / vh)
        return Matrix().apply { setTranslate(-vx, -vy); postScale(s, s); postTranslate((GRID - vw * s) / 2f, (GRID - vh * s) / 2f) }
    }

    fun metrics(id: String): SymbolLayout.Metrics? = metrics[id] ?: run {
        val m = BrandSymbolData.marks[id] ?: return null
        val path = PathParser.createPathFromPathData(m.path).apply { transform(gridMatrix(m)) }
        val bmp = Bitmap.createBitmap(GRID, GRID, Bitmap.Config.ALPHA_8)
        Canvas(bmp).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG))
        val alpha = ByteArray(GRID * GRID).also { bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(it)) }
        bmp.recycle()
        SymbolLayout.measure(alpha, GRID)?.also { metrics[id] = it }
    }

    /** The mark, white on transparent, balanced inside a [w]x[h] px slot with nominal size [size] px. */
    fun render(id: String, w: Int, h: Int, size: Float): Bitmap? {
        val m = BrandSymbolData.marks[id] ?: return null
        val met = metrics(id) ?: return null
        val p = SymbolLayout.place(met, w.toFloat(), h.toFloat(), size)
        val matrix = gridMatrix(m).apply { postScale(p.scale, p.scale); postTranslate(p.left, p.top) }
        val path = PathParser.createPathFromPathData(m.path).apply { transform(matrix) }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }) }
    }
}

/** What a slot shows in the symbols style, for one app. */
sealed interface AppSymbolSource {
    data class Brand(val id: String) : AppSymbolSource
    /** Cut out of the app's own icon on this TV. */
    data object FromIcon : AppSymbolSource
    data class Monogram(val text: String) : AppSymbolSource
    /** The user's own picture for this app (icon override), shown as it is. */
    data object Custom : AppSymbolSource
}

object AppSymbolLoader {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /**
     * The bitmap for [app] in a [w]x[h] px slot, and where it came from: a bundled brand mark, a symbol cut from the
     * app's icon (never from a wide banner wordmark), or null for a monogram (no usable icon, or Android's generic one).
     */
    suspend fun load(context: Context, app: AppInfo, w: Int, h: Int, size: Float): Pair<AppSymbolSource, ImageBitmap?> {
        if (app.customIconUri != null) return AppSymbolSource.Custom to null
        val key = "${app.key}|$w|$h|${size.toInt()}"
        val brand = BrandSymbols.idFor(app.packageName)
        cache.get(key)?.let { return (if (brand != null) AppSymbolSource.Brand(brand) else AppSymbolSource.FromIcon) to it }
        return withContext(Dispatchers.Default) {
            if (brand != null) BrandSymbols.render(brand, w, h, size)?.asImageBitmap()?.let { return@withContext AppSymbolSource.Brand(brand) to it.also { b -> cache.put(key, b) } }
            val mask = runCatching { AppIconLoader.symbolMask(context, app) }.getOrNull()
            if (mask == null) return@withContext AppSymbolSource.Monogram(SymbolLayout.monogram(app.label)) to null
            // Same balancing as the bundled marks, on the cut-out's own pixels.
            val n = maxOf(mask.width, mask.height)
            val alpha = ByteArray(n * n)
            val ox = (n - mask.width) / 2; val oy = (n - mask.height) / 2
            for (y in 0 until mask.height) for (x in 0 until mask.width) alpha[(y + oy) * n + x + ox] = (mask.pixels[y * mask.width + x] ushr 24).toByte()
            val met = SymbolLayout.measure(alpha, n) ?: return@withContext AppSymbolSource.Monogram(SymbolLayout.monogram(app.label)) to null
            val p = SymbolLayout.place(met, w.toFloat(), h.toFloat(), size)
            val src = Bitmap.createBitmap(mask.pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, Matrix().apply { setTranslate(ox.toFloat(), oy.toFloat()); postScale(p.scale, p.scale); postTranslate(p.left, p.top) }, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            AppSymbolSource.FromIcon to out.asImageBitmap().also { cache.put(key, it) }
        }
    }

    fun invalidate() = cache.evictAll()
}

/**
 * The app as a compact monochrome symbol (Appearance ▸ App logos ▸ CleanFrame symbols), centred in a [width]x[height]
 * slot. A custom icon the user chose is shown as it is; an app without a usable icon gets its initials.
 */
@Composable
fun AppSymbol(app: AppInfo, width: Dp, height: Dp, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Aether.colors.textPrimary) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val wPx = with(density) { width.roundToPx() }.coerceAtLeast(8)
    val hPx = with(density) { height.roundToPx() }.coerceAtLeast(8)
    val size = hPx * 0.86f
    val state by produceState<Pair<AppSymbolSource, ImageBitmap?>?>(null, app.key, app.customIconUri, wPx, hPx) {
        value = AppSymbolLoader.load(context, app, wPx, hPx, size)
    }
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        val s = state ?: return@Box
        when (val src = s.first) {
            AppSymbolSource.Custom -> AppIcon(app, height)
            is AppSymbolSource.Monogram -> AText(
                src.text, Aether.type.panelTitle.copy(fontSize = with(density) { (size * if (src.text.length > 1) 0.5f else 0.62f).toSp() }, fontWeight = FontWeight.Medium),
                color = color,
            )
            else -> s.second?.let { Image(it, null, Modifier.size(width, height), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None, colorFilter = ColorFilter.tint(color)) }
        }
    }
}
