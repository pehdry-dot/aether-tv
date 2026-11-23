package com.aether.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
    @Test fun apps() { key(Key.DirectionUp); key(Key.DirectionRight); key(Key.DirectionDown); shot("02_apps") }
    @Test fun favorites() { key(Key.DirectionUp); key(Key.DirectionRight, 4); key(Key.DirectionDown); shot("03_favorites") }
    @Test fun settings() { key(Key.DirectionUp); key(Key.DirectionRight, 5); key(Key.DirectionDown); shot("04_settings") }
    @Test fun media() { key(Key.DirectionUp); key(Key.DirectionRight, 2); shot("05_media") }
    @Test fun games() { key(Key.DirectionUp); key(Key.DirectionRight, 3); shot("06_games") }
    @Test fun settingsAppearance() { key(Key.DirectionUp); key(Key.DirectionRight, 5); key(Key.DirectionDown); key(Key.DirectionRight); shot("07_settings_appearance") }
    @Test fun appOptions() { key(Key.Menu); shot("08_app_options") }
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
