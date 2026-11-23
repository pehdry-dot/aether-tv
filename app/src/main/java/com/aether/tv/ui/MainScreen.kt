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
import com.aether.tv.core.designsystem.FocusMemory
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
    val overlay by vm.overlay.collectAsStateWithLifecycle()
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
            // Focus returns to whatever was focused before an overlay opened (focusRestorer remembers it).
            val scaffoldFocus = remember { FocusRequester() }
            var hadOverlay by remember { mutableStateOf(false) }
            var restoreTo by remember { mutableStateOf<FocusRequester?>(null) }
            LaunchedEffect(overlay == null) {
                if (overlay != null && !hadOverlay) restoreTo = FocusMemory.last
                if (overlay == null && hadOverlay) {
                    delay(16)
                    val ok = restoreTo?.let { r -> runCatching { r.requestFocus() }.isSuccess } ?: false
                    if (!ok) runCatching { scaffoldFocus.requestFocus() }
                    restoreTo = null
                }
                hadOverlay = overlay != null
            }
            Box(Modifier.fillMaxSize().focusRequester(scaffoldFocus).focusRestorer().focusGroup()) {
                if (state.ready) LauncherScaffold(state, vm, overlayOpen = overlay != null)
            }
            OverlayHost(overlay, state, vm)
            NoticeHost(vm)
            PicturePickerHost(vm)
        }
    }
}

@Composable
private fun LauncherScaffold(state: LauncherUiState, vm: LauncherViewModel, overlayOpen: Boolean) {
    val layout = state.settings.layout
    val behavior = state.settings.behavior
    val tabs = state.visibleTabs
    var tab by rememberSaveable { mutableStateOf(layout.defaultTab) }
    if (tab !in tabs) tab = LauncherTab.Home
    val tabRequesters = remember(tabs) { tabs.associateWith { FocusRequester() } }
    val contentEntry = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var tabsHaveFocus by remember { mutableStateOf(false) }
    val moveMode by vm.moveMode.collectAsStateWithLifecycle()
    val activity = androidx.activity.compose.LocalActivity.current
    val motion = Aether.motion

    /** Pages built so far (kept alive afterwards; see the page container below). */
    val built = remember { mutableStateListOf(tab) }

    fun select(t: LauncherTab, focusTab: Boolean) {
        tab = t
        if (focusTab) runCatching { tabRequesters[t]?.requestFocus() }
    }

    BackHandler(enabled = !overlayOpen) {
        when {
            moveMode != null -> vm.endMove()
            contentHasFocus -> runCatching { tabRequesters[tab]?.requestFocus() }
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
            onSelect = { tab = it },
            modifier = Modifier.onFocusChanged { tabsHaveFocus = it.hasFocus },
        )
        Spacer(Modifier.height(20.dp))
        // Pages stay composed once built: building one took 0.3–0.45 s on the M450's 32-bit CPU, every tab switch.
        // Hidden pages are composed but not placed, so they're not drawn, D-pad focus search skips them (Compose only
        // considers placed nodes) and they're removed from the accessibility tree.
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
                            .then(if (active) Modifier else Modifier.clearAndSetSemantics {})
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                layout(placeable.width, placeable.height) {
                                    if (active) placeable.placeWithLayer(0, 0) { this.alpha = alpha.value }
                                }
                            },
                    ) {
                        CompositionLocalProvider(LocalPageActive provides active) {
                            when (page) {
                                LauncherTab.Home -> HomePage(state, vm, initialFocus = contentEntry, onOpenTab = { select(it, focusTab = true) })
                                LauncherTab.Apps -> AppsPage(state, vm)
                                LauncherTab.Media -> MediaPage(state, vm)
                                LauncherTab.Games -> GamesPage(state, vm)
                                LauncherTab.Favorites -> FavoritesPage(state, vm)
                                LauncherTab.Settings -> SettingsPage(state, vm)
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
            if (t !in built) built.add(t)
            delay(PagePrewarm.GAP_MS)
        }
    }

    // Initial focus: first Home tile (as in the reference design), once apps are known.
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.apps != null) {
        if (!initialFocusDone && state.apps != null) {
            delay(60)
            val ok = runCatching { if (tab == LauncherTab.Home) contentEntry.requestFocus() else tabRequesters[tab]?.requestFocus() }.isSuccess
            initialFocusDone = ok
            if (!ok) runCatching { tabRequesters[tab]?.requestFocus() }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = tabsHaveFocus
}

/** True for the page currently shown; false for pages kept alive in the background (see LauncherScaffold). */
val LocalPageActive = compositionLocalOf { true }

/** Last remote/keyboard key press (uptime ms), recorded by MainActivity for every key, overlays included. */
object UserActivity {
    @Volatile var lastKeyAt: Long = 0L
}

/** Building hidden pages ahead of time. Tests switch it off so they control when pages are built. */
internal object PagePrewarm {
    @Volatile var enabled = true
    /** How many times each page has been built in this process (diagnostics and tests). */
    val builds = java.util.concurrent.ConcurrentHashMap<LauncherTab, Int>()
    const val START_MS = 2_500L
    const val IDLE_MS = 1_200L
    const val GAP_MS = 600L
}

/** How long focus must rest on a tab before "When focused" switches the page. */
internal const val TAB_SETTLE_MS = 220L

@Composable
private fun NavTabs(
    tabs: List<LauncherTab>,
    selected: LauncherTab,
    requesters: Map<LauncherTab, FocusRequester>,
    activation: TabActivation,
    onSelect: (LauncherTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fallback = requesters[selected] ?: FocusRequester.Default
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
        modifier.focusRestorer(fallback).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val isSel = t == selected
            GlassTile(
                onClick = { onSelect(t) },
                shape = Aether.shapes.pill,
                focusRequester = requesters[t],
                selected = isSel,
                restingSurface = isSel,
                scaleOnFocus = false,
                onFocus = { f -> if (f) resting = t else if (resting == t) resting = null },
                modifier = Modifier.semantics { contentDescription = t.label; this.selected = isSel },
            ) { focused ->
                Row(Modifier.padding(horizontal = 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    val c = if (isSel || focused) Aether.colors.textPrimary else Aether.colors.textPrimary.copy(alpha = 0.82f)
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
