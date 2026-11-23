package com.aether.tv.unit

import com.aether.tv.core.model.AppInfo
import com.aether.tv.core.model.AppSort
import com.aether.tv.core.model.DeviceKind
import com.aether.tv.core.model.DeviceStatus
import com.aether.tv.core.model.LauncherSettings
import com.aether.tv.core.model.TvInput
import com.aether.tv.data.apps.AppRepository
import com.aether.tv.data.db.DeviceEntity
import com.aether.tv.data.devices.DeviceRepository
import com.aether.tv.data.media.normalizeServerUrl
import com.aether.tv.data.settings.AetherJson
import com.aether.tv.data.settings.SettingsRepository
import com.aether.tv.platform.BluetoothPeer
import com.aether.tv.platform.Controller
import com.aether.tv.platform.PeripheralSnapshot
import com.aether.tv.platform.TvInputController
import com.aether.tv.ui.apps.AppFilter
import com.aether.tv.ui.apps.filterApps
import com.aether.tv.ui.home.homeDevices
import com.aether.tv.ui.settings.moved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSerializationTest {
    @Test fun missingJsonGivesDefaults() = assertEquals(LauncherSettings(), SettingsRepository.decode(null))
    @Test fun corruptJsonFallsBackToDefaults() = assertEquals(LauncherSettings(), SettingsRepository.decode("{not json"))
    @Test fun unknownFieldsAreIgnored() {
        val s = SettingsRepository.decode("""{"appearance":{"glassOpacity":0.2,"futureField":true},"somethingNew":1}""")
        assertEquals(0.2f, s.appearance.glassOpacity, 0.0001f)
    }
    @Test fun roundTripPreservesValues() {
        val s = LauncherSettings().let { it.copy(appearance = it.appearance.copy(cornerRadiusDp = 22, showAppLabels = false), layout = it.layout.copy(appShortcutCount = 6)) }
        val decoded = SettingsRepository.decode(AetherJson.encodeToString(LauncherSettings.serializer(), s))
        assertEquals(s, decoded)
    }
}

class AppLibraryLogicTest {
    private fun app(label: String, tv: Boolean = true, hidden: Boolean = false, game: Boolean = false, launches: Int = 0, last: Long = 0, installed: Long = 0, cat: String? = null, fav: Boolean = false) =
        AppInfo("p.$label/p.$label.Main", "p.$label", "p.$label.Main", label, null, tv, false, game, null, installed, hidden, fav, cat, null, launches, last)

    private val apps = listOf(
        app("Netflixy", launches = 5, last = 30, installed = 1),
        app("alpha", launches = 9, last = 10, installed = 3),
        app("Zeta", tv = false, last = 20, installed = 2),
        app("Hidden One", hidden = true),
        app("Racer", game = true, cat = "Kids"),
    )

    @Test fun sortsByNameCaseInsensitive() = assertEquals(listOf("alpha", "Hidden One", "Netflixy", "Racer", "Zeta"), AppRepository.sort(apps, AppSort.NameAsc).map { it.label })
    @Test fun sortsByRecentAndUsage() {
        assertEquals("Netflixy", AppRepository.sort(apps, AppSort.Recent).first().label)
        assertEquals("alpha", AppRepository.sort(apps, AppSort.MostUsed).first().label)
        assertEquals("alpha", AppRepository.sort(apps, AppSort.Installed).first().label)
    }
    @Test fun hiddenAndNonTvFiltering() {
        val v = AppRepository.visible(apps, showHidden = false, showNonTv = false).map { it.label }
        assertFalse("Hidden One" in v); assertFalse("Zeta" in v); assertTrue("alpha" in v)
        assertTrue("Hidden One" in AppRepository.visible(apps, true, true).map { it.label })
    }
    @Test fun searchRanksPrefixFirst() {
        assertTrue(AppRepository.matchScore("Netflixy", "net") > AppRepository.matchScore("Planet", "net"))
        assertEquals(0, AppRepository.matchScore("Zeta", "xyz"))
        assertTrue(AppRepository.matchScore("Steam Link", "link") > 0)
        assertTrue(AppRepository.matchScore("Prime Video", "pvd") > 0) // subsequence
    }
    @Test fun filtersByCategoryGamesAndHidden() {
        assertEquals(listOf("Racer"), filterApps(apps, AppFilter.Games, "", false, true, AppSort.NameAsc).map { it.label })
        assertEquals(listOf("Racer"), filterApps(apps, AppFilter.Category("Kids"), "", false, true, AppSort.NameAsc).map { it.label })
        assertEquals(listOf("Hidden One"), filterApps(apps, AppFilter.Hidden, "", false, true, AppSort.NameAsc).map { it.label })
        assertEquals(listOf("alpha"), filterApps(apps, AppFilter.All, "alp", false, true, AppSort.NameAsc).map { it.label })
    }
    @Test fun customLabelOverridesSystemLabel() = assertEquals("My TV", app("x").copy(customLabel = "My TV").label)
    @Test fun listMoveClampsAtEdges() {
        assertEquals(listOf(2, 1, 3), listOf(1, 2, 3).moved(1, -1))
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(0, -1))
        assertEquals(listOf(1, 3, 2), listOf(1, 2, 3).moved(1, 5))
    }
}

class DeviceStatusTest {
    private val noPeripherals = PeripheralSnapshot(true, true, true, emptyList(), emptyList())
    private fun input(id: String, state: DeviceStatus, port: Int? = 1) = TvInput(id, "HDMI ${port ?: "?"}", 1007, port, state, true)
    private fun device(inputId: String? = null, bt: String? = null, port: Int? = null) =
        DeviceEntity(id = "d", name = "Xbox Series X", kind = DeviceKind.Xbox.name, inputId = inputId, bluetoothAddress = bt, hdmiPort = port)

    @Test fun unconfiguredShortcutIsNeverConnected() {
        val d = DeviceRepository.resolve(listOf(device()), emptyList(), noPeripherals, frameworkAvailable = false).single()
        assertEquals(DeviceStatus.Shortcut, d.status)
        assertEquals("Not set up", d.detail)
    }
    @Test fun inputStateIsReportedWhenThePlatformKnowsIt() {
        val inputs = listOf(input("hw/HW2", DeviceStatus.Connected), input("hw/HW3", DeviceStatus.Standby, 2))
        assertEquals(DeviceStatus.Connected, DeviceRepository.resolve(listOf(device("hw/HW2")), inputs, noPeripherals, true).single().status)
        assertEquals(DeviceStatus.Standby, DeviceRepository.resolve(listOf(device("hw/HW3")), inputs, noPeripherals, true).single().status)
    }
    @Test fun missingInputIsUnavailableOnlyWhenInputsAreListed() {
        val listed = listOf(input("hw/HW2", DeviceStatus.Connected))
        assertEquals(DeviceStatus.Unavailable, DeviceRepository.resolve(listOf(device("gone")), listed, noPeripherals, true).single().status)
        // Platform lists nothing: we can't know, so it stays a plain shortcut.
        assertEquals(DeviceStatus.Shortcut, DeviceRepository.resolve(listOf(device("gone", port = 1)), emptyList(), noPeripherals, false).single().status)
    }
    @Test fun bluetoothStates() {
        val peers = listOf(BluetoothPeer("Headphones", "AA", DeviceKind.Headphones, connected = true), BluetoothPeer("Pad", "BB", DeviceKind.Controller, connected = false))
        val snap = noPeripherals.copy(peers = peers)
        assertEquals(DeviceStatus.Connected, DeviceRepository.resolve(listOf(device(bt = "AA")), emptyList(), snap, false).first().status)
        assertEquals(DeviceStatus.Paired, DeviceRepository.resolve(listOf(device(bt = "BB")), emptyList(), snap, false).first().status)
        assertEquals(DeviceStatus.Unavailable, DeviceRepository.resolve(listOf(device(bt = "CC")), emptyList(), snap, false).first().status)
        // Without permission we can't verify anything.
        assertEquals(DeviceStatus.Shortcut, DeviceRepository.resolve(listOf(device(bt = "AA")), emptyList(), snap.copy(bluetoothPermission = false), false).first().status)
    }
    @Test fun discoveredDevicesAppendedAndHomeShowsOnlyActiveOnes() {
        val snap = noPeripherals.copy(
            peers = listOf(BluetoothPeer("Buds", "AA", DeviceKind.Headphones, true), BluetoothPeer("Old speaker", "BB", DeviceKind.Speaker, false)),
            controllers = listOf(Controller("Wireless Controller", "desc", 1, 2)),
        )
        val all = DeviceRepository.resolve(listOf(device()), emptyList(), snap, false)
        assertEquals(4, all.size)
        val home = homeDevices(all, 5).map { it.name }
        assertEquals(listOf("Xbox Series X", "Buds", "Wireless Controller"), home)
    }
}

class ParsingTest {
    @Test fun hardwareIndex() {
        assertEquals(2, TvInputController.parseHardwareIndex("com.mediatek.tvinput/.hdmi.HDMIInputService/HW2"))
        assertEquals(10, TvInputController.parseHardwareIndex("x/HW10"))
        assertNull(TvInputController.parseHardwareIndex("com.example/.Tuner"))
    }
    @Test fun hdmiPort() {
        assertEquals(3, TvInputController.parseHdmiPort("HDMI 3", "x"))
        assertEquals(2, TvInputController.parseHdmiPort("HDMI2 (ARC)", "x"))
        assertNull(TvInputController.parseHdmiPort("Tuner", "x/HW4"))
    }
    @Test fun serverUrls() {
        assertEquals("http://192.168.1.2:8096", normalizeServerUrl("192.168.1.2", 8096))
        assertEquals("https://media.example:443", normalizeServerUrl("https://media.example:443/", 8096))
        assertEquals("http://kodi.local:9090", normalizeServerUrl("kodi.local:9090", 8080))
    }
}
