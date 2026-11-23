package com.aether.tv.ui.media

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.GlassTile
import com.aether.tv.core.designsystem.SectionTitle
import com.aether.tv.core.designsystem.StatusDot
import com.aether.tv.core.model.DeviceStatus
import com.aether.tv.data.media.MediaCapability
import com.aether.tv.data.media.ProviderState
import com.aether.tv.data.media.ProviderStatus
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.AppTile
import com.aether.tv.ui.common.MediaCard
import com.aether.tv.ui.home.CW_EXPLAINER
import com.aether.tv.ui.home.fillWidth
import com.aether.tv.ui.home.rowFocus
import com.aether.tv.ui.common.BleedRow

fun ProviderStatus.short(): String = when (this) {
    ProviderStatus.NotConfigured -> "Not connected"
    ProviderStatus.Connecting -> "Connecting…"
    is ProviderStatus.Connected -> "Connected"
    is ProviderStatus.NeedsPermission -> "Needs permission"
    is ProviderStatus.Error -> "Unreachable"
}

fun ProviderStatus.dot(): DeviceStatus = when (this) {
    is ProviderStatus.Connected -> DeviceStatus.Connected
    is ProviderStatus.Error -> DeviceStatus.Unavailable
    else -> DeviceStatus.Paired
}

@Composable
fun MediaPage(state: LauncherUiState, vm: LauncherViewModel) {
    // The page stays alive in the background now: refresh each time it's shown, as before.
    val pageActive = com.aether.tv.ui.LocalPageActive.current
    LaunchedEffect(pageActive) { if (pageActive) vm.mediaRepo.refresh() }
    val media = state.media
    val restore = state.settings.behavior.restoreFocus
    val mediaApps = state.favoriteApps.map { it.second }.filter { !it.isGame && !it.hidden }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        // Sources
        BleedRow(restore, 10.dp) {
            items(media.providers.size) { i -> ProviderChip(media.providers[i]) { vm.show(Overlay.ProviderSetup(media.providers[i].provider.id)) } }
            item {
                GlassTile(onClick = { vm.refreshMedia() }, shape = Aether.shapes.pill, scaleOnFocus = false) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AIcon(AetherIcons.RefreshCw, size = 15.dp, tint = Aether.colors.textSecondary)
                        Spacer(Modifier.width(8.dp)); AText(if (media.loading) "Refreshing…" else "Refresh", Aether.type.body)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 11.dp
            val mw = maxWidth
            val w = fillWidth(mw, 3, gap)
            Column {
                SectionTitle("Continue Watching", trailing = if (media.continueWatching.isEmpty()) null else "${media.continueWatching.size} items")
                Spacer(Modifier.height(10.dp))
                if (media.continueWatching.isEmpty()) {
                    GlassTile(onClick = { vm.show(Overlay.Message("Continue Watching", CW_EXPLAINER)) }, shape = Aether.shapes.card, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            AIcon(AetherIcons.Info, size = 20.dp, tint = Aether.colors.textSecondary); Spacer(Modifier.width(12.dp))
                            AText("Nothing in progress from your connected sources. Select to learn which apps can share progress.", Aether.type.body, color = Aether.colors.textSecondary, maxLines = 2)
                        }
                    }
                } else BleedRow(restore, gap) {
                    items(media.continueWatching.size, key = { media.continueWatching[it].providerId + media.continueWatching[it].id }) { i ->
                        val item = media.continueWatching[i]
                        MediaCard(item, { vm.resumeMedia(item) }, { vm.show(Overlay.MediaOptions(item)) }, Modifier.width(w).height(w * 0.5f))
                    }
                }
                if (media.recentlyAdded.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    SectionTitle("Recently Added")
                    Spacer(Modifier.height(10.dp))
                    val w4 = fillWidth(mw, 4, gap)
                    BleedRow(restore, gap) {
                        items(media.recentlyAdded.size, key = { "ra" + media.recentlyAdded[it].providerId + media.recentlyAdded[it].id }) { i ->
                            val item = media.recentlyAdded[i]
                            MediaCard(item, { vm.resumeMedia(item) }, { vm.show(Overlay.MediaOptions(item)) }, Modifier.width(w4).height(w4 * 0.5f))
                        }
                    }
                }
                if (mediaApps.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    SectionTitle("Your Apps")
                    Spacer(Modifier.height(10.dp))
                    val w9 = fillWidth(mw, 9, gap)
                    BleedRow(restore, gap) {
                        items(mediaApps.size, key = { mediaApps[it].key }) { i ->
                            val a = mediaApps[i]
                            AppTile(a, { vm.launchApp(a) }, { vm.onAppLongPress(a, null) }, Modifier.width(w9), height = 64.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderChip(p: ProviderState, onClick: () -> Unit) {
    GlassTile(onClick = onClick, shape = Aether.shapes.pill, scaleOnFocus = false) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(p.status.dot())
            Spacer(Modifier.width(8.dp))
            AText(if (MediaCapability.NowPlaying in p.provider.capabilities) "Now playing" else p.provider.displayName, Aether.type.body)
            Spacer(Modifier.width(6.dp))
            AText(p.status.short(), Aether.type.caption, color = Aether.colors.textTertiary)
        }
    }
}
