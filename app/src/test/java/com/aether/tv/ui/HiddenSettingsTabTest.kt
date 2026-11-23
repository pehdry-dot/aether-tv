package com.aether.tv.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.core.model.LauncherTab
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings ▸ Home Layout can hide the Settings tab; the ⚙ button and every shortcut still reach Settings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class HiddenSettingsTabTest : LauncherUiTest() {
    private fun settingsTabShown() = rule.onAllNodes(hasContentDescription("Settings")).fetchSemanticsNodes().isNotEmpty()

    private fun hideSettings(profile: String = "default", hidden: Boolean = true) = runBlocking {
        env.settings.update(profile) { s -> s.copy(layout = s.layout.copy(tabs = s.layout.tabs.map { if (it.tab == LauncherTab.Settings) it.copy(visible = !hidden) else it })) }
    }

    @Test fun hideAndRestoreWhileEditingTheLayout() {
        up(); right(5); rest()
        down(3) // Overview, Appearance, Home Layout
        assertTrue(focusedLabel().startsWith("Home Layout"))
        right()
        settleUntil { focused().singleOrNull()?.toString()?.contains("Favorite apps") == true }
        down(4 + 5) // 4 section rows, then the tab rows Home..Favorites -> Settings
        assertEquals("Settings", focusedLabel())
        ok()
        settleUntil { LauncherTab.Settings !in vm.state.value.visibleTabs }
        assertFalse("The Settings tab is gone from the tab row", rule.onAllNodes(hasContentDescription("Settings").and(androidx.compose.ui.test.isSelectable())).fetchSemanticsNodes().isNotEmpty())
        assertEquals("Focus stays on the row you changed", "Settings", focusedLabel())
        assertTrue("The page stays open, reached through ⚙", rule.onAllNodes(hasContentDescription("Open settings").and(androidx.compose.ui.test.isSelected())).fetchSemanticsNodes().isNotEmpty())
        ok()
        settleUntil { LauncherTab.Settings in vm.state.value.visibleTabs }
        assertEquals("Settings", focusedLabel())
        up(20) // back to the top of the panel, then the tabs: the restored tab is the open page's
        settleUntil { true }
        assertTrue(rule.onAllNodes(hasContentDescription("Settings").and(androidx.compose.ui.test.isSelected())).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun gearOpensSettingsWithoutATabAndBackReturns() {
        hideSettings()
        settleUntil { LauncherTab.Settings !in vm.state.value.visibleTabs }
        assertEquals(listOf(LauncherTab.Home, LauncherTab.Apps, LauncherTab.Media, LauncherTab.Games, LauncherTab.Favorites), vm.state.value.visibleTabs)
        up() // Home tab
        up() // top bar
        right(10) // the ⚙ button is the last icon
        assertEquals("Open settings", focusedLabel())
        ok()
        settleUntil { focused().singleOrNull()?.toString()?.startsWith("[Overview") == true }
        down()
        assertTrue(focusedLabel().startsWith("Appearance"))
        back()
        assertEquals("Back from a Settings page with no tab returns to the tab you came from", "Home", focusedLabel())
        rest()
        assertTrue(rule.onAllNodes(hasContentDescription("Aurora Play")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun internalShortcutsWorkWithoutATab() {
        hideSettings()
        settleUntil { LauncherTab.Settings !in vm.state.value.visibleTabs }
        vm.openSettings("devices")
        settleUntil { focused().singleOrNull()?.toString()?.contains("Add device") == true }
        vm.openSettings("integrations")
        settleUntil { focused().singleOrNull()?.toString()?.contains("Notification") == true }
    }

    @Test fun profilesHaveTheirOwnTabRow() {
        runBlocking { env.profiles.create("Kids", 0xFFFFD9A6, copySettingsFrom = "default") }
        val kids = runBlocking { env.db.profiles().all().first { it.name == "Kids" } }
        hideSettings(kids.id)
        settleUntil { vm.state.value.profiles.any { it.id == kids.id } }
        vm.switchProfile(kids.id)
        settleUntil { vm.state.value.profileId == kids.id && LauncherTab.Settings !in vm.state.value.visibleTabs }
        vm.switchProfile("default")
        settleUntil { vm.state.value.profileId == "default" && LauncherTab.Settings in vm.state.value.visibleTabs }
        assertTrue(settingsTabShown())
    }

    @Test fun startTabSettingsFallsBackHomeWhenHidden() {
        runBlocking { env.settings.update("default") { it.copy(layout = it.layout.copy(defaultTab = LauncherTab.Settings)) } }
        hideSettings()
        settleUntil { vm.state.value.settings.layout.defaultTab == LauncherTab.Home }
    }

    @Test fun homeButtonClosesOverlaysAndShowsHome() {
        up(); right(5); rest(); down(2); right() // Settings ▸ Appearance
        settleUntil { focused().singleOrNull()?.toString()?.contains("Wallpaper") == true }
        ok()
        settleUntil { vm.overlay.value is Overlay.WallpaperPicker }
        rule.runOnUiThread { vm.onHomePressed() }
        settleUntil { vm.overlay.value == null }
        settleUntil { focused().singleOrNull()?.toString()?.contains("Aurora Play") == true }
        assertTrue(rule.onAllNodes(hasContentDescription("Home").and(androidx.compose.ui.test.isSelected())).fetchSemanticsNodes().isNotEmpty())
        // From Home, Home again is harmless and keeps the first app focused.
        right(2)
        rule.runOnUiThread { vm.onHomePressed() }
        settleUntil { focused().singleOrNull()?.toString()?.contains("Aurora Play") == true }
    }
}
