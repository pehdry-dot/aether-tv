package com.aether.tv.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.data.store.AppStores
import com.aether.tv.ui.LauncherUiState
import com.aether.tv.ui.LauncherViewModel
import com.aether.tv.ui.Overlay
import com.aether.tv.ui.common.GroupLabel
import com.aether.tv.ui.common.OptionRow
import com.aether.tv.ui.overlays.DialogPanel
import com.aether.tv.ui.settings.UNKNOWN_APPS_PATH

/**
 * Apps ▸ Store: the stores that open on this TV, each described for what it is, and a remote-friendly way to get
 * Aurora Store when it's missing. The list is worked out again whenever the installed apps change, so a store that was
 * just installed appears at once.
 */
@Composable
fun StorePickerSheet(state: LauncherUiState, vm: LauncherViewModel) {
    val first = remember { FocusRequester() }
    val options = remember(state.apps) { vm.stores.options() }
    val setup by vm.stores.setup.collectAsStateWithLifecycle()
    val usable = options.filter { it.launch != null }
    val aurora = options.first { it.store.packageName == AppStores.AURORA_PACKAGE }
    DialogPanel("Store", "Install apps on this TV", width = 520.dp, initial = first) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 380.dp)) {
            usable.forEachIndexed { i, o ->
                item(o.store.id) {
                    OptionRow(o.store.label, { vm.dismissOverlay(); vm.openStore(o) }, icon = AetherIcons.AppsGrid, detail = o.store.description,
                        focusRequester = if (i == 0) first else null, chevron = true)
                }
            }
            if (usable.isEmpty()) item("none") {
                OptionRow("No store opens on this TV", {}, icon = AetherIcons.Info, focusRequester = if (aurora.launch != null) null else null,
                    detail = "Amazon Appstore, Google Play and F-Droid aren't installed or can't open here.")
            }
            if (aurora.launch == null) {
                item("g-aurora") { GroupLabel("Get Aurora Store") }
                item("aurora-install") {
                    val (title, detail) = when (val s = setup) {
                        AppStores.Setup.Idle -> "Download and install Aurora Store" to "From Aurora's official release (gitlab.com/AuroraOSS). Checked against Aurora's signing key; Android then asks you to confirm."
                        is AppStores.Setup.Working -> s.step to if (s.total > 0) "${s.done * 100 / s.total} %" else "Please wait…"
                        is AppStores.Setup.NeedsPermission -> "Allow Aether to install apps" to "Then come back here: the installation continues. $UNKNOWN_APPS_PATH"
                        AppStores.Setup.WaitingForYou -> "Choose Install on Android's screen" to "Use the remote to confirm."
                        AppStores.Setup.Installed -> "Aurora Store is installed" to "It's now in the list above and in Apps."
                        is AppStores.Setup.Failed -> "Couldn't install Aurora Store" to "${s.message} Select to try again."
                    }
                    OptionRow(title, {
                        when (setup) {
                            is AppStores.Setup.NeedsPermission -> vm.openUnknownAppsSettings()
                            is AppStores.Setup.Working, AppStores.Setup.WaitingForYou -> Unit
                            else -> { vm.stores.reset(); vm.stores.installAurora() }
                        }
                    }, icon = AetherIcons.Download, detail = detail, focusRequester = if (usable.isEmpty()) first else null)
                }
                item("aurora-page") {
                    val browser = remember { vm.stores.browser(AppStores.AURORA_PAGE) }
                    OptionRow(if (browser != null) "Open Aurora's official page" else "Aurora's official page", {
                        if (browser != null) vm.openIntent(browser)
                        else vm.show(Overlay.Message("Aurora Store", "This TV has no web browser that opens pages for apps. On a phone or computer, see:\n\n${AppStores.AURORA_PAGE}\n${AppStores.AURORA_FDROID_PAGE}\n\nAether's \"Download and install\" above needs no browser."))
                    }, icon = AetherIcons.Globe, detail = AppStores.AURORA_PAGE)
                }
            }
            item("close") { OptionRow("Close", { vm.dismissOverlay() }, icon = AetherIcons.X, minHeight = 38.dp) }
        }
    }
}
