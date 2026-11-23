package com.aether.tv.ui.overlays

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.EmptyState
import com.aether.tv.core.designsystem.GlassTile
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.core.model.MediaItem
import com.aether.tv.core.model.SearchScope
import com.aether.tv.data.apps.AppRepository
import com.aether.tv.data.media.JellyfinProvider
import com.aether.tv.data.media.KodiProvider
import com.aether.tv.data.media.LinkCode
import com.aether.tv.data.media.MediaSessionProvider
import com.aether.tv.data.media.PlexProvider
import com.aether.tv.data.media.ProviderStatus
import com.aether.tv.platform.NotificationHub
import com.aether.tv.platform.SystemShortcut
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.AppTile
import com.aether.tv.ui.common.GroupLabel
import com.aether.tv.ui.common.MediaCard
import com.aether.tv.ui.common.OptionRow
import com.aether.tv.ui.common.TvTextField
import com.aether.tv.ui.common.formatTime
import com.aether.tv.ui.common.icon
import com.aether.tv.ui.media.short
import com.aether.tv.ui.settings.SettingsCategory
import com.aether.tv.ui.settings.notificationAdb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------------------------------------ Quick controls
@Composable
fun QuickControlsPanel(state: LauncherUiState, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    val entries = listOf(
        SystemShortcut.Network to state.network.label,
        SystemShortcut.Bluetooth to "${state.devices.peripherals.peers.count { it.connected }} connected",
        SystemShortcut.Sound to null,
        SystemShortcut.Display to null,
        SystemShortcut.Accessibility to null,
        SystemShortcut.StartScreensaver to null,
        SystemShortcut.AllSettings to null,
    )
    SidePanel("Quick Controls", "Opens the TV's own settings — Aether doesn't fake system toggles", initial = first) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                OptionRow("Inputs", {
                    vm.dismissOverlay()
                    val r = vm.inputs.openInputsFallback()
                    if (r is com.aether.tv.platform.InputSwitchResult.Failed) vm.show(Overlay.Message("Inputs", r.reason))
                }, icon = AetherIcons.Hdmi, detail = "${state.devices.inputs.size} reported by this TV", focusRequester = first)
            }
            items(entries, key = { it.first.name }) { (s, detail) ->
                val ok = remember { vm.shortcuts.isAvailable(s) }
                OptionRow(s.label, { vm.dismissOverlay(); vm.openSystem(s) }, icon = if (s == SystemShortcut.Network) state.network.icon() else s.icon,
                    detail = if (!ok) "Not available on this TV" else detail ?: s.description, enabled = ok)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Notifications
@Composable
fun NotificationsPanel(state: LauncherUiState, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    val context = LocalContext.current
    SidePanel("Notifications", if (state.notificationAccess) "${state.notifications.size} active" else "Access not granted", initial = first) {
        if (!state.notificationAccess) {
            AText("Aether can only show notifications after you grant notification access. Nothing is shown until then.", Aether.type.body, color = Aether.colors.textSecondary, maxLines = 5)
            Spacer(Modifier.height(12.dp))
            OptionRow("Grant access", {
                if (vm.shortcuts.isAvailable(SystemShortcut.NotificationAccess)) vm.openSystem(SystemShortcut.NotificationAccess)
                else vm.show(Overlay.Message("Notification access", notificationAdb(context.packageName)))
            }, icon = AetherIcons.Bell, focusRequester = first, chevron = true)
        } else if (state.notifications.isEmpty()) {
            EmptyState("You're all caught up", null, AetherIcons.Bell, Modifier.fillMaxWidth())
            OptionRow("Close", { vm.dismissOverlay() }, icon = AetherIcons.X, focusRequester = first)
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.notifications, key = { it.key }) { n ->
                val app = state.apps.orEmpty().firstOrNull { it.packageName == n.packageName }
                OptionRow(n.title, {
                    vm.show(Overlay.Choice(n.title, listOfNotNull(app?.let { "Open ${it.label}" }, if (n.clearable) "Dismiss" else null), 0) { i ->
                        if (app != null && i == 0) vm.launchApp(app) else NotificationHub.dismiss(n.key)
                    })
                }, icon = AetherIcons.BellRing, detail = listOfNotNull(app?.label, n.text, formatTime(n.postedAt, android.text.format.DateFormat.is24HourFormat(context))).joinToString(" • "),
                    focusRequester = if (n == state.notifications.first()) first else null)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Search
private data class SettingHit(val title: String, val detail: String, val action: () -> Unit)

@Composable
fun SearchScreen(state: LauncherUiState, vm: LauncherViewModel) {
    var query by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val context = LocalContext.current
    val appsOnly = state.settings.behavior.defaultSearch == SearchScope.Apps
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it }
    }
    val voiceIntent = remember { Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM) }
    val hasVoice = remember { voiceIntent.resolveActivity(context.packageManager) != null }

    val apps = remember(query, state.apps) {
        if (query.isBlank()) emptyList() else state.apps.orEmpty().filter { !it.hidden }
            .map { it to AppRepository.matchScore(it.label, query) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.take(16)
    }
    val other = remember(query, state) {
        if (query.isBlank() || appsOnly) emptyList() else buildList {
            state.devices.devices.filter { AppRepository.matchScore(it.name, query) > 0 }.forEach { d -> add(SettingHit(d.name, "Device • ${d.status.label}") { vm.activateDevice(d) }) }
            state.games.filter { AppRepository.matchScore(it.title, query) > 0 }.forEach { g -> add(SettingHit(g.title, "Game shortcut") { vm.playGame(g) }) }
            state.favorites.filter { it.type != FavoriteType.App && AppRepository.matchScore(it.title ?: "", query) > 0 }.forEach { f -> add(SettingHit(f.title!!, "Favorite") { vm.openFavorite(f) }) }
            SettingsCategory.entries.filter { AppRepository.matchScore(it.title + " " + it.subtitle, query) > 0 }.forEach { c ->
                add(SettingHit("Settings ▸ ${c.title}", c.subtitle) { vm.openSettings(c.key) })
            }
            SystemShortcut.entries.filter { AppRepository.matchScore(it.label + " " + it.description, query) > 0 && vm.shortcuts.isAvailable(it) }
                .forEach { s -> add(SettingHit(s.label, "System • ${s.description}") { vm.openSystem(s) }) }
        }
    }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    LaunchedEffect(query) {
        media = emptyList()
        if (query.length >= 2 && !appsOnly) { delay(450); media = runCatching { vm.mediaRepo.search(query) }.getOrDefault(emptyList()) }
    }

    Scrim(0.78f) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AText("Search", Aether.type.display)
                Spacer(Modifier.width(24.dp))
                TvTextField(query, { query = it }, if (appsOnly) "Search apps" else "Apps, games, devices, settings, media", Modifier.weight(1f),
                    icon = AetherIcons.Search, imeAction = ImeAction.Search, focusRequester = field, startEditing = true)
                if (hasVoice) {
                    Spacer(Modifier.width(10.dp))
                    GlassTile(onClick = { runCatching { voice.launch(voiceIntent) } }, shape = Aether.shapes.pill, scaleOnFocus = false) {
                        Box(Modifier.padding(10.dp)) { AIcon(AetherIcons.Mic, size = 18.dp) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (query.isBlank()) {
                AText("Type with the on-screen keyboard" + if (hasVoice) " or use the microphone." else ".", Aether.type.body, color = Aether.colors.textSecondary)
            } else if (apps.isEmpty() && other.isEmpty() && media.isEmpty()) {
                EmptyState("No results for “$query”", "Try a shorter name.", AetherIcons.Search, Modifier.fillMaxWidth().padding(top = 40.dp))
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (apps.isNotEmpty()) {
                    item { GroupLabel("Apps") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(apps, key = { it.key }) { a -> AppTile(a, { vm.dismissOverlay(); vm.launchApp(a) }, { vm.onAppLongPress(a, null) }, Modifier.width(96.dp), height = 66.dp) }
                        }
                    }
                }
                if (media.isNotEmpty()) {
                    item { GroupLabel("From your media sources") }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(media, key = { it.providerId + it.id }) { m -> MediaCard(m, { vm.dismissOverlay(); vm.resumeMedia(m) }, null, Modifier.width(190.dp).height(96.dp)) }
                        }
                    }
                }
                if (other.isNotEmpty()) {
                    item { GroupLabel("Devices, games & settings") }
                    items(other.size) { i -> val h = other[i]; OptionRow(h.title, { vm.dismissOverlay(); h.action() }, detail = h.detail, minHeight = 40.dp) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Provider setup
@Composable
fun ProviderSetupSheet(o: Overlay.ProviderSetup, state: LauncherUiState, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    val repo = vm.mediaRepo
    val provider = repo.provider(o.providerId) ?: return
    val status = state.media.providers.firstOrNull { it.provider.id == provider.id }?.status ?: ProviderStatus.NotConfigured
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var link by remember { mutableStateOf<LinkCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    fun done(msg: String) { vm.dismissOverlay(); vm.notice(msg); vm.refreshMedia() }
    fun fail(t: Throwable) { busy = false; vm.notice("Couldn't connect: ${t.message ?: t.javaClass.simpleName}") }
    val askTvListings = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) done("Continue Watching from apps on this TV is on") else vm.notice("TV listings access wasn't allowed")
    }

    // Device-link polling (Plex PIN / Jellyfin Quick Connect) — every 3 s for up to 5 minutes.
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        repeat(100) {
            delay(3000)
            val ok = runCatching {
                when (provider) { is PlexProvider -> provider.pollLink(l.pollToken); is JellyfinProvider -> provider.pollQuickConnect(l.pollToken); else -> false }
            }.getOrDefault(false)
            if (ok) { done("${provider.displayName} connected"); return@LaunchedEffect }
        }
        link = null; vm.notice("The code expired. Try again.")
    }

    DialogPanel(provider.displayName, provider.description, width = 480.dp, icon = { SmallIcon(AetherIcons.Server) }, initial = first) {
        AText("Status: ${status.short()}" + ((status as? ProviderStatus.Connected)?.detail?.let { " — $it" } ?: (status as? ProviderStatus.Error)?.message?.let { " — $it" } ?: ""),
            Aether.type.caption, color = Aether.colors.textSecondary, maxLines = 3)
        AText("Provides: " + provider.capabilities.joinToString { it.label.lowercase() }, Aether.type.caption, color = Aether.colors.textTertiary, maxLines = 2)
        Spacer(Modifier.height(12.dp))
        val l = link
        if (l != null) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AText(l.code, Aether.type.display.copy(letterSpacing = Aether.type.display.fontSize * 0.2f), align = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                AText(l.instructions, Aether.type.body, color = Aether.colors.textSecondary, maxLines = 3, align = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                AText("Waiting for approval…", Aether.type.caption, color = Aether.colors.textTertiary)
                Spacer(Modifier.height(10.dp))
                OptionRow("Cancel", { link = null }, icon = AetherIcons.X, focusRequester = first, minHeight = 38.dp)
            }
            return@DialogPanel
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 320.dp)) {
            when (provider) {
                is com.aether.tv.data.media.TvWatchNextProvider -> item {
                    val granted = provider.hasAccess()
                    OptionRow(if (granted) "Access allowed" else "Allow TV listings access", {
                        if (granted) done("Continue Watching refreshed") else askTvListings.launch(com.aether.tv.data.media.TvWatchNextProvider.PERMISSION)
                    }, icon = AetherIcons.Tv, focusRequester = first,
                        detail = "Reads only what apps publish to Android TV for its home screen: Watch Next and “Continue watching” channels.")
                }
                is MediaSessionProvider -> item {
                    OptionRow(if (state.notificationAccess) "Access granted" else "Grant notification access", {
                        if (vm.shortcuts.isAvailable(SystemShortcut.NotificationAccess)) vm.openSystem(SystemShortcut.NotificationAccess)
                        else vm.show(Overlay.Message("Notification access", notificationAdb(context.packageName)))
                    }, icon = AetherIcons.Bell, focusRequester = first, detail = "Reads only what apps publish as their media session — not a viewing history.")
                }
                is PlexProvider -> {
                    item { OptionRow("Link with plex.tv/link", { busy = true; scope.launch { runCatching { provider.startLink() }.onSuccess { link = it; busy = false }.onFailure(::fail) } },
                        icon = AetherIcons.Link, detail = "Recommended: enter a short code on your phone", focusRequester = first) }
                    item { GroupLabel("Or enter manually") }
                    item { TvTextField(server, { server = it }, "Server address (e.g. 192.168.1.10:32400)", Modifier.fillMaxWidth(), icon = AetherIcons.Server, keyboardType = KeyboardType.Uri) }
                    item { TvTextField(pass, { pass = it }, "X-Plex-Token", Modifier.fillMaxWidth(), icon = AetherIcons.Lock, password = true) }
                    item { OptionRow("Save", { if (server.isBlank() || pass.isBlank()) vm.notice("Enter the server and token") else { provider.setManual(server, pass); done("Plex saved") } }, icon = AetherIcons.Check, minHeight = 38.dp) }
                }
                is JellyfinProvider -> {
                    item { TvTextField(server, { server = it }, "Server address (e.g. 192.168.1.10:8096)", Modifier.fillMaxWidth(), icon = AetherIcons.Server, keyboardType = KeyboardType.Uri, focusRequester = first) }
                    item { OptionRow("Use Quick Connect", { if (server.isBlank()) vm.notice("Enter the server address first") else { busy = true; scope.launch { runCatching { provider.startQuickConnect(server) }.onSuccess { link = it; busy = false }.onFailure(::fail) } } },
                        icon = AetherIcons.Link, detail = "Approve with a code from another signed-in device") }
                    item { GroupLabel("Or sign in") }
                    item { TvTextField(user, { user = it }, "Username", Modifier.fillMaxWidth(), icon = AetherIcons.User) }
                    item { TvTextField(pass, { pass = it }, "Password", Modifier.fillMaxWidth(), icon = AetherIcons.Lock, password = true) }
                    item { OptionRow("Sign in", { busy = true; scope.launch { runCatching { provider.signIn(server, user, pass) }.onSuccess { done("Jellyfin connected") }.onFailure(::fail) } }, icon = AetherIcons.Check, minHeight = 38.dp) }
                }
                is KodiProvider -> {
                    item { TvTextField(server, { server = it }, "Kodi address (127.0.0.1:8080 for this TV)", Modifier.fillMaxWidth(), icon = AetherIcons.Server, keyboardType = KeyboardType.Uri, focusRequester = first) }
                    item { TvTextField(user, { user = it }, "Username (if set in Kodi)", Modifier.fillMaxWidth(), icon = AetherIcons.User) }
                    item { TvTextField(pass, { pass = it }, "Password (if set)", Modifier.fillMaxWidth(), icon = AetherIcons.Lock, password = true) }
                    item { OptionRow("Save & test", {
                        if (server.isBlank()) vm.notice("Enter Kodi's address") else {
                            provider.configure(server, user, pass); busy = true
                            scope.launch { val s = provider.status(); busy = false; if (s is ProviderStatus.Connected) done("Kodi connected") else vm.notice((s as? ProviderStatus.Error)?.message ?: "Saved, but Kodi didn't answer") }
                        }
                    }, icon = AetherIcons.Check, detail = "In Kodi: Settings ▸ Services ▸ Control ▸ Allow remote control via HTTP", minHeight = 38.dp) }
                }
            }
            if (provider !is MediaSessionProvider && status !is ProviderStatus.NotConfigured) item {
                OptionRow("Disconnect", {
                    vm.show(Overlay.Confirm("Disconnect ${provider.displayName}?", "Removes the stored server and credentials from this TV.", "Disconnect") {
                        when (provider) { is PlexProvider -> provider.disconnect(); is JellyfinProvider -> provider.disconnect(); is KodiProvider -> provider.disconnect() }
                        vm.refreshMedia()
                    })
                }, icon = AetherIcons.Trash)
            }
        }
        if (busy) AText("Working…", Aether.type.caption, color = Aether.colors.textTertiary, modifier = Modifier.padding(top = 8.dp))
    }
}
