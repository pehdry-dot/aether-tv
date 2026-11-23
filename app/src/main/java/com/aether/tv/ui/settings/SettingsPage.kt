package com.aether.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.BuiltinWallpaper
import com.aether.tv.core.designsystem.GlassPanel
import com.aether.tv.core.designsystem.GlassTile
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.Artwork
import com.aether.tv.ui.common.DeviceCard
import com.aether.tv.ui.home.fillWidth
import android.view.KeyEvent as NativeKeyEvent

enum class SettingsCategory(val key: String, val title: String, val subtitle: String, val icon: ImageVector) {
    Appearance("appearance", "Appearance", "Theme, wallpaper, glass, colors", AetherIcons.Monitor),
    HomeLayout("layout", "Home Layout", "Sections, ordering, visibility", AetherIcons.AppsGrid),
    Behavior("behavior", "Behavior", "Navigation, startup, animations", AetherIcons.SlidersHorizontal),
    Applications("applications", "Applications", "Manage apps, favorites, groups", AetherIcons.Layers),
    Devices("devices", "Devices", "HDMI, Bluetooth, controllers", AetherIcons.Tv),
    TvSystem("system", "TV & System", "Inputs, picture, sound, network", AetherIcons.Settings),
    Integrations("integrations", "Integrations", "Plex, Jellyfin, Kodi, media sources", AetherIcons.Link),
    Profiles("profiles", "Profiles", "Users, layouts, syncing", AetherIcons.Users),
    Advanced("advanced", "Advanced", "Backup, data, diagnostics", AetherIcons.Code),
}

private const val CATEGORY_SETTLE_MS = 250L

@Composable
fun SettingsPage(state: LauncherUiState, vm: LauncherViewModel) {
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var highlighted by rememberSaveable { mutableStateOf(SettingsCategory.Appearance.key) }
    val requesters = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val detailEntry = remember { FocusRequester() }
    val target by vm.settingsTarget.collectAsStateWithLifecycle()
    var listHasFocus by remember { mutableStateOf(false) }
    var focusDetail by remember { mutableStateOf(0) }

    // Deep links from elsewhere (e.g. Home ▸ Inputs tile).
    LaunchedEffect(target) {
        target?.let { t -> highlighted = t; opened = t; vm.settingsTarget.value = null; focusDetail++ }
    }
    LaunchedEffect(focusDetail) {
        if (focusDetail > 0) { kotlinx.coroutines.delay(80); runCatching { detailEntry.requestFocus() } }
    }
    // Master-detail: once a category is open, the panel follows the category list as focus moves through it
    // (after a short rest, so scrolling past categories doesn't rebuild every panel on the way).
    LaunchedEffect(highlighted, listHasFocus) {
        if (opened != null && listHasFocus && highlighted != opened) {
            kotlinx.coroutines.delay(CATEGORY_SETTLE_MS)
            opened = highlighted
        }
    }
    // Pages stay alive in the background: only the visible Settings page may take the Back key.
    BackHandler(enabled = com.aether.tv.ui.LocalPageActive.current && opened != null && !listHasFocus) {
        val cat = SettingsCategory.entries.firstOrNull { it.key == opened }
        opened = null
        cat?.let { runCatching { requesters[it]?.requestFocus() } }
    }

    // Entering the page from the tabs lands on the category list (not the nearest panel on the right).
    Row(Modifier.fillMaxSize().focusRestorer(requesters[SettingsCategory.entries.firstOrNull { it.key == highlighted } ?: SettingsCategory.Appearance]!!).focusGroup()) {
        LazyColumn(
            Modifier.width(236.dp).fillMaxHeight().focusRestorer(requesters[SettingsCategory.Appearance]!!).focusGroup()
                .onFocusChanged { listHasFocus = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(SettingsCategory.entries, key = { _, c -> c.key }) { _, c ->
                CategoryItem(
                    c, selected = c.key == (opened ?: highlighted),
                    requester = requesters[c]!!,
                    onFocus = { highlighted = c.key },
                    onOpen = { opened = c.key; focusDetail++ },
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val cat = SettingsCategory.entries.firstOrNull { it.key == opened }
            if (cat == null) SettingsOverview(state, vm, onOpen = { opened = it.key; focusDetail++ })
            else GlassPanel(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
                Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                    AText(cat.title, Aether.type.panelTitle)
                    AText(cat.subtitle, Aether.type.body, color = Aether.colors.textSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                    Box(Modifier.fillMaxSize().focusRestorer().focusGroup()) {
                        SettingsDetail(cat, state, vm, detailEntry)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(c: SettingsCategory, selected: Boolean, requester: FocusRequester, onFocus: () -> Unit, onOpen: () -> Unit) {
    GlassTile(
        onClick = onOpen, focusRequester = requester, selected = selected, scaleOnFocus = false,
        onFocus = { if (it) onFocus() },
        modifier = Modifier.fillMaxWidth().height(42.dp).semantics { contentDescription = "${c.title}. ${c.subtitle}" }
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_RIGHT) { onOpen(); true } else false },
    ) { focused ->
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AIcon(c.icon, size = 19.dp, tint = Aether.colors.textPrimary.copy(alpha = if (focused || selected) 1f else 0.82f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                AText(c.title, Aether.type.bodyStrong)
                AText(c.subtitle, Aether.type.caption, color = Aether.colors.textSecondary)
            }
            AIcon(AetherIcons.ChevronRight, size = 15.dp, tint = Aether.colors.textSecondary)
        }
    }
}

/** The landing view (reference design): a devices summary and appearance shortcuts. */
@Composable
private fun SettingsOverview(state: LauncherUiState, vm: LauncherViewModel, onOpen: (SettingsCategory) -> Unit) {
    val a = state.settings.appearance
    Column(Modifier.fillMaxSize().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        AText("Connected Devices", Aether.type.panelTitle)
                        AText("Manage your HDMI, Bluetooth and external devices", Aether.type.body, color = Aether.colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
                    }
                    GlassTile(onClick = { vm.show(Overlay.DeviceEditor(null)) }, shape = Aether.shapes.tile, scaleOnFocus = false) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AIcon(AetherIcons.Plus, size = 15.dp); Spacer(Modifier.width(8.dp)); AText("Add Device", Aether.type.bodyStrong)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val devices = state.devices.devices.take(4)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val gap = 10.dp
                    val unit = fillWidth(maxWidth, 5, gap)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        items(devices.size, key = { devices[it].id }) { i ->
                            val d = devices[i]
                            val featured = i == 0 && d.configured
                            DeviceCard(d, { vm.show(Overlay.DeviceOptions(d.id)) }, { vm.show(Overlay.DeviceOptions(d.id)) },
                                Modifier.width(if (featured) unit * 2 + gap else unit * 1.0f).height(78.dp), featured = featured, vertical = !featured)
                        }
                        if (devices.isEmpty()) item {
                            AText("No devices yet. Add your console or other HDMI device to switch to it from Home.", Aether.type.body, color = Aether.colors.textSecondary)
                        }
                    }
                }
            }
        }
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
                AText("Appearance", Aether.type.panelTitle)
                AText("Customize the look and feel of Aether TV", Aether.type.body, color = Aether.colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val gap = 10.dp
                    val w = fillWidth(maxWidth, 5, gap)
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        val wpLabel = a.wallpaper.removePrefix("builtin:").let { id -> BuiltinWallpaper.entries.firstOrNull { it.id == id }?.label ?: "Custom" }
                        QuickTile("Wallpaper", wpLabel, AetherIcons.Image, Modifier.width(w), thumbnail = true, state = state) { onOpen(SettingsCategory.Appearance) }
                        QuickTile("Theme", a.theme.name, AetherIcons.Palette, Modifier.width(w)) { vm.updateSettings { it.copy(appearance = it.appearance.copy(theme = if (a.theme == com.aether.tv.core.model.ThemeMode.Dark) com.aether.tv.core.model.ThemeMode.Light else com.aether.tv.core.model.ThemeMode.Dark)) } }
                        QuickTile("Glass & Blur", "Opacity, blur, depth", AetherIcons.Layers, Modifier.width(w)) { onOpen(SettingsCategory.Appearance) }
                        QuickTile("Accent Color", a.accent.label, AetherIcons.Square, Modifier.width(w)) {
                            val all = com.aether.tv.core.model.AccentColor.entries
                            vm.show(Overlay.Choice("Accent color", all.map { it.label }, a.accent.ordinal) { i -> vm.updateSettings { it.copy(appearance = it.appearance.copy(accent = all[i])) } })
                        }
                        QuickTile("Typography", "Size, style", AetherIcons.TextSize, Modifier.width(w)) { onOpen(SettingsCategory.Appearance) }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickTile(title: String, value: String, icon: ImageVector, modifier: Modifier, thumbnail: Boolean = false, state: LauncherUiState? = null, onClick: () -> Unit) {
    val shape = Aether.shapes.card
    GlassTile(onClick = onClick, shape = shape, modifier = modifier.height(76.dp).semantics { contentDescription = "$title: $value" }) {
        Box(Modifier.fillMaxSize().clip(shape)) {
            if (thumbnail && state != null) {
                val spec = state.settings.appearance.wallpaper
                val res = BuiltinWallpaper.entries.firstOrNull { "builtin:${it.id}" == spec }
                // Coil decodes off the main thread and downsamples to the thumbnail's size. painterResource decoded the
                // full-size wallpaper JPEG on the UI thread when Settings opened (a ~450 ms stall measured on the M450).
                if (res != null) coil3.compose.AsyncImage(
                    res.res, null, Modifier.fillMaxWidth().height(44.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                ) else if (spec.startsWith("file:")) Artwork(spec, Modifier.fillMaxWidth().height(44.dp))
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.2f to androidx.compose.ui.graphics.Color.Transparent, 0.75f to androidx.compose.ui.graphics.Color(0xCC0B1118))))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 10.dp, bottom = 9.dp)) {
                if (!thumbnail) { AIcon(icon, size = 20.dp); Spacer(Modifier.height(8.dp)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (thumbnail) { AIcon(icon, size = 14.dp); Spacer(Modifier.width(6.dp)) }
                    AText(title, Aether.type.bodyStrong)
                }
                AText(value, Aether.type.caption, color = Aether.colors.textSecondary)
            }
        }
    }
}
