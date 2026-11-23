package com.aether.tv.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.TestEnv
import com.aether.tv.core.model.LauncherTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Drives the real launcher UI with D-pad key events only — no touch. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class NavigationTest : LauncherUiTest() {
    @Test fun initialFocusIsFirstFavoriteApp() {
        rule.onNodeWithContentDescription("Aurora Play").assertIsFocused()
    }

    @Test fun dpadMovesAlongTheAppRow() {
        key(Key.DirectionRight)
        rule.onNodeWithContentDescription("Cinema+").assertIsFocused()
        key(Key.DirectionRight, 2)
        rule.onNodeWithContentDescription("Prime Reel").assertIsFocused()
        key(Key.DirectionLeft)
        rule.onNodeWithContentDescription("StarStream").assertIsFocused()
    }

    @Test fun upReachesSelectedTabAndRightSwitchesPage() {
        key(Key.DirectionRight, 3) // focus a tile under a different tab
        key(Key.DirectionUp)
        rule.onNodeWithContentDescription("Home").assertIsFocused() // the selected tab, not the nearest one
        key(Key.DirectionRight)
        rule.onNodeWithContentDescription("Apps").assertIsFocused()
        rest()
        rule.onNodeWithText("Search apps…").assertExists()
    }

    @Test fun sweepingAcrossTabsOnlyLoadsThePageYouStopOn() {
        key(Key.DirectionUp)
        key(Key.DirectionRight, 5) // Apps, Media, Games, Favorites, Settings — no pause on the way
        rule.onNodeWithContentDescription("Settings").assertIsFocused()
        rule.onNodeWithText("Search apps…").assertDoesNotExist() // Apps page was never built
        rest()
        rule.onNodeWithText("TV & System").assertExists() // the page you stopped on
    }

    @Test fun pagesAreBuiltOnceAndKeptAlive() {
        val behavior = hasContentDescription("Behavior.", substring = true)
        key(Key.DirectionUp); key(Key.DirectionRight, 5); rest() // Settings
        key(Key.DirectionDown, 4) // Overview, Appearance, Home Layout, Behavior
        assertTrue("focused: ${focused()}", focused().any { it.toString().contains("Behavior.") })
        key(Key.Back) // category list -> tabs
        key(Key.DirectionLeft, 5); rest() // Home
        rule.onNode(behavior).assertDoesNotExist() // the hidden Settings page is out of the semantics tree
        key(Key.DirectionRight, 5); rest() // Settings again
        rule.onNode(behavior).assertExists()
        assertEquals(1, PagePrewarm.builds[LauncherTab.Settings]) // shown again, not rebuilt
        assertEquals(1, PagePrewarm.builds[LauncherTab.Home])
    }

    @Test fun focusNeverMovesIntoAHiddenPage() {
        key(Key.DirectionUp); key(Key.DirectionRight); rest() // build the Apps page
        rule.onNodeWithText("Search apps…").assertExists()
        key(Key.DirectionLeft); rest() // back to Home; Apps stays alive, hidden
        key(Key.DirectionDown)
        key(Key.DirectionRight, 15); key(Key.DirectionDown, 6); key(Key.DirectionRight, 6)
        // Focus is still on something visible: a node inside a hidden page would not be in the semantics tree.
        assertEquals(1, rule.onAllNodes(isFocused()).fetchSemanticsNodes().size)
        rule.onNodeWithText("Search apps…").assertDoesNotExist()
    }

    @Test fun backFromContentGoesToTabsThenHome() {
        key(Key.DirectionUp); key(Key.DirectionRight, 4) // Favorites tab
        rule.onNodeWithContentDescription("Favorites").assertIsFocused()
        rest()
        key(Key.DirectionDown)
        key(Key.Back)
        rule.onNodeWithContentDescription("Favorites").assertIsFocused()
        key(Key.Back)
        rule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test fun menuKeyOpensOptionsAndBackClosesThem() {
        key(Key.Menu)
        rule.onNodeWithText("Remove from Favorites").assertExists()
        assertTrue(vm.overlay.value is Overlay.AppOptions)
        key(Key.Back)
        rule.waitForIdle()
        assertEquals(null, vm.overlay.value)
        rule.onNodeWithContentDescription("Aurora Play").assertIsFocused()
    }

    @Test fun moveModeReordersWithTheRemote() {
        key(Key.Menu)
        key(Key.DirectionDown, 2) // Open, Remove from Favorites, Move
        key(Key.Enter)
        assertTrue(vm.moveMode.value != null)
        key(Key.DirectionRight)
        settleUntil { vm.state.value.favoriteApps.first().second.label == "Cinema+" }
        key(Key.Enter)
        assertEquals(null, vm.moveMode.value)
    }

    @Test fun settingsSliderChangesWithDpad() {
        key(Key.DirectionUp); key(Key.DirectionRight, 5) // Settings tab
        rule.onNodeWithContentDescription("Settings").assertIsFocused()
        rest()
        key(Key.DirectionDown, 2) // Overview, Appearance
        key(Key.DirectionRight) // opens Appearance detail
        settleUntil { true }; Thread.sleep(150); settleUntil { true }
        val before = vm.state.value.settings.appearance.glassOpacity
        // Wallpaper, theme, accent, glass rendering, opacity
        key(Key.DirectionDown, 4)
        key(Key.DirectionRight)
        settleUntil { vm.state.value.settings.appearance.glassOpacity > before }
        assertEquals(before + 0.02f, vm.state.value.settings.appearance.glassOpacity, 0.001f)
    }

    @Test fun searchFindsApps() {
        vm.show(Overlay.Search)
        settleUntil { true }
        assertEquals(LauncherTab.Home, LauncherTab.Home)
        rule.onNodeWithText("Apps, games, devices, settings, media").assertExists()
    }
}
