package com.aether.tv.unit

import com.aether.tv.core.model.HomeLayoutSettings
import com.aether.tv.core.model.LauncherSettings
import com.aether.tv.core.model.LauncherTab
import com.aether.tv.core.model.TabConfig
import com.aether.tv.core.model.TextScale
import com.aether.tv.core.model.normalized
import com.aether.tv.core.model.visibleTabs
import com.aether.tv.data.settings.SettingsRepository
import com.aether.tv.ui.NavRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRulesTest {
    @Test fun olderProfilesKeepTheirSettingsTabAndStartTab() {
        // 1.1.0 always showed Settings whatever was stored, and could start on it.
        val json = """{"layout":{"tabs":[{"tab":"Home"},{"tab":"Apps"},{"tab":"Settings","visible":false},{"tab":"Favorites"}],"defaultTab":"Settings"}}"""
        val s = SettingsRepository.decode(json)
        assertTrue(s.layout.settingsTabHideable)
        assertEquals(LauncherTab.Settings, s.layout.defaultTab)
        assertEquals(listOf(LauncherTab.Home, LauncherTab.Apps, LauncherTab.Settings, LauncherTab.Favorites, LauncherTab.Media, LauncherTab.Games), s.layout.tabs.map { it.tab })
        assertTrue(LauncherTab.Settings in s.layout.visibleTabs())
    }

    @Test fun hidingTheStartTabFallsBackToHome() {
        val l = HomeLayoutSettings(settingsTabHideable = true, defaultTab = LauncherTab.Settings,
            tabs = LauncherTab.entries.map { TabConfig(it, visible = it != LauncherTab.Settings) })
        val n = LauncherSettings(layout = l).normalized()
        assertEquals(LauncherTab.Home, n.layout.defaultTab)
        assertTrue(LauncherTab.Settings !in n.layout.visibleTabs())
    }

    @Test fun homeCantBeHiddenAndDuplicatesAreDropped() {
        val l = HomeLayoutSettings(settingsTabHideable = true, tabs = listOf(TabConfig(LauncherTab.Home, false), TabConfig(LauncherTab.Apps), TabConfig(LauncherTab.Apps, false)))
        val n = LauncherSettings(layout = l).normalized().layout
        assertEquals(LauncherTab.entries.size, n.tabs.size)
        assertTrue(n.tabs.first { it.tab == LauncherTab.Home }.visible)
        assertTrue(n.tabs.first { it.tab == LauncherTab.Apps }.visible)
    }

    @Test fun detachedSettingsPageStaysOpen() {
        val visible = listOf(LauncherTab.Home, LauncherTab.Apps)
        assertEquals(LauncherTab.Settings, NavRules.resolve(LauncherTab.Settings, visible))
        assertEquals(LauncherTab.Home, NavRules.resolve(LauncherTab.Games, visible))
        assertEquals(LauncherTab.Home, NavRules.startTab(LauncherTab.Settings, visible))
    }

    @Test fun textScaleSteps() {
        assertEquals(0.9f, TextScale.clamp(0.5f), 1e-4f)
        assertEquals(1.5f, TextScale.clamp(2f), 1e-4f)
        assertEquals(1.15f, TextScale.clamp(1.16f), 1e-4f)
        assertEquals(1.5f, SettingsRepository.decode("""{"appearance":{"textScale":3.0}}""").appearance.textScale, 1e-4f)
    }
}
