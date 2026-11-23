package com.aether.tv.platform

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.aether.tv.core.designsystem.AetherIcons
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Native system screens Aether links to. Each has ordered intent candidates; the first that resolves wins. */
enum class SystemShortcut(val label: String, val description: String, val icon: ImageVector) {
    AllSettings("System settings", "Open the TV's own settings", AetherIcons.Settings),
    Network("Network & Wi-Fi", "Wi-Fi, Ethernet and connection status", AetherIcons.Wifi),
    Bluetooth("Bluetooth & controllers", "Pair remotes, headphones and controllers", AetherIcons.Bluetooth),
    Display("Display", "Resolution, picture and HDR", AetherIcons.Monitor),
    Sound("Sound", "Audio output and surround", AetherIcons.Volume2),
    Accessibility("Accessibility", "Captions, text size and screen reader", AetherIcons.Accessibility),
    Applications("Applications", "Manage installed apps", AetherIcons.AppWindow),
    Storage("Storage", "Free space and storage devices", AetherIcons.HardDrive),
    Screensaver("Screensaver", "Screensaver and sleep timing", AetherIcons.Moon),
    StartScreensaver("Start screensaver", "Start the screensaver now", AetherIcons.Moon),
    DateTime("Date & time", "Time zone and clock format", AetherIcons.Clock),
    About("About this TV", "Device information and system updates", AetherIcons.Info),
    SystemUpdate("System update", "Check for software updates", AetherIcons.Download),
    LiveTv("Live TV", "Open the TV's live TV / channels app", AetherIcons.LiveTv),
    DeveloperOptions("Developer options", "ADB and developer settings", AetherIcons.Code),
    HomeApp("Default home app", "Choose the Home app, where the platform allows it", AetherIcons.Home),
    NotificationAccess("Notification access", "Allow Aether to read notifications and media sessions", AetherIcons.Bell),
    OverlayPermission("Display over other apps", "Needed only for “open Aether after boot”", AetherIcons.Layers),
}

sealed interface LaunchResult {
    data class Opened(val via: String) : LaunchResult
    data class Unavailable(val reason: String) : LaunchResult
}

@Singleton
class SystemShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platform: PlatformInfo,
) {
    private val pm get() = context.packageManager

    fun candidates(s: SystemShortcut): List<Intent> {
        val amazon = platform.isFireTv
        fun action(a: String) = Intent(a)
        fun comp(pkg: String, cls: String) = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, cls))
        return when (s) {
            SystemShortcut.AllSettings -> listOfNotNull(
                if (amazon) comp("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity") else null,
                action(Settings.ACTION_SETTINGS),
            )
            SystemShortcut.Network -> listOfNotNull(
                if (amazon) comp("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.network.NetworkActivity") else null,
                action(Settings.ACTION_WIFI_SETTINGS), action(Settings.ACTION_WIRELESS_SETTINGS),
            )
            // Fire OS 14 has no handler for ACTION_BLUETOOTH_SETTINGS; Amazon's own screen is exported (verified on the M450).
            SystemShortcut.Bluetooth -> listOfNotNull(
                if (amazon) comp("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity") else null,
                action("android.settings.CONTROLLERS_SETTINGS"),
                action(Settings.ACTION_BLUETOOTH_SETTINGS),
            )
            SystemShortcut.Display -> listOf(action(Settings.ACTION_DISPLAY_SETTINGS))
            SystemShortcut.Sound -> listOf(action(Settings.ACTION_SOUND_SETTINGS))
            SystemShortcut.Accessibility -> listOf(action(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SystemShortcut.Applications -> listOf(action(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), action(Settings.ACTION_APPLICATION_SETTINGS))
            SystemShortcut.Storage -> listOf(action(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
            SystemShortcut.Screensaver -> listOf(action(Settings.ACTION_DREAM_SETTINGS))
            SystemShortcut.StartScreensaver -> listOf(comp("com.android.systemui", "com.android.systemui.Somnambulator"))
            SystemShortcut.DateTime -> listOf(action(Settings.ACTION_DATE_SETTINGS))
            SystemShortcut.About -> listOf(action(Settings.ACTION_DEVICE_INFO_SETTINGS))
            SystemShortcut.SystemUpdate -> listOf(action("android.settings.SYSTEM_UPDATE_SETTINGS"), action(Settings.ACTION_DEVICE_INFO_SETTINGS))
            SystemShortcut.LiveTv -> listOf(
                Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.Channels.CONTENT_URI),
                Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelsUriForInput(null)),
            )
            SystemShortcut.DeveloperOptions -> listOf(action(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            SystemShortcut.HomeApp -> listOf(action(Settings.ACTION_HOME_SETTINGS), action(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            SystemShortcut.NotificationAccess -> listOf(action(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            SystemShortcut.OverlayPermission -> listOf(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                action(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            )
        }
    }

    /** Returns the first candidate that resolves to a real (non-stub) activity, or null. */
    fun resolve(s: SystemShortcut): Intent? = candidates(s).firstOrNull { isUsable(it) }

    fun isAvailable(s: SystemShortcut) = resolve(s) != null

    fun isUsable(intent: Intent): Boolean {
        val ri = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrNull() ?: return false
        val ai = ri.activityInfo ?: return false
        // Fire OS resolves many standard settings actions to a CTS stub that shows nothing.
        if (ai.name.contains("CTSDummy", ignoreCase = true) || ai.name.contains("Dummy", ignoreCase = false)) return false
        if (!ai.exported && ai.packageName != context.packageName) return false
        if (ai.permission != null && context.checkSelfPermission(ai.permission) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    /** Amazon's launcher can be disabled ("Aether only" mode); its Settings menu goes with it. */
    fun amazonLauncherDisabled(): Boolean = platform.isFireTv && runCatching {
        pm.getApplicationEnabledSetting(AMAZON_LAUNCHER) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }.getOrDefault(false)

    fun open(s: SystemShortcut): LaunchResult {
        for (intent in candidates(s)) {
            if (!isUsable(intent)) continue
            try {
                // Fire TV draws Settings inside Amazon's launcher: tell the Home button service not to cover it.
                if (platform.isFireTv && s != SystemShortcut.LiveTv && s != SystemShortcut.StartScreensaver) HomeButtonService.beginSettingsSession()
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return LaunchResult.Opened(intent.component?.flattenToShortString() ?: intent.action ?: "intent")
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        EventLog.log("System screen unavailable: ${s.name}")
        if (amazonLauncherDisabled() && s in LAUNCHER_HOSTED) {
            return LaunchResult.Unavailable(
                "Amazon's home screen is switched off (Aether-only mode), and the full Fire TV Settings menu is part of it.\n\n" +
                    "Available here: Network, Bluetooth, Applications and Device. The remote's Settings (⚙) button opens " +
                    "Picture, Sound and Inputs.\n\nFor everything else, switch Amazon's launcher back on from a computer:\n" +
                    "scripts/launcher-mode.sh full-settings --apply",
            )
        }
        return LaunchResult.Unavailable("This TV doesn't let apps open “${s.label}” directly. Use the remote's Settings (⚙) button instead.")
    }

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        /** Screens only Amazon's launcher can open on Fire OS 14 (they require its private permission or live inside it). */
        val LAUNCHER_HOSTED = setOf(SystemShortcut.AllSettings, SystemShortcut.Display, SystemShortcut.Sound,
            SystemShortcut.Accessibility, SystemShortcut.Screensaver, SystemShortcut.DateTime, SystemShortcut.Storage)
    }
}
