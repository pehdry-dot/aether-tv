package com.aether.tv.core.designsystem

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Keys that activate the focused item on TV remotes, keyboards and game controllers. */
private val SelectKeys = setOf(
    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER,
    NativeKeyEvent.KEYCODE_NUMPAD_ENTER, NativeKeyEvent.KEYCODE_BUTTON_A,
)

/**
 * Remembers the most recently focused tile so focus can be put back exactly where it was after an overlay
 * (options menu, dialog) closes.
 */
object FocusMemory {
    @Volatile var last: FocusRequester? = null
}

/** Global switches for secondary actions (set from Behavior settings). */
object InputBehavior {
    @Volatile var menuKeyOpensOptions: Boolean = true
    @Volatile var longPressEnabled: Boolean = true
}

/**
 * Click handling that works identically with a D-pad remote, a game controller, a keyboard and touch.
 * - OK / Enter / A: click on key-up (so a long hold doesn't also click).
 * - Holding OK past the system long-press timeout: [onLongClick].
 * - Menu key (☰ on Fire TV remotes): [onLongClick] — the options menu.
 */
fun Modifier.tvClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val state = remember { PressState() }
    val interaction = remember { MutableInteractionSource() }
    this
        .onPreviewKeyEvent { e ->
            if (!enabled) return@onPreviewKeyEvent false
            val native = e.nativeKeyEvent
            val code = native.keyCode
            when {
                code in SelectKeys -> {
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (native.repeatCount == 0) { state.pressed = true; state.longFired = false }
                            else if (state.pressed && !state.longFired && onLongClick != null && InputBehavior.longPressEnabled) {
                                state.longFired = true; onLongClick()
                            }
                        }
                        KeyEventType.KeyUp -> {
                            if (state.pressed && !state.longFired) onClick()
                            state.pressed = false
                        }
                    }
                    true
                }
                code == NativeKeyEvent.KEYCODE_MENU && onLongClick != null && InputBehavior.menuKeyOpensOptions -> {
                    if (e.type == KeyEventType.KeyDown && native.repeatCount == 0) onLongClick()
                    true
                }
                else -> false
            }
        }
        // Explicitly focusable in every input mode (clickable is only focusable in keyboard mode).
        .focusable(enabled = enabled, interactionSource = interaction)
        .semantics {
            this.role = role
            onClick { if (enabled) onClick(); enabled }
            if (onLongClick != null) onLongClick { if (enabled) onLongClick(); enabled }
            if (!enabled) disabled()
        }
        .pointerInput(enabled, onLongClick) {
            if (enabled) detectTapGestures(onLongPress = onLongClick?.let { lc -> { _ -> lc() } }, onTap = { onClick() })
        }
}

private class PressState { var pressed = false; var longFired = false }

/**
 * Paints a glass surface: blurred backdrop sampled at this element's screen position, a tint, a soft top sheen
 * and a hairline border. [focus] (0..1) brightens the surface and border. Reads of [focus] and the element
 * position happen in the draw phase only, so focus animations and scrolling never trigger recomposition.
 */
fun Modifier.glass(
    shape: Shape,
    focus: () -> Float = { 0f },
    tintAlphaOverride: Float? = null,
    selected: Boolean = false,
    borderWidthResting: Float = 1f,
    restingVisible: Boolean = true,
    sheenEnabled: Boolean = true,
): Modifier = composed {
    val backdrop = LocalBackdrop.current
    val motion = LocalWallpaperMotion.current
    val colors = Aether.colors
    val glassStyle = Aether.glass
    val pos = remember { mutableStateOf(Offset.Zero) }
    // Glass inside a glass panel already sits on the panel's frosted backdrop: sampling the backdrop again draws the
    // same pixels twice. The TV's GPU redraws the whole screen every frame, and every translucent layer over a large
    // area costs milliseconds (Settings measured at ~25 ms GPU per frame), so nested glass is tint + border only.
    val nested = LocalGlassDepth.current > 0
    val useBackdrop = !nested && glassStyle.quality == com.aether.tv.core.model.GlassQuality.Auto && backdrop?.blurred != null
    this
        .then(if (useBackdrop) Modifier.onGloballyPositioned { pos.value = it.positionInRoot() } else Modifier)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val restingBorder = borderWidthResting.dp.toPx()
            val focusedBorder = 1.6.dp.toPx()
            val sheenTopAlpha = if (colors.isDark) 0.07f else 0.25f
            val sheen = Brush.verticalGradient(
                0f to Color.White.copy(alpha = sheenTopAlpha),
                0.55f to Color.Transparent,
            )
            // The blurred backdrop is sampled through a shader positioned at this element's screen position, so only the
            // element's own pixels are touched. (Clipping a full-screen backdrop image to each tile cost ~25 ms frames on
            // the M450's Mali GPU, measured 2026-11-21.)
            val backdropBitmap = if (useBackdrop) backdrop!!.blurred!! else null
            val shader = backdropBitmap?.let {
                android.graphics.BitmapShader(it.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP).also { s ->
                    if (Build.VERSION.SDK_INT >= 33) s.filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR
                }
            }
            val shaderBrush = shader?.let { ShaderBrush(it) }
            val matrix = android.graphics.Matrix()
            // Last values the shader matrix was built from. setLocalMatrix rebuilds the native shader, so it's only
            // called when this element actually moved (scrolling, wallpaper drift), not on every focus frame.
            var lastX = Float.NaN; var lastY = Float.NaN; var lastDx = Float.NaN
            val radius = uniformCornerRadius(outline)
            val onePass = radius != null && GlassOnePass.available
            onDrawBehind {
                val f = focus().coerceIn(0f, 1f)
                val vis = if (restingVisible || selected) 1f else f
                if (vis <= 0.001f) return@onDrawBehind
                if (shader != null) {
                    val root = backdrop!!.rootSize
                    val zoom = motion.zoom
                    val dx = if (zoom > 1f) -(root.width * (zoom - 1f)) * motion.offsetX.value else 0f
                    // bitmap pixel -> root coordinates (as drawBackdropImage draws it) -> this element's coordinates
                    val px = pos.value.x; val py = pos.value.y
                    if (px != lastX || py != lastY || dx != lastDx) {
                        matrix.setScale(root.width * zoom / backdropBitmap.width, root.height * zoom / backdropBitmap.height)
                        matrix.postTranslate(dx - px, -py)
                        shader.setLocalMatrix(matrix)
                        lastX = px; lastY = py; lastDx = dx
                    }
                }
                val base = tintAlphaOverride ?: colors.glassAlpha
                val sel = if (selected) 0.06f else 0f
                val extra = if (nested) {
                    // On top of a panel that already carries the base tint: a faint separation at rest, the usual
                    // brightening when focused or selected.
                    (NESTED_REST_TINT + sel + (colors.glassFocusedAlpha - base) * f).coerceIn(0f, 1f)
                } else {
                    val target = (base + sel + (colors.glassFocusedAlpha - base) * f).coerceIn(0f, 1f)
                    // Part of the tint may already be baked into the backdrop; draw only what's missing so the result
                    // composites to exactly `target` (1 - (1 - target) / (1 - baked)). At rest that's usually nothing.
                    val baked = if (shader != null) backdrop!!.blurredTintAlpha.coerceIn(0f, 0.95f) else 0f
                    if (baked > 0f) (1f - (1f - target) / (1f - baked)).coerceIn(0f, 1f) else target
                }
                val tintAlpha = if (extra > 0.002f) (extra * vis).coerceIn(0f, 1f) else 0f
                // Sheen: full on top-level tiles; on nested glass only while focused (the panel's surface shows through).
                val sheenAlpha = if (!sheenEnabled) 0f else if (nested) f else vis
                val borderColor = lerpColor(colors.border, colors.borderFocused, f).let { it.copy(alpha = it.alpha * vis) }
                val bw = restingBorder + (focusedBorder - restingBorder) * f
                if (onePass) {
                    GlassOnePass.draw(
                        this, radius!!, shader, vis, colors.glassTint, tintAlpha,
                        if (sheenAlpha > 0.01f) sheenTopAlpha * sheenAlpha else 0f, borderColor, bw,
                    )
                } else {
                    if (shaderBrush != null) drawOutline(outline, shaderBrush, alpha = vis)
                    if (tintAlpha > 0f) drawOutline(outline, colors.glassTint.copy(alpha = tintAlpha))
                    if (sheenAlpha > 0.01f) drawOutline(outline, sheen, alpha = sheenAlpha)
                    drawOutlineStroke(outline, borderColor, bw)
                }
            }
        }
}

/** Corner radius of a rectangle (0) or a rounded rectangle with four equal circular corners; null for anything else. */
private fun uniformCornerRadius(outline: Outline): Float? = when (outline) {
    is Outline.Rectangle -> 0f
    is Outline.Rounded -> outline.roundRect.let { rr ->
        val r = rr.topLeftCornerRadius.x
        val uniform = listOf(rr.topLeftCornerRadius, rr.topRightCornerRadius, rr.bottomLeftCornerRadius, rr.bottomRightCornerRadius)
            .all { it.x == r && it.y == r }
        if (uniform && r * 2f <= minOf(rr.width, rr.height) + 0.5f) r else null
    }
    is Outline.Generic -> null
}

/**
 * Glass in one GPU pass (Android 13+, AGSL). Backdrop, tint, top sheen, border and the anti-aliased rounded edge are
 * computed by one shader over the element's rectangle, compositing to the same result as the four separate draws it
 * replaces (backdrop fill, tint fill, sheen gradient, border stroke; Porter-Duff source-over is associative). On the
 * M450 those four draws per tile were the largest cost on Settings: removing any one of them saved 2–6 ms per frame
 * (measured 2026-11-21). Older Android versions, and any failure to build the shader, use the four draws.
 */
internal object GlassOnePass {
    private val impl: Any? by lazy {
        if (Build.VERSION.SDK_INT < 33) null
        else runCatching { GlassOnePassImpl() }
            .onFailure { android.util.Log.w("AetherGlass", "One-pass glass shader unavailable", it) }
            .getOrNull()
    }
    val available: Boolean get() = impl != null

    fun draw(
        scope: DrawScope, radius: Float, backdrop: android.graphics.BitmapShader?, backdropAlpha: Float,
        tint: Color, tintAlpha: Float, sheenAlpha: Float, border: Color, borderWidth: Float,
    ) {
        if (Build.VERSION.SDK_INT >= 33) (impl as GlassOnePassImpl).draw(scope, radius, backdrop, backdropAlpha, tint, tintAlpha, sheenAlpha, border, borderWidth)
    }
}

@RequiresApi(33)
private class GlassOnePassImpl {
    // One shader per variant, shared by every glass element: uniforms are set right before each draw, and the
    // recorded draw keeps its own snapshot of them. Compose records draws on the main thread only.
    private val withBackdrop = android.graphics.RuntimeShader(glassSksl(backdrop = true))
    private val plain = android.graphics.RuntimeShader(glassSksl(backdrop = false))
    private val paint = android.graphics.Paint()

    fun draw(
        scope: DrawScope, radius: Float, backdrop: android.graphics.BitmapShader?, backdropAlpha: Float,
        tint: Color, tintAlpha: Float, sheenAlpha: Float, border: Color, borderWidth: Float,
    ) {
        val s = if (backdrop != null) withBackdrop else plain
        val w = scope.size.width; val h = scope.size.height
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("radius", radius)
        if (backdrop != null) {
            s.setInputShader("backdrop", backdrop)
            s.setFloatUniform("bdAlpha", backdropAlpha)
        }
        s.setFloatUniform("tint", tint.red * tintAlpha, tint.green * tintAlpha, tint.blue * tintAlpha, tintAlpha)
        s.setFloatUniform("sheen", sheenAlpha)
        s.setFloatUniform("sheenEnd", (h * 0.55f).coerceAtLeast(1f))
        val ba = border.alpha
        s.setFloatUniform("border", border.red * ba, border.green * ba, border.blue * ba, ba)
        s.setFloatUniform("borderWidth", borderWidth)
        paint.shader = s
        // One pixel of margin so the anti-aliased edge is rasterized wherever the element sits on the pixel grid.
        scope.drawContext.canvas.nativeCanvas.drawRect(-1f, -1f, w + 1f, h + 1f, paint)
    }
}

/**
 * Colours are premultiplied. The sheen is white fading to transparent over the top 55%, interpolated in premultiplied
 * space as Android's gradients are. The border is the ring between the shape's edge and `borderWidth` inside it, the
 * same band the stroke in [drawOutlineStroke] covers.
 */
private fun glassSksl(backdrop: Boolean) = """
    ${if (backdrop) "uniform shader backdrop;\nuniform float bdAlpha;" else ""}
    uniform float2 size;
    uniform float radius;
    uniform float4 tint;
    uniform float sheen;
    uniform float sheenEnd;
    uniform float4 border;
    uniform float borderWidth;

    half4 main(float2 p) {
        float2 c = size * 0.5;
        float2 q = abs(p - c) - (c - radius);
        float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
        float cov = clamp(0.5 - d, 0.0, 1.0);
        if (cov <= 0.0) { return half4(0.0); }
        float4 col = ${if (backdrop) "float4(backdrop.eval(p)) * bdAlpha" else "float4(0.0)"};
        col = tint + col * (1.0 - tint.a);
        float sa = sheen * (1.0 - clamp(p.y / sheenEnd, 0.0, 1.0));
        col = float4(sa) + col * (1.0 - sa);
        col *= cov;
        float4 b = border * (cov - clamp(0.5 - (d + borderWidth), 0.0, 1.0));
        return half4(b + col * (1.0 - b.a));
    }
""".trimIndent()

/** Soft, restrained halo outside a focused element: concentric hairlines fading out. Cheap on every GPU. */
fun Modifier.focusGlow(shape: Shape, focus: () -> Float, strength: Float, color: Color): Modifier =
    if (strength <= 0f) this else this.drawWithCache {
        val rings = 5
        val spread = 7.dp.toPx() * (0.6f + strength * 0.6f)
        val outlines = (1..rings).map { i ->
            val grow = spread * i / rings
            val o = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
            grow to o
        }
        onDrawBehind {
            val f = focus()
            if (f <= 0.01f) return@onDrawBehind
            outlines.forEachIndexed { idx, (grow, o) ->
                val a = strength * f * 0.16f * (1f - idx / rings.toFloat())
                translate(-grow, -grow) { drawOutlineStroke(o, color.copy(alpha = a), spread / rings + 0.5f) }
            }
        }
    }

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOutlineStroke(outline: Outline, color: Color, width: Float) {
    val inset = width / 2f
    when (outline) {
        is Outline.Rounded -> {
            val rr = outline.roundRect
            val r = rr.topLeftCornerRadius.x
            drawRoundRect(
                color = color,
                topLeft = Offset(rr.left + inset, rr.top + inset),
                size = Size(rr.width - width, rr.height - width),
                cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
                style = Stroke(width),
            )
        }
        is Outline.Rectangle -> drawRect(color, Offset(inset, inset), Size(size.width - width, size.height - width), style = Stroke(width))
        is Outline.Generic -> drawPath(outline.path, color, style = Stroke(width))
    }
}

fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/** How many glass panels enclose the current element (0 = drawn straight on the wallpaper). */
val LocalGlassDepth = androidx.compose.runtime.staticCompositionLocalOf { 0 }

/** Resting tint of glass drawn inside a panel (the panel already carries the base tint). */
private const val NESTED_REST_TINT = 0.035f

/**
 * Non-interactive glass container (panels, headers). Large, so it skips the sheen gradient (a full extra
 * translucent layer over most of the screen on Settings). Glass inside it is drawn as nested glass.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = Aether.shapes.panel,
    tintAlpha: Float? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val depth = LocalGlassDepth.current
    Box(modifier.glass(shape, tintAlphaOverride = tintAlpha, sheenEnabled = false)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalGlassDepth provides depth + 1) { content() }
    }
}

/**
 * The primary interactive element: a glass tile that lifts subtly when focused.
 * [content] receives the focus state so labels/icons can brighten.
 */
@Composable
fun GlassTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Aether.shapes.tile,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    selected: Boolean = false,
    scaleOnFocus: Boolean = true,
    surface: Boolean = true,
    restingSurface: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val selfRequester = remember { FocusRequester() }
    val motion = Aether.motion
    val glassStyle = Aether.glass
    val progress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(motion.focusMs),
        label = "focus",
    )
    val targetScale = if (scaleOnFocus && motion.scaleEnabled) glassStyle.focusScale else 1f
    val glowColor = Aether.colors.borderFocused
    Box(
        modifier
            .graphicsLayer {
                val s = 1f + (targetScale - 1f) * progress
                scaleX = s; scaleY = s
            }
            .focusGlow(shape, { progress }, glassStyle.focusGlow, glowColor)
            .then(if (surface) Modifier.glass(shape, { progress }, selected = selected, restingVisible = restingSurface) else Modifier)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusRequester(selfRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) FocusMemory.last = selfRequester
                onFocus?.invoke(it.isFocused)
            }
            .tvClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        content(focused)
    }
}

/** Rounded-rect helper for custom drawing. */
fun roundRectOf(size: Size, r: Float) = RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))
