package com.aether.tv.ui.overlays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.AppIcon
import com.aether.tv.core.designsystem.icon
import com.aether.tv.core.model.AppInfo
import com.aether.tv.core.model.DeviceKind
import com.aether.tv.core.model.DeviceLaunchAction
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.core.model.GameKind
import com.aether.tv.data.db.DeviceEntity
import com.aether.tv.data.db.GameShortcutEntity
import com.aether.tv.platform.SystemShortcut
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.MoveScope
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.ChoiceRow
import com.aether.tv.ui.common.OptionRow
import com.aether.tv.ui.common.ToggleRow
import com.aether.tv.ui.common.TvTextField
import com.aether.tv.ui.common.AppTile

// ------------------------------------------------------------------------------------------------ App options
@Composable
fun AppOptionsSheet(o: Overlay.AppOptions, state: LauncherUiState, vm: LauncherViewModel) {
    val app = state.appsByKey[o.appKey] ?: run { LaunchedEffect(Unit) { vm.dismissOverlay() }; return }
    val first = remember { FocusRequester() }
    val fav = state.favorites.firstOrNull { it.type == FavoriteType.App && it.targetKey == app.key }
    val options = buildList {
        add(MenuOption("Open", AetherIcons.CirclePlay) { vm.launchApp(app) })
        add(MenuOption(if (fav != null) "Remove from Favorites" else "Add to Favorites", if (fav != null) AetherIcons.StarOff else AetherIcons.Star) { vm.toggleFavoriteApp(app) })
        if (fav != null && o.moveScope != null) add(MenuOption("Move", AetherIcons.ArrowLeftRight, "Reorder with ◀ ▶") { vm.startMove(fav.id, o.moveScope) })
        add(MenuOption("Rename", AetherIcons.Pencil, app.customLabel?.let { "Original: ${app.systemLabel}" }) {
            vm.show(Overlay.TextInput("Rename ${app.systemLabel}", app.label, "Leave empty to restore the original name") { vm.renameApp(app, it) })
        })
        add(MenuOption("Change icon", AetherIcons.Image) { vm.show(iconChooser(app, vm)) })
        add(MenuOption("Category", AetherIcons.Tag, app.category ?: "None") {
            val opts = listOf("None") + state.categories + "New category…"
            vm.show(Overlay.Choice("Category for ${app.label}", opts, app.category?.let { state.categories.indexOf(it) + 1 } ?: 0) { i ->
                when (i) {
                    0 -> vm.setAppCategory(app, null)
                    opts.lastIndex -> vm.show(Overlay.TextInput("New category", "", null) { name -> vm.addCategory(name); vm.setAppCategory(app, name.trim()) })
                    else -> vm.setAppCategory(app, opts[i])
                }
            })
        })
        add(MenuOption(if (app.isGame) "Not a game" else "Mark as game", AetherIcons.Games, app.gameKind?.label) { vm.setGameOverride(app, !app.isGame) })
        add(MenuOption(if (app.hidden) "Unhide" else "Hide", if (app.hidden) AetherIcons.Eye else AetherIcons.EyeOff) { vm.setHidden(app, !app.hidden) })
        add(MenuOption("App info", AetherIcons.Info, "System app settings") { vm.appInfo(app) })
        if (!app.isSystemApp) add(MenuOption("Uninstall", AetherIcons.Trash, "Opens the system uninstaller") { vm.uninstall(app) })
    }
    DialogPanel(app.label, app.packageName, width = 380.dp, icon = { AppIcon(app, 36.dp) }, initial = first) { MenuList(options, vm, first) }
}

private fun iconChooser(app: AppInfo, vm: LauncherViewModel): Overlay = Overlay.Choice(
    "Icon for ${app.label}",
    listOf("Image from web address…", "Original icon"), 0,
) { i ->
    when (i) {
        0 -> vm.show(Overlay.TextInput("Icon image URL", "https://", "PNG or JPEG, square works best") { vm.setAppIconFromUrl(app, it) })
        else -> vm.resetAppIcon(app)
    }
}

// ------------------------------------------------------------------------------------------------ Favorite options
@Composable
fun FavoriteOptionsSheet(o: Overlay.FavoriteOptions, state: LauncherUiState, vm: LauncherViewModel) {
    val f = state.favorites.firstOrNull { it.id == o.favoriteId } ?: run { LaunchedEffect(Unit) { vm.dismissOverlay() }; return }
    val first = remember { FocusRequester() }
    val options = listOf(
        MenuOption("Open", AetherIcons.CirclePlay) { vm.openFavorite(f) },
        MenuOption("Move", AetherIcons.ArrowLeftRight, "Reorder with ◀ ▶") { vm.startMove(f.id, MoveScope.AllFavorites) },
        MenuOption("Rename", AetherIcons.Pencil) { vm.show(Overlay.TextInput("Name", f.title ?: "", "Leave empty for the default name") { t -> vm.editFavorite(f, t.ifBlank { null }, f.artworkUri, f.group) }) },
        MenuOption("Artwork from web address", AetherIcons.Image) { vm.show(Overlay.TextInput("Artwork URL", "https://", "Landscape images look best") { vm.setFavoriteArtFromUrl(f, it) }) },
        MenuOption("Reset artwork", AetherIcons.RotateCcw, enabled = f.artworkUri != null) { vm.editFavorite(f, f.title, null, f.group) },
        MenuOption("Remove from Favorites", AetherIcons.StarOff) { vm.removeFavorite(f.id) },
    )
    DialogPanel(f.title ?: "Favorite", f.type.name, width = 380.dp, icon = { SmallIcon(AetherIcons.Star) }, initial = first) { MenuList(options, vm, first) }
}

// ------------------------------------------------------------------------------------------------ Devices
@Composable
fun DeviceOptionsSheet(o: Overlay.DeviceOptions, state: LauncherUiState, vm: LauncherViewModel) {
    val d = state.device(o.deviceId) ?: run { LaunchedEffect(Unit) { vm.dismissOverlay() }; return }
    val first = remember { FocusRequester() }
    val isFav = state.favorites.any { it.type == FavoriteType.Device && it.targetKey == d.id }
    val options = buildList {
        add(MenuOption(if (d.launchAction == DeviceLaunchAction.SwitchInput) "Switch to ${d.name}" else "Open", AetherIcons.CirclePlay) { vm.activateDevice(d) })
        if (d.configured) {
            add(MenuOption("Edit", AetherIcons.Pencil, "Name, input, picture, action") { vm.show(Overlay.DeviceEditor(d.id)) })
            add(MenuOption(if (d.artworkUri != null) "Change picture" else "Add picture", AetherIcons.Image, "A real photo of your device") {
                vm.show(devicePicturePicker(vm, d.artworkUri != null) { uri -> vm.setDevicePicture(d.id, uri) })
            })
            add(MenuOption(if (isFav) "Remove from Favorites" else "Add to Favorites", if (isFav) AetherIcons.StarOff else AetherIcons.Star) { vm.toggleFavorite(FavoriteType.Device, d.id) })
            d.networkHost?.let { h -> add(MenuOption("Check network", AetherIcons.Globe, h) { vm.probeHost(h) }) }
            add(MenuOption("Delete", AetherIcons.Trash) { vm.show(Overlay.Confirm("Delete ${d.name}?", "Only the shortcut is removed.", "Delete") { vm.deleteDevice(d.id) }) })
        } else {
            add(MenuOption("Create shortcut", AetherIcons.Plus, "Name it, pin it to Home") { vm.show(Overlay.DeviceEditor(null, presetBluetooth = d.bluetoothAddress, presetName = d.name)) })
            add(MenuOption("Bluetooth settings", AetherIcons.Bluetooth) { vm.openSystem(SystemShortcut.Bluetooth) })
        }
    }
    DialogPanel(d.name, "${d.detail} • ${d.status.label}", width = 380.dp, icon = { AIcon(d.kind.icon(), size = 24.dp) }, initial = first) { MenuList(options, vm, first) }
}

@Composable
fun DeviceEditor(o: Overlay.DeviceEditor, state: LauncherUiState, vm: LauncherViewModel) {
    var loaded by remember { mutableStateOf(o.deviceId == null) }
    var e by remember {
        mutableStateOf(
            DeviceEntity(
                id = o.deviceId ?: vm.newDeviceId(), name = o.presetName ?: "",
                kind = (if (o.presetBluetooth != null) DeviceKind.Headphones else DeviceKind.Xbox).name,
                inputId = o.presetInputId, bluetoothAddress = o.presetBluetooth,
                launchAction = if (o.presetBluetooth != null) DeviceLaunchAction.None.name else DeviceLaunchAction.SwitchInput.name,
                hdmiPort = o.presetInputId?.let { id -> state.devices.inputs.firstOrNull { it.id == id }?.hdmiPort },
                sortOrder = state.devices.devices.count { it.configured },
            ),
        )
    }
    LaunchedEffect(o.deviceId) { if (o.deviceId != null) { vm.deviceEntity(o.deviceId)?.let { e = it }; loaded = true } }
    if (!loaded) return
    val first = remember { FocusRequester() }
    val inputs = state.devices.inputs
    val peers = state.devices.peripherals.peers
    val action = runCatching { DeviceLaunchAction.valueOf(e.launchAction) }.getOrDefault(DeviceLaunchAction.None)
    val kind = runCatching { DeviceKind.valueOf(e.kind) }.getOrDefault(DeviceKind.Other)
    DialogPanel(if (o.deviceId == null) "Add device" else "Edit ${e.name}", "Shortcuts never claim a connection Aether can't verify", width = 480.dp, initial = first) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 360.dp)) {
            item { TvTextField(e.name, { e = e.copy(name = it) }, "Name (e.g. Xbox Series X)", Modifier.fillMaxWidth(), icon = AetherIcons.Pencil, focusRequester = first) }
            item { ChoiceRow("Type", DeviceKind.entries, kind, { it.label }, { e = e.copy(kind = it.name) }, icon = kind.icon()) }
            item { ChoiceRow("When selected", DeviceLaunchAction.entries, action, { when (it) { DeviceLaunchAction.SwitchInput -> "Switch to its input"; DeviceLaunchAction.OpenApp -> "Open an app"; DeviceLaunchAction.None -> "Show status only" } }, { e = e.copy(launchAction = it.name) }, icon = AetherIcons.CirclePlay) }
            if (action == DeviceLaunchAction.SwitchInput) {
                item {
                    val labels = listOf("Not assigned") + inputs.map { "${it.label} (${it.state.label})" }
                    val sel = inputs.indexOfFirst { it.id == e.inputId } + 1
                    OptionRow("TV input", {
                        if (inputs.isEmpty()) vm.show(Overlay.Message("No inputs reported", "This TV doesn't list its HDMI inputs for apps. Set the HDMI port number below as a label; selecting the device will open the TV's own input screen."))
                        else vm.show(Overlay.Choice("Input for ${e.name.ifBlank { "device" }}", labels, sel) { i -> e = e.copy(inputId = inputs.getOrNull(i - 1)?.id, hdmiPort = inputs.getOrNull(i - 1)?.hdmiPort ?: e.hdmiPort) })
                    }, icon = AetherIcons.Hdmi, detail = inputs.firstOrNull { it.id == e.inputId }?.label ?: if (inputs.isEmpty()) "None reported by this TV" else "Not assigned", chevron = true)
                }
                item {
                    val ports = listOf<Int?>(null, 1, 2, 3, 4)
                    ChoiceRow("HDMI port label", ports, e.hdmiPort, { it?.let { p -> "HDMI $p" } ?: "None" }, { e = e.copy(hdmiPort = it) }, detail = "Shown on the card; used when the TV doesn't report inputs")
                }
            }
            if (action == DeviceLaunchAction.OpenApp) item {
                val app = state.apps.orEmpty().firstOrNull { it.packageName == e.launchPackage }
                OptionRow("App to open", { vm.show(Overlay.AppPicker("Choose app", gamesFirst = true) { a -> e = e.copy(launchPackage = a.packageName) }) }, icon = AetherIcons.AppWindow, detail = app?.label ?: "None", chevron = true)
            }
            item {
                val labels = listOf("None") + peers.map { it.name }
                OptionRow("Bluetooth device", {
                    if (peers.isEmpty()) vm.show(Overlay.Message("No paired devices", "Pair the device in system Bluetooth settings first (and allow Aether to see paired devices in Settings ▸ Devices)."))
                    else vm.show(Overlay.Choice("Bluetooth device", labels, peers.indexOfFirst { it.address == e.bluetoothAddress } + 1) { i -> e = e.copy(bluetoothAddress = peers.getOrNull(i - 1)?.address) })
                }, icon = AetherIcons.Bluetooth, detail = peers.firstOrNull { it.address == e.bluetoothAddress }?.name ?: "None — status comes from the input", chevron = true)
            }
            item { TvTextField(e.networkHost.orEmpty(), { e = e.copy(networkHost = it.trim().ifEmpty { null }) }, "Network address (optional, e.g. 192.168.1.20)", Modifier.fillMaxWidth(), icon = AetherIcons.Globe) }
            item {
                OptionRow("Picture", { vm.show(devicePicturePicker(vm, e.artworkUri != null) { uri -> e = e.copy(artworkUri = uri) }) }, icon = AetherIcons.Image,
                    detail = if (e.artworkUri != null) "Your photo is set" else "Add a real photo of your ${e.name.ifBlank { "device" }}", chevron = true,
                    trailing = e.artworkUri?.let { u -> { com.aether.tv.ui.common.DevicePhoto(u, Modifier.height(34.dp).width(44.dp)) } })
            }
            item { ToggleRow("Show on Home", e.showOnHome, { e = e.copy(showOnHome = it) }, icon = AetherIcons.Home) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { OptionRow("Cancel", { vm.dismissOverlay() }, icon = AetherIcons.X, minHeight = 38.dp) }
                    Box(Modifier.weight(1f)) {
                        OptionRow("Save", {
                            if (e.name.isBlank()) vm.notice("Give the device a name") else { vm.dismissOverlay(); vm.saveDevice(e) }
                        }, icon = AetherIcons.Check, minHeight = 38.dp)
                    }
                }
            }
        }
    }
}

/**
 * Choose a real photo for a device: download from a web address, pick a file (if the TV has a picker), or use an
 * image copied to the app's devices folder. [onPicked] receives a local "file:" uri, or null to remove the picture.
 */
fun devicePicturePicker(vm: LauncherViewModel, hasPicture: Boolean, onPicked: (String?) -> Unit): Overlay {
    val files = vm.devicePictureFiles()
    val builtins = com.aether.tv.ui.common.BuiltinDevicePhoto.entries
    val options = buildList {
        builtins.forEach { add(it.label) }
        add("From a web address…")
        add("From storage (file picker)…")
        add("From the TV folder" + if (files.isEmpty()) " — empty" else " (${files.size})")
        if (hasPicture) add("Remove picture")
    }
    return Overlay.Choice("Device picture", options, 0) { idx ->
        if (idx < builtins.size) { onPicked("builtin:${builtins[idx].id}"); return@Choice }
        val i = idx - builtins.size
        when (i) {
            0 -> vm.show(Overlay.TextInput("Image address", "https://", "Direct link to a JPG, PNG or WebP photo. It's downloaded once and stored on the TV.") { url ->
                vm.importPictureFromUrl(url) { uri -> if (uri != null) onPicked(uri) }
            })
            1 -> vm.pendingPicturePick.value = onPicked
            2 -> if (files.isEmpty()) vm.show(Overlay.Message("TV folder is empty", "Copy a photo to:\n\n${vm.devicePictureFolder().path}\n\nfor example:\nadb push xbox.png ${vm.devicePictureFolder().path}/\n\nthen choose it here."))
                 else vm.show(Overlay.Choice("Choose a photo", files.map { it.name }, 0) { j -> onPicked("file:${files[j].path}") })
            else -> onPicked(null)
        }
    }
}

// ------------------------------------------------------------------------------------------------ Games
@Composable
fun GameEditor(o: Overlay.GameEditor, state: LauncherUiState, vm: LauncherViewModel) {
    val existing = state.games.firstOrNull { it.id == o.gameId }
    var g by remember { mutableStateOf(existing ?: GameShortcutEntity(vm.newGameId(), state.profileId, "", kind = GameKind.Console.name, sortOrder = state.games.size)) }
    val first = remember { FocusRequester() }
    val kind = runCatching { GameKind.valueOf(g.kind) }.getOrDefault(GameKind.Console)
    val consoles = state.devices.devices.filter { it.configured }
    DialogPanel(if (existing == null) "Add game" else "Edit ${g.title}", "Point a game at a console input or an app", width = 480.dp, initial = first) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 360.dp)) {
            item { TvTextField(g.title, { g = g.copy(title = it) }, "Title (e.g. Elden Ring)", Modifier.fillMaxWidth(), icon = AetherIcons.Pencil, focusRequester = first) }
            item { ChoiceRow("Played", GameKind.entries, kind, { it.label }, { k -> g = g.copy(kind = k.name, deviceId = if (k == GameKind.Console) g.deviceId else null, appKey = if (k == GameKind.Console) null else g.appKey) }, icon = AetherIcons.Games) }
            if (kind == GameKind.Console) item {
                OptionRow("Console", {
                    if (consoles.isEmpty()) vm.show(Overlay.Message("No devices", "Add your console in Settings ▸ Devices first."))
                    else vm.show(Overlay.Choice("Console", consoles.map { it.name }, consoles.indexOfFirst { it.id == g.deviceId }) { i -> g = g.copy(deviceId = consoles[i].id) })
                }, icon = AetherIcons.Hdmi, detail = consoles.firstOrNull { it.id == g.deviceId }?.name ?: "Choose a device", chevron = true)
            } else item {
                OptionRow("App", { vm.show(Overlay.AppPicker("Choose app", gamesFirst = true) { a -> g = g.copy(appKey = a.key) }) }, icon = AetherIcons.AppWindow,
                    detail = state.appsByKey[g.appKey]?.label ?: "Choose an installed or streaming app", chevron = true)
            }
            item { TvTextField(g.artworkUri?.takeIf { it.startsWith("http") }.orEmpty(), { g = g.copy(artworkUri = it.trim().ifEmpty { null }) }, "Artwork URL (optional, downloaded once)", Modifier.fillMaxWidth(), icon = AetherIcons.Image) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { OptionRow("Cancel", { vm.dismissOverlay() }, icon = AetherIcons.X, minHeight = 38.dp) }
                    Box(Modifier.weight(1f)) {
                        OptionRow("Save", {
                            when {
                                g.title.isBlank() -> vm.notice("Give the game a title")
                                g.deviceId == null && g.appKey == null -> vm.notice("Choose a console or an app")
                                else -> {
                                    vm.dismissOverlay()
                                    val url = g.artworkUri?.takeIf { it.startsWith("http") }
                                    vm.saveGame(g.copy(artworkUri = if (url != null) existing?.artworkUri else g.artworkUri))
                                    if (url != null) vm.setGameArtFromUrl(g.copy(artworkUri = null), url)
                                }
                            }
                        }, icon = AetherIcons.Check, minHeight = 38.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun GameOptionsSheet(o: Overlay.GameOptions, state: LauncherUiState, vm: LauncherViewModel) {
    val g = state.games.firstOrNull { it.id == o.gameId } ?: run { LaunchedEffect(Unit) { vm.dismissOverlay() }; return }
    val first = remember { FocusRequester() }
    val isFav = state.favorites.any { it.type == FavoriteType.Game && it.targetKey == g.id }
    DialogPanel(g.title, runCatching { GameKind.valueOf(g.kind).label }.getOrNull(), width = 380.dp, icon = { SmallIcon(AetherIcons.Games) }, initial = first) {
        MenuList(listOf(
            MenuOption("Play", AetherIcons.CirclePlay) { vm.playGame(g) },
            MenuOption("Edit", AetherIcons.Pencil) { vm.show(Overlay.GameEditor(g.id)) },
            MenuOption(if (isFav) "Remove from Favorites" else "Add to Favorites", if (isFav) AetherIcons.StarOff else AetherIcons.Star) { vm.toggleFavorite(FavoriteType.Game, g.id) },
            MenuOption("Delete", AetherIcons.Trash) { vm.show(Overlay.Confirm("Delete ${g.title}?", "Only the shortcut is removed.", "Delete") { vm.deleteGame(g.id) }) },
        ), vm, first)
    }
}

// ------------------------------------------------------------------------------------------------ Media
@Composable
fun MediaOptionsSheet(o: Overlay.MediaOptions, state: LauncherUiState, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    val provider = vm.mediaRepo.provider(o.item.providerId)
    DialogPanel(o.item.title, listOfNotNull(o.item.subtitle, provider?.displayName).joinToString(" • "), width = 400.dp, icon = { SmallIcon(AetherIcons.Film) }, initial = first) {
        MenuList(buildList {
            add(MenuOption("Resume", AetherIcons.CirclePlay) { vm.resumeMedia(o.item) })
            provider?.appPackage?.let { pkg -> add(MenuOption("Open ${provider.displayName}", AetherIcons.AppWindow) { state.apps.orEmpty().firstOrNull { it.packageName == pkg }?.let { vm.launchApp(it) } ?: vm.notice("${provider.displayName} app isn't installed") }) }
            add(MenuOption("Hide Continue Watching", AetherIcons.EyeOff, "Settings ▸ Home Layout") { vm.updateSettings { s -> s.copy(layout = s.layout.copy(sections = s.layout.sections.map { if (it.section == com.aether.tv.core.model.HomeSection.ContinueWatching) it.copy(visible = false) else it })) } })
        }, vm, first)
    }
}

// ------------------------------------------------------------------------------------------------ Add favorite & pickers
@Composable
fun AddFavoriteSheet(state: LauncherUiState, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    DialogPanel("Add Favorite", "What would you like to pin?", width = 380.dp, icon = { SmallIcon(AetherIcons.CirclePlus) }, initial = first) {
        MenuList(listOf(
            MenuOption("App", AetherIcons.AppsGrid) { vm.show(Overlay.AppPicker("Add app to Favorites") { a -> vm.addFavorite(FavoriteType.App, a.key) }) },
            MenuOption("Game shortcut", AetherIcons.Games, if (state.games.isEmpty()) "Create one on the Games tab" else "${state.games.size} available", enabled = state.games.isNotEmpty()) {
                vm.show(Overlay.Choice("Game", state.games.map { it.title }, 0) { i -> vm.addFavorite(FavoriteType.Game, state.games[i].id, art = state.games[i].artworkUri) })
            },
            MenuOption("HDMI input", AetherIcons.Hdmi, if (state.devices.inputs.isEmpty()) "No inputs reported by this TV" else "${state.devices.inputs.size} inputs", enabled = state.devices.inputs.isNotEmpty()) {
                val ins = state.devices.inputs
                vm.show(Overlay.Choice("Input", ins.map { it.label }, 0) { i -> vm.addFavorite(FavoriteType.Input, ins[i].id, ins[i].label) })
            },
            MenuOption("Device", AetherIcons.Tv, enabled = state.devices.devices.any { it.configured }) {
                val ds = state.devices.devices.filter { it.configured }
                vm.show(Overlay.Choice("Device", ds.map { it.name }, 0) { i -> vm.addFavorite(FavoriteType.Device, ds[i].id) })
            },
            MenuOption("System setting", AetherIcons.Settings, "Wi-Fi, display, sound, Live TV…") { vm.show(Overlay.SystemShortcutPicker { s -> vm.addFavorite(FavoriteType.System, s.name) }) },
        ), vm, first)
    }
}

@Composable
fun AppPickerSheet(o: Overlay.AppPicker, state: LauncherUiState, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    val apps = state.apps.orEmpty().filter { !it.hidden }.sortedWith(compareBy<AppInfo>({ if (o.gamesFirst) !it.isGame else false }, { it.label.lowercase() }))
    DialogPanel(o.title, width = 640.dp, initial = first) {
        LazyVerticalGrid(GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.heightIn(max = 340.dp)) {
            itemsIndexed(apps, key = { _, a -> a.key }) { i, a ->
                AppTile(a, { vm.dismissOverlay(); o.onPick(a) }, {}, height = 64.dp, focusRequester = if (i == 0) first else null)
            }
        }
    }
}

@Composable
fun SystemPickerSheet(o: Overlay.SystemShortcutPicker, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    val list = remember { SystemShortcut.entries.filter { vm.shortcuts.isAvailable(it) } }
    DialogPanel("System setting", "Only screens this TV can open are listed", width = 420.dp, initial = first) {
        MenuList(list.map { s -> MenuOption(s.label, s.icon, s.description) { o.onPick(s) } }, vm, first)
    }
}

