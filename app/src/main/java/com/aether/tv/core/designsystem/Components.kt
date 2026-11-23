package com.aether.tv.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aether.tv.core.model.DeviceKind
import com.aether.tv.core.model.DeviceStatus

@Composable
fun AText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Aether.colors.textPrimary,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (align != null) style.copy(color = color, textAlign = align) else style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun AIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = Aether.colors.textPrimary, contentDescription: String? = null) {
    Image(icon, contentDescription, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        AText(text, Aether.type.section, color = Aether.colors.textPrimary.copy(alpha = 0.9f))
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            AText(trailing, Aether.type.caption, color = Aether.colors.textTertiary)
        }
    }
}

/** Thin progress track as used on Continue Watching cards. */
@Composable
fun ProgressTrack(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val track = Aether.colors.track
    val fill = if (Aether.colors.isDark) Color.White.copy(alpha = 0.95f) else Aether.colors.accent
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(fill, size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = r)
    }
}

@Composable
fun StatusDot(status: DeviceStatus, modifier: Modifier = Modifier) {
    val c = when (status) {
        DeviceStatus.Connected -> Aether.colors.positive
        DeviceStatus.Standby, DeviceStatus.Paired, DeviceStatus.NoSignal -> Aether.colors.neutral
        DeviceStatus.Unavailable -> Aether.colors.warning
        DeviceStatus.Shortcut -> Color.Transparent
    }
    Box(modifier.size(6.dp).clip(CircleShape).background(c))
}

@Composable
fun StatusLine(status: DeviceStatus, modifier: Modifier = Modifier, detail: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (status == DeviceStatus.Shortcut) {
            AIcon(AetherIcons.Link, size = 11.dp, tint = Aether.colors.textSecondary)
        } else StatusDot(status)
        Spacer(Modifier.width(6.dp))
        AText(listOfNotNull(detail, status.label).joinToString(" • "), Aether.type.caption, color = Aether.colors.textSecondary)
    }
}

/** On/off pill used by toggle settings rows. */
@Composable
fun TogglePill(checked: Boolean, modifier: Modifier = Modifier) {
    val accent = Aether.colors.accent
    val off = Aether.colors.track
    val knob = if (Aether.colors.isDark) Color.White else Color.White
    Canvas(modifier.size(34.dp, 19.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(if (checked) accent.copy(alpha = 0.85f) else off, cornerRadius = r)
        val d = size.height - 5.dp.toPx()
        val x = if (checked) size.width - d - 2.5.dp.toPx() else 2.5.dp.toPx()
        drawCircle(if (checked) Color(0xFF0E1620) else knob.copy(alpha = 0.9f), d / 2, Offset(x + d / 2, size.height / 2))
    }
}

/** Value bar for slider rows. */
@Composable
fun ValueBar(fraction: Float, modifier: Modifier = Modifier) {
    val accent = Aether.colors.accent
    val track = Aether.colors.track
    Canvas(modifier.width(110.dp).height(4.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        drawRoundRect(accent, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = r)
        drawCircle(Color.White, 5.dp.toPx(), Offset(size.width * fraction.coerceIn(0f, 1f), size.height / 2))
    }
}

/** Small rounded label, e.g. "Streaming", "Requires permission". */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, color: Color = Aether.colors.textSecondary) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Aether.colors.glassTint.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { AText(text, Aether.type.caption, color = color) }
}

/** Centered empty-state message with an optional hint line. */
@Composable
fun EmptyState(title: String, hint: String?, icon: ImageVector, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AIcon(icon, size = 26.dp, tint = Aether.colors.textTertiary)
        Spacer(Modifier.height(8.dp))
        AText(title, Aether.type.bodyStrong, color = Aether.colors.textSecondary, align = TextAlign.Center)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            AText(hint, Aether.type.caption, color = Aether.colors.textTertiary, maxLines = 3, align = TextAlign.Center)
        }
    }
}

fun DeviceKind.icon(): ImageVector = when (this) {
    DeviceKind.Xbox, DeviceKind.PlayStation, DeviceKind.NintendoSwitch -> AetherIcons.Games
    DeviceKind.Pc -> AetherIcons.Monitor
    DeviceKind.MediaPlayer -> AetherIcons.MonitorPlay
    DeviceKind.Headphones -> AetherIcons.Headphones
    DeviceKind.Speaker -> AetherIcons.Speaker
    DeviceKind.Controller -> AetherIcons.Controller
    DeviceKind.Other -> AetherIcons.Hdmi
}
