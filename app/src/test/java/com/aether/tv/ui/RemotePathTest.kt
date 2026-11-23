package com.aether.tv.ui

import com.aether.tv.core.model.TabActivation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The reported trap, key by key, through the Activity like a real remote: Settings ▸ Appearance, change a slider, up to
 * the tabs, over to Favorites, open it and start a favorite, without Back. Before every OK the test checks which control
 * holds focus, i.e. where that OK will land.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class RemotePathTest : LauncherUiTest() {

    private fun setActivation(a: TabActivation) {
        runBlocking { env.settings.update("default") { it.copy(behavior = it.behavior.copy(tabActivation = a)) } }
        settleUntil { vm.state.value.settings.behavior.tabActivation == a }
    }

    /** Home ▸ Settings tab ▸ Appearance, focus on the Wallpaper row. */
    private fun openAppearance(activation: TabActivation) {
        up() // Home tab
        assertEquals("Home", focusedLabel())
        right(5)
        assertEquals("Settings", focusedLabel())
        if (activation == TabActivation.Select) ok() else rest()
        assertTrue(vm.state.value.ready)
        down() // sidebar: Overview
        assertTrue(focusedLabel(), focusedLabel().startsWith("Overview"))
        down() // Appearance
        assertTrue(focusedLabel(), focusedLabel().startsWith("Appearance"))
        right() // opens Appearance and moves into it
        settleUntil { focused().singleOrNull()?.toString()?.contains("Wallpaper") == true }
    }

    /** From a row in Settings ▸ Appearance: change the glass opacity slider, then back up to the tabs. */
    private fun sliderThenUpToTabs(fast: Boolean) {
        down(4) // Theme, Accent, Glass rendering, Glass opacity
        assertEquals("Glass opacity", focusedLabel())
        val before = vm.state.value.settings.appearance.glassOpacity
        right()
        settleUntil { vm.state.value.settings.appearance.glassOpacity > before }
        // Glass opacity -> Glass rendering -> Accent -> Theme -> Wallpaper -> the tabs: five presses.
        if (fast) up(5) else repeat(5) { up(); if (focusedLabel() == "Settings") return@repeat }
        assertEquals("Up from the panel lands on the open page's tab", "Settings", focusedLabel())
    }

    /** From the Settings tab: Favorites, open it, start the first favorite app. */
    private fun favoritesAndLaunch(activation: TabActivation, fast: Boolean) {
        left()
        assertEquals("Favorites", focusedLabel())
        if (activation == TabActivation.Focus && !fast) {
            rest()
            settleUntil { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Add Favorite")).fetchSemanticsNodes().isNotEmpty() }
            // The page has switched; OK on the open tab moves into it.
            ok()
        } else {
            ok() // opens Favorites
            settleUntil { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Add Favorite")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Focus stays on the tab after opening it", "Favorites", focusedLabel())
            ok() // into the page
        }
        assertEquals("OK on the open tab moves into its page", "Aurora Play", focusedLabel())
        lastStartedPackage() // drain
        ok()
        settleUntil { lastStartedPackage() == "demo.aurora" }
        // The launch is also counted (Recently used); let that write finish before the test's database closes.
        settleUntil { vm.state.value.apps?.firstOrNull { it.packageName == "demo.aurora" }?.launchCount == 1 }
        assertEquals("The route needs no Back", 0, backPresses)
    }

    @Test fun focusModeRoute() {
        setActivation(TabActivation.Focus)
        openAppearance(TabActivation.Focus)
        sliderThenUpToTabs(fast = false)
        favoritesAndLaunch(TabActivation.Focus, fast = false)
    }

    @Test fun clickModeRoute() {
        setActivation(TabActivation.Select)
        openAppearance(TabActivation.Select)
        sliderThenUpToTabs(fast = false)
        favoritesAndLaunch(TabActivation.Select, fast = false)
    }

    @Test fun fastKeysRoute() {
        setActivation(TabActivation.Focus)
        openAppearance(TabActivation.Focus)
        sliderThenUpToTabs(fast = true)
        favoritesAndLaunch(TabActivation.Focus, fast = true)
    }

    @Test fun afterClosingADialog() {
        setActivation(TabActivation.Focus)
        openAppearance(TabActivation.Focus)
        ok() // Wallpaper picker
        settleUntil { vm.overlay.value is Overlay.WallpaperPicker }
        assertTrue(focusedLabel(), focusedLabel().startsWith("Current"))
        back() // closes the picker: the only Back in this test
        settleUntil { vm.overlay.value == null }
        assertEquals("Focus returns to the row that opened the dialog", "Wallpaper", focusedLabel())
        backPresses = 0
        sliderThenUpToTabs(fast = false)
        favoritesAndLaunch(TabActivation.Focus, fast = false)
    }

    @Test fun backOnTheTabsNeverPullsFocusIntoSettings() {
        openAppearance(TabActivation.Focus)
        sliderThenUpToTabs(fast = false)
        back()
        assertEquals("Back from the tabs goes Home, not into the Settings panel", "Home", focusedLabel())
        rest()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Aurora Play")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun backFromThePanelGoesToItsCategoryThenTheTab() {
        openAppearance(TabActivation.Focus)
        down(2)
        back()
        assertTrue(focusedLabel(), focusedLabel().startsWith("Appearance"))
        back()
        assertEquals("Settings", focusedLabel())
    }

    @Test fun searchDeepLinkWhileOnHomeOpensSettingsVisibly() {
        // Before: the deep link opened the category inside the hidden Settings page and moved focus there, invisibly.
        right(5); rest() // build Settings once
        left(5); rest() // back to Home, Settings stays alive hidden
        down()
        vm.openSettings("devices")
        settleUntil { focused().singleOrNull()?.toString()?.contains("Add device") == true }
        assertTrue("Settings is now the page on screen", rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Devices", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }
}
