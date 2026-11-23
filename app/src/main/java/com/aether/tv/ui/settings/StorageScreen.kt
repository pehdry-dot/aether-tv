package com.aether.tv.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.ProgressTrack
import com.aether.tv.data.storage.StorageInspector
import com.aether.tv.data.storage.StorageReport
import com.aether.tv.platform.SystemShortcut
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.GroupLabel
import com.aether.tv.ui.common.OptionRow
import com.aether.tv.ui.overlays.DialogPanel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings ▸ TV & System ▸ Storage. Works with Amazon's launcher switched off: Aether measures and cleans its own files.
 * Every removal is confirmed, and the numbers are measured again afterwards.
 */
@Composable
fun StorageScreen(state: LauncherUiState, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    LaunchedEffect(generation) { report = vm.storage.report() }
    // "Measuring…" held focus; when the numbers arrive, focus moves to the first of them.
    LaunchedEffect(report == null) { if (report != null) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } } }
    fun run(done: String, op: suspend () -> Long) = scope.launch {
        val freed = op()
        vm.notice("$done${if (freed > 0) " • ${StorageInspector.format(freed)} freed" else ""}")
        generation++
    }
    fun fmt(b: Long) = StorageInspector.format(b)
    val apps = remember(state.apps) { state.apps.orEmpty().filter { !it.isSystemApp }.sortedBy { it.label.lowercase() } }
    DialogPanel("Storage", "This TV's internal storage and what Aether keeps on it", width = (560 * Aether.dimens.textScale.coerceAtLeast(1f)).coerceAtMost(760f).dp, initial = first) {
        val r = report
        if (r == null) {
            AText("Measuring…", Aether.type.body, color = Aether.colors.textSecondary)
            OptionRow("Close", { vm.dismissOverlay() }, icon = AetherIcons.X, focusRequester = first, minHeight = 38.dp)
            return@DialogPanel
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            val used = (r.totalBytes - r.freeBytes).coerceAtLeast(0)
            AText("${fmt(r.freeBytes)} free of ${fmt(r.totalBytes)}", Aether.type.bodyStrong)
            Box(Modifier.padding(vertical = 6.dp)) { ProgressTrack(if (r.totalBytes > 0) used.toFloat() / r.totalBytes else 0f, height = 5.dp) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 340.dp)) {
            item("cache") {
                OptionRow("Artwork cache", {
                    vm.show(Overlay.Confirm("Clear the artwork cache?", "Posters and logos are downloaded again when they're shown. Nothing you set up is removed.", "Clear") {
                        run("Artwork cache cleared") { vm.storage.clearArtworkCache() }
                    })
                }, icon = AetherIcons.Image, detail = "${fmt(r.artworkCacheBytes)} • can be cleared, it refills as needed", focusRequester = first)
            }
            item("g-pictures") { GroupLabel("Your pictures in Aether") }
            r.assets.forEach { a ->
                item("a-${a.kind}") {
                    OptionRow(a.kind.label, {
                        if (a.unusedFiles == 0) vm.notice("All ${a.kind.label.lowercase()} are in use")
                        else vm.show(Overlay.Confirm("Remove ${a.unusedFiles} unused?", "These copies aren't used by any profile, favorite, device or game. Pictures in use and the originals in the TV folder are kept.", "Remove") {
                            run("Unused pictures removed") { vm.storage.deleteUnusedPictures() }
                        })
                    }, icon = AetherIcons.Folder,
                        detail = "${a.files} file(s), ${fmt(a.bytes)}" + if (a.unusedFiles > 0) " • ${a.unusedFiles} unused (${fmt(a.unusedBytes)}): select to remove" else " • all in use")
                }
            }
            item("tv") { OptionRow("TV folder (your files)", {}, icon = AetherIcons.HardDrive, detail = "${fmt(r.tvFolderBytes)} • pictures you copied there; Aether never deletes them") }
            item("data") { OptionRow("Profiles, favorites and settings", {}, icon = AetherIcons.Shield, detail = "${fmt(r.dataBytes)} • kept; reset or back up under Advanced") }
            item("g-backups") { GroupLabel("Backups (${r.backups.size})") }
            if (r.backups.isEmpty()) item("nob") { OptionRow("No backups on this TV", {}, icon = AetherIcons.Info) }
            r.backups.forEach { b ->
                item("b-${b.file.name}") {
                    OptionRow(b.file.name, {
                        vm.show(Overlay.Confirm("Delete ${b.file.name}?", "This backup is removed from the TV. Your current setup isn't affected.", "Delete") {
                            run("Backup deleted") { if (vm.storage.deleteBackup(b.file)) b.size else 0L }
                        })
                    }, icon = AetherIcons.Trash, detail = "${fmt(b.size)} • ${SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(b.modified))} • select to delete")
                }
            }
            if (r.updateBytes > 0) item("upd") {
                OptionRow("Downloaded updates", { run("Update downloads removed") { vm.storage.deleteUpdateDownloads() } }, icon = AetherIcons.Download,
                    detail = "${fmt(r.updateBytes)} • select to remove (Settings ▸ Updates downloads again if needed)")
            }
            item("g-apps") { GroupLabel("Other apps") }
            item("manage") {
                val ok = remember { vm.shortcuts.isAvailable(SystemShortcut.Applications) }
                OptionRow("Manage installed applications", { vm.openSystem(SystemShortcut.Applications) }, icon = AetherIcons.AppWindow, enabled = ok, chevron = ok,
                    detail = if (ok) "The TV's own app list: sizes, clear data, uninstall" else "This TV has no app-list screen Aether can open; use each app's info below")
            }
            apps.forEach { app ->
                item("app-${app.key}") {
                    val ok = remember(app.packageName) {
                        vm.shortcuts.isUsable(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
                    }
                    OptionRow(app.label, { vm.appInfo(app) }, icon = AetherIcons.Info, enabled = ok, chevron = ok,
                        detail = if (ok) "App info: storage, clear cache, uninstall" else "No app info screen for this app on this TV")
                }
            }
            item("close") { OptionRow("Done", { vm.dismissOverlay() }, icon = AetherIcons.Check, minHeight = 38.dp) }
        }
    }
}
