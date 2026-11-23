package com.aether.tv.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.core.model.AppLogoStyle
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.core.model.FontChoice
import com.aether.tv.core.model.LauncherTab
import com.aether.tv.data.apps.InstalledApp
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.onRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Screens rendered for review (not part of the docs): run with the environment variable AETHER_REVIEW_SHOTS=<dir>. Includes real package
 * names, so the CleanFrame symbols show actual brand marks.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class ReviewShotsTest : LauncherUiTest() {
    private val dir: String? = System.getenv("AETHER_REVIEW_SHOTS")
    override val extraApps = listOf(
        "com.nuvio.tv" to "Nuvio", "com.amazon.firetv.youtube" to "YouTube", "com.netflix.ninja" to "Netflix",
        "com.amazon.amazonvideo.livingroom" to "Prime Video", "com.spotify.tv.android" to "Spotify", "com.disney.disneyplus" to "Disney+",
        "com.plexapp.android" to "Plex", "com.example.generic" to "Sideloaded Tool",
    ).mapIndexed { i, (pkg, label) -> InstalledApp("$pkg/$pkg.Main", pkg, "$pkg.Main", label, true, false, null, 1_700_000_100_000 + i) }

    private fun shot(name: String) {
        assumeTrue(dir != null)
        repeat(6) { settleUntil { true }; Thread.sleep(250) }
        rule.onRoot().captureRoboImage("$dir/$name.png")
    }

    private fun brandFavorites() = runBlocking {
        env.db.favorites().clear("default")
        extraApps.forEach { env.favorites.add("default", FavoriteType.App, it.key) }
    }

    @Test fun settingsOverviewFocused() { up(); right(5); rest(); down(); right(); shot("a_settings_overview") }
    @Test fun tabFocusVersusSelected() { up(); right(5); rest(); down(); up(); left(); shot("b_tabs_focus_favorites_while_settings_open") }
    @Test fun hiddenSettingsTab() {
        runBlocking { env.settings.update("default") { s -> s.copy(layout = s.layout.copy(showSettingsTab = false)) } }
        settleUntil { LauncherTab.Settings !in vm.state.value.visibleTabs }
        up(); up(); right(10); ok(); shot("c_settings_via_gear_tab_hidden")
    }
    @Test fun wallpaperPreview() { up(); right(5); rest(); down(); right(); down(); ok(); down(2); ok(); settleUntil(30_000) { vm.overlay.value is Overlay.WallpaperPreview }; shot("d_wallpaper_preview_blue_hour") }
    @Test fun symbolsHome() { brandFavorites(); vm.updateSettings { it.copy(appearance = it.appearance.copy(appLogos = AppLogoStyle.Symbols)) }; shot("e_symbols_home") }
    @Test fun symbolsHomeNoLabels() { brandFavorites(); vm.updateSettings { it.copy(appearance = it.appearance.copy(appLogos = AppLogoStyle.Symbols, showAppLabels = false)) }; shot("f_symbols_home_no_labels") }
    @Test fun symbolsApps() { vm.updateSettings { it.copy(appearance = it.appearance.copy(appLogos = AppLogoStyle.Symbols)) }; up(); right(); rest(); shot("g_symbols_apps") }
    @Test fun typography150Editor() {
        vm.updateSettings { it.copy(appearance = it.appearance.copy(textScale = 1.5f, font = FontChoice.Atkinson)) }
        vm.show(Overlay.Typography); shot("h_typography_150_editor")
    }
    @Test fun typography150Home() { vm.updateSettings { it.copy(appearance = it.appearance.copy(textScale = 1.5f)) }; shot("i_home_150") }
    @Test fun typography150Settings() { vm.updateSettings { it.copy(appearance = it.appearance.copy(textScale = 1.5f)) }; up(); right(5); rest(); down(2); right(); shot("j_settings_150") }
    @Test fun typography150Apps() { vm.updateSettings { it.copy(appearance = it.appearance.copy(textScale = 1.5f)) }; up(); right(); rest(); shot("k_apps_150") }
    @Test fun typography150Dialog() { vm.updateSettings { it.copy(appearance = it.appearance.copy(textScale = 1.5f)) }; key(androidx.compose.ui.input.key.Key.Menu); shot("l_dialog_150") }
    @Test fun storage() { vm.show(Overlay.Storage); shot("m_storage") }
    @Test fun store() { up(); right(); rest(); vm.show(Overlay.StorePicker); shot("n_store") }
    @Test fun updates() { vm.openSettings("updates"); shot("o_updates") }
    @Test fun remoteShortcuts() { vm.openSettings("system"); down(4); shot("p_tv_system_remote") }
    @Test fun integrations() { vm.openSettings("integrations"); shot("q_integrations") }
}
