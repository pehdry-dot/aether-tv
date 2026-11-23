package com.aether.tv.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The TV's IP address: top bar, Quick Controls, Settings ▸ TV & System, and the adb instructions. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi", application = android.app.Application::class)
class IpAddressTest : LauncherUiTest() {
    private val ip = UiHarness.DEMO_IP
    private fun topBarShowsIp() = rule.onAllNodes(hasContentDescription("IP address $ip")).fetchSemanticsNodes().isNotEmpty()
    private fun textShown(t: String) = rule.onAllNodes(hasText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun topBarShowsTheAddressAndTheSwitchHidesIt() {
        settleUntil { topBarShowsIp() }
        runBlocking { env.settings.update("default") { it.copy(layout = it.layout.copy(showIpAddress = false)) } }
        settleUntil { !topBarShowsIp() }
        runBlocking { env.settings.update("default") { it.copy(layout = it.layout.copy(showIpAddress = true)) } }
        settleUntil { topBarShowsIp() }
    }

    @Test fun noAddressMeansNothingIsShown() {
        vm.testStateTransform.value = { s -> UiHarness.transform(s).let { it.copy(network = it.network.copy(ipAddress = null)) } }
        settleUntil { vm.state.value.network.ipAddress == null }
        assertFalse(topBarShowsIp())
        vm.openSettings("system")
        settleUntil { focused().singleOrNull()?.toString()?.contains("Storage") == true }
        down()
        assertEquals("IP address", focusedLabel())
        assertTrue(textShown("no address reported"))
    }

    @Test fun quickControlsShowTheAddress() {
        vm.show(Overlay.QuickControls)
        settleUntil { textShown("IP $ip") }
    }

    @Test fun settingsRowGivesTheAdbConnectCommand() {
        vm.openSettings("system")
        settleUntil { focused().singleOrNull()?.toString()?.contains("Storage") == true }
        down()
        assertEquals("IP address", focusedLabel())
        assertTrue(textShown(ip))
        ok()
        settleUntil { vm.overlay.value is Overlay.Message }
        val msg = vm.overlay.value as Overlay.Message
        assertTrue(msg.message, msg.message.contains("adb connect $ip:5555"))
        back()
        settleUntil { vm.overlay.value == null && focusedLabel() == "IP address" }
    }
}
