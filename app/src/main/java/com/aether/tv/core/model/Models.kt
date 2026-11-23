package com.aether.tv.core.model

import android.content.ComponentName

/** A launchable activity discovered through PackageManager, merged with user metadata. */
data class AppInfo(
    val key: String,              // flattened ComponentName "pkg/cls"
    val packageName: String,
    val activityName: String,
    val systemLabel: String,
    val customLabel: String? = null,
    val isTvApp: Boolean,         // declares LEANBACK_LAUNCHER
    val isSystemApp: Boolean,
    val isGame: Boolean,
    val gameKind: GameKind? = null,
    val installedAt: Long = 0,
    val hidden: Boolean = false,
    val favorite: Boolean = false,
    val category: String? = null,
    val customIconUri: String? = null,
    val launchCount: Int = 0,
    val lastLaunchedAt: Long = 0,
) {
    val label: String get() = customLabel?.takeIf { it.isNotBlank() } ?: systemLabel
    val component: ComponentName get() = ComponentName(packageName, activityName)
}

/** How a game-like entry is played — Aether never implies a TV can run console-exclusive games. */
enum class GameKind(val label: String) {
    Local("Plays on this TV"),
    Streaming("Game streaming"),
    Emulator("Emulator"),
    Console("Via HDMI console"),
}

enum class FavoriteType { App, Device, Input, Media, System, Game }

data class Favorite(
    val id: Long,
    val type: FavoriteType,
    val targetKey: String,
    val title: String?,
    val artworkUri: String?,
    val group: String?,
    val order: Int,
)

/** Device categories with their own artwork and default copy. */
enum class DeviceKind(val label: String) {
    Xbox("Xbox console"),
    PlayStation("PlayStation console"),
    NintendoSwitch("Nintendo Switch"),
    Pc("PC"),
    MediaPlayer("Media player"),
    Headphones("Headphones"),
    Speaker("Speaker / soundbar"),
    Controller("Game controller"),
    Other("Other device"),
}

/**
 * Honest device status. Each value states exactly what Aether was able to verify.
 */
enum class DeviceStatus(val label: String) {
    /** Platform confirms an active link (HDMI signal present, Bluetooth audio connected, controller attached). */
    Connected("Connected"),
    /** HDMI input reports the device in standby (CEC). */
    Standby("Standby"),
    /** Bluetooth bonded, but no active audio connection reported. */
    Paired("Paired"),
    /** Platform reports the input/port exists but no signal. */
    NoSignal("No signal"),
    /** User-configured shortcut; the platform does not expose status for it. */
    Shortcut("Input shortcut"),
    /** The configured input or Bluetooth device could not be found. */
    Unavailable("Unavailable"),
}

enum class DeviceLaunchAction { SwitchInput, OpenApp, None }

data class ConnectedDevice(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val status: DeviceStatus,
    val detail: String,          // e.g. "HDMI 1", "Bluetooth"
    val inputId: String? = null,
    val hdmiPort: Int? = null,
    val bluetoothAddress: String? = null,
    val artworkUri: String? = null,
    val launchAction: DeviceLaunchAction = DeviceLaunchAction.None,
    val launchPackage: String? = null,
    val networkHost: String? = null,
    val configured: Boolean,     // true = user-configured shortcut, false = discovered only
    val favorite: Boolean = false,
    val showOnHome: Boolean = true,
)

/** A TV input exposed by TvInputManager. */
data class TvInput(
    val id: String,
    val label: String,
    val type: Int,
    val hdmiPort: Int?,
    val state: DeviceStatus,
    val isPassthrough: Boolean,
)

/** A media item supplied by a media provider adapter. Never synthesised. */
data class MediaItem(
    /** Stable, episode-level identity within its provider (also the card's key, so focus follows it). */
    val id: String,
    val providerId: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val progress: Float?,         // 0..1, only when the provider reports it
    val durationMs: Long? = null,
    val kind: MediaKind = MediaKind.Video,
    /** App that published it (Watch Next, channels, media sessions). */
    val packageName: String? = null,
    /** When the user last watched it (epoch ms), when the source says so. Drives the order on Home. */
    val engagedAt: Long? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** A live media session ("Now Playing"), not a resumable listing. */
    val nowPlaying: Boolean = false,
    /** The source's resume link, when it has one (deduplication across Watch Next and channels). */
    val link: String? = null,
)

enum class MediaKind { Video, Episode, Movie, Music, Live }
