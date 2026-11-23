package com.aether.tv.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.EmptyState
import com.aether.tv.core.model.Favorite
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.platform.SystemShortcut
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.MoveScope
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.AppTile
import com.aether.tv.ui.common.ArtCard
import com.aether.tv.ui.common.DeviceCard
import com.aether.tv.ui.common.IconTile
import com.aether.tv.ui.common.moveKeys
import com.aether.tv.ui.common.bleedHorizontal
import com.aether.tv.ui.home.fillWidth
import com.aether.tv.ui.home.rowFocus
import com.aether.tv.ui.common.BleedRow

@Composable
fun FavoritesPage(state: LauncherUiState, vm: LauncherViewModel) {
    val favs = state.favorites
    if (favs.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState("No favorites yet", "Pin apps, games, HDMI inputs, devices and system settings here.", AetherIcons.Star)
            Spacer(Modifier.height(12.dp))
            IconTile("Add Favorite", AetherIcons.CirclePlus, { vm.show(Overlay.AddFavorite()) }, Modifier.width(150.dp), height = 72.dp)
        }
        return
    }
    if (state.settings.layout.favoritesGrouped) GroupedFavorites(state, vm) else GridFavorites(state, vm)
}

private fun isGameFav(f: Favorite, state: LauncherUiState) =
    f.type == FavoriteType.Game || (f.type == FavoriteType.App && state.appsByKey[f.targetKey]?.isGame == true)

@Composable
private fun GroupedFavorites(state: LauncherUiState, vm: LauncherViewModel) {
    val restore = state.settings.behavior.restoreFocus
    val favs = state.favorites
    val apps = favs.filter { it.type == FavoriteType.App && !isGameFav(it, state) }
    val games = favs.filter { isGameFav(it, state) }
    val inputs = favs.filter { it.type == FavoriteType.Input || it.type == FavoriteType.System }
    val media = favs.filter { it.type == FavoriteType.Media }
    val devices = favs.filter { it.type == FavoriteType.Device }
    val gap = 11.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w7 = fillWidth(maxWidth, 7, gap)
        val w8 = fillWidth(maxWidth, 8, gap)
        // Row 2 (games | inputs) and row 3 (media | devices + add) size to fit the screen when they can.
        val row2Count = (games.size + inputs.size).coerceAtLeast(1)
        val w2 = fillWidth(maxWidth - if (games.isNotEmpty() && inputs.isNotEmpty()) 16.dp else 0.dp, row2Count.coerceIn(7, 9), gap)
        val row3Units = media.size * 2f + devices.size * 1.45f + 1.1f
        val u3 = ((maxWidth - (if (media.isNotEmpty()) 16.dp else 0.dp) - gap * (media.size + devices.size)) / row3Units.coerceAtLeast(6.5f))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (apps.isNotEmpty()) BleedRow(restore, gap) {
                favItems(apps, state, vm, w7, 68.dp)
            }
            if (games.isNotEmpty() || inputs.isNotEmpty()) BleedRow(restore, gap) {
                favItems(games, state, vm, w2, 78.dp, art = true)
                if (games.isNotEmpty() && inputs.isNotEmpty()) item(key = "div1") { Divider(78.dp) }
                favItems(inputs, state, vm, w2, 78.dp)
            }
            BleedRow(restore, gap) {
                favItems(media, state, vm, u3 * 2, 82.dp, art = true)
                if (media.isNotEmpty()) item(key = "div2") { Divider(82.dp) }
                favItems(devices, state, vm, u3 * 1.45f, 82.dp)
                item(key = "add") { IconTile("Add Favorite", AetherIcons.CirclePlus, { vm.show(Overlay.AddFavorite()) }, Modifier.width(u3 * 1.1f), height = 82.dp) }
            }
        }
    }
}

@Composable
private fun Divider(h: Dp) {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(h * 0.8f).background(Aether.colors.border))
}

private fun LazyListScope.favItems(list: List<Favorite>, state: LauncherUiState, vm: LauncherViewModel, w: Dp, h: Dp, art: Boolean = false) {
    items(list.size, key = { list[it].id }) { i -> FavoriteItem(list[i], state, vm, Modifier.width(w).height(h), h, art) }
}

@Composable
private fun GridFavorites(state: LauncherUiState, vm: LauncherViewModel) {
    LazyVerticalGrid(
        GridCells.Fixed(7), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 6.dp, start = com.aether.tv.ui.common.RowBleed, end = com.aether.tv.ui.common.RowBleed),
        modifier = Modifier.fillMaxSize().then(Modifier.bleedHorizontal()).rowFocus(state.settings.behavior.restoreFocus),
    ) {
        items(state.favorites, key = { it.id }) { f -> FavoriteItem(f, state, vm, Modifier.height(72.dp), 72.dp, art = false) }
        item(key = "add") { IconTile("Add Favorite", AetherIcons.CirclePlus, { vm.show(Overlay.AddFavorite()) }, height = 72.dp) }
    }
}

/** Renders any favorite type with the right tile and behaviour. */
@Composable
fun FavoriteItem(f: Favorite, state: LauncherUiState, vm: LauncherViewModel, modifier: Modifier, h: Dp, art: Boolean) {
    val moveMode by vm.moveMode.collectAsStateWithLifecycle()
    val moving = moveMode?.favoriteId == f.id
    val mod = modifier.moveKeys(moving, vm::moveBy, vm::endMove)
    val open = { if (moving) vm.endMove() else vm.openFavorite(f) }
    val options = { vm.show(Overlay.FavoriteOptions(f.id)) }
    when (f.type) {
        FavoriteType.App -> {
            val app = state.appsByKey[f.targetKey]
            if (app == null) IconTile(f.title ?: "Missing app", AetherIcons.Help, options, mod, sublabel = "Not installed", height = h, onLongClick = options)
            else if (art && f.artworkUri != null) ArtCard(f.title ?: app.label, null, f.artworkUri, open, mod, onLongClick = options, moving = moving)
            else AppTile(app, open, { vm.onAppLongPress(app, MoveScope.AllFavorites) }, mod, height = h, moving = moving)
        }
        FavoriteType.Game -> {
            val g = state.games.firstOrNull { it.id == f.targetKey }
            ArtCard(f.title ?: g?.title ?: "Missing game", null, f.artworkUri ?: g?.artworkUri, open, mod, onLongClick = options, moving = moving, fallbackIcon = AetherIcons.Games, compact = true)
        }
        FavoriteType.Media -> ArtCard(f.title ?: "Media", null, f.artworkUri, open, mod, onLongClick = options, playBadge = true, moving = moving, fallbackIcon = AetherIcons.Film)
        FavoriteType.Device -> {
            val d = state.device(f.targetKey)
            if (d == null) IconTile(f.title ?: "Removed device", AetherIcons.Help, options, mod, height = h, onLongClick = options)
            else DeviceCard(d.copy(name = f.title ?: d.name), open, options, mod, vertical = true)
        }
        FavoriteType.Input -> {
            val input = state.devices.inputs.firstOrNull { it.id == f.targetKey }
            val owner = state.devices.devices.firstOrNull { it.inputId == f.targetKey && it.configured }
            IconTile(f.title ?: input?.label ?: "Input", AetherIcons.Hdmi, open, mod, sublabel = owner?.name ?: input?.state?.label ?: "Unavailable", height = h, onLongClick = options)
        }
        FavoriteType.System -> {
            val s = runCatching { SystemShortcut.valueOf(f.targetKey) }.getOrNull()
            IconTile(f.title ?: s?.label ?: "Setting", s?.icon ?: AetherIcons.Settings, open, mod, height = h, onLongClick = options)
        }
    }
}
