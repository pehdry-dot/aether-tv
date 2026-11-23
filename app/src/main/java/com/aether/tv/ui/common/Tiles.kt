package com.aether.tv.ui.common

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.AppIcon
import com.aether.tv.core.designsystem.AppLogo
import com.aether.tv.core.designsystem.GlassTile
import com.aether.tv.core.designsystem.LocalAether
import com.aether.tv.core.designsystem.ProgressTrack
import com.aether.tv.core.designsystem.StatusLine
import com.aether.tv.core.designsystem.icon
import com.aether.tv.core.model.AppInfo
import com.aether.tv.core.model.ConnectedDevice
import com.aether.tv.core.model.DeviceKind
import com.aether.tv.core.model.DeviceStatus
import com.aether.tv.core.model.MediaItem
import com.aether.tv.data.media.SessionArtCache
import java.io.File

/** Intercepts ◀ ▶ while an item is being moved. */
fun Modifier.moveKeys(active: Boolean, onMove: (Int) -> Unit, onDone: () -> Unit): Modifier =
    if (!active) this else this.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.nativeKeyEvent.keyCode in setOf(NativeKeyEvent.KEYCODE_DPAD_LEFT, NativeKeyEvent.KEYCODE_DPAD_RIGHT, NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER)
        when (e.nativeKeyEvent.keyCode) {
            NativeKeyEvent.KEYCODE_DPAD_LEFT -> { onMove(-1); true }
            NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { onMove(1); true }
            NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_BUTTON_A -> { onDone(); true }
            NativeKeyEvent.KEYCODE_DPAD_UP, NativeKeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        }
    }

@Composable
fun AppTile(
    app: AppInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 68.dp,
    focusRequester: FocusRequester? = null,
    moving: Boolean = false,
    showLabel: Boolean = LocalAether.current.showLabels,
    banner: Boolean = false,
    mono: Boolean = LocalAether.current.monoLogos,
) {
    val iconScale = Aether.dimens.iconScale
    GlassTile(
        onClick = onClick,
        onLongClick = onLongClick,
        focusRequester = focusRequester,
        selected = moving,
        modifier = modifier.height(height).semantics { contentDescription = app.label },
    ) { focused ->
        if (mono) Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            // White logos replace both icons and banners (a banner, when the app has one, is where the logo comes from).
            val logoH = if (showLabel) (height.value * 0.33f * iconScale).dp else (height.value * 0.42f * iconScale).dp
            AppLogo(app, maxWidth = logoH * 3.1f, height = logoH)
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                AText(app.label, Aether.type.tileLabel, color = if (focused) Aether.colors.textPrimary else Aether.colors.textSecondary.copy(alpha = 0.85f), align = TextAlign.Center)
            }
        } else if (banner) {
            AppIcon(app, size = height - 16.dp, banner = true, modifier = Modifier.align(Alignment.Center))
        } else Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val iconSize = if (showLabel) (height.value * 0.44f * iconScale).dp else (height.value * 0.56f * iconScale).dp
            AppIcon(app, size = iconSize)
            if (showLabel) {
                Spacer(Modifier.height(5.dp))
                AText(app.label, Aether.type.tileLabel, color = if (focused) Aether.colors.textPrimary else Aether.colors.textSecondary.copy(alpha = 0.85f), align = TextAlign.Center)
            }
        }
        if (moving) MoveArrows()
    }
}

/**
 * One item of the Home dock: a logo on a glass tile that only appears while focused (lighter frost and a bright
 * outline), with the name underneath on the dock itself.
 */
@Composable
fun DockItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tileHeight: Dp,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    moving: Boolean = false,
    tileModifier: Modifier = Modifier,
    showLabel: Boolean = LocalAether.current.showLabels,
    logo: @Composable () -> Unit,
) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // No focus zoom (as in the dock design): the logo is prepared at its exact pixel size, and scaling it would
        // soften it. Focus shows as the frosted tile, bright outline and glow.
        GlassTile(
            onClick = onClick, onLongClick = onLongClick, focusRequester = focusRequester, selected = moving,
            restingSurface = false, scaleOnFocus = false, onFocus = { focused = it },
            modifier = tileModifier.fillMaxWidth().height(tileHeight).semantics { contentDescription = label },
        ) {
            Box(Modifier.align(Alignment.Center)) { logo() }
            if (moving) MoveArrows()
        }
        if (showLabel) {
            Spacer(Modifier.height(7.dp))
            AText(label, Aether.type.tileLabel, color = if (focused) Aether.colors.textPrimary else Aether.colors.textSecondary.copy(alpha = 0.85f), align = TextAlign.Center)
        }
    }
}

@Composable
fun androidx.compose.foundation.layout.BoxScope.MoveArrows() {
    Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        AIcon(AetherIcons.ChevronLeft, size = 14.dp, tint = Aether.colors.accent)
        AIcon(AetherIcons.ChevronRight, size = 14.dp, tint = Aether.colors.accent)
    }
}

/** Generic icon + label tile (All Apps, Add Favorite, HDMI inputs, system shortcuts). */
@Composable
fun IconTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    height: Dp = 68.dp,
    focusRequester: FocusRequester? = null,
    showLabel: Boolean = true,
) {
    GlassTile(onClick = onClick, onLongClick = onLongClick, focusRequester = focusRequester, modifier = modifier.height(height).semantics { contentDescription = label }) { focused ->
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AIcon(icon, size = (height.value * (if (showLabel) 0.36f else 0.46f) * Aether.dimens.iconScale).dp, tint = Aether.colors.textPrimary.copy(alpha = if (focused) 1f else 0.9f))
            if (showLabel) {
                Spacer(Modifier.height(6.dp))
                AText(label, Aether.type.tileLabel, color = if (focused) Aether.colors.textPrimary else Aether.colors.textSecondary.copy(alpha = 0.85f), align = TextAlign.Center)
                if (sublabel != null) AText(sublabel, Aether.type.caption, color = Aether.colors.textTertiary, align = TextAlign.Center)
            }
        }
    }
}

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val session = remember(url) { SessionArtCache.get(url) }
    when {
        session != null -> Image(session.asImageBitmap(), contentDescription, modifier, contentScale = ContentScale.Crop)
        url == null -> Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF2A3644), Color(0xFF141C25)))))
        url.startsWith("file:") -> AsyncImage(File(url.removePrefix("file:")), contentDescription, modifier, contentScale = ContentScale.Crop)
        else -> AsyncImage(url, contentDescription, modifier, contentScale = ContentScale.Crop)
    }
}

/** Cinematic artwork card used for Continue Watching, game art and media favorites. */
@Composable
fun ArtCard(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    playBadge: Boolean = false,
    moving: Boolean = false,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    compact: Boolean = false,
) {
    val shape = Aether.shapes.card
    GlassTile(onClick = onClick, onLongClick = onLongClick, focusRequester = focusRequester, shape = shape, selected = moving, modifier = modifier.semantics { contentDescription = title }) { focused ->
        Box(Modifier.fillMaxSize().clip(shape)) {
            if (artworkUrl == null && fallbackIcon != null) {
                AIcon(fallbackIcon, size = 28.dp, tint = Aether.colors.textTertiary, modifier = Modifier.align(Alignment.Center).padding(bottom = 18.dp))
            } else Artwork(artworkUrl, Modifier.fillMaxSize(), title)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color(0xE6070B10))))
            val pad = if (compact) 10.dp else 16.dp
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = pad, end = pad, bottom = if (compact) 8.dp else 12.dp)) {
                AText(title, if (compact) Aether.type.bodyStrong else Aether.type.cardTitle, color = Color.White, maxLines = if (compact) 2 else 1)
                if (subtitle != null) AText(subtitle, Aether.type.cardSubtitle, color = Color.White.copy(alpha = 0.78f), modifier = Modifier.padding(top = 1.dp))
                if (progress != null) {
                    Spacer(Modifier.height(8.dp))
                    ProgressTrack(progress)
                }
            }
            if (playBadge) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = if (progress != null) 18.dp else 12.dp).size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape).background(Color.White.copy(alpha = if (focused) 0.3f else 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { AIcon(AetherIcons.ArrowRight, size = 12.dp, tint = Color.White) }
            }
            if (moving) MoveArrows()
        }
    }
}

@Composable
fun MediaCard(item: MediaItem, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) =
    ArtCard(item.title, item.subtitle, item.artworkUrl, onClick, modifier, item.progress, onLongClick, focusRequester)

private fun DeviceKind.accent(): Color = when (this) {
    DeviceKind.Xbox -> Color(0xFF3FA34D)
    DeviceKind.PlayStation -> Color(0xFF3D6FD1)
    DeviceKind.NintendoSwitch -> Color(0xFFD9473F)
    else -> Color(0xFF7FA6C9)
}

/**
 * Connected-device card. [featured] shows console artwork (reference Home screen); [vertical] stacks icon, name and
 * status for narrow slots (reference Settings and Favorites screens).
 */
@Composable
fun DeviceCard(
    device: ConnectedDevice,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    featured: Boolean = false,
    vertical: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val shape = Aether.shapes.card
    val tint = device.kind.accent()
    val nameStyle = Aether.type.cardTitle.copy(fontSize = Aether.type.cardTitle.fontSize * 0.92f)
    GlassTile(onClick = onClick, onLongClick = onLongClick, focusRequester = focusRequester, shape = shape, modifier = modifier.semantics { contentDescription = "${device.name}, ${device.detail}, ${device.status.label}" }) { _ ->
        Box(Modifier.fillMaxSize().clip(shape)) {
            if (featured && device.status == DeviceStatus.Connected) {
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.10f), tint.copy(alpha = 0.34f)))))
            }
            // Only a real picture the user chose for this device is shown — never generated artwork.
            val picture = device.artworkUri
            if (featured && picture != null) {
                DevicePhoto(picture, Modifier.fillMaxHeight().width(72.dp).align(Alignment.CenterEnd).padding(end = 6.dp, top = 4.dp, bottom = 4.dp))
            }
            if (vertical) {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    AIcon(device.kind.icon(), size = (22 * Aether.dimens.iconScale).dp, tint = Aether.colors.textPrimary.copy(alpha = 0.92f))
                    Column {
                        AText(device.name, nameStyle)
                        Spacer(Modifier.height(2.dp))
                        StatusLine(device.status, detail = device.detail.takeIf { it != "Bluetooth" && it != "Controller" })
                    }
                }
            } else Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AIcon(device.kind.icon(), size = (24 * Aether.dimens.iconScale).dp, tint = Aether.colors.textPrimary.copy(alpha = 0.92f))
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    AText(device.name, nameStyle)
                    Spacer(Modifier.height(2.dp))
                    StatusLine(device.status, detail = device.detail)
                }
                if (featured && picture != null) Spacer(Modifier.width(58.dp))
            }
        }
    }
}

/** Device photos bundled with the app, addressed as "builtin:<id>". */
enum class BuiltinDevicePhoto(val id: String, val label: String, val res: Int) {
    XboxSeriesX("xbox_series_x", "Xbox Series X photo", com.aether.tv.R.drawable.device_xbox_series_x),
}

/** A device photo (product shot) fitted inside its box without cropping. */
@Composable
fun DevicePhoto(uri: String, modifier: Modifier = Modifier) {
    val builtin = BuiltinDevicePhoto.entries.firstOrNull { "builtin:${it.id}" == uri }
    when {
        // Product shots are shown far smaller than they're stored: shrink them once, properly, so edges stay smooth.
        builtin != null -> {
            val res = androidx.compose.ui.platform.LocalContext.current.resources
            com.aether.tv.core.designsystem.SharpPhoto("res:${builtin.res}", modifier) { o -> android.graphics.BitmapFactory.decodeResource(res, builtin.res, o) }
        }
        uri.startsWith("file:") -> {
            val f = File(uri.removePrefix("file:"))
            com.aether.tv.core.designsystem.SharpPhoto("file:${f.path}:${f.lastModified()}", modifier) { o -> android.graphics.BitmapFactory.decodeFile(f.path, o) }
        }
        else -> AsyncImage(uri, null, modifier, contentScale = ContentScale.Fit)
    }
}
