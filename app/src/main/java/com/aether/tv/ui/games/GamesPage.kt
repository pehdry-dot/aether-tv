package com.aether.tv.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.SectionTitle
import com.aether.tv.core.model.DeviceKind
import com.aether.tv.core.model.GameKind
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.AppTile
import com.aether.tv.ui.common.ArtCard
import com.aether.tv.ui.common.DeviceCard
import com.aether.tv.ui.common.IconTile
import com.aether.tv.ui.home.fillWidth
import com.aether.tv.ui.home.rowFocus
import com.aether.tv.ui.common.BleedRow

private val consoleKinds = setOf(DeviceKind.Xbox, DeviceKind.PlayStation, DeviceKind.NintendoSwitch, DeviceKind.Pc)

/**
 * Games are grouped by HOW they're played, so it's always clear what runs on the TV itself, what streams from
 * another machine or the cloud, and what simply switches to a console on an HDMI input.
 */
@Composable
fun GamesPage(state: LauncherUiState, vm: LauncherViewModel) {
    val restore = state.settings.behavior.restoreFocus
    val apps = state.apps.orEmpty().filter { !it.hidden }
    val local = apps.filter { it.gameKind == GameKind.Local || it.gameKind == GameKind.Emulator }.sortedByDescending { it.lastLaunchedAt }
    val streaming = apps.filter { it.gameKind == GameKind.Streaming }
    val consoles = state.devices.devices.filter { it.configured && it.kind in consoleKinds }
    val shortcuts = state.games.sortedWith(compareByDescending<com.aether.tv.data.db.GameShortcutEntity> { it.lastPlayedAt }.thenBy { it.sortOrder })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 11.dp
            val w5 = fillWidth(maxWidth, 5, gap)
            val w8 = fillWidth(maxWidth, 8, gap)
            Column {
                SectionTitle("Your Games", trailing = "Shortcuts to a console, streaming app or installed game")
                Spacer(Modifier.height(10.dp))
                BleedRow(restore, gap) {
                    items(shortcuts.size, key = { shortcuts[it].id }) { i ->
                        val g = shortcuts[i]
                        val via = g.deviceId?.let { id -> state.device(id)?.name?.let { "on $it" } } ?: g.appKey?.let { k -> state.appsByKey[k]?.label?.let { "via $it" } }
                        ArtCard(g.title, via, g.artworkUri, { vm.playGame(g) }, Modifier.width(w5).height(w5 * 0.56f), onLongClick = { vm.show(Overlay.GameOptions(g.id)) }, fallbackIcon = AetherIcons.Games)
                    }
                    item(key = "add") { IconTile("Add game", AetherIcons.CirclePlus, { vm.show(Overlay.GameEditor(null)) }, Modifier.width(w5 * 0.6f), height = w5 * 0.56f) }
                }
                if (consoles.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    SectionTitle("Consoles", trailing = "Switches the TV to the console's HDMI input")
                    Spacer(Modifier.height(10.dp))
                    BleedRow(restore, gap) {
                        items(consoles.size, key = { consoles[it].id }) { i ->
                            val d = consoles[i]
                            DeviceCard(d, { vm.activateDevice(d) }, { vm.show(Overlay.DeviceOptions(d.id)) }, Modifier.width(w5 * 1.5f).height(64.dp), featured = true)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                SectionTitle("Play on this TV", trailing = "Installed Android games and emulators")
                Spacer(Modifier.height(10.dp))
                if (local.isEmpty()) IconTile("No installed games", AetherIcons.Help, { vm.show(Overlay.Message("Installed games", "Android games you install appear here automatically when they declare themselves as games. You can also long-press any app ▸ Mark as game.")) }, Modifier.width(w8 * 2), height = 64.dp)
                else BleedRow(restore, gap) {
                    items(local.size, key = { local[it].key }) { i -> val a = local[i]; AppTile(a, { vm.launchApp(a) }, { vm.onAppLongPress(a, null) }, Modifier.width(w8), height = 64.dp) }
                }
                Spacer(Modifier.height(18.dp))
                SectionTitle("Stream", trailing = "Games run on a PC, console or cloud server and stream to this TV")
                Spacer(Modifier.height(10.dp))
                if (streaming.isEmpty()) IconTile("No streaming apps found", AetherIcons.Cloud, { vm.show(Overlay.Message("Game streaming", "Install Steam Link, Moonlight, Xbox Cloud Gaming, GeForce NOW or similar, and they'll appear here.")) }, Modifier.width(w8 * 2), height = 64.dp)
                else BleedRow(restore, gap) {
                    items(streaming.size, key = { streaming[it].key }) { i -> val a = streaming[i]; AppTile(a, { vm.launchApp(a) }, { vm.onAppLongPress(a, null) }, Modifier.width(w8), height = 64.dp) }
                }
            }
        }
    }
}
