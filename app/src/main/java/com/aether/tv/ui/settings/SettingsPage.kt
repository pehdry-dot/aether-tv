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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.semantics.selected
import com.aether.tv.core.designsystem.tryFocus
import com.aether.tv.core.designsystem.dpadEntry
import kotlin.math.roundToInt
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
    Overview("overview", "Overview", "Devices and appearance at a glance", AetherIcons.AppsGrid),
    Appearance("appearance", "Appearance", "Theme, wallpaper, glass, colors", AetherIcons.Monitor),
    HomeLayout("layout", "Home Layout", "Sections, ordering, visibility", AetherIcons.AppsGrid),
    Behavior("behavior", "Behavior", "Navigation, startup, animations", AetherIcons.SlidersHorizontal),
    Applications("applications", "Applications", "Manage apps, favorites, groups", AetherIcons.Layers),
    Devices("devices", "Devices", "HDMI, Bluetooth, controllers", AetherIcons.Tv),
    TvSystem("system", "TV & System", "Storage, remote, picture, network", AetherIcons.Settings),
    Integrations("integrations", "Integrations", "Continue Watching sources", AetherIcons.Link),
    Profiles("profiles", "Profiles", "Users, layouts, syncing", AetherIcons.Users),
    Advanced("advanced", "Advanced", "Backup, data, diagnostics", AetherIcons.Code),
    ;
    companion object {
        fun of(key: String?): SettingsCategory? = entries.firstOrNull { it.key == key }
    }
}

private const val CATEGORY_SETTLE_MS = 250L

/**
 * Settings: a sidebar of categories (Overview first) and the open category's panel.
 *
 * Who has the remote is explicit: the sidebar, the panel, or neither (the tabs or the top bar). Settings only acts on
 * Back while the panel has focus, and only moves focus by itself while the remote is in Settings or a deep link asked
 * for it: once you reach the tabs, Settings lets go. Deep links ("Settings ▸ Devices" from Search or Home) wait until
 * Settings is the page on screen. [entry] lands on the open category in the sidebar (the ⚙ button with the tab hidden).
 */
@Composable
fun SettingsPage(state: LauncherUiState, vm: LauncherViewModel, entry: FocusRequester = remember { FocusRequester() }) {
    var opened by rememberSaveable { mutableStateOf(SettingsCategory.Overview.key) }
    var highlighted by rememberSaveable { mutableStateOf(SettingsCategory.Overview.key) }
    val requesters = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val detailEntry = remember { FocusRequester() }
    val target by vm.settingsTarget.collectAsStateWithLifecycle()
    val pageActive = com.aether.tv.ui.LocalPageActive.current
    var sidebarHasFocus by remember { mutableStateOf(false) }
    var detailHasFocus by remember { mutableStateOf(false) }
    // Requests to move focus into the panel: (count, from a deep link). A plain request only happens while the remote is
    // in Settings; a deep link is the one case where Settings takes the remote from elsewhere.
    var focusDetail by remember { mutableStateOf(0 to false) }
    val openedCat = SettingsCategory.of(opened) ?: SettingsCategory.Overview

    fun open(c: SettingsCategory, focus: Boolean, deepLink: Boolean = false) {
        opened = c.key; highlighted = c.key
        if (focus) focusDetail = (focusDetail.first + 1) to deepLink
    }

    // Deep links from elsewhere (Search, Home ▸ Inputs, Continue Watching setup), only once Settings is on screen.
    LaunchedEffect(target, pageActive) {
        val t = target ?: return@LaunchedEffect
        if (!pageActive) return@LaunchedEffect
        vm.settingsTarget.value = null
        SettingsCategory.of(t.substringBefore('#'))?.let { open(it, focus = true, deepLink = true) }
    }
    LaunchedEffect(focusDetail) {
        val (n, deepLink) = focusDetail
        if (n == 0) return@LaunchedEffect
        // Two frames: the panel for the newly opened category is composed and placed.
        withFrameNanos { }
        withFrameNanos { }
        if (!pageActive) return@LaunchedEffect
        if (!deepLink && !sidebarHasFocus && !detailHasFocus) return@LaunchedEffect // the remote went elsewhere meanwhile
        if (!detailEntry.tryFocus()) requesters[openedCat]?.tryFocus()
    }
    // Master-detail: the panel follows the category list as focus moves through it (after a short rest, so scrolling
    // past categories doesn't rebuild every panel on the way).
    LaunchedEffect(highlighted, sidebarHasFocus) {
        if (sidebarHasFocus && highlighted != opened) {
            kotlinx.coroutines.delay(CATEGORY_SETTLE_MS)
            if (sidebarHasFocus) opened = highlighted
        }
    }
    // Back in the panel returns to its category in the sidebar. Anywhere else Back belongs to the launcher.
    BackHandler(enabled = pageActive && detailHasFocus) { requesters[openedCat]?.tryFocus() }

    // Entering the page from the tabs lands on the open category in the sidebar (not the nearest panel on the right).
    Row(Modifier.fillMaxSize().dpadEntry { requesters[openedCat] }.focusGroup()) {
        LazyColumn(
            Modifier.width(sidebarWidth()).fillMaxHeight().focusGroup()
                .onFocusChanged { sidebarHasFocus = it.hasFocus },
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(SettingsCategory.entries, key = { _, c -> c.key }) { _, c ->
                CategoryItem(
                    c, selected = c == openedCat,
                    requester = requesters[c]!!,
                    extraRequester = if (c == openedCat) entry else null,
                    onFocus = { highlighted = c.key },
                    onOpen = { open(c, focus = true) },
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .onFocusChanged { detailHasFocus = it.hasFocus }
                // ◀ from the panel's left edge goes to its own category, not whichever sidebar row is level with it.
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == FocusDirection.Left) {
                            runCatching { requesters[openedCat]?.requestFocus() }
                        }
                    }
                }
                .focusGroup(),
        ) {
            if (openedCat == SettingsCategory.Overview) {
                SettingsOverview(state, vm, entry = detailEntry, onOpenCategory = { open(it, focus = true) })
            } else GlassPanel(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
                Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
                    AText(openedCat.title, Aether.type.panelTitle)
                    AText(openedCat.subtitle, Aether.type.body, color = Aether.colors.textSecondary, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                    Box(Modifier.fillMaxSize().focusGroup()) {
                        SettingsDetail(openedCat, state, vm, detailEntry)
                    }
                }
            }
        }
    }
}

/** The sidebar grows with the text size so category names stay readable at 150 %. */
@Composable
private fun sidebarWidth() = (236 * Aether.dimens.textScale.coerceAtLeast(1f)).dp

@Composable
private fun CategoryItem(c: SettingsCategory, selected: Boolean, requester: FocusRequester, extraRequester: FocusRequester?, onFocus: () -> Unit, onOpen: () -> Unit) {
    GlassTile(
        onClick = onOpen, focusRequester = requester, selected = selected, scaleOnFocus = false,
        onFocus = { if (it) onFocus() },
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp)
            .then(if (extraRequester != null) Modifier.focusRequester(extraRequester) else Modifier)
            .semantics { contentDescription = "${c.title}. ${c.subtitle}"; this.selected = selected }
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_RIGHT) { onOpen(); true } else false },
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            // The open category keeps an accent bar, so it's clear which panel is showing while focus is elsewhere.
            Box(Modifier.width(3.dp).height(22.dp).clip(Aether.shapes.pill).background(if (selected) Aether.colors.accent else androidx.compose.ui.graphics.Color.Transparent))
            Spacer(Modifier.width(9.dp))
            AIcon(c.icon, size = 19.dp, tint = Aether.colors.textPrimary.copy(alpha = if (focused || selected) 1f else 0.82f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                AText(c.title, Aether.type.bodyStrong)
                AText(c.subtitle, Aether.type.caption, color = Aether.colors.textSecondary)
            }
            AIcon(AetherIcons.ChevronRight, size = 15.dp, tint = Aether.colors.textSecondary)
        }
    }
}

/**
 * The landing view (reference design): connected devices and appearance shortcuts. Every card opens its own control
 * (device editor, wallpaper picker, theme, glass, accent, typography); closing it returns focus to the card.
 * ◀ from the leftmost cards goes to "Overview" in the sidebar; ▲ from the devices goes to "Add Device", then the tabs.
 */
@Composable
private fun SettingsOverview(state: LauncherUiState, vm: LauncherViewModel, entry: FocusRequester, onOpenCategory: (SettingsCategory) -> Unit) {
    val a = state.settings.appearance
    val addDevice = remember { FocusRequester() }
    val firstAppearance = remember { FocusRequester() }
    val configured = state.devices.devices.filter { it.configured }
    val discovered = state.devices.devices.filter { !it.configured }
    // Five slots across: the first configured device is a double-width card, then single cards and "All devices".
    val all = configured + discovered
    val devices = all.take(if (all.firstOrNull()?.configured == true) 3 else 4)
    val paired = state.devices.peripherals.peers.size
    Column(Modifier.fillMaxSize().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        AText("Connected Devices", Aether.type.panelTitle)
                        AText("Manage your HDMI, Bluetooth and external devices", Aether.type.body, color = Aether.colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
                    }
                    GlassTile(
                        onClick = { vm.show(Overlay.DeviceEditor(null)) }, shape = Aether.shapes.tile, scaleOnFocus = false, focusRequester = addDevice,
                        modifier = Modifier.semantics { contentDescription = "Add Device" }.focusProperties {
                            right = FocusRequester.Cancel
                            down = if (devices.isNotEmpty()) entry else firstAppearance
                        },
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AIcon(AetherIcons.Plus, size = 15.dp); Spacer(Modifier.width(8.dp)); AText("Add Device", Aether.type.bodyStrong)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val gap = 10.dp
                    val unit = fillWidth(maxWidth, 5, gap)
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        devices.forEachIndexed { i, d ->
                            val featured = i == 0 && d.configured
                            DeviceCard(
                                d,
                                onClick = { if (d.configured) vm.show(Overlay.DeviceEditor(d.id)) else vm.show(Overlay.DeviceOptions(d.id)) },
                                onLongClick = { vm.show(Overlay.DeviceOptions(d.id)) },
                                modifier = Modifier.width(if (featured) unit * 2 + gap else unit * 1.0f).height(78.dp).focusProperties { up = addDevice },
                                featured = featured, vertical = !featured,
                                focusRequester = if (i == 0) entry else null,
                            )
                        }
                        // Paired Bluetooth devices and everything else live in Settings ▸ Devices.
                        GlassTile(
                            onClick = { onOpenCategory(SettingsCategory.Devices) }, shape = Aether.shapes.card,
                            focusRequester = if (devices.isEmpty()) entry else null,
                            modifier = Modifier.width(unit).height(78.dp).semantics { contentDescription = "All devices" }
                                .focusProperties { up = addDevice; right = FocusRequester.Cancel },
                        ) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.SpaceBetween) {
                                AIcon(AetherIcons.Bluetooth, size = 20.dp, tint = Aether.colors.textPrimary.copy(alpha = 0.92f))
                                Column {
                                    AText("All devices", Aether.type.bodyStrong)
                                    AText(if (paired > 0) "$paired paired" else "Inputs, Bluetooth", Aether.type.caption, color = Aether.colors.textSecondary)
                                }
                            }
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
                    val last = Modifier.focusProperties { right = FocusRequester.Cancel; down = FocusRequester.Cancel }
                    val inner = Modifier.focusProperties { down = FocusRequester.Cancel }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        val wpLabel = wallpaperLabel(a.wallpaper)
                        QuickTile("Wallpaper", wpLabel, AetherIcons.Image, Modifier.width(w).then(inner), thumbnail = true, state = state, requester = firstAppearance) {
                            vm.show(Overlay.WallpaperPicker)
                        }
                        QuickTile("Theme", a.theme.name, AetherIcons.Palette, Modifier.width(w).then(inner)) {
                            val all = com.aether.tv.core.model.ThemeMode.entries
                            vm.show(Overlay.Choice("Theme", all.map { it.name }, a.theme.ordinal) { i -> vm.updateSettings { it.copy(appearance = it.appearance.copy(theme = all[i])) } })
                        }
                        QuickTile("Glass & Blur", "${a.glassQuality.label}, blur ${a.blurRadiusDp} dp", AetherIcons.Layers, Modifier.width(w).then(inner)) { vm.show(Overlay.GlassSettings) }
                        QuickTile("Accent Color", a.accent.label, AetherIcons.Square, Modifier.width(w).then(inner)) {
                            val all = com.aether.tv.core.model.AccentColor.entries
                            vm.show(Overlay.Choice("Accent color", all.map { it.label }, a.accent.ordinal) { i -> vm.updateSettings { it.copy(appearance = it.appearance.copy(accent = all[i])) } })
                        }
                        QuickTile("Typography", "${a.font.label}, ${(a.textScale * 100).roundToInt()} %", AetherIcons.TextSize, Modifier.width(w).then(last)) { vm.show(Overlay.Typography) }
                    }
                }
            }
        }
    }
}

fun wallpaperLabel(spec: String): String = when {
    spec.startsWith("builtin:") -> BuiltinWallpaper.entries.firstOrNull { it.id == spec.removePrefix("builtin:") }?.label ?: "Built-in"
    spec.startsWith("solid:") -> "Solid color"
    else -> "Your image"
}

@Composable
private fun QuickTile(title: String, value: String, icon: ImageVector, modifier: Modifier, thumbnail: Boolean = false, state: LauncherUiState? = null, requester: FocusRequester? = null, onClick: () -> Unit) {
    val shape = Aether.shapes.card
    GlassTile(onClick = onClick, shape = shape, focusRequester = requester, modifier = modifier.height((76 * Aether.dimens.textScale.coerceAtLeast(1f)).dp).semantics { contentDescription = "$title: $value" }) {
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
                else if (spec.startsWith("solid:")) Box(Modifier.fillMaxWidth().height(44.dp).background(androidx.compose.ui.graphics.Color(spec.removePrefix("solid:").toLong(16).toInt())))
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
