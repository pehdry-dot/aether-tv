package com.aether.tv.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.AppLogo
import com.aether.tv.core.designsystem.Backdrop
import com.aether.tv.core.designsystem.BuiltinWallpaper
import com.aether.tv.core.designsystem.GlassPanel
import com.aether.tv.core.designsystem.LocalBackdrop
import com.aether.tv.core.designsystem.ProgressTrack
import com.aether.tv.core.designsystem.WallpaperRenderer
import com.aether.tv.core.designsystem.glass
import com.aether.tv.core.model.AppearanceSettings
import com.aether.tv.core.model.FontChoice
import com.aether.tv.core.model.GlassQuality
import com.aether.tv.core.model.TextScale
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.WallpaperCandidate
import com.aether.tv.ui.WallpaperSource
import com.aether.tv.ui.icon
import com.aether.tv.ui.common.ChoiceRow
import com.aether.tv.ui.common.GroupLabel
import com.aether.tv.ui.common.OptionRow
import com.aether.tv.ui.common.SliderRow
import com.aether.tv.ui.common.formatDate
import com.aether.tv.ui.common.formatTime
import com.aether.tv.ui.overlays.DialogPanel
import com.aether.tv.ui.overlays.Scrim
import com.aether.tv.ui.overlays.SidePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun LauncherViewModel.appearance(t: (AppearanceSettings) -> AppearanceSettings) = updateSettings { it.copy(appearance = t(it.appearance)) }
private fun pct(v: Float) = "${(v * 100).roundToInt()} %"

/** Solid colours offered as wallpapers. */
val SolidWallpapers = listOf("Midnight" to "FF0E1620", "Graphite" to "FF1C1F24", "Deep blue" to "FF0B1E3A", "Forest" to "FF0F1F17", "Plum" to "FF22152A")

// ------------------------------------------------------------------------------------------------ Wallpaper picker
/**
 * Every wallpaper source in one list. Choosing an entry opens a full-screen preview; nothing changes until Apply there.
 * Back from the preview returns here, to the entry you chose.
 */
@Composable
fun WallpaperPickerSheet(state: LauncherUiState, vm: LauncherViewModel) {
    val context = LocalContext.current
    val current = state.settings.appearance.wallpaper
    val first = remember { FocusRequester() }
    val files = remember { vm.dropFolderImages() }
    val hasPicker = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").resolveActivity(context.packageManager) != null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.previewWallpaperUri(it) } }
    fun preview(spec: String, label: String, source: WallpaperSource) = vm.previewWallpaper(WallpaperCandidate(spec, label, source))
    DialogPanel("Wallpaper", "Choose one to preview it full screen — nothing changes until you apply it", width = (520 * Aether.dimens.textScale.coerceAtLeast(1f)).dp, initial = first) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 390.dp)) {
            item("cur") {
                OptionRow("Current: ${wallpaperLabel(current)}", { preview(current, wallpaperLabel(current), WallpaperSource.Current) }, icon = AetherIcons.Check,
                    detail = "Preview what you have now", focusRequester = first, minHeight = 40.dp)
            }
            item("g1") { GroupLabel("Built-in") }
            BuiltinWallpaper.entries.forEach { w ->
                item("b-${w.id}") { OptionRow(w.label, { preview("builtin:${w.id}", w.label, WallpaperSource.Builtin) }, icon = AetherIcons.Image, minHeight = 40.dp, chevron = true) }
            }
            item("g2") { GroupLabel("Solid colours") }
            SolidWallpapers.forEach { (name, hex) ->
                item("s-$hex") {
                    OptionRow(name, { preview("solid:$hex", name, WallpaperSource.Solid) }, minHeight = 40.dp, chevron = true,
                        trailing = { Box(Modifier.size(22.dp).clip(CircleShape).background(Color(hex.toLong(16).toInt()))) })
                }
            }
            item("g3") { GroupLabel("Your pictures") }
            if (hasPicker) item("pick") {
                OptionRow("From storage…", { runCatching { picker.launch(arrayOf("image/*")) } }, icon = AetherIcons.Folder, detail = "Choose a picture with the system file picker", minHeight = 40.dp, chevron = true)
            }
            item("web") {
                OptionRow("From a web address…", {
                    vm.show(Overlay.TextInput("Picture address", "https://", "A direct link to a JPEG, PNG or WebP, up to 15 MB") { url -> vm.previewWallpaperUrl(url) })
                }, icon = AetherIcons.Globe, detail = "Downloaded for the preview; kept only if you apply it", minHeight = 40.dp, chevron = true)
            }
            if (files.isEmpty()) item("folder-empty") {
                OptionRow("TV folder (empty)", {
                    vm.show(Overlay.Message("Add pictures to the TV folder", "Copy pictures to:\n\n${vm.wallpaperFolder().path}\n\nfor example with:\nadb push photo.jpg ${vm.wallpaperFolder().path}/\n\nThey then appear in this list. Aether copies a picture when you apply it and never changes or deletes the original."))
                }, icon = AetherIcons.HardDrive, detail = "Select for how to add pictures", minHeight = 40.dp)
            } else files.forEach { f ->
                item("f-${f.name}") {
                    OptionRow(f.name, { preview("file:${f.path}", f.name, WallpaperSource.TvFolder) }, icon = AetherIcons.HardDrive,
                        detail = "TV folder • ${f.length() / 1024} KB", minHeight = 40.dp, chevron = true)
                }
            }
        }
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Ready(val backdrop: Backdrop) : PreviewState
    data class Failed(val reason: String) : PreviewState
}

/**
 * Full-screen preview of a wallpaper exactly as Home will draw it: the same center crop, dimming, legibility gradients
 * and frosted glass, under representative launcher UI (clock, tabs, app dock, a Continue Watching card). Apply saves it;
 * Cancel or Back returns to the picker with the current wallpaper unchanged.
 */
@Composable
fun WallpaperPreviewScreen(o: Overlay.WallpaperPreview, state: LauncherUiState, vm: LauncherViewModel) {
    val c = o.candidate
    val context = LocalContext.current
    val a = state.settings.appearance
    val colors = Aether.colors
    val blur = Aether.glass.blurRadius
    val density = LocalDensity.current
    var applied by remember { mutableStateOf(false) }
    // A downloaded or picked picture that isn't applied is removed when the preview closes, whichever way it closes.
    DisposableEffect(c) { onDispose { if (!applied) vm.discardWallpaperPreview(c) } }
    val apply = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.coerceAtLeast(1); val h = constraints.maxHeight.coerceAtLeast(1)
        val radiusPx = with(density) { blur.toPx() }
        val blurOn = a.glassQuality == GlassQuality.Auto && a.blurRadiusDp > 0
        val result by produceState<PreviewState>(PreviewState.Loading, c.spec, w, h, a.backgroundDim, colors.isDark, radiusPx, blurOn) {
            value = withContext(Dispatchers.Default) {
                WallpaperRenderer.problem(context, c.spec)?.let { PreviewState.Failed(it) }
                    ?: WallpaperRenderer.backdrop(context, c.spec, w, h, a.backgroundDim, colors.isDark, if (blurOn) radiusPx else 0f, colors.glassTint, colors.glassAlpha)
                        ?.let { PreviewState.Ready(it) }
                    ?: PreviewState.Failed("Android couldn't decode that picture.")
            }
        }
        when (val r = result) {
            PreviewState.Loading -> Scrim(0.85f) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    AText("Preparing the preview…", Aether.type.panelTitle)
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.width(220.dp)) { OptionRow("Cancel", { vm.dismissOverlay() }, icon = AetherIcons.X, focusRequester = apply, minHeight = 38.dp) }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(30); runCatching { apply.requestFocus() } }
            }
            is PreviewState.Failed -> DialogPanel("Can't use this picture", c.label, initial = apply) {
                AText(r.reason + "\n\nYour wallpaper hasn't changed.", Aether.type.body, color = Aether.colors.textSecondary, maxLines = 6)
                Spacer(Modifier.height(14.dp))
                OptionRow("Back to the list", { vm.dismissOverlay() }, icon = AetherIcons.ArrowLeft, focusRequester = apply, minHeight = 38.dp)
            }
            is PreviewState.Ready -> {
                Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Preview of ${c.label}" }) { drawImage(r.backdrop.full, dstSize = IntSize(w, h)) }
                CompositionLocalProvider(LocalBackdrop provides r.backdrop) {
                    MockHome(state)
                    GlassPanel(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 40.dp).widthIn(min = 300.dp, max = 380.dp), tintAlpha = if (colors.isDark) 0.22f else 0.8f) {
                        Column(Modifier.padding(16.dp).focusGroup()) {
                            AText("Preview: ${c.label}", Aether.type.panelTitle, maxLines = 2)
                            AText("Dimming ${pct(a.backgroundDim)} and cropping as Home shows it", Aether.type.caption, color = Aether.colors.textSecondary, maxLines = 2)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.weight(1f)) {
                                    OptionRow("Apply", { applied = true; vm.applyWallpaper(c); vm.closeWallpaperFlow() }, icon = AetherIcons.Check, focusRequester = apply, minHeight = 38.dp)
                                }
                                Box(Modifier.weight(1f)) { OptionRow("Cancel", { vm.dismissOverlay() }, icon = AetherIcons.X, minHeight = 38.dp) }
                            }
                        }
                    }
                }
                androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(30); runCatching { apply.requestFocus() } }
            }
        }
    }
}

/** Static launcher UI for the preview: what you read on Home, drawn the way Home draws it. Not focusable. */
@Composable
private fun MockHome(state: LauncherUiState) {
    val layout = state.settings.layout
    val now = System.currentTimeMillis()
    val apps = state.favoriteApps.map { it.second }.take(6)
    Column(Modifier.fillMaxSize().padding(horizontal = Aether.dimens.screenH).padding(top = Aether.dimens.screenTop - 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                AText(formatTime(now, android.text.format.DateFormat.is24HourFormat(LocalContext.current)), Aether.type.clock)
                AText(formatDate(now), Aether.type.date, color = Aether.colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(AetherIcons.Search, AetherIcons.Bell, AetherIcons.Wifi, AetherIcons.Settings).forEach { i ->
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { AIcon(i, size = 19.dp, tint = Aether.colors.textPrimary.copy(alpha = 0.88f)) }
                }
            }
        }
        if (layout.showPageTitle) { Spacer(Modifier.height(16.dp)); AText("Home", Aether.type.display); Spacer(Modifier.height(10.dp)) } else Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            state.visibleTabs.forEachIndexed { i, t ->
                val sel = i == 0
                Row(
                    Modifier.then(if (sel) Modifier.glass(Aether.shapes.pill, selected = true) else Modifier).padding(horizontal = 18.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AIcon(t.icon(), size = 18.dp, tint = Aether.colors.textPrimary.copy(alpha = if (sel) 1f else 0.82f))
                    Spacer(Modifier.width(10.dp))
                    AText(t.label, Aether.type.nav, color = Aether.colors.textPrimary.copy(alpha = if (sel) 1f else 0.82f))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        GlassPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                apps.forEach { app ->
                    Column(Modifier.width(124.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) { AppLogo(app, maxWidth = 84.dp, height = 25.dp) }
                        AText(app.label, Aether.type.tileLabel, color = Aether.colors.textSecondary, align = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
                    }
                }
                if (apps.isEmpty()) AText("Your favorite apps appear here", Aether.type.body, color = Aether.colors.textSecondary)
            }
        }
        Spacer(Modifier.height(18.dp))
        AText("Continue Watching", Aether.type.section)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            listOf("The Long Road" to "S2 E4 · Endure and Survive · 24 min left", "Neon Harbor" to "S3 E7 · The Conflict").forEachIndexed { i, (t, sub) ->
                Box(Modifier.width(300.dp).height(135.dp).glass(Aether.shapes.card)) {
                    Box(Modifier.fillMaxSize().clip(Aether.shapes.card).background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color(0xE6070B10))))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        AText(t, Aether.type.cardTitle, color = Color.White)
                        AText(sub, Aether.type.cardSubtitle, color = Color.White.copy(alpha = 0.78f))
                        Spacer(Modifier.height(8.dp))
                        ProgressTrack(if (i == 0) 0.62f else 0.3f)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Glass & blur
/** Glass settings beside the screen they change (the side panel leaves most of the screen visible). */
@Composable
fun GlassSettingsPanel(state: LauncherUiState, vm: LauncherViewModel) {
    val a = state.settings.appearance
    val first = remember { FocusRequester() }
    SidePanel("Glass & Blur", "Changes show behind this panel right away", initial = first) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { ChoiceRow("Glass rendering", GlassQuality.entries, a.glassQuality, { it.label }, { v -> vm.appearance { it.copy(glassQuality = v) } }, icon = AetherIcons.Layers, focusRequester = first) }
            item { SliderRow("Glass opacity", a.glassOpacity, 0f..0.4f, 0.02f, { v -> vm.appearance { it.copy(glassOpacity = v) } }, ::pct) }
            item { SliderRow("Blur intensity", a.blurRadiusDp.toFloat(), 0f..40f, 2f, { v -> vm.appearance { it.copy(blurRadiusDp = v.roundToInt()) } }, { "${it.roundToInt()} dp" }) }
            item { SliderRow("Border intensity", a.borderIntensity, 0f..0.4f, 0.02f, { v -> vm.appearance { it.copy(borderIntensity = v) } }, ::pct) }
            item { SliderRow("Background dimming", a.backgroundDim, 0f..0.8f, 0.05f, { v -> vm.appearance { it.copy(backgroundDim = v) } }, ::pct) }
            item { SliderRow("Corner radius", a.cornerRadiusDp.toFloat(), 0f..28f, 2f, { v -> vm.appearance { it.copy(cornerRadiusDp = v.roundToInt()) } }, { "${it.roundToInt()} dp" }) }
            item { OptionRow("Done", { vm.dismissOverlay() }, icon = AetherIcons.Check, minHeight = 38.dp) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Typography
/**
 * Typeface and text size (90–150 % in 5 % steps, per profile) with live samples of the text that matters on a TV:
 * a page heading, a navigation tab, an app label and an episode subtitle. Everything changes as you go, the editor too,
 * so you see at once whether the focused row and the buttons still fit.
 */
@Composable
fun TypographyEditor(state: LauncherUiState, vm: LauncherViewModel) {
    val a = state.settings.appearance
    val first = remember { FocusRequester() }
    val scale = Aether.dimens.textScale
    DialogPanel("Typography", "Typeface and text size for this profile", width = (820 * scale.coerceAtLeast(1f)).coerceAtMost(900f).dp, initial = first) {
        Row(Modifier.fillMaxWidth().heightIn(max = 400.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    ChoiceRow("Typeface", FontChoice.entries, a.font, { it.label }, { v -> vm.appearance { it.copy(font = v) } }, icon = AetherIcons.Type, focusRequester = first,
                        detail = a.font.description, onOpenPicker = {
                            vm.show(Overlay.Choice("Typeface", FontChoice.entries.map { "${it.label} — ${it.description}" }, a.font.ordinal) { i -> vm.appearance { it.copy(font = FontChoice.entries[i]) } })
                        })
                }
                item {
                    SliderRow("Text size", a.textScale, TextScale.MIN..TextScale.MAX, TextScale.STEP, { v -> vm.appearance { it.copy(textScale = TextScale.clamp(v)) } }, ::pct,
                        icon = AetherIcons.TextSize, detail = "◀ ▶ in 5 % steps, 90–150 %")
                }
                item {
                    OptionRow("Reset to default", { vm.appearance { it.copy(font = FontChoice.Inter, textScale = TextScale.DEFAULT) } }, icon = AetherIcons.RotateCcw,
                        detail = "Inter at 100 %", enabled = a.font != FontChoice.Inter || a.textScale != TextScale.DEFAULT)
                }
                item { OptionRow("Done", { vm.dismissOverlay() }, icon = AetherIcons.Check, minHeight = 38.dp) }
            }
            GlassPanel(Modifier.weight(1.15f).fillMaxHeight()) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AText("Samples", Aether.type.caption, color = Aether.colors.textTertiary)
                    AText("Home", Aether.type.display)
                    Row(Modifier.glass(Aether.shapes.pill, selected = true).padding(horizontal = 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        AIcon(AetherIcons.Star, size = 18.dp); Spacer(Modifier.width(10.dp)); AText("Favorites", Aether.type.nav)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width((110 * scale).dp).glass(Aether.shapes.tile).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AIcon(AetherIcons.CirclePlay, size = 26.dp)
                            Spacer(Modifier.height(5.dp))
                            AText("YouTube", Aether.type.tileLabel, align = TextAlign.Center)
                        }
                        AText("App label", Aether.type.caption, color = Aether.colors.textTertiary)
                    }
                    Column(Modifier.fillMaxWidth().glass(Aether.shapes.card).padding(14.dp)) {
                        AText("The Long Road", Aether.type.cardTitle)
                        AText("S2 E4 · Endure and Survive · 24 min left", Aether.type.cardSubtitle, color = Aether.colors.textSecondary, maxLines = 2)
                        Spacer(Modifier.height(8.dp))
                        ProgressTrack(0.62f)
                    }
                    AText("Body text, as in settings descriptions and dialogs.", Aether.type.body, color = Aether.colors.textSecondary, maxLines = 3)
                    AText("${a.font.label} • ${pct(a.textScale)}", Aether.type.caption, color = Aether.colors.textTertiary)
                }
            }
        }
    }
}
