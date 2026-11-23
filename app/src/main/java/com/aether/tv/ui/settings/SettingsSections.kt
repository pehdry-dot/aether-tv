package com.aether.tv.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aether.tv.BuildConfig
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.BuiltinWallpaper
import com.aether.tv.core.model.AccentColor
import com.aether.tv.core.model.AnimationLevel
import com.aether.tv.core.model.AppSort
import com.aether.tv.core.model.ClockFormat
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.core.model.FontChoice
import com.aether.tv.core.model.GlassQuality
import com.aether.tv.core.model.IconSize
import com.aether.tv.core.model.LauncherSettings
import com.aether.tv.core.model.LauncherTab
import com.aether.tv.core.model.visibleTabs
import com.aether.tv.core.model.LibraryView
import com.aether.tv.core.model.LongPressAction
import com.aether.tv.core.model.SearchScope
import com.aether.tv.core.model.TabActivation
import com.aether.tv.core.model.ThemeMode
import com.aether.tv.core.model.TileSize
import com.aether.tv.data.media.MediaCapability
import com.aether.tv.platform.EventLog
import com.aether.tv.platform.NotificationHub
import com.aether.tv.platform.SystemShortcut
import com.aether.tv.platform.TvInputController
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.ChoiceRow
import com.aether.tv.ui.common.GroupLabel
import com.aether.tv.ui.common.OptionRow
import com.aether.tv.ui.common.SliderRow
import com.aether.tv.ui.common.ToggleRow
import com.aether.tv.ui.media.short
import android.view.KeyEvent as NativeKeyEvent
import kotlin.math.roundToInt


@Composable
fun SettingsDetail(cat: SettingsCategory, state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        when (cat) {
            SettingsCategory.Overview -> Unit // its own dashboard (SettingsPage)
            SettingsCategory.Appearance -> appearance(state, vm, first)
            SettingsCategory.HomeLayout -> homeLayout(state, vm, first)
            SettingsCategory.Behavior -> behavior(state, vm, first)
            SettingsCategory.Applications -> applications(state, vm, first)
            SettingsCategory.Devices -> devices(state, vm, first)
            SettingsCategory.TvSystem -> tvSystem(state, vm, first)
            SettingsCategory.Integrations -> integrations(state, vm, first)
            SettingsCategory.Profiles -> profiles(state, vm, first)
            SettingsCategory.Updates -> item("updates") { UpdatesSection(vm, first) }
            SettingsCategory.Advanced -> advanced(state, vm, first)
        }
    }
}

private fun LauncherViewModel.appearance(t: (com.aether.tv.core.model.AppearanceSettings) -> com.aether.tv.core.model.AppearanceSettings) =
    updateSettings { it.copy(appearance = t(it.appearance)) }
private fun LauncherViewModel.layout(t: (com.aether.tv.core.model.HomeLayoutSettings) -> com.aether.tv.core.model.HomeLayoutSettings) =
    updateSettings { it.copy(layout = t(it.layout)) }
private fun LauncherViewModel.behavior(t: (com.aether.tv.core.model.BehaviorSettings) -> com.aether.tv.core.model.BehaviorSettings) =
    updateSettings { it.copy(behavior = t(it.behavior)) }
private fun LauncherViewModel.library(t: (com.aether.tv.core.model.AppLibrarySettings) -> com.aether.tv.core.model.AppLibrarySettings) =
    updateSettings { it.copy(library = t(it.library)) }

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

// ------------------------------------------------------------------------------------------------ Appearance
private fun LazyListScope.appearance(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    val a = state.settings.appearance
    item("wp") {
        OptionRow("Wallpaper", { vm.show(Overlay.WallpaperPicker) }, icon = AetherIcons.Image, detail = wallpaperLabel(a.wallpaper) + " • preview before applying",
            chevron = true, focusRequester = first)
    }
    item("g1") { GroupLabel("Theme") }
    item("theme") { ChoiceRow("Theme", ThemeMode.entries, a.theme, { it.name }, { v -> vm.appearance { it.copy(theme = v) } }, icon = AetherIcons.Palette) }
    item("accent") { ChoiceRow("Accent color", AccentColor.entries, a.accent, { it.label }, { v -> vm.appearance { it.copy(accent = v) } }, icon = AetherIcons.Square) }
    item("g2") { GroupLabel("Glass & blur") }
    item("quality") { ChoiceRow("Glass rendering", GlassQuality.entries, a.glassQuality, { it.label }, { v -> vm.appearance { it.copy(glassQuality = v) } }, icon = AetherIcons.Layers,
        detail = if (vm.platform.isLowRam) "This TV reports limited memory — “Flat tint” is lightest." else "Blur is pre-computed once, so it costs almost nothing per frame.") }
    item("opacity") { SliderRow("Glass opacity", a.glassOpacity, 0f..0.4f, 0.02f, { v -> vm.appearance { it.copy(glassOpacity = v) } }, ::pct) }
    item("blur") { SliderRow("Blur intensity", a.blurRadiusDp.toFloat(), 0f..40f, 2f, { v -> vm.appearance { it.copy(blurRadiusDp = v.roundToInt()) } }, { "${it.roundToInt()} dp" }) }
    item("border") { SliderRow("Border intensity", a.borderIntensity, 0f..0.4f, 0.02f, { v -> vm.appearance { it.copy(borderIntensity = v) } }, ::pct) }
    item("dim") { SliderRow("Background dimming", a.backgroundDim, 0f..0.8f, 0.05f, { v -> vm.appearance { it.copy(backgroundDim = v) } }, ::pct) }
    item("radius") { SliderRow("Corner radius", a.cornerRadiusDp.toFloat(), 0f..28f, 2f, { v -> vm.appearance { it.copy(cornerRadiusDp = v.roundToInt()) } }, { "${it.roundToInt()} dp" }) }
    item("g3") { GroupLabel("Focus") }
    item("glow") { SliderRow("Focus glow", a.focusGlow, 0f..1f, 0.05f, { v -> vm.appearance { it.copy(focusGlow = v) } }, ::pct, detail = "0% shows only the thin focus border") }
    item("scale") { SliderRow("Focus zoom", a.focusScale, 1f..1.1f, 0.01f, { v -> vm.appearance { it.copy(focusScale = v) } }, { "${((it - 1f) * 100).roundToInt()}%" }) }
    item("g4") { GroupLabel("Icons & text") }
    item("logos") { ChoiceRow("App logos", com.aether.tv.core.model.AppLogoStyle.entries, a.appLogos, { it.label }, { v -> vm.appearance { it.copy(appLogos = v) } }, icon = AetherIcons.AppWindow, detail = "White logos sit in a glass dock on Home") }
    item("icon") { ChoiceRow("Icon size", IconSize.entries, a.iconSize, { it.label }, { v -> vm.appearance { it.copy(iconSize = v) } }, icon = AetherIcons.AppsGrid) }
    item("labels") { ToggleRow("Show app names", a.showAppLabels, { v -> vm.appearance { it.copy(showAppLabels = v) } }, icon = AetherIcons.Tag) }
    item("banners") { ToggleRow("Use app banners", state.settings.library.useBanners, { v -> vm.library { it.copy(useBanners = v) } }, detail = "Wide TV banners instead of icons, where apps provide them", icon = AetherIcons.AppWindow) }
    item("typography") { OptionRow("Typography", { vm.show(Overlay.Typography) }, icon = AetherIcons.TextSize, detail = "${a.font.label} • ${pct(a.textScale)} text size", chevron = true) }
    item("g5") { GroupLabel("Motion") }
    item("anim") { ChoiceRow("Animations", AnimationLevel.entries, a.animation, { it.label }, { v -> vm.appearance { it.copy(animation = v) } }, icon = AetherIcons.Move, detail = "Reduced or Off also disables focus zoom") }
    item("animWp") { ToggleRow("Animated wallpaper", a.animatedWallpaper, { v -> vm.appearance { it.copy(animatedWallpaper = v) } }, detail = "Very slow drift. Uses a little GPU continuously.", icon = AetherIcons.Sun) }
}

// ------------------------------------------------------------------------------------------------ Home layout
private fun LazyListScope.homeLayout(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    val l = state.settings.layout
    item("g0") { GroupLabel("Home sections — OK shows/hides, ◀ ▶ reorders") }
    l.sections.forEachIndexed { i, cfg ->
        item("sec-${cfg.section}") {
            OptionRow(
                cfg.section.label,
                { vm.layout { s -> s.copy(sections = s.sections.map { if (it.section == cfg.section) it.copy(visible = !it.visible) else it }) } },
                icon = if (cfg.visible) AetherIcons.Eye else AetherIcons.EyeOff,
                detail = "Position ${i + 1} • " + if (cfg.visible) "Shown" else "Hidden",
                focusRequester = if (i == 0) first else null,
                onKey = { code -> reorderKey(code) { d -> vm.layout { s -> s.copy(sections = s.sections.moved(i, d)) } } },
            )
        }
    }
    item("g1") { GroupLabel("Navigation tabs — OK shows/hides, ◀ ▶ reorders") }
    l.tabs.forEachIndexed { i, cfg ->
        item("tab-${cfg.tab}") {
            val locked = cfg.tab == LauncherTab.Home
            val settingsTab = cfg.tab == LauncherTab.Settings
            OptionRow(
                cfg.tab.label,
                { if (!locked) vm.layout { s -> s.copy(tabs = s.tabs.map { if (it.tab == cfg.tab) it.copy(visible = !it.visible) else it }) } },
                icon = if (cfg.visible || locked) AetherIcons.Eye else AetherIcons.EyeOff,
                detail = when {
                    locked -> "Always shown"
                    settingsTab && cfg.visible -> "Shown • the ⚙ button at the top right also opens Settings"
                    settingsTab -> "Hidden • open Settings with the ⚙ button at the top right"
                    cfg.visible -> "Shown"
                    else -> "Hidden"
                },
                stateText = if (cfg.visible || locked) "Shown" else "Hidden",
                onKey = { code -> reorderKey(code) { d -> vm.layout { s -> s.copy(tabs = s.tabs.moved(i, d)) } } },
            )
        }
    }
    item("default") {
        val startTabs = l.visibleTabs()
        ChoiceRow("Start on tab", startTabs, l.defaultTab.takeIf { it in startTabs } ?: LauncherTab.Home, { it.label }, { v -> vm.layout { it.copy(defaultTab = v) } }, icon = AetherIcons.Home,
            detail = "Only tabs that are shown can be the start tab")
    }
    item("g2") { GroupLabel("Rows") }
    item("count") { SliderRow("Favorite app shortcuts", l.appShortcutCount.toFloat(), 4f..12f, 1f, { v -> vm.layout { it.copy(appShortcutCount = v.roundToInt()) } }, { "${it.roundToInt()}" }) }
    item("allApps") { ToggleRow("“All Apps” tile", l.showAllAppsTile, { v -> vm.layout { it.copy(showAllAppsTile = v) } }) }
    item("tile") { ChoiceRow("Tile size", TileSize.entries, l.tileSize, { it.label }, { v -> vm.layout { it.copy(tileSize = v) } }) }
    item("cw") { SliderRow("Continue Watching items", l.continueWatchingLimit.toFloat(), 2f..16f, 1f, { v -> vm.layout { it.copy(continueWatchingLimit = v.roundToInt()) } }, { "${it.roundToInt()}" }) }
    item("dev") { SliderRow("Device cards", l.deviceLimit.toFloat(), 1f..8f, 1f, { v -> vm.layout { it.copy(deviceLimit = v.roundToInt()) } }, { "${it.roundToInt()}" }) }
    item("inputs") { ToggleRow("“Inputs” tile", l.showInputsTile, { v -> vm.layout { it.copy(showInputsTile = v) } }) }
    item("favLayout") { ToggleRow("Grouped Favorites page", l.favoritesGrouped, { v -> vm.layout { it.copy(favoritesGrouped = v) } }, detail = "Off shows all favorites in one simple grid") }
    item("g3") { GroupLabel("Status bar") }
    item("clock") { ToggleRow("Clock", l.showClock, { v -> vm.layout { it.copy(showClock = v) } }, icon = AetherIcons.Clock) }
    item("clockFmt") { ChoiceRow("Clock format", ClockFormat.entries, l.clockFormat, { it.label }, { v -> vm.layout { it.copy(clockFormat = v) } }) }
    item("date") { ToggleRow("Date", l.showDate, { v -> vm.layout { it.copy(showDate = v) } }) }
    item("weather") { WeatherRows(l, vm) }
    item("net") { ToggleRow("Network status", l.showNetworkStatus, { v -> vm.layout { it.copy(showNetworkStatus = v) } }, icon = AetherIcons.Wifi) }
    item("notif") { ToggleRow("Notifications", l.showNotifications, { v -> vm.layout { it.copy(showNotifications = v) } }, icon = AetherIcons.Bell) }
    item("title") { ToggleRow("Page title", l.showPageTitle, { v -> vm.layout { it.copy(showPageTitle = v) } }, icon = AetherIcons.Type) }
}

private inline fun reorderKey(code: Int, move: (Int) -> Unit): Boolean = when (code) {
    NativeKeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); true }
    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
    else -> false
}

fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val target = (index + delta).coerceIn(0, lastIndex)
    if (target == index) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}

// ------------------------------------------------------------------------------------------------ Behavior
private fun LazyListScope.behavior(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    val b = state.settings.behavior
    item("tabs") { ChoiceRow("Switch tabs", TabActivation.entries, b.tabActivation, { it.label }, { v -> vm.behavior { it.copy(tabActivation = v) } }, icon = AetherIcons.Move, focusRequester = first) }
    item("restore") { ToggleRow("Remember focus in rows", b.restoreFocus, { v -> vm.behavior { it.copy(restoreFocus = v) } }, detail = "Return to the item you last used when re-entering a row") }
    item("back") { ToggleRow("Back returns to Home tab", b.backGoesHome, { v -> vm.behavior { it.copy(backGoesHome = v) } }) }
    item("long") { ChoiceRow("Long-press OK", LongPressAction.entries, b.longPressAction, { it.label }, { v -> vm.behavior { it.copy(longPressAction = v) } }) }
    item("menu") { ToggleRow("Menu (☰) key opens options", b.menuKeyOpensOptions, { v -> vm.behavior { it.copy(menuKeyOpensOptions = v) } }) }
    item("search") { ChoiceRow("Search looks in", SearchScope.entries, b.defaultSearch, { it.label }, { v -> vm.behavior { it.copy(defaultSearch = v) } }, icon = AetherIcons.Search) }
    item("g1") { GroupLabel("Screen") }
    item("scale") { SliderRow("Interface scale", b.uiScale, 0.85f..1.15f, 0.05f, { v -> vm.behavior { it.copy(uiScale = v) } }, ::pct) }
    item("overscan") { SliderRow("Overscan margin", b.overscanDp.toFloat(), 0f..48f, 4f, { v -> vm.behavior { it.copy(overscanDp = v.roundToInt()) } }, { "${it.roundToInt()} dp" }, detail = "Increase if the edges are cut off on your TV") }
    item("g2") { GroupLabel("Startup & Home button") }
    item("home") { HomeStatusRow(vm) }
    if (vm.platform.isFireTv) item("homeSvc") { HomeServiceRow(vm) }
    item("boot") { BootRow(state, vm) }
    item("_f") { OptionRow("Reset this profile's settings", { vm.show(Overlay.Confirm("Reset settings?", "Appearance, layout and behavior return to defaults. Favorites and devices are kept.", "Reset") { vm.resetProfileSettings() }) }, icon = AetherIcons.RotateCcw, focusRequester = null) }
}

@Composable
private fun HomeStatusRow(vm: LauncherViewModel) {
    val isHome = remember { vm.platform.isDefaultHome() }
    val fire = vm.platform.isFireTv
    val amazonOff = remember { vm.shortcuts.amazonLauncherDisabled() }
    OptionRow(
        when {
            fire && amazonOff && isHome -> "Aether is the Home app (Amazon's home is off)"
            isHome -> "Aether is the Home app"
            else -> "Aether isn't the default Home app"
        },
        {
            if (fire) vm.show(Overlay.Message("Home button on Fire TV", FIRE_HOME_EXPLAINER))
            else if (vm.shortcuts.isAvailable(SystemShortcut.HomeApp)) vm.openSystem(SystemShortcut.HomeApp)
            else vm.show(Overlay.Message("Default Home app", ANDROID_TV_HOME_EXPLAINER))
        },
        icon = AetherIcons.Home,
        detail = when {
            fire && amazonOff && isHome -> "Home opens Aether directly. The full Fire TV Settings menu is off with Amazon's home."
            isHome -> "Pressing Home on the remote opens Aether."
            fire -> "Fire OS sends Home to Amazon's home first. Select for options."
            else -> "Select to choose the Home app, where this TV allows it."
        },
        chevron = true,
    )
}

/** Fire TV only: the accessibility service that brings Aether back after Amazon's home shows. */
@Composable
private fun HomeServiceRow(vm: LauncherViewModel) {
    val context = LocalContext.current
    val paused by vm.homeRedirectPaused.collectAsState()
    val enabled = remember { com.aether.tv.platform.HomeButtonService.isEnabled(context) }
    val amazonOff = remember { vm.shortcuts.amazonLauncherDisabled() }
    if (!enabled) {
        OptionRow(
            "Home button returns to Aether: off",
            { vm.show(Overlay.Message("Turn on the Home button service", HOME_SERVICE_EXPLAINER)) },
            icon = AetherIcons.Home,
            detail = if (amazonOff) "Not needed while Amazon's home is switched off." else "Fire TV has no screen for this; it is turned on from a computer. Select for the commands.",
            chevron = true,
        )
    } else {
        ToggleRow(
            "Home button returns to Aether", !paused, { v -> vm.setHomeRedirectPaused(!v) },
            detail = when {
                amazonOff -> "On, but idle: Amazon's home is switched off, so Home opens Aether directly."
                paused -> "Paused: Home shows Amazon's home screen."
                else -> "After Amazon's home appears, Aether comes back (about a second). Paused while you use Fire TV Settings."
            },
            icon = AetherIcons.Home,
        )
    }
}

const val FIRE_HOME_EXPLAINER = "Fire OS sends the Home button to Amazon's own home screen and ignores the usual “default Home app” setting.\n\n" +
    "Two ways around it on this TV (both set up from a computer with adb):\n" +
    "• Amazon's home switched off (“Aether only”): Home opens Aether directly, no Amazon screen at all. The full Fire TV Settings menu goes with it; " +
    "Network, Bluetooth, Applications and Device stay reachable from Aether, Picture/Sound/Inputs from the remote's ⚙ button. Switch with scripts/launcher-mode.sh.\n" +
    "• Aether's Home button service: Amazon's home appears for a moment, then Aether comes back. Keeps the full Settings menu.\n\n" +
    "Both can stop working after a Fire OS update."

const val HOME_SERVICE_EXPLAINER = "Fire TV has no Accessibility screen for this service. Turn it on once from a computer:\n\n" +
    com.aether.tv.platform.HomeButtonService.ENABLE_COMMANDS +
    "\n\nIt only sees which app window is showing — never screen content — and you can pause it here at any time."

const val ANDROID_TV_HOME_EXPLAINER = "This TV doesn't expose a Home-app chooser to apps. On many Google TV devices the stock launcher can only be replaced via adb (see KNOWN_LIMITATIONS.md). Aether keeps working as a normal app either way."

@Composable
private fun BootRow(state: LauncherUiState, vm: LauncherViewModel) {
    val context = LocalContext.current
    val canOverlay = remember(state.settings.behavior.launchOnBoot) { Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context) }
    ToggleRow(
        "Open Aether after boot", state.settings.behavior.launchOnBoot,
        { v ->
            vm.behavior { it.copy(launchOnBoot = v) }
            if (v && !canOverlay) {
                if (vm.shortcuts.isAvailable(SystemShortcut.OverlayPermission)) vm.openSystem(SystemShortcut.OverlayPermission)
                else vm.show(Overlay.Message("Permission needed", "Android 10+ only lets apps open themselves after boot with “Display over other apps”. This TV has no screen for it; grant it with:\n\nadb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"))
            }
        },
        detail = if (canOverlay) "Brings Aether to the front when the TV starts." else "Needs “Display over other apps” permission on this Android version (not granted).",
        icon = AetherIcons.Power,
    )
}

// ------------------------------------------------------------------------------------------------ Applications
private fun LazyListScope.applications(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    val lib = state.settings.library
    item("sort") { ChoiceRow("Sort apps", AppSort.entries, lib.sort, { it.label }, { v -> vm.library { it.copy(sort = v) } }, icon = AetherIcons.List, focusRequester = first) }
    item("view") { ChoiceRow("Library view", LibraryView.entries, lib.view, { it.label }, { v -> vm.library { it.copy(view = v) } }, icon = AetherIcons.AppsGrid) }
    item("hidden") { ToggleRow("Show hidden apps in library", lib.showHidden, { v -> vm.library { it.copy(showHidden = v) } }, icon = AetherIcons.Eye) }
    item("nontv") { ToggleRow("Show phone/tablet apps", lib.showNonTvApps, { v -> vm.library { it.copy(showNonTvApps = v) } }, detail = "Many sideloaded apps only have a phone launcher entry") }
    item("g1") { GroupLabel("Categories") }
    state.categories.forEach { c ->
        item("cat-$c") {
            val n = state.apps.orEmpty().count { it.category == c }
            OptionRow(c, { vm.show(Overlay.Confirm("Delete “$c”?", "Apps in this category are kept; they just lose the category.", "Delete") { vm.deleteCategory(c) }) }, icon = AetherIcons.Tag, detail = "$n apps • Select to delete")
        }
    }
    item("newcat") { OptionRow("New category", { vm.show(Overlay.TextInput("Category name", "", "e.g. Kids, Sports, Music") { vm.addCategory(it) }) }, icon = AetherIcons.Plus, detail = "Assign apps with long-press ▸ Category") }
    val hidden = state.apps.orEmpty().filter { it.hidden }
    item("g2") { GroupLabel("Hidden apps (${hidden.size})") }
    hidden.forEach { app -> item("h-${app.key}") { OptionRow(app.label, { vm.setHidden(app, false) }, icon = AetherIcons.EyeOff, detail = "Select to unhide") } }
    item("g3") { GroupLabel("Favorites") }
    item("fav") { OptionRow("Add a favorite", { vm.show(Overlay.AddFavorite()) }, icon = AetherIcons.Star, detail = "${state.favorites.size} favorites") }
}

// ------------------------------------------------------------------------------------------------ Devices
private fun LazyListScope.devices(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    val o = state.devices
    item("add") { OptionRow("Add device", { vm.show(Overlay.DeviceEditor(null)) }, icon = AetherIcons.Plus, detail = "Console, PC, media player or Bluetooth device", focusRequester = first) }
    item("g1") { GroupLabel("Your devices") }
    o.devices.filter { it.configured }.forEach { d ->
        item("d-${d.id}") { OptionRow(d.name, { vm.show(Overlay.DeviceOptions(d.id)) }, icon = AetherIcons.Games.takeIf { d.kind.name in setOf("Xbox", "PlayStation", "NintendoSwitch") } ?: AetherIcons.Monitor, detail = "${d.detail} • ${d.status.label}", chevron = true) }
    }
    item("g2") { GroupLabel("TV inputs") }
    if (!o.inputFrameworkAvailable) item("noinputs") { OptionRow("TV input list not available", { vm.show(Overlay.Message("TV inputs", INPUTS_EXPLAINER)) }, icon = AetherIcons.Info, detail = "This device doesn't expose HDMI inputs to apps. Device shortcuts still open the closest system screen.") }
    else if (o.inputs.isEmpty()) item("emptyinputs") { OptionRow("No HDMI inputs reported", { vm.show(Overlay.Message("TV inputs", INPUTS_EXPLAINER)) }, icon = AetherIcons.Info, detail = "The TV input service returned no HDMI inputs to Aether.") }
    o.inputs.forEach { input ->
        item("in-${input.id}") {
            val owner = o.devices.firstOrNull { it.inputId == input.id && it.configured }
            OptionRow(
                input.label,
                { vm.show(Overlay.Choice(input.label, listOf("Switch to this input", "Create device shortcut", "Add to Favorites"), 0) { i ->
                    when (i) { 0 -> vm.switchInput(input.id); 1 -> vm.show(Overlay.DeviceEditor(null, presetInputId = input.id)); else -> vm.addFavorite(FavoriteType.Input, input.id, input.label) }
                }) },
                icon = AetherIcons.Hdmi,
                detail = listOfNotNull(input.state.label, owner?.let { "Used by ${it.name}" }, TvInputController.parseHardwareIndex(input.id)?.let { "HW$it" }).joinToString(" • "),
                chevron = true,
            )
        }
    }
    item("g3") { GroupLabel("Bluetooth") }
    item("bt") { BluetoothPermissionRow(state, vm) }
    o.peripherals.peers.forEach { p ->
        item("bt-${p.address}") {
            OptionRow(p.name, { vm.show(Overlay.DeviceEditor(null, presetBluetooth = p.address, presetName = p.name)) }, icon = AetherIcons.Bluetooth,
                detail = "${p.kind.label} • ${if (p.connected) "Connected" else "Paired"} • Select to create a shortcut")
        }
    }
    item("btsettings") { OptionRow("Bluetooth settings", { vm.openSystem(SystemShortcut.Bluetooth) }, icon = AetherIcons.Settings, detail = "Pair new remotes, headphones and controllers", chevron = true) }
    item("g4") { GroupLabel("Controllers") }
    if (o.peripherals.controllers.isEmpty()) item("noctrl") { OptionRow("No game controllers attached", {}, icon = AetherIcons.Controller, detail = "Connected controllers appear here and on Home automatically") }
    o.peripherals.controllers.forEach { c -> item("c-${c.descriptor}") { OptionRow(c.name, {}, icon = AetherIcons.Controller, detail = "Connected • vendor ${c.vendorId}, product ${c.productId}") } }
    item("g5") { GroupLabel("Diagnostics") }
    item("diag") {
        val fireSvc = vm.platform.isFireTv && remember { runCatching { vm.getApplication<android.app.Application>().packageManager.getPackageInfo(TvInputController.AMAZON_INPUT_PKG, 0); true }.getOrDefault(false) }
        OptionRow("Input switching method", { vm.show(Overlay.Message("Input switching", INPUTS_EXPLAINER)) }, icon = AetherIcons.Cpu,
            detail = when {
                fireSvc -> "Fire TV input service found; standard passthrough as fallback"
                o.inputFrameworkAvailable -> "Android TV input framework (passthrough)"
                else -> "No direct switching — opens system screens"
            })
    }
}

const val INPUTS_EXPLAINER = "How Aether switches inputs, in order:\n\n" +
    "1. Fire TV: Amazon's input service (not an official API — it may change with updates).\n" +
    "2. Android TV input framework: asks the TV's Live TV app to show the input.\n" +
    "3. If neither is available, Aether opens the Live TV app or Settings so you can pick the input yourself.\n\n" +
    "Aether can't see whether the TV actually switched, so it never reports success it can't verify. Settings ▸ Advanced ▸ Recent events shows what was tried."

@Composable
private fun BluetoothPermissionRow(state: LauncherUiState, vm: LauncherViewModel) {
    val p = state.devices.peripherals
    var asked by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { asked++ }
    when {
        !p.bluetoothAvailable -> OptionRow("Bluetooth not available", {}, icon = AetherIcons.Bluetooth)
        !p.bluetoothPermission -> OptionRow("Allow access to paired devices", { if (Build.VERSION.SDK_INT >= 31) launcher.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }, icon = AetherIcons.Bluetooth,
            detail = "Needed to show headphones and controllers you've paired. Aether never scans for new devices." + if (asked > 0) " (Permission was not granted.)" else "")
        !p.bluetoothEnabled -> OptionRow("Bluetooth is off", { vm.openSystem(SystemShortcut.Bluetooth) }, icon = AetherIcons.Bluetooth, detail = "Turn it on in system settings")
        else -> OptionRow("Bluetooth on", {}, icon = AetherIcons.Bluetooth, detail = "${p.peers.size} paired device(s)")
    }
}

// ------------------------------------------------------------------------------------------------ TV & System
private fun LazyListScope.tvSystem(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    item("storage") {
        OptionRow("Storage", { vm.show(Overlay.Storage) }, icon = AetherIcons.HardDrive, focusRequester = first, chevron = true,
            detail = "Free space, Aether's cache, backups and pictures, app info — works with Amazon's home switched off")
    }
    item("qc") { OptionRow("Quick Controls", { vm.show(Overlay.QuickControls) }, icon = AetherIcons.SlidersHorizontal, detail = "Also opens from the network icon on the top bar", chevron = true) }
    item("inputs") { OptionRow("Inputs", { vm.inputs.openInputsFallback().let { r -> if (r is com.aether.tv.platform.InputSwitchResult.Failed) vm.show(Overlay.Message("Inputs", r.reason)) } }, icon = AetherIcons.Hdmi, detail = "Open the TV's own input selection", chevron = true) }
    item("g-remote") { GroupLabel("Remote shortcuts") }
    item("gesture") {
        OptionRow("Picture & sound while watching", { vm.show(Overlay.Message("TV settings from any app or HDMI", REMOTE_GESTURE)) }, icon = AetherIcons.Tv,
            detail = "Press ⚙ on the remote (or hold Home) over Nuvio, YouTube or the Xbox", chevron = true)
    }
    item("panel") {
        val ok = remember { com.aether.tv.platform.TvQuickSettings.intent(vm.shortcuts) != null }
        OptionRow("Open the TV's quick panel now", { vm.openTvQuickPanel() }, icon = AetherIcons.SlidersHorizontal, chevron = ok,
            detail = if (ok) "Found on this TV — it opens over Aether; from an app, use ⚙ or the shortcut below" else "Not found for apps on this TV; ⚙ on the remote still opens it")
    }
    item("holdMenu") { HoldMenuRow(vm) }
    item("remoteTest") { OptionRow("Test the remote's buttons", { vm.show(Overlay.RemoteTest) }, icon = AetherIcons.Joystick, detail = "Shows which buttons reach Aether", chevron = true) }
    item("g1") { GroupLabel("System screens — long-press to add to Favorites") }
    SystemShortcut.entries.filter { it !in setOf(SystemShortcut.NotificationAccess, SystemShortcut.OverlayPermission, SystemShortcut.HomeApp) }.forEach { s ->
        item("s-${s.name}") { SystemRow(s, vm) }
    }
}

@Composable
private fun SystemRow(s: SystemShortcut, vm: LauncherViewModel) {
    val available = remember { vm.shortcuts.isAvailable(s) }
    // With Amazon's launcher switched off ("Aether only"), its Settings screens are gone; say why and how to get them back.
    val offWithAmazonHome = !available && s in com.aether.tv.platform.SystemShortcuts.LAUNCHER_HOSTED && remember { vm.shortcuts.amazonLauncherDisabled() }
    OptionRow(
        s.label, { vm.openSystem(s) }, icon = s.icon,
        detail = when {
            available -> s.description
            offWithAmazonHome -> "Off while Amazon's home screen is off — select for details"
            else -> "Not available on this TV"
        },
        enabled = available || offWithAmazonHome, chevron = available,
    )
}

const val REMOTE_GESTURE = "While an app (Nuvio, YouTube…) or an HDMI input (the Xbox on HDMI 1) is on screen:\n\n" +
    "1. Press ⚙ (Settings) on the remote once. Fire OS opens its quick panel on the right, over the picture. " +
    "If ⚙ does nothing, hold Home for about two seconds: on Fire TV smart TVs that opens the same panel.\n" +
    "2. Choose Picture (or Sound). On HDMI 1 the change applies to HDMI 1: Fire OS keeps picture settings per input.\n" +
    "3. Press Back until the panel closes. You're back in the app, or on the Xbox picture.\n\n" +
    "Aether's Home button service leaves this panel alone. With the shortcut below on, holding ☰ (Menu) for a second opens it too.\n\n" +
    "How sure is this? ⚙ and held Home are Amazon's documented gestures for Fire TV smart TVs. They haven't been tried on this TV " +
    "with Amazon's home switched off: use \"Test the remote's buttons\" and \"Open the TV's quick panel now\" to check."

@Composable
private fun HoldMenuRow(vm: LauncherViewModel) {
    val context = LocalContext.current
    val on by vm.keyShortcuts.collectAsState()
    val service = remember { com.aether.tv.platform.HomeButtonService.isEnabled(context) }
    if (!service) OptionRow("Hold ☰ anywhere for the TV's quick panel: needs the Home button service", { vm.show(Overlay.Message("Turn on the Home button service", HOME_SERVICE_EXPLAINER)) },
        icon = AetherIcons.Keyboard, chevron = true, detail = "Android only lets an accessibility service see buttons in other apps. Select for how to turn it on.")
    else ToggleRow("Hold ☰ anywhere for the TV's quick panel", on, { vm.setKeyShortcuts(it) }, icon = AetherIcons.Keyboard,
        detail = "A short ☰ press still goes to the app; Home, Back, volume and playback are never touched. The service sees key codes only.")
}

// ------------------------------------------------------------------------------------------------ Integrations
private fun LazyListScope.integrations(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    item("notif") { NotificationAccessRow(state, vm, first) }
    item("g1") { GroupLabel("Media sources") }
    state.media.providers.forEachIndexed { _, ps ->
        item("p-${ps.provider.id}") {
            val src = state.media.sources[ps.provider.id]
            val now = remember(src) { System.currentTimeMillis() }
            OptionRow(ps.provider.displayName, { vm.show(Overlay.ProviderSetup(ps.provider.id)) }, icon = if (MediaCapability.NowPlaying in ps.provider.capabilities) AetherIcons.CirclePlay else AetherIcons.Server,
                detail = listOfNotNull(src?.let { sourceSummary(it, now) } ?: ps.status.short(), sourceScope(ps.provider.id)).joinToString("\n"), chevron = true)
        }
    }
    item("refreshAll") {
        OptionRow("Refresh Continue Watching", { vm.mediaRepo.refresh(force = true); vm.notice("Refreshing every source…") }, icon = AetherIcons.RefreshCw,
            detail = "Home also refreshes by itself: apps on this TV as they publish, servers when Aether comes to the front")
    }
    item("g2") { GroupLabel("Privacy") }
    item("privacy") { OptionRow("No ads, no analytics", {}, icon = AetherIcons.Shield, detail = "Aether sends nothing anywhere except to the media servers you connect and, with Weather on, the weather city to Open-Meteo. Credentials are encrypted with this TV's keystore and excluded from backups.") }
}

@Composable
private fun NotificationAccessRow(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    val context = LocalContext.current
    val granted = state.notificationAccess || NotificationHub.isAccessGranted(context)
    OptionRow(
        "Notification & media access", {
            if (granted) vm.show(Overlay.Message("Notification access", "Granted. Aether shows notifications under the bell icon and what's playing under Continue Watching. Revoke it in the TV's system settings."))
            else if (vm.shortcuts.isAvailable(SystemShortcut.NotificationAccess)) vm.openSystem(SystemShortcut.NotificationAccess)
            else vm.show(Overlay.Message("Notification access", notificationAdb(context.packageName)))
        },
        icon = AetherIcons.Bell, focusRequester = first, chevron = true,
        detail = if (granted) "Granted" else "Not granted — needed for notifications and Now Playing",
    )
}

/** One line for Diagnostics: what a source said last, how many items it gave, how fresh they are, and any error. */
fun sourceSummary(s: com.aether.tv.data.media.SourceState, now: Long): String {
    fun ago(t: Long): String {
        val sec = ((now - t) / 1000).coerceAtLeast(0)
        return when { sec < 60 -> "${sec} s ago"; sec < 3600 -> "${sec / 60} min ago"; sec < 86_400 -> "${sec / 3600} h ago"; else -> "${sec / 86_400} d ago" }
    }
    val status = when (val st = s.status) {
        is com.aether.tv.data.media.ProviderStatus.NotConfigured -> return "Not set up"
        is com.aether.tv.data.media.ProviderStatus.NeedsPermission -> return "Permission needed: ${st.detail}"
        is com.aether.tv.data.media.ProviderStatus.Connecting -> if (s.lastAttemptAt == 0L) return "Not read yet" else "Reading…"
        is com.aether.tv.data.media.ProviderStatus.Connected -> "Allowed"
        is com.aether.tv.data.media.ProviderStatus.Error -> "Unreachable"
    }
    val items = if (s.lastSuccessAt > 0) "${s.items.size} item${if (s.items.size == 1) "" else "s"} • read ${ago(s.lastSuccessAt)}" else "never read successfully"
    val err = s.lastError?.let { " • last error: $it${if (s.lastSuccessAt > 0) " (showing the last good read)" else ""}" }.orEmpty()
    return "$status • $items$err" + if (s.refreshing) " • refreshing…" else ""
}

private fun sourceScope(id: String): String? = when (id) {
    com.aether.tv.data.media.PlexProvider.ID, com.aether.tv.data.media.JellyfinProvider.ID, com.aether.tv.data.media.KodiProvider.ID -> "Account shared by every profile on this TV"
    else -> null
}

fun notificationAdb(pkg: String) = "This TV has no settings screen for notification access. You can grant it once from a computer with adb:\n\n" +
    "adb shell cmd notification allow_listener $pkg/com.aether.tv.platform.AetherNotificationListener\n\n" +
    "Aether then shows notifications and media sessions. Some low-memory TVs ignore this permission entirely."

// ------------------------------------------------------------------------------------------------ Profiles
private fun LazyListScope.profiles(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    state.profiles.forEachIndexed { i, p ->
        item("pr-${p.id}") {
            val active = p.id == state.profileId
            OptionRow(
                p.name, {
                    vm.show(Overlay.Choice(p.name, listOfNotNull(if (!active) "Switch to this profile" else null, "Rename", if (p.pinHash == null) "Set PIN" else "Remove PIN", if (p.id != "default") "Delete profile" else null), 0) { idx ->
                        val opts = listOfNotNull(if (!active) "switch" else null, "rename", "pin", if (p.id != "default") "delete" else null)
                        when (opts[idx]) {
                            "switch" -> vm.switchProfile(p.id)
                            "rename" -> vm.show(Overlay.TextInput("Profile name", p.name, null) { vm.renameProfile(p.id, it) })
                            "pin" -> if (p.pinHash == null) vm.show(Overlay.TextInput("New PIN (4–8 digits)", "", "Required to switch to this profile", password = true) { pin -> if (pin.length in 4..8 && pin.all(Char::isDigit)) vm.setProfilePin(p.id, pin) else vm.notice("PIN must be 4–8 digits") }) else vm.setProfilePin(p.id, null)
                            "delete" -> vm.show(Overlay.Confirm("Delete ${p.name}?", "Its favorites, layout and settings are removed. Devices are shared and kept.", "Delete") { vm.deleteProfile(p.id) })
                        }
                    })
                },
                icon = if (p.pinHash != null) AetherIcons.Lock else AetherIcons.User,
                detail = listOfNotNull(if (active) "Active" else null, if (p.pinHash != null) "PIN protected" else null).joinToString(" • ").ifEmpty { "Select for options" },
                focusRequester = if (i == 0) first else null, chevron = true,
            )
        }
    }
    item("add") { OptionRow("Add profile", { vm.show(Overlay.TextInput("Profile name", "", "Starts with a copy of the current profile's settings") { vm.createProfile(it) }) }, icon = AetherIcons.Plus) }
    item("note") { OptionRow("About profiles", {}, icon = AetherIcons.Info, detail = "Each profile has its own favorites, wallpaper, layout, theme and hidden apps. Profiles are stored on this TV only.") }
}

// ------------------------------------------------------------------------------------------------ Advanced
private fun LazyListScope.advanced(state: LauncherUiState, vm: LauncherViewModel, first: FocusRequester) {
    item("g1") { GroupLabel("Backup & restore") }
    item("export") { OptionRow("Back up to TV storage", { vm.exportBackup() }, icon = AetherIcons.Download, detail = "Profiles, settings, favorites, devices and their pictures (no passwords)", focusRequester = first) }
    item("exportPick") { BackupPickerRows(vm) }
    item("restore") {
        OptionRow("Restore a backup from TV storage", {
            val files = vm.backup.listBackups()
            if (files.isEmpty()) vm.show(Overlay.Message("No backups found", "Backups are stored in:\n${vm.backup.backupDir.path}\n\nCopy a backup there with adb push, then try again."))
            else vm.show(Overlay.Choice("Restore which backup?", files.map { "${it.name} • ${it.length() / 1024} KB" }, 0) { i ->
                vm.show(Overlay.Confirm("Restore ${files[i].name}?", "The backup is checked first; if it's damaged nothing changes. Then it replaces all profiles, favorites, devices, settings and pictures. Your current data is kept so you can undo.", "Restore") { vm.restoreBackup(files[i]) })
            })
        }, icon = AetherIcons.Upload)
    }
    item("undoRestore") {
        OptionRow("Undo the last restore", { vm.show(Overlay.Confirm("Undo the last restore?", "Puts back the profiles, settings and pictures you had just before the last restore.", "Undo") { vm.undoRestore() }) },
            icon = AetherIcons.RotateCcw, enabled = remember { vm.backup.preRestoreBackup.exists() }, detail = "Available after a restore")
    }
    item("g2") { GroupLabel("Permissions") }
    item("perm") {
        val ctx = LocalContext.current
        val lines = listOf(
            "Notification access: " + if (state.notificationAccess) "granted" else "not granted",
            "Paired Bluetooth devices: " + if (state.devices.peripherals.bluetoothPermission) "allowed" else "not allowed",
            "Display over other apps: " + if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(ctx)) "allowed" else "not allowed",
            "Default Home app: " + if (vm.platform.isDefaultHome()) "yes" else "no",
        )
        OptionRow("Permission status", {}, icon = AetherIcons.Shield, detail = lines.joinToString("\n"))
    }
    item("g3") { GroupLabel("Diagnostics") }
    item("diag") {
        val entries = remember { vm.platform.diagnostics() }
        OptionRow("Device & platform", { vm.show(Overlay.Message("Diagnostics", entries.joinToString("\n") { "${it.label}: ${it.value}" })) }, icon = AetherIcons.Cpu,
            detail = entries.take(5).joinToString("\n") { "${it.label}: ${it.value}" }, chevron = true)
    }
    item("events") { OptionRow("Recent events", { vm.show(Overlay.Message("Recent events", EventLog.recent().ifEmpty { listOf("Nothing logged yet.") }.joinToString("\n"))) }, icon = AetherIcons.List, detail = "Launch failures and input-switch attempts", chevron = true) }
    item("latency") { RemoteResponseRow(vm) }
    item("prewarm") {
        val on by vm.prewarm.collectAsState()
        ToggleRow("Build pages in advance", on, { vm.setPrewarm(it) }, icon = AetherIcons.Layers,
            detail = "Prepares the other tabs while the remote is idle, so a first visit doesn't pause. Off: each tab is built on its first visit.")
    }
    item("g4") { GroupLabel("About") }
    item("version") { OptionRow("Aether TV ${BuildConfig.VERSION_NAME}", { vm.show(Overlay.Message("Licenses", LICENSES)) }, icon = AetherIcons.Info, detail = "Build ${BuildConfig.VERSION_CODE} • Select for open-source licenses") }
}

@Composable
private fun BackupPickerRows(vm: LauncherViewModel) {
    val context = LocalContext.current
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.exportBackupTo(it) } }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { u -> vm.show(Overlay.Confirm("Restore this backup?", "The backup is checked first; if it's damaged nothing changes. Then it replaces all profiles, favorites, devices, settings and pictures.", "Restore") { vm.restoreBackupFrom(u) }) } }
    val hasPicker = remember { Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").resolveActivity(context.packageManager) != null }
    if (hasPicker) {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OptionRow("Export backup to a file…", { create.launch("aether-backup.zip") }, icon = AetherIcons.Folder, detail = "For example to a USB drive: it survives reinstalling Aether")
            OptionRow("Restore from a file…", { open.launch(arrayOf("application/zip", "application/json", "*/*")) }, icon = AetherIcons.Folder)
        }
    }
}

const val LICENSES = "Aether TV is built with AndroidX, Jetpack Compose, Hilt/Dagger, Room, DataStore, Coil, OkHttp and kotlinx (Apache License 2.0).\n\n" +
    "Inter typeface © The Inter Project Authors — SIL Open Font License 1.1.\n" +
    "Icons derived from Lucide (lucide.dev) — ISC License.\n" +
    "Wallpapers are original procedurally generated artwork made for Aether.\n" +
    "The Xbox Series X photo is taken from the owner's own design references for personal use; Xbox is a trademark of Microsoft.\n\n" +
    "App names and icons shown belong to their respective owners and are read from the apps installed on this TV."

/** Status bar ▸ Weather: on/off with the current reading, the city, and the unit. */
@Composable
private fun WeatherRows(l: com.aether.tv.core.model.HomeLayoutSettings, vm: LauncherViewModel) {
    val w by vm.weather.collectAsState()
    val autoCity = remember { com.aether.tv.data.weather.WeatherRules.cityFromTimeZone(java.util.TimeZone.getDefault().id) }
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        ToggleRow(
            "Weather", l.showWeather, { v -> vm.layout { it.copy(showWeather = v) } }, icon = AetherIcons.CloudSun,
            detail = when {
                !l.showWeather -> "Shows the weather next to the date"
                w != null -> "${w!!.fullPlace}: ${w!!.kind.label.lowercase()}, ${w!!.temperature}°${if (w!!.fahrenheit) "F" else "C"} · from Open-Meteo, no account"
                else -> "Looking up the weather…"
            },
        )
        if (l.showWeather) {
            OptionRow(
                "Weather city", {
                    vm.show(Overlay.TextInput("Weather city", l.weatherCity, "City (e.g. “Cambridge, MA”), or leave empty for ${autoCity ?: "the TV's time zone"}") { v ->
                        vm.layout { it.copy(weatherCity = v.trim()) }
                    })
                },
                icon = AetherIcons.Globe,
                detail = l.weatherCity.ifBlank { "Automatic: ${autoCity ?: "set a city"} (from the TV's time zone)" },
                chevron = true,
            )
            ChoiceRow("Temperature", com.aether.tv.core.model.TemperatureUnit.entries, l.temperatureUnit, { it.label }, { v -> vm.layout { it.copy(temperatureUnit = v) } })
        }
    }
}


/** Advanced ▸ Remote response: measured on this TV, split by whether pages are built in advance. */
@Composable
private fun RemoteResponseRow(vm: LauncherViewModel) {
    var tick by remember { mutableIntStateOf(0) }
    fun line(on: Boolean): String = com.aether.tv.ui.InputLatency.stats(on)?.let { st ->
        "${if (on) "Pages built in advance" else "Built on first visit"}: median ${st.median} ms, 95 % ${st.p95} ms, worst ${st.worst} ms (${st.count} presses)" +
            (st.worstDuringBuild?.let { " • worst while a page was being built: $it ms (${st.builds})" } ?: "")
    } ?: "${if (on) "Pages built in advance" else "Built on first visit"}: no presses measured yet"
    @Suppress("UNUSED_EXPRESSION") tick
    OptionRow("Remote response", { tick++ }, icon = AetherIcons.Cpu, detail = line(true) + "\n" + line(false) + "\nKey press to next frame, measured on this TV. Select to update; switch the option below to compare.", minHeight = 44.dp)
}

/** Shows the last buttons that reached Aether (and, with Remote shortcuts on, the whole system). */
@Composable
fun RemoteTestPanel(vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.aether.tv.platform.RemoteKeyLog.listener = { tick++ }
        onDispose { com.aether.tv.platform.RemoteKeyLog.listener = null }
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val entries = com.aether.tv.platform.RemoteKeyLog.recent().reversed()
    com.aether.tv.ui.overlays.SidePanel("Remote buttons", "Press any button. Back closes this panel.", initial = first) {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (entries.isEmpty()) com.aether.tv.core.designsystem.AText("Nothing yet.", com.aether.tv.core.designsystem.Aether.type.body)
            entries.take(10).forEach { e ->
                com.aether.tv.core.designsystem.AText(
                    "${com.aether.tv.platform.RemoteKeyLog.label(e.code)} (${e.code})${if (e.repeat > 0) " held" else ""} — reached ${e.where}",
                    com.aether.tv.core.designsystem.Aether.type.body,
                )
            }
            com.aether.tv.core.designsystem.AText(
                "If ⚙ opens the TV's panel and doesn't appear here, Fire OS handles it before apps see it — that's expected. \"system\" lines appear only with the ☰ shortcut on.",
                com.aether.tv.core.designsystem.Aether.type.caption, color = com.aether.tv.core.designsystem.Aether.colors.textSecondary, maxLines = 5,
            )
            OptionRow("Clear", { com.aether.tv.platform.RemoteKeyLog.clear(); tick++ }, icon = AetherIcons.RotateCcw, focusRequester = first, minHeight = 38.dp)
        }
    }
}
