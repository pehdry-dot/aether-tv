package com.aether.tv.ui.overlays

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.aether.tv.core.designsystem.FocusLayer
import com.aether.tv.core.designsystem.FocusMemory
import com.aether.tv.core.designsystem.FocusSpot
import com.aether.tv.core.designsystem.LocalFocusLayer
import com.aether.tv.core.designsystem.tryFocus
import com.aether.tv.ui.OverlayEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.GlassPanel
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.OptionRow
import com.aether.tv.ui.common.TvTextField
import kotlinx.coroutines.delay

/**
 * Renders every open overlay, bottom first. All of them stay composed, so a dialog keeps what you typed or picked while
 * a picker sits on top of it; only the top one is placed, drawn and focusable (each has its own [FocusLayer]).
 *
 * Focus goes back to whatever opened an overlay when it closes: the tile on the launcher, or the row of the dialog
 * underneath. An overlay that replaced another (a menu item opening the next dialog) inherits the replaced one's
 * opener. [onNothingToRestore] runs when none of them is still on screen.
 */
@Composable
fun OverlayHost(stack: List<OverlayEntry>, state: LauncherUiState, vm: LauncherViewModel, onNothingToRestore: () -> Unit) {
    val openers = remember { HashMap<Long, List<FocusSpot>>() }
    var shownIds by remember { mutableStateOf(emptyList<Long>()) }
    val ids = stack.map { it.id }
    LaunchedEffect(ids) {
        val removed = shownIds.filter { it !in ids }
        val added = ids.filter { it !in shownIds }
        val inherited = removed.flatMap { openers[it].orEmpty() }
        // The tile that was focused when an overlay first appears is its opener (its own focus request comes later).
        added.forEach { id -> openers[id] = listOfNotNull(FocusMemory.lastSpot) + inherited }
        shownIds = ids
        if (removed.isNotEmpty() && vm.skipFocusRestore) vm.skipFocusRestore = false
        else if (removed.isNotEmpty() && added.isEmpty()) {
            // Closed: wait a frame so the layer underneath is active and placed again, then go back to the opener.
            withFrameNanos { }
            val ok = removed.reversed().asSequence().flatMap { openers[it].orEmpty() }.any { it.tryFocus() }
            if (!ok && ids.isEmpty()) onNothingToRestore()
        }
        removed.forEach { openers.remove(it) }
    }
    if (stack.isEmpty()) return
    BackHandler { vm.dismissOverlay() }
    stack.forEachIndexed { i, entry ->
        key(entry.id) { OverlayLayer(entry, top = i == stack.lastIndex, state, vm) }
    }
}

@Composable
private fun OverlayLayer(entry: OverlayEntry, top: Boolean, state: LauncherUiState, vm: LauncherViewModel) {
    val layer = remember { FocusLayer("overlay ${entry.id}") }
    SideEffect { layer.setActive(top) }
    DisposableEffect(Unit) { onDispose { layer.setActive(false) } }
    val alpha = remember { Animatable(0f) }
    val ms = Aether.motion.navMs / 2 + 1
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(ms)) }
    Box(
        Modifier
            .fillMaxSize()
            .then(if (top) Modifier else Modifier.clearAndSetSemantics {})
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height) { if (top) p.placeWithLayer(0, 0) { this.alpha = alpha.value } }
            },
    ) {
        CompositionLocalProvider(LocalFocusLayer provides layer, com.aether.tv.ui.LocalPageActive provides top) {
            OverlayContent(entry.overlay, state, vm)
        }
    }
}

@Composable
private fun OverlayContent(o: Overlay, state: LauncherUiState, vm: LauncherViewModel) {
    when (o) {
        is Overlay.Message -> MessageDialog(o, vm)
        is Overlay.Confirm -> ConfirmDialog(o, vm)
        is Overlay.Choice -> ChoiceDialog(o, vm)
        is Overlay.TextInput -> TextInputDialog(o, vm)
        is Overlay.PinEntry -> PinDialog(o, vm)
        is Overlay.AppOptions -> AppOptionsSheet(o, state, vm)
        is Overlay.FavoriteOptions -> FavoriteOptionsSheet(o, state, vm)
        is Overlay.DeviceOptions -> DeviceOptionsSheet(o, state, vm)
        is Overlay.DeviceEditor -> DeviceEditor(o, state, vm)
        is Overlay.GameEditor -> GameEditor(o, state, vm)
        is Overlay.GameOptions -> GameOptionsSheet(o, state, vm)
        is Overlay.MediaOptions -> MediaOptionsSheet(o, state, vm)
        is Overlay.AddFavorite -> AddFavoriteSheet(state, vm)
        is Overlay.AppPicker -> AppPickerSheet(o, state, vm)
        is Overlay.SystemShortcutPicker -> SystemPickerSheet(o, vm)
        is Overlay.LinkCodeDisplay -> Unit
        is Overlay.ProviderSetup -> ProviderSetupSheet(o, state, vm)
        Overlay.QuickControls -> QuickControlsPanel(state, vm)
        Overlay.Notifications -> NotificationsPanel(state, vm)
        Overlay.Search -> SearchScreen(state, vm)
        Overlay.WallpaperPicker -> com.aether.tv.ui.settings.WallpaperPickerSheet(state, vm)
        is Overlay.WallpaperPreview -> com.aether.tv.ui.settings.WallpaperPreviewScreen(o, state, vm)
        Overlay.GlassSettings -> com.aether.tv.ui.settings.GlassSettingsPanel(state, vm)
        Overlay.Typography -> com.aether.tv.ui.settings.TypographyEditor(state, vm)
    }
}

/** Dimmed backdrop that also swallows pointer input to the launcher below. */
@Composable
fun Scrim(alpha: Float = 0.62f, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha)).pointerInput(Unit) { }, content = content)
}

/** Centered glass dialog. The first focusable child receives focus on open via [initial]. */
@Composable
fun DialogPanel(
    title: String,
    subtitle: String? = null,
    width: Dp = 420.dp,
    icon: (@Composable () -> Unit)? = null,
    initial: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scrim {
        GlassPanel(Modifier.align(Alignment.Center).width(width).heightIn(max = 480.dp), tintAlpha = if (Aether.colors.isDark) 0.14f else 0.75f) {
            Column(Modifier.padding(20.dp).focusGroup()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) { icon(); Spacer(Modifier.width(14.dp)) }
                    Column(Modifier.weight(1f)) {
                        AText(title, Aether.type.panelTitle, maxLines = 2)
                        if (subtitle != null) AText(subtitle, Aether.type.caption, color = Aether.colors.textSecondary, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
    if (initial != null) LaunchedEffect(Unit) { delay(30); runCatching { initial.requestFocus() } }
}

/** Right-hand side sheet (Quick Controls, Notifications). */
@Composable
fun SidePanel(title: String, subtitle: String?, initial: FocusRequester?, content: @Composable ColumnScope.() -> Unit) {
    Scrim(0.45f) {
        GlassPanel(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(340.dp).padding(vertical = 18.dp).padding(end = 18.dp), tintAlpha = if (Aether.colors.isDark) 0.14f else 0.75f) {
            Column(Modifier.padding(20.dp).focusGroup()) {
                AText(title, Aether.type.panelTitle)
                if (subtitle != null) AText(subtitle, Aether.type.caption, color = Aether.colors.textSecondary, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
    if (initial != null) LaunchedEffect(Unit) { delay(30); runCatching { initial.requestFocus() } }
}

data class MenuOption(val label: String, val icon: ImageVector, val detail: String? = null, val enabled: Boolean = true, val action: () -> Unit)

/** A vertical list of actions; selecting one closes the menu first, then runs the action. */
@Composable
fun MenuList(options: List<MenuOption>, vm: LauncherViewModel, initial: FocusRequester, closeFirst: Boolean = true) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 380.dp)) {
        itemsIndexed(options) { i, o ->
            OptionRow(o.label, { if (closeFirst) vm.dismissOverlay(); o.action() }, icon = o.icon, detail = o.detail, enabled = o.enabled, focusRequester = if (i == 0) initial else null, minHeight = 40.dp)
        }
    }
}

@Composable
private fun MessageDialog(o: Overlay.Message, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    DialogPanel(o.title.ifEmpty { "Aether" }, width = 480.dp, initial = first) {
        Column(Modifier.heightIn(max = 330.dp).verticalScroll(rememberScrollState())) {
            AText(o.message, Aether.type.body, color = Aether.colors.textSecondary, maxLines = 40)
        }
        Spacer(Modifier.height(14.dp))
        OptionRow("OK", { vm.dismissOverlay() }, icon = AetherIcons.Check, focusRequester = first, minHeight = 38.dp)
    }
}

@Composable
private fun ConfirmDialog(o: Overlay.Confirm, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    DialogPanel(o.title, initial = first) {
        AText(o.message, Aether.type.body, color = Aether.colors.textSecondary, maxLines = 8)
        Spacer(Modifier.height(14.dp))
        // Cancel is focused first so an accidental OK never confirms a destructive action.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { OptionRow("Cancel", { vm.dismissOverlay() }, icon = AetherIcons.X, focusRequester = first, minHeight = 38.dp) }
            Box(Modifier.weight(1f)) { OptionRow(o.confirmLabel, { vm.dismissOverlay(); o.onConfirm() }, icon = AetherIcons.Check, minHeight = 38.dp) }
        }
    }
}

@Composable
private fun ChoiceDialog(o: Overlay.Choice, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    val start = o.selected.coerceIn(0, (o.options.size - 1).coerceAtLeast(0))
    DialogPanel(o.title, initial = first) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 380.dp)) {
            itemsIndexed(o.options) { i, label ->
                OptionRow(label, { vm.dismissOverlay(); o.onPick(i) }, icon = if (i == o.selected) AetherIcons.Check else null,
                    focusRequester = if (i == start) first else null, minHeight = 38.dp)
            }
        }
    }
}

@Composable
private fun TextInputDialog(o: Overlay.TextInput, vm: LauncherViewModel) {
    var text by remember { mutableStateOf(o.initial) }
    val first = remember { FocusRequester() }
    DialogPanel(o.title, o.hint, initial = first) {
        TvTextField(text, { text = it }, o.hint ?: o.title, Modifier.fillMaxWidth(), password = o.password, focusRequester = first, startEditing = false,
            keyboardType = if (o.password) KeyboardType.NumberPassword else KeyboardType.Text, onSubmit = { })
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { OptionRow("Cancel", { vm.dismissOverlay() }, icon = AetherIcons.X, minHeight = 38.dp) }
            Box(Modifier.weight(1f)) { OptionRow("Save", { vm.dismissOverlay(); o.onDone(text) }, icon = AetherIcons.Check, minHeight = 38.dp) }
        }
    }
}

@Composable
private fun PinDialog(o: Overlay.PinEntry, vm: LauncherViewModel) {
    var pin by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    DialogPanel(o.title, "Enter the profile PIN", initial = first) {
        TvTextField(pin, { if (it.length <= 8 && it.all(Char::isDigit)) pin = it }, "PIN", Modifier.fillMaxWidth(), password = true, keyboardType = KeyboardType.NumberPassword, focusRequester = first, onSubmit = { o.onPin(pin) })
        Spacer(Modifier.height(12.dp))
        OptionRow("Unlock", { o.onPin(pin) }, icon = AetherIcons.Lock, minHeight = 38.dp)
    }
}

@Composable
fun SmallIcon(icon: ImageVector) = AIcon(icon, size = 22.dp)
