package com.aether.tv.ui.home

import com.aether.tv.ui.common.BleedRow
import com.aether.tv.ui.common.RowBleed

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.EmptyState
import com.aether.tv.core.designsystem.AppLogo
import com.aether.tv.core.designsystem.GlassPanel
import com.aether.tv.core.designsystem.GlassTile
import com.aether.tv.core.designsystem.SectionTitle
import com.aether.tv.core.model.ConnectedDevice
import com.aether.tv.core.model.DeviceKind
import com.aether.tv.core.model.DeviceStatus
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.core.model.HomeSection
import com.aether.tv.core.model.LauncherTab
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.MoveScope
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.AppTile
import com.aether.tv.ui.common.DockItem
import com.aether.tv.ui.common.ArtCard
import com.aether.tv.ui.common.DeviceCard
import com.aether.tv.ui.common.IconTile
import com.aether.tv.ui.common.MediaCard
import com.aether.tv.ui.common.moveKeys

/** Width of each of [count] items that exactly fill [total] with [gap] spacing. */
fun fillWidth(total: Dp, count: Int, gap: Dp): Dp = if (count <= 0) total else (total - gap * (count - 1)) / count

/** Applies focus restoration to rows when enabled in Behavior settings. */
@Composable
fun Modifier.rowFocus(enabled: Boolean): Modifier = if (enabled) this.focusRestorer().focusGroup() else this.focusGroup()

@Composable
fun HomePage(state: LauncherUiState, vm: LauncherViewModel, initialFocus: FocusRequester, onOpenTab: (LauncherTab) -> Unit, resetKey: Int = 0) {
    val layout = state.settings.layout
    val scroll = rememberScrollState()
    // The Home button: back to the top of Home.
    androidx.compose.runtime.LaunchedEffect(resetKey) { if (resetKey > 0) scroll.scrollTo(0) }
    var firstAssigned = false
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
        layout.sections.filter { it.visible }.forEachIndexed { index, cfg ->
            if (index > 0) Spacer(Modifier.height(18.dp))
            val requester = if (!firstAssigned) { firstAssigned = true; initialFocus } else null
            when (cfg.section) {
                HomeSection.Apps -> AppsRow(state, vm, requester, onOpenTab)
                HomeSection.ContinueWatching -> ContinueWatching(state, vm, requester, onOpenTab)
                HomeSection.Devices -> DevicesRow(state, vm, requester, onOpenTab)
                HomeSection.Games -> GamesRow(state, vm, requester, onOpenTab)
            }
        }
    }
}

@Composable
private fun AppsRow(state: LauncherUiState, vm: LauncherViewModel, requester: FocusRequester?, onOpenTab: (LauncherTab) -> Unit) {
    val layout = state.settings.layout
    val moveMode by vm.moveMode.collectAsStateWithLifecycle()
    val favs = state.favoriteApps.filter { !it.second.hidden }.take(layout.appShortcutCount)
    val restore = state.settings.behavior.restoreFocus
    val gap = 11.dp
    val height = layout.tileSize.heightDp.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val slots = layout.appShortcutCount + if (layout.showAllAppsTile) 1 else 0
        val w = fillWidth(maxWidth, slots.coerceAtLeast(5), gap)
        if (state.apps == null) {
            Box(Modifier.height(height))
        } else if (favs.isEmpty()) {
            Row(Modifier.rowFocus(restore), horizontalArrangement = Arrangement.spacedBy(gap)) {
                IconTile("Add favorites", AetherIcons.CirclePlus, { onOpenTab(LauncherTab.Apps) }, Modifier.width(w * 2 + gap), height = height, focusRequester = requester,
                    sublabel = "Long-press any app ▸ Add to Favorites")
                if (layout.showAllAppsTile) IconTile("All Apps", AetherIcons.AppsGrid, { onOpenTab(LauncherTab.Apps) }, Modifier.width(w), height = height)
            }
        } else if (com.aether.tv.core.designsystem.LocalAether.current.monoLogos) {
            AppsDock(favs, layout.showAllAppsTile, moveMode?.favoriteId, restore, requester, vm, onOpenTab, maxWidth)
        } else {
            BleedRow(restore, gap) {
                itemsIndexedKeyed(favs) { i, (fav, app) ->
                    val moving = moveMode?.favoriteId == fav.id
                    AppTile(
                        app = app,
                        onClick = { if (moving) vm.endMove() else vm.launchApp(app) },
                        onLongClick = { vm.onAppLongPress(app, MoveScope.HomeApps) },
                        modifier = Modifier.width(w).moveKeys(moving, vm::moveBy, vm::endMove),
                        height = height,
                        focusRequester = if (i == 0) requester else null,
                        moving = moving,
                        banner = state.settings.library.useBanners,
                    )
                }
                if (layout.showAllAppsTile) item(key = "all-apps") {
                    IconTile("All Apps", AetherIcons.AppsGrid, { onOpenTab(LauncherTab.Apps) }, Modifier.width(w), height = height, showLabel = com.aether.tv.core.designsystem.LocalAether.current.showLabels)
                }
            }
        }
    }
}

/** Favorite apps as white logos in one glass dock; the focused app gets a frosted tile with a bright outline. */
@Composable
private fun AppsDock(
    favs: List<Pair<com.aether.tv.core.model.Favorite, com.aether.tv.core.model.AppInfo>>,
    showAllApps: Boolean,
    movingId: Long?,
    restore: Boolean,
    requester: FocusRequester?,
    vm: LauncherViewModel,
    onOpenTab: (LauncherTab) -> Unit,
    width: Dp,
) {
    val scale = Aether.dimens.iconScale
    val itemW = (124 * scale).dp
    val tileH = (58 * scale).dp
    val logoH = (25 * scale).dp
    val gap = 6.dp
    val count = favs.size + if (showAllApps) 1 else 0
    // Spread the apps evenly across the dock while they fit; past that it scrolls like any other row.
    val fits = itemW * count + gap * (count - 1) <= width - 24.dp - RowBleed * 2
    GlassPanel(Modifier.fillMaxWidth(), shape = Aether.shapes.panel) {
        BleedRow(
            restore, gap,
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth()
                // ◀ ▶ stay in the dock: past its last app, focus would otherwise jump up to the top bar.
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Left ||
                            requestedFocusDirection == androidx.compose.ui.focus.FocusDirection.Right) cancelFocusChange()
                    }
                },
            arrangement = if (fits) Arrangement.SpaceEvenly else null,
        ) {
            itemsIndexedKeyed(favs) { i, (fav, app) ->
                val moving = movingId == fav.id
                DockItem(
                    label = app.label,
                    onClick = { if (moving) vm.endMove() else vm.launchApp(app) },
                    onLongClick = { vm.onAppLongPress(app, MoveScope.HomeApps) },
                    modifier = Modifier.width(itemW),
                    tileHeight = tileH,
                    focusRequester = if (i == 0) requester else null,
                    moving = moving,
                    tileModifier = Modifier.moveKeys(moving, vm::moveBy, vm::endMove),
                ) { AppLogo(app, maxWidth = itemW - 40.dp, height = logoH) }
            }
            if (showAllApps) item(key = "all-apps") {
                DockItem("All Apps", { onOpenTab(LauncherTab.Apps) }, Modifier.width(itemW), tileHeight = tileH) {
                    AIcon(AetherIcons.AppsGrid, size = logoH)
                }
            }
        }
    }
}

private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.itemsIndexedKeyed(
    list: List<Pair<com.aether.tv.core.model.Favorite, T>>,
    crossinline content: @Composable (Int, Pair<com.aether.tv.core.model.Favorite, T>) -> Unit,
) {
    items(list.size, key = { list[it].first.id }) { i -> content(i, list[i]) }
}

@Composable
private fun ContinueWatching(state: LauncherUiState, vm: LauncherViewModel, requester: FocusRequester?, onOpenTab: (LauncherTab) -> Unit) {
    val items = state.media.continueWatching.take(state.settings.layout.continueWatchingLimit)
    val restore = state.settings.behavior.restoreFocus
    SectionTitle("Continue Watching")
    Spacer(Modifier.height(10.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 11.dp
        val w = fillWidth(maxWidth, 4, gap)
        val h = w * 0.45f
        if (items.isEmpty()) {
            val anyConnected = state.media.providers.any { it.status is com.aether.tv.data.media.ProviderStatus.Connected }
            GlassTile(onClick = { vm.openSettings("integrations") }, onLongClick = { vm.show(Overlay.Message("Continue Watching", CW_EXPLAINER)) }, focusRequester = requester, shape = Aether.shapes.card,
                modifier = Modifier.fillMaxWidth().height(h * 0.62f)) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    AIcon(AetherIcons.CirclePlay, size = 24.dp, tint = Aether.colors.textSecondary)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        AText(if (anyConnected) "Nothing in progress right now" else "Connect a media source", Aether.type.bodyStrong)
                        AText(
                            if (anyConnected) "Items you've started in your connected sources will appear here." else "Link Plex, Jellyfin or Kodi, or allow notification access to show what's playing — Settings ▸ Integrations.",
                            Aether.type.caption, color = Aether.colors.textSecondary, maxLines = 2,
                        )
                    }
                }
            }
        } else {
            BleedRow(restore, gap) {
                items(items.size, key = { items[it].providerId + items[it].id }) { i ->
                    val item = items[i]
                    MediaCard(item, onClick = { vm.resumeMedia(item) }, onLongClick = { vm.show(Overlay.MediaOptions(item)) },
                        modifier = Modifier.width(w).height(h), focusRequester = if (i == 0) requester else null)
                }
            }
        }
    }
}

const val CW_EXPLAINER = "Aether only shows progress that a source actually provides:\n\n" +
    "• Plex, Jellyfin and Kodi report what you've started, with progress.\n" +
    "• With notification access, any app's active media session (Netflix, YouTube, Spotify…) appears while it's playing or paused.\n\n" +
    "Streaming apps like Netflix or Disney+ don't share their viewing history with other apps, so their progress can't be shown.\n\n" +
    "Open Settings ▸ Integrations to connect a source."

@Composable
private fun DevicesRow(state: LauncherUiState, vm: LauncherViewModel, requester: FocusRequester?, onOpenTab: (LauncherTab) -> Unit) {
    val layout = state.settings.layout
    val restore = state.settings.behavior.restoreFocus
    val devices = homeDevices(state.devices.devices, layout.deviceLimit)
    SectionTitle("Connected Devices")
    Spacer(Modifier.height(10.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 11.dp
        val count = devices.size + if (layout.showInputsTile) 1 else 0
        val featuredWeight = 1.6f
        val units = (count - 1).coerceAtLeast(0) + if (devices.isNotEmpty()) featuredWeight else 1f
        val unit = (maxWidth - gap * (count - 1).coerceAtLeast(0)) / units.coerceAtLeast(4.2f)
        val h = 64.dp
        Row(Modifier.rowFocus(restore), horizontalArrangement = Arrangement.spacedBy(gap)) {
            devices.forEachIndexed { i, d ->
                val featured = i == 0 && d.kind in setOf(DeviceKind.Xbox, DeviceKind.PlayStation, DeviceKind.NintendoSwitch, DeviceKind.Pc, DeviceKind.MediaPlayer)
                DeviceCard(
                    d, onClick = { vm.activateDevice(d) }, onLongClick = { vm.show(Overlay.DeviceOptions(d.id)) },
                    modifier = Modifier.width(if (featured) unit * featuredWeight else unit).height(h), featured = featured,
                    focusRequester = if (i == 0) requester else null,
                )
            }
            if (layout.showInputsTile) {
                GlassTile(onClick = { vm.openSettings("devices") },
                    shape = Aether.shapes.card, focusRequester = if (devices.isEmpty()) requester else null, modifier = Modifier.width(unit).height(h)) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AIcon(AetherIcons.Monitor, size = (24 * Aether.dimens.iconScale).dp, tint = Aether.colors.textPrimary.copy(alpha = 0.9f))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            AText("Inputs", Aether.type.cardTitle)
                            AText(if (state.devices.inputs.isEmpty()) "Manage devices" else "${state.devices.inputs.size} sources", Aether.type.caption, color = Aether.colors.textSecondary)
                        }
                        AIcon(AetherIcons.ChevronRight, size = 16.dp, tint = Aether.colors.textSecondary)
                    }
                }
            }
        }
    }
}

/** Configured devices marked for Home first, then discovered devices that are actively connected. */
fun homeDevices(all: List<ConnectedDevice>, limit: Int): List<ConnectedDevice> {
    val configured = all.filter { it.configured && it.showOnHome }
    val discovered = all.filter { !it.configured && it.status == DeviceStatus.Connected }
    return (configured + discovered).take(limit.coerceAtLeast(1))
}

@Composable
private fun GamesRow(state: LauncherUiState, vm: LauncherViewModel, requester: FocusRequester?, onOpenTab: (LauncherTab) -> Unit) {
    val restore = state.settings.behavior.restoreFocus
    val games = state.games.sortedByDescending { it.lastPlayedAt }
    val gameApps = state.apps.orEmpty().filter { it.isGame && !it.hidden }.sortedByDescending { it.lastLaunchedAt }
    SectionTitle("Games")
    Spacer(Modifier.height(10.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 11.dp
        val w = fillWidth(maxWidth, 5, gap)
        if (games.isEmpty() && gameApps.isEmpty()) {
            GlassTile(onClick = { onOpenTab(LauncherTab.Games) }, focusRequester = requester, shape = Aether.shapes.card, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                EmptyState("No games yet", "Open the Games tab to add game shortcuts and streaming apps.", AetherIcons.Games, Modifier.align(Alignment.Center))
            }
        } else BleedRow(restore, gap) {
            items(games.size, key = { "g" + games[it].id }) { i ->
                val g = games[i]
                ArtCard(g.title, null, g.artworkUri, { vm.playGame(g) }, Modifier.width(w).height(w * 0.56f), onLongClick = { vm.show(Overlay.GameOptions(g.id)) },
                    focusRequester = if (i == 0) requester else null, fallbackIcon = AetherIcons.Games)
            }
            items(gameApps.size, key = { "a" + gameApps[it].key }) { i ->
                val a = gameApps[i]
                AppTile(a, { vm.launchApp(a) }, { vm.onAppLongPress(a, null) }, Modifier.width(w), height = w * 0.56f,
                    focusRequester = if (games.isEmpty() && i == 0) requester else null)
            }
        }
    }
}
