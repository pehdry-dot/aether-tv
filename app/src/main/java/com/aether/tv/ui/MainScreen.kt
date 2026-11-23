package com.aether.tv.ui

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.background
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import com.aether.tv.core.designsystem.FocusLayer
import com.aether.tv.core.designsystem.LocalFocusLayer
import com.aether.tv.core.designsystem.tryFocus
import com.aether.tv.core.designsystem.dpadEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.AetherTheme
import com.aether.tv.core.designsystem.GlassPanel
import com.aether.tv.core.designsystem.GlassTile
import com.aether.tv.core.designsystem.InputBehavior
import com.aether.tv.core.designsystem.WallpaperHost
import com.aether.tv.core.model.GlassQuality
import com.aether.tv.core.model.LauncherTab
import com.aether.tv.core.model.LongPressAction
import com.aether.tv.core.model.TabActivation
import com.aether.tv.ui.apps.AppsPage
import com.aether.tv.ui.favorites.FavoritesPage
import com.aether.tv.ui.games.GamesPage
import com.aether.tv.ui.home.HomePage
import com.aether.tv.ui.media.MediaPage
import com.aether.tv.ui.overlays.OverlayHost
import com.aether.tv.ui.settings.SettingsPage
import com.aether.tv.ui.common.TopBar
import kotlinx.coroutines.delay

fun LauncherTab.icon() = when (this) {
    LauncherTab.Home -> AetherIcons.Home
    LauncherTab.Apps -> AetherIcons.AppsGrid
    LauncherTab.Media -> AetherIcons.Media
    LauncherTab.Games -> AetherIcons.Games
    LauncherTab.Favorites -> AetherIcons.Star
    LauncherTab.Settings -> AetherIcons.Settings
}

@Composable
fun LauncherApp(vm: LauncherViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val stack by vm.overlays.collectAsStateWithLifecycle()
    val s = state.settings
    LaunchedEffect(s.behavior) {
        InputBehavior.menuKeyOpensOptions = s.behavior.menuKeyOpensOptions
        InputBehavior.longPressEnabled = s.behavior.longPressAction != LongPressAction.Nothing
    }
    AetherTheme(s.appearance, s.behavior) {
        WallpaperHost(
            spec = s.appearance.wallpaper,
            dim = s.appearance.backgroundDim,
            blurEnabled = s.appearance.glassQuality == GlassQuality.Auto && s.appearance.blurRadiusDp > 0,
            animated = s.appearance.animatedWallpaper,
        ) {
            // The launcher is one focus layer; while any overlay is open nothing in it can take focus, so the remote
            // can't wander behind a dialog. Closing an overlay puts focus back on whatever opened it (OverlayHost).
            val launcherLayer = remember { FocusLayer("launcher") }
            SideEffect { launcherLayer.setActive(stack.isEmpty()) }
            val nav = remember { LauncherNav() }
            CompositionLocalProvider(LocalFocusLayer provides launcherLayer) {
                Box(Modifier.fillMaxSize().focusGroup()) {
                    if (state.ready) LauncherScaffold(state, vm, overlayOpen = stack.isNotEmpty(), nav)
                }
            }
            OverlayHost(stack, state, vm, onNothingToRestore = { nav.focusCurrent() })
            NoticeHost(vm)
            PicturePickerHost(vm)
        }
    }
}

/** Lets the overlay host put focus somewhere sensible when what opened an overlay is gone. */
internal class LauncherNav { var focusCurrent: () -> Unit = {} }

/** Pure navigation rules (unit-tested). */
object NavRules {
    /** Tab shown at start: the profile's start tab when it is visible, else Home. */
    fun startTab(defaultTab: LauncherTab, visible: List<LauncherTab>): LauncherTab = if (defaultTab in visible) defaultTab else LauncherTab.Home

    /**
     * The page to show for [current]. A hidden tab's page can't stay open, except Settings: it is still reachable from
     * the ⚙ button with its tab hidden, so it is shown "detached" from the tab row.
     */
    fun resolve(current: LauncherTab, visible: List<LauncherTab>): LauncherTab =
        if (current in visible || current == LauncherTab.Settings) current else LauncherTab.Home
}

@Composable
private fun LauncherScaffold(state: LauncherUiState, vm: LauncherViewModel, overlayOpen: Boolean, nav: LauncherNav) {
    val layout = state.settings.layout
    val behavior = state.settings.behavior
    val tabs = state.visibleTabs
    var tab by rememberSaveable { mutableStateOf(NavRules.startTab(layout.defaultTab, tabs)) }
    // Last tab that has a place in the tab row: Back from a detached Settings page returns there.
    var previous by rememberSaveable { mutableStateOf(LauncherTab.Home) }
    val resolved = NavRules.resolve(tab, tabs)
    if (resolved != tab) tab = resolved
    val tabRequesters = remember(tabs) { tabs.associateWith { FocusRequester() } }
    val gearRequester = remember { FocusRequester() }
    val contentEntry = remember { FocusRequester() }
    val settingsEntry = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var pendingSettingsFocus by remember { mutableStateOf(false) }
    var homeReset by remember { mutableIntStateOf(0) }
    val moveMode by vm.moveMode.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    val motion = Aether.motion
    val launcherLayer = LocalFocusLayer.current
    val pageLayers = remember { HashMap<LauncherTab, FocusLayer>() }
    val pageEntries = remember { LauncherTab.entries.associateWith { FocusRequester() } }

    /** Pages built so far (kept alive afterwards; see the page container below). */
    val built = remember { mutableStateListOf(tab) }

    fun focusTab(t: LauncherTab): Boolean = tabRequesters[t]?.tryFocus() ?: false

    fun select(t: LauncherTab, focusTab: Boolean) {
        if (t != tab && tab in tabs) previous = tab
        tab = t
        if (!focusTab) return
        if (t in tabs) runCatching { tabRequesters[t]?.requestFocus() }
        else if (t == LauncherTab.Settings) pendingSettingsFocus = true // detached: focus goes into the page itself
    }

    nav.focusCurrent = {
        if (!(tab in tabs && focusTab(tab)) && !(tab == LauncherTab.Settings && settingsEntry.tryFocus())) focusTab(LauncherTab.Home)
    }

    // The Home button while Aether is in front (default Home app, or brought back by the Home button service):
    // overlays and move mode are already closed by the view model; show Home from the top with its first item focused.
    LaunchedEffect(Unit) {
        vm.homePresses.collect {
            select(LauncherTab.Home, focusTab = false)
            homeReset++
            withFrameNanos { }
            withFrameNanos { }
            if (!contentEntry.tryFocus()) focusTab(LauncherTab.Home)
        }
    }
    // Other screens asking for a page (Search ▸ Settings ▸ …, Home ▸ Inputs, Continue Watching setup).
    LaunchedEffect(Unit) {
        vm.navigation.collect { t -> select(t, focusTab = t != LauncherTab.Settings || vm.settingsTarget.value == null) }
    }
    // The ⚙ button with the Settings tab hidden: there is no tab to focus, so focus goes into the page once it's placed.
    LaunchedEffect(pendingSettingsFocus, tab) {
        if (!pendingSettingsFocus || tab != LauncherTab.Settings) return@LaunchedEffect
        repeat(6) { withFrameNanos { }; if (settingsEntry.tryFocus()) { pendingSettingsFocus = false; return@LaunchedEffect } }
        pendingSettingsFocus = false
        gearRequester.tryFocus()
    }

    BackHandler(enabled = !overlayOpen) {
        when {
            moveMode != null -> vm.endMove()
            // From inside a page to its tab; a detached Settings page goes back to the tab you came from.
            contentHasFocus -> if (tab in tabs) focusTab(tab) else select(previous.takeIf { it in tabs } ?: LauncherTab.Home, focusTab = true)
            tab != LauncherTab.Home && behavior.backGoesHome -> select(LauncherTab.Home, focusTab = true)
            vm.platform.isDefaultHome() -> Unit // We are Home: nothing to go back to.
            else -> activity?.moveTaskToBack(true)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Aether.dimens.screenH)
            .padding(top = Aether.dimens.screenTop - 8.dp),
    ) {
        TopBar(
            layout = layout,
            network = state.network,
            hasNotifications = state.notifications.isNotEmpty(),
            onSearch = { vm.show(Overlay.Search) },
            onNotifications = { vm.show(Overlay.Notifications) },
            onNetwork = { vm.show(Overlay.QuickControls) },
            onSettings = { select(LauncherTab.Settings, focusTab = true) },
            weather = vm.weather.collectAsStateWithLifecycle().value,
            settingsOpen = tab == LauncherTab.Settings,
            settingsRequester = gearRequester,
        )
        if (layout.showPageTitle) {
            Spacer(Modifier.height(16.dp))
            AText(tab.label, Aether.type.display, modifier = Modifier.padding(start = 0.dp))
            Spacer(Modifier.height(10.dp))
        } else Spacer(Modifier.height(18.dp))
        NavTabs(
            tabs = tabs,
            selected = tab,
            requesters = tabRequesters,
            activation = behavior.tabActivation,
            onSelect = { select(it, focusTab = false) },
            // OK on the open tab: into the page, at its first item (or the item a row remembers).
            onEnterPage = { pageEntries[tab]?.tryFocus() },
        )
        Spacer(Modifier.height(20.dp))
        // Pages stay composed once built: building one took 0.3–0.45 s on the M450's 32-bit CPU, every tab switch.
        // Hidden pages are composed but not placed, so they're not drawn, D-pad focus search skips them (Compose only
        // considers placed nodes) and they're removed from the accessibility tree. Each page is its own focus layer, so
        // nothing in a hidden page can be given focus either (a delayed request, a restore, a deep link).
        LaunchedEffect(tab) { if (tab !in built) built.add(tab) }
        val pages = if (tab in built) built.toList() else built.toList() + tab
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusGroup(),
        ) {
            pages.forEach { page ->
                key(page) {
                    val active = page == tab
                    val pageLayer = remember { pageLayers.getOrPut(page) { FocusLayer("page ${page.name}", launcherLayer) } }
                    SideEffect { pageLayer.setActive(active) }
                    remember { PagePrewarm.builds[page] = (PagePrewarm.builds[page] ?: 0) + 1 }
                    val alpha = remember { Animatable(if (active) 1f else 0f) }
                    LaunchedEffect(active) {
                        when {
                            !active -> alpha.snapTo(0f)
                            alpha.value >= 1f -> Unit
                            motion.navMs > 0 -> alpha.animateTo(1f, tween(motion.navMs))
                            else -> alpha.snapTo(1f)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .focusRequester(pageEntries.getValue(page))
                            .focusGroup()
                            .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    if (active) placeable.placeWithLayer(0, 0) { this.alpha = alpha.value }
                                }
                            },
                    ) {
                        CompositionLocalProvider(LocalPageActive provides active, LocalFocusLayer provides pageLayer) {
                            when (page) {
                                LauncherTab.Home -> HomePage(state, vm, initialFocus = contentEntry, onOpenTab = { select(it, focusTab = true) }, resetKey = homeReset)
                                LauncherTab.Apps -> AppsPage(state, vm)
                                LauncherTab.Media -> MediaPage(state, vm)
                                LauncherTab.Games -> GamesPage(state, vm)
                                LauncherTab.Favorites -> FavoritesPage(state, vm)
                                LauncherTab.Settings -> SettingsPage(state, vm, entry = settingsEntry)
                            }
                        }
                    }
                }
            }
        }
    }

    // Build the remaining pages ahead of time, one at a time, while the remote is idle, so even the first visit to a
    // tab doesn't pause. Each build is a ~0.3–0.45 s main-thread job, hence the wait for a quiet moment.
    val currentTabs by rememberUpdatedState(tabs)
    LaunchedEffect(Unit) {
        if (!PagePrewarm.enabled) return@LaunchedEffect
        delay(PagePrewarm.START_MS)
        for (t in LauncherTab.entries) {
            if (t in built || t !in currentTabs) continue
            while (SystemClock.uptimeMillis() - UserActivity.lastKeyAt < PagePrewarm.IDLE_MS) delay(250)
            if (t !in built) {
                PagePrewarm.building = t
                built.add(t)
                withFrameNanos { }
                PagePrewarm.building = null
            }
            delay(PagePrewarm.GAP_MS)
        }
    }

    // Initial focus: first Home tile (as in the reference design), once apps are known.
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.apps != null) {
        if (!initialFocusDone && state.apps != null) {
            delay(60)
            initialFocusDone = if (tab == LauncherTab.Home) contentEntry.tryFocus() else focusTab(tab)
            if (!initialFocusDone) focusTab(tab)
        }
    }
}

/** True for the page currently shown; false for pages kept alive in the background (see LauncherScaffold). */
val LocalPageActive = compositionLocalOf { true }

/** Last remote/keyboard key press (uptime ms), recorded by MainActivity for every key, overlays included. */
object UserActivity {
    @Volatile var lastKeyAt: Long = 0L
}

/** Building hidden pages ahead of time. Tests switch it off so they control when pages are built. */
internal object PagePrewarm {
    /** Settings ▸ Advanced ▸ "Build pages in advance" (and tests) switch this off. */
    @Volatile var enabled = true
    /** The page being built in the background right now, if any (latency diagnostics). */
    @Volatile var building: LauncherTab? = null
    /** How many times each page has been built in this process (diagnostics and tests). */
    val builds = java.util.concurrent.ConcurrentHashMap<LauncherTab, Int>()
    const val START_MS = 2_500L
    const val IDLE_MS = 1_200L
    const val GAP_MS = 600L
}

/** How long focus must rest on a tab before "When focused" switches the page. */
internal const val TAB_SETTLE_MS = 220L

/**
 * The tab row. The tab that will receive OK is a solid pill; the page that's showing keeps a glass pill.
 * OK on another tab opens it (in both activation modes); OK on the tab that's already open moves into its page.
 */
@Composable
private fun NavTabs(
    tabs: List<LauncherTab>,
    selected: LauncherTab,
    requesters: Map<LauncherTab, FocusRequester>,
    activation: TabActivation,
    onSelect: (LauncherTab) -> Unit,
    onEnterPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // "When focused" switches the page only once focus rests on a tab. Sweeping from Home to Settings used to build,
    // draw and cross-fade every page on the way (measured as the roughest part of navigation on the M450).
    var resting by remember { mutableStateOf<LauncherTab?>(null) }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(resting, activation) {
        val t = resting ?: return@LaunchedEffect
        if (activation != TabActivation.Focus || t == currentSelected) return@LaunchedEffect
        delay(TAB_SETTLE_MS)
        currentOnSelect(t)
    }
    Row(
        // Up from a page lands on the tab of the page you're on; a programmatic request for a tab is never redirected.
        modifier.dpadEntry { requesters[currentSelected] ?: requesters[LauncherTab.Home] }.focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val isSel = t == selected
            GlassTile(
                onClick = { if (t == currentSelected) onEnterPage() else currentOnSelect(t) },
                shape = Aether.shapes.pill,
                focusRequester = requesters[t],
                selected = isSel,
                restingSurface = isSel,
                scaleOnFocus = false,
                onFocus = { f -> if (f) resting = t else if (resting == t) resting = null },
                modifier = Modifier.semantics { contentDescription = t.label; this.selected = isSel },
            ) { focused ->
                val solid = Aether.colors.textPrimary
                Row(
                    Modifier
                        .then(if (focused) Modifier.background(solid.copy(alpha = 0.94f), Aether.shapes.pill) else Modifier)
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val c = when {
                        focused -> Aether.colors.base
                        isSel -> Aether.colors.textPrimary
                        else -> Aether.colors.textPrimary.copy(alpha = 0.82f)
                    }
                    AIcon(t.icon(), size = 18.dp, tint = c)
                    Spacer(Modifier.width(10.dp))
                    AText(t.label, Aether.type.nav, color = c)
                }
            }
        }
    }
}

/** Short, unobtrusive confirmation messages ("Added to Favorites"). */
@Composable
private fun NoticeHost(vm: LauncherViewModel) {
    var text by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        vm.notices.collect { text = it; last = it; delay(2600); text = null }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = text != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            GlassPanel(shape = Aether.shapes.pill, tintAlpha = 0.16f) {
                AText(last, Aether.type.body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp), maxLines = 2)
            }
        }
    }
}

/** Launches the system image picker when requested; explains the alternatives when the TV has none. */
@Composable
private fun PicturePickerHost(vm: LauncherViewModel) {
    val pending by vm.pendingPicturePick.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val cb = vm.pendingPicturePick.value
        vm.pendingPicturePick.value = null
        if (uri != null && cb != null) vm.importPictureFromUri(uri) { u -> if (u != null) cb(u) }
    }
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        val hasPicker = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/*")
            .resolveActivity(context.packageManager) != null
        if (hasPicker) runCatching { launcher.launch(arrayOf("image/*")) }.onFailure { vm.pendingPicturePick.value = null }
        else {
            vm.pendingPicturePick.value = null
            vm.show(Overlay.Message("No file picker on this TV", "Use “From a web address”, or copy a photo to\n${vm.devicePictureFolder().path}\nwith adb push and choose “From the TV folder”."))
        }
    }
}
