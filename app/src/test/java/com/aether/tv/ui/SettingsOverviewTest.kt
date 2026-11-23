package com.aether.tv.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aether.tv.core.model.FontChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings ▸ Overview with the remote only: entering, every card, Left/Up/Back, and focus coming back to the card. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class SettingsOverviewTest : LauncherUiTest() {
    private val xbox = "Xbox Series X, HDMI 1, Connected"

    private fun toOverview() {
        up(); right(5); rest()
        down()
        assertTrue(focusedLabel(), focusedLabel().startsWith("Overview"))
    }

    private fun intoDashboard(withOk: Boolean = false) {
        if (withOk) ok() else right()
        settleUntil { focused().singleOrNull()?.toString()?.contains("Xbox Series X") == true }
        assertEquals(xbox, focusedLabel())
    }

    @Test fun rightAndOkBothEnterTheDashboard() {
        toOverview(); intoDashboard(withOk = false)
        left()
        assertTrue("◀ from the leftmost card returns to Overview", focusedLabel().startsWith("Overview"))
        intoDashboard(withOk = true)
    }

    @Test fun addDeviceWithANestedPickerKeepsTheEditAndReturnsToTheCard() {
        toOverview(); intoDashboard()
        up()
        assertEquals("▲ from the devices goes to Add Device", "Add Device", focusedLabel())
        ok()
        settleUntil { (vm.overlay.value as? Overlay.DeviceEditor)?.deviceId == null && vm.overlay.value is Overlay.DeviceEditor }
        settleUntil { focused().singleOrNull()?.toString()?.contains("Name") == true }
        down(3) // Type, When selected, TV input
        assertEquals("TV input", focusedLabel())
        ok() // the input picker opens on top of the editor
        settleUntil { vm.overlay.value is Overlay.Choice }
        down() // "Not assigned" -> HDMI 1
        ok()
        settleUntil { vm.overlay.value is Overlay.DeviceEditor }
        assertEquals("Focus goes back to the row that opened the picker", "TV input", focusedLabel())
        assertTrue("The editor kept the chosen input", rule.onAllNodes(androidx.compose.ui.test.hasText("HDMI 1", substring = true)).fetchSemanticsNodes().isNotEmpty())
        back()
        settleUntil { vm.overlay.value == null }
        assertEquals("Closing the editor returns to Add Device", "Add Device", focusedLabel())
        up()
        assertEquals("▲ from Add Device reaches the tabs", "Settings", focusedLabel())
    }

    @Test fun xboxOpensItsEditorAndBackReturnsToTheCard() {
        toOverview(); intoDashboard()
        ok()
        settleUntil { (vm.overlay.value as? Overlay.DeviceEditor)?.deviceId == "xbox-series-x" }
        back()
        settleUntil { vm.overlay.value == null }
        assertEquals(xbox, focusedLabel())
        back()
        assertTrue("Back from a card goes to Overview", focusedLabel().startsWith("Overview"))
    }

    @Test fun wallpaperOpensThePickerAndPreviewAndBackComesHome() {
        toOverview(); intoDashboard()
        down()
        assertTrue(focusedLabel(), focusedLabel().startsWith("Wallpaper"))
        val before = vm.state.value.settings.appearance.wallpaper
        ok()
        settleUntil { vm.overlay.value is Overlay.WallpaperPicker }
        down(2) // Current -> Alpine Dusk -> Blue Hour
        assertEquals("Blue Hour", focusedLabel())
        ok()
        settleUntil { vm.overlay.value is Overlay.WallpaperPreview }
        settleUntil(30_000) { focused().singleOrNull()?.toString()?.contains("Apply") == true }
        back() // cancel the preview
        settleUntil { vm.overlay.value is Overlay.WallpaperPicker }
        assertEquals("Back from the preview returns to the picker entry", "Blue Hour", focusedLabel())
        assertEquals("Nothing was applied", before, vm.state.value.settings.appearance.wallpaper)
        back()
        settleUntil { vm.overlay.value == null }
        assertTrue(focusedLabel(), focusedLabel().startsWith("Wallpaper"))
        left()
        assertTrue("◀ from the leftmost card returns to Overview", focusedLabel().startsWith("Overview"))
    }

    @Test fun applyingAPreviewSavesItAndReturnsToTheCard() {
        toOverview(); intoDashboard(); down()
        ok(); settleUntil { vm.overlay.value is Overlay.WallpaperPicker }
        down(3) // Moonlit Night
        ok(); settleUntil(30_000) { focused().singleOrNull()?.toString()?.contains("Apply") == true }
        ok()
        settleUntil { vm.state.value.settings.appearance.wallpaper == "builtin:night" }
        settleUntil { vm.overlay.value == null }
        assertTrue(focusedLabel(), focusedLabel().startsWith("Wallpaper"))
    }

    @Test fun typographyOpensTheEditorAndReturnsToTheCard() {
        toOverview(); intoDashboard(); down()
        right(4)
        assertTrue(focusedLabel(), focusedLabel().startsWith("Typography"))
        right()
        assertTrue("▶ stops at the last card", focusedLabel().startsWith("Typography"))
        ok()
        settleUntil { vm.overlay.value is Overlay.Typography }
        settleUntil { focused().singleOrNull()?.toString()?.contains("Typeface") == true }
        right() // next typeface in place
        settleUntil { vm.state.value.settings.appearance.font == FontChoice.Atkinson }
        down() // text size
        right(2)
        settleUntil { vm.state.value.settings.appearance.textScale > 1.09f }
        assertEquals(1.10f, vm.state.value.settings.appearance.textScale, 0.001f)
        back()
        settleUntil { vm.overlay.value == null }
        assertTrue(focusedLabel(), focusedLabel().startsWith("Typography"))
    }

    @Test fun themeAccentAndGlassOpenTheirOwnControls() {
        toOverview(); intoDashboard(); down()
        right(); ok()
        settleUntil { (vm.overlay.value as? Overlay.Choice)?.title == "Theme" }
        back(); settleUntil { vm.overlay.value == null }
        assertTrue(focusedLabel(), focusedLabel().startsWith("Theme"))
        right(); ok()
        settleUntil { vm.overlay.value is Overlay.GlassSettings }
        back(); settleUntil { vm.overlay.value == null }
        assertTrue(focusedLabel(), focusedLabel().startsWith("Glass"))
        right(); ok()
        settleUntil { (vm.overlay.value as? Overlay.Choice)?.title == "Accent color" }
        back(); settleUntil { vm.overlay.value == null }
        assertTrue(focusedLabel(), focusedLabel().startsWith("Accent"))
    }
}
