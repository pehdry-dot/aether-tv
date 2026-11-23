package com.aether.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.tv.BuildConfig
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.data.update.UpdateRules
import com.aether.tv.data.update.UpdateState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.GroupLabel
import com.aether.tv.ui.common.OptionRow
import com.aether.tv.ui.common.ToggleRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings ▸ Updates: what's installed, what's offered, and one row that does the next step. */
@Composable
fun UpdatesSection(vm: LauncherViewModel, first: FocusRequester) {
    val u = vm.updates
    val s by u.state.collectAsStateWithLifecycle()
    val auto by u.autoCheck.collectAsStateWithLifecycle()
    val last by u.lastCheckAt.collectAsStateWithLifecycle()
    val installed = remember { runCatching { vm.installedApp() }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OptionRow(
            "Installed: Aether ${BuildConfig.VERSION_NAME}", { vm.show(Overlay.Message("About signing", signingText(installed?.signerSha256.orEmpty()))) },
            icon = AetherIcons.Info, focusRequester = first, chevron = true,
            detail = "Version code ${BuildConfig.VERSION_CODE} • signing key ${installed?.signerSha256?.firstOrNull()?.let { UpdateRules.short(it) } ?: "unknown"} • select for details",
        )
        val (title, detail) = describe(s, last)
        OptionRow(title, { primary(vm, s) }, icon = icon(s), detail = detail, chevron = true)
        release(s)?.let { r ->
            OptionRow("Release notes", { vm.show(Overlay.Message("Aether ${r.name}", UpdateRules.plainNotes(r.notes).ifBlank { "No notes for this release." })) },
                icon = AetherIcons.List, detail = "${r.name}${r.publishedAt?.let { " • released ${it.take(10)}" }.orEmpty()}", chevron = true)
        }
        if (s is UpdateState.Downloading) OptionRow("Cancel download", { u.cancel() }, icon = AetherIcons.X)
        if (s is UpdateState.Available || s is UpdateState.Ready || s is UpdateState.NeedsPermission) {
            OptionRow("Hide the notice for this version", { u.dismissNotice(); vm.notice("The Home notice is hidden until a newer version") }, icon = AetherIcons.EyeOff)
        }
        OptionRow("Check now", { u.check() }, icon = AetherIcons.RefreshCw, detail = "Releases from github.com/${BuildConfig.UPDATE_REPO}")
        GroupLabel("Options")
        ToggleRow("Check automatically", auto, { u.setAutoCheck(it) }, icon = AetherIcons.Clock,
            detail = "Once a day, when Aether comes to the front. Never in the background.")
        OptionRow(
            if (vm.canInstallApps()) "Install unknown apps: allowed" else "Install unknown apps: not allowed yet",
            { vm.openUnknownAppsSettings() }, icon = AetherIcons.Shield, chevron = true,
            detail = "Android asks you to allow Aether once before it can install its own updates. $UNKNOWN_APPS_PATH",
        )
    }
}

const val UNKNOWN_APPS_PATH = "On Fire TV: Settings ▸ Device & Software (or My Fire TV) ▸ Developer options ▸ Install unknown apps ▸ Aether TV ▸ On."

private fun release(s: UpdateState) = when (s) {
    is UpdateState.Available -> s.release; is UpdateState.Ready -> s.release; is UpdateState.NeedsPermission -> s.release
    is UpdateState.Downloading -> s.release; is UpdateState.Installing -> s.release; is UpdateState.Incompatible -> s.release
    is UpdateState.UpToDate -> s.newest; is UpdateState.Verifying -> s.release; is UpdateState.Failed -> s.release
    else -> null
}

private fun icon(s: UpdateState) = when (s) {
    is UpdateState.Available, is UpdateState.Downloading -> AetherIcons.Download
    is UpdateState.Ready, is UpdateState.Installing, is UpdateState.NeedsPermission -> AetherIcons.CirclePlay
    is UpdateState.Failed, is UpdateState.Incompatible -> AetherIcons.Info
    else -> AetherIcons.Check
}

private fun describe(s: UpdateState, lastCheck: Long): Pair<String, String> {
    val checked = if (lastCheck > 0) "Last checked ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(lastCheck))}" else "Not checked yet"
    fun mb(b: Long) = "%.1f MB".format(Locale.US, b / 1_048_576.0)
    return when (s) {
        UpdateState.Idle -> "Latest version: unknown" to "$checked • select to check now"
        UpdateState.Checking -> "Checking GitHub…" to checked
        is UpdateState.UpToDate -> "Aether is up to date" to listOfNotNull(s.newest?.metadata?.let { "Latest: ${it.versionName} (code ${it.versionCode})" }, s.note, checked).joinToString(" • ")
        is UpdateState.Available -> "Download and install ${s.meta.versionName}" to "Version code ${s.meta.versionCode} • ${mb(s.meta.size)}${s.release.publishedAt?.let { " • released ${it.take(10)}" }.orEmpty()}"
        is UpdateState.Downloading -> "Downloading ${s.meta.versionName}: ${if (s.total > 0) s.done * 100 / s.total else 0} %" to "${mb(s.done)} of ${mb(s.total)} • stored in Aether's private storage"
        is UpdateState.Verifying -> "Checking the download…" to "Package, version, checksum and signing key"
        is UpdateState.Ready -> "Install ${s.meta.versionName}" to (s.note ?: "Verified. Android shows its install screen: choose Install with the remote. Your profiles and settings stay.")
        is UpdateState.NeedsPermission -> "Allow Aether to install its update" to "Then come back: the update continues. $UNKNOWN_APPS_PATH"
        is UpdateState.Installing -> "Waiting for Android's install screen…" to "Choose Install with the remote. Aether restarts on the new version."
        is UpdateState.Incompatible -> "${s.release.name} can't be installed here" to s.reason
        is UpdateState.Failed -> when (s.retry) {
            UpdateState.Retry.Check -> "Update check failed" to "${s.message} Select to try again."
            UpdateState.Retry.Download -> "Download stopped" to "${s.message} Select to continue."
            UpdateState.Retry.Install -> "Installation didn't finish" to "${s.message} Select to try again."
        }
    }
}

private fun primary(vm: LauncherViewModel, s: UpdateState) {
    val u = vm.updates
    when (s) {
        UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Incompatible -> u.check()
        is UpdateState.Available -> u.download()
        is UpdateState.Downloading -> vm.show(Overlay.Confirm("Cancel the download?", "What was downloaded so far is removed.", "Cancel download") { u.cancel() })
        is UpdateState.Ready -> u.install()
        is UpdateState.NeedsPermission -> vm.openUnknownAppsSettings()
        is UpdateState.Failed -> when (s.retry) { UpdateState.Retry.Check -> u.check(); UpdateState.Retry.Download -> u.download(); UpdateState.Retry.Install -> u.install() }
        else -> Unit
    }
}

private fun signingText(installed: List<String>): String =
    "Android only installs an update signed with the same key as the installed app, and keeps your profiles and settings when it does.\n\n" +
        "This installation's signing key (SHA-256):\n${installed.firstOrNull()?.chunked(2)?.joinToString(":") ?: "unknown"}\n\n" +
        "Releases on GitHub list their key in aether-update.json. If it differs, Aether won't offer the update; moving to another key needs a " +
        "one-time reinstall (back up first: Settings ▸ Advanced ▸ Back up). See SIGNING.md in the project."
