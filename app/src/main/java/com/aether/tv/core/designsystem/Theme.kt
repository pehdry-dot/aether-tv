package com.aether.tv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aether.tv.R
import com.aether.tv.core.model.AnimationLevel
import com.aether.tv.core.model.AppearanceSettings
import com.aether.tv.core.model.BehaviorSettings
import com.aether.tv.core.model.FontChoice
import com.aether.tv.core.model.GlassQuality
import com.aether.tv.core.model.ThemeMode

@Immutable
data class AetherColors(
    val isDark: Boolean,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    /** Color mixed into glass surfaces. */
    val glassTint: Color,
    val glassAlpha: Float,
    val glassFocusedAlpha: Float,
    val border: Color,
    val borderFocused: Color,
    /** Base color the wallpaper fades into at the bottom of the screen. */
    val base: Color,
    val scrim: Color,
    val positive: Color,
    val neutral: Color,
    val warning: Color,
    val track: Color,
)

@Immutable
data class AetherShapes(
    val tile: RoundedCornerShape,
    val card: RoundedCornerShape,
    val panel: RoundedCornerShape,
    val pill: RoundedCornerShape,
    val tileRadius: Dp,
)

@Immutable
data class AetherMotion(val level: AnimationLevel) {
    val enabled get() = level != AnimationLevel.Off
    val focusMs get() = when (level) { AnimationLevel.Full -> 150; AnimationLevel.Reduced -> 90; AnimationLevel.Off -> 0 }
    val navMs get() = when (level) { AnimationLevel.Full -> 240; AnimationLevel.Reduced -> 120; AnimationLevel.Off -> 0 }
    val scaleEnabled get() = level == AnimationLevel.Full
}

@Immutable
data class AetherGlass(
    val quality: GlassQuality,
    val blurRadius: Dp,
    val focusGlow: Float,
    val focusScale: Float,
)

@Immutable
data class AetherType(
    val display: TextStyle,
    val clock: TextStyle,
    val date: TextStyle,
    val nav: TextStyle,
    val section: TextStyle,
    val panelTitle: TextStyle,
    val tileLabel: TextStyle,
    val cardTitle: TextStyle,
    val cardSubtitle: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val caption: TextStyle,
)

@Immutable
data class AetherDimens(
    val screenH: Dp,
    val screenTop: Dp,
    val gap: Dp,
    val iconScale: Float,
)

@Immutable
data class AetherThemeData(
    val colors: AetherColors,
    val shapes: AetherShapes,
    val motion: AetherMotion,
    val glass: AetherGlass,
    val type: AetherType,
    val dimens: AetherDimens,
    val showLabels: Boolean,
    /** App logos drawn as single-colour marks (and the Home apps row as a dock). */
    val monoLogos: Boolean = false,
)

val LocalAether = staticCompositionLocalOf { buildTheme(AppearanceSettings(), BehaviorSettings()) }

object Aether {
    val colors: AetherColors @Composable get() = LocalAether.current.colors
    val shapes: AetherShapes @Composable get() = LocalAether.current.shapes
    val motion: AetherMotion @Composable get() = LocalAether.current.motion
    val glass: AetherGlass @Composable get() = LocalAether.current.glass
    val type: AetherType @Composable get() = LocalAether.current.type
    val dimens: AetherDimens @Composable get() = LocalAether.current.dimens
}

val InterFamily = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

fun buildTheme(a: AppearanceSettings, b: BehaviorSettings): AetherThemeData {
    val dark = a.theme == ThemeMode.Dark
    val accent = Color(a.accent.argb)
    val colors = if (dark) AetherColors(
        isDark = true,
        textPrimary = Color(0xFFF4F7FA),
        textSecondary = Color(0xFFF4F7FA).copy(alpha = 0.72f),
        textTertiary = Color(0xFFF4F7FA).copy(alpha = 0.50f),
        accent = accent,
        glassTint = Color(0xFFDCE8F5),
        glassAlpha = a.glassOpacity.coerceIn(0f, 0.4f),
        glassFocusedAlpha = (a.glassOpacity + 0.10f).coerceIn(0f, 0.5f),
        border = Color.White.copy(alpha = a.borderIntensity.coerceIn(0f, 0.4f)),
        borderFocused = lerpWhite(accent, 0.55f).copy(alpha = 0.92f),
        base = Color(0xFF0E1620),
        scrim = Color(0xE60A1017),
        positive = Color(0xFF3DDC84),
        neutral = Color(0xFF9AA5B1),
        warning = Color(0xFFFFC266),
        track = Color.White.copy(alpha = 0.22f),
    ) else AetherColors(
        isDark = false,
        textPrimary = Color(0xFF111821),
        textSecondary = Color(0xFF111821).copy(alpha = 0.72f),
        textTertiary = Color(0xFF111821).copy(alpha = 0.52f),
        accent = darken(accent),
        glassTint = Color.White,
        glassAlpha = (a.glassOpacity * 2.5f + 0.25f).coerceIn(0.2f, 0.85f),
        glassFocusedAlpha = (a.glassOpacity * 2.5f + 0.45f).coerceIn(0.4f, 0.95f),
        border = Color.White.copy(alpha = (a.borderIntensity * 3f).coerceIn(0.2f, 0.9f)),
        borderFocused = Color(0xFF1B2733).copy(alpha = 0.85f),
        base = Color(0xFFE6ECF2),
        scrim = Color(0xE6E9EEF3),
        positive = Color(0xFF1E9E5A),
        neutral = Color(0xFF5E6873),
        warning = Color(0xFFB7791F),
        track = Color(0xFF111821).copy(alpha = 0.18f),
    )
    val r = a.cornerRadiusDp.coerceIn(0, 32).dp
    val shapes = AetherShapes(
        tile = RoundedCornerShape(r),
        card = RoundedCornerShape(r + 2.dp),
        panel = RoundedCornerShape(r + 4.dp),
        pill = RoundedCornerShape(50),
        tileRadius = r,
    )
    val family = if (a.font == FontChoice.Inter) InterFamily else FontFamily.SansSerif
    val s = a.textScale.coerceIn(0.85f, 1.3f)
    fun st(size: Float, w: FontWeight, ls: Float = 0f, lh: Float = size * 1.25f) = TextStyle(
        fontFamily = family, fontWeight = w, fontSize = (size * s).sp, letterSpacing = ls.sp, lineHeight = (lh * s).sp,
    )
    val type = AetherType(
        display = st(38f, FontWeight.Normal, ls = -0.6f, lh = 44f),
        clock = st(21f, FontWeight.Normal, ls = -0.2f, lh = 26f),
        date = st(12.5f, FontWeight.Normal, lh = 16f),
        nav = st(14.5f, FontWeight.Normal, lh = 18f),
        section = st(16.5f, FontWeight.Normal, ls = 0.1f, lh = 21f),
        panelTitle = st(19f, FontWeight.Normal, ls = -0.2f, lh = 24f),
        tileLabel = st(11.5f, FontWeight.Medium, lh = 14f),
        cardTitle = st(14.5f, FontWeight.Medium, lh = 18f),
        cardSubtitle = st(10.5f, FontWeight.Normal, lh = 14f),
        body = st(12.5f, FontWeight.Normal, lh = 17f),
        bodyStrong = st(12.5f, FontWeight.Medium, lh = 17f),
        caption = st(10.5f, FontWeight.Normal, lh = 14f),
    )
    return AetherThemeData(
        colors = colors,
        shapes = shapes,
        motion = AetherMotion(a.animation),
        glass = AetherGlass(a.glassQuality, a.blurRadiusDp.coerceIn(0, 48).dp, a.focusGlow.coerceIn(0f, 1f), a.focusScale.coerceIn(1f, 1.12f)),
        type = type,
        dimens = AetherDimens(screenH = (48 + b.overscanDp).dp, screenTop = (24 + b.overscanDp / 2).dp, gap = 12.dp, iconScale = a.iconSize.scale),
        showLabels = a.showAppLabels,
        monoLogos = a.appLogos == com.aether.tv.core.model.AppLogoStyle.White,
    )
}

private fun lerpWhite(c: Color, t: Float) = Color(
    red = c.red + (1f - c.red) * t, green = c.green + (1f - c.green) * t, blue = c.blue + (1f - c.blue) * t, alpha = c.alpha,
)

private fun darken(c: Color) = Color(red = c.red * 0.45f, green = c.green * 0.5f, blue = c.blue * 0.6f, alpha = 1f)

@Composable
fun AetherTheme(appearance: AppearanceSettings, behavior: BehaviorSettings, content: @Composable () -> Unit) {
    val theme = remember(appearance, behavior) { buildTheme(appearance, behavior) }
    val density = LocalDensity.current
    val scaled = remember(density, behavior.uiScale) {
        val f = behavior.uiScale.coerceIn(0.8f, 1.2f)
        Density(density.density * f, density.fontScale)
    }
    CompositionLocalProvider(LocalAether provides theme, LocalDensity provides scaled, content = content)
}

/** Font size helper so text never drops below TV-legible sizes. */
fun TextUnit.atLeast(min: TextUnit): TextUnit = if (this.value < min.value) min else this
