package com.aether.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.TestEnv
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Renders the real launcher at 1920×1080 (960×540 dp, xhdpi — the Android TV / Fire TV UI resolution) and writes
 * PNGs to docs/screenshots when run with `./gradlew recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class ScreenshotTest : LauncherUiTest() {
    private fun settle() { repeat(6) { settleUntil { true }; Thread.sleep(250) } }
    private fun shot(name: String) { settle(); rule.onRoot().captureRoboImage("../docs/screenshots/$name.png") }

    @Test fun home() = shot("01_home")
    // Keys go through the Activity like the remote's (see LauncherUiTest.remote); tabs switch after a short rest.
    @Test fun apps() { up(); right(); rest(); down(); shot("02_apps") }
    @Test fun favorites() { up(); right(4); rest(); down(); shot("03_favorites") }
    @Test fun settings() { up(); right(5); rest(); down(); shot("04_settings") } // Overview
    @Test fun media() { up(); right(2); rest(); shot("05_media") }
    @Test fun games() { up(); right(3); rest(); shot("06_games") }
    @Test fun settingsAppearance() { up(); right(5); rest(); down(2); right(); shot("07_settings_appearance") }
    @Test fun appOptions() { remote(android.view.KeyEvent.KEYCODE_MENU); shot("08_app_options") }
    @Test fun quickControls() { vm.show(Overlay.QuickControls); shot("09_quick_controls") }
    @Test fun lightTheme() {
        vm.updateSettings { it.copy(appearance = it.appearance.copy(theme = com.aether.tv.core.model.ThemeMode.Light)) }
        shot("10_home_light")
    }
    @Test fun labelsOff() {
        vm.updateSettings { it.copy(appearance = it.appearance.copy(showAppLabels = false)) }
        shot("11_home_icons_only")
    }
}
