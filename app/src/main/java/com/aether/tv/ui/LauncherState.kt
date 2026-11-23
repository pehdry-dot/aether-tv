package com.aether.tv.ui

import com.aether.tv.core.model.AppInfo
import com.aether.tv.core.model.ConnectedDevice
import com.aether.tv.core.model.Favorite
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.core.model.LauncherSettings
import com.aether.tv.core.model.LauncherTab
import com.aether.tv.core.model.MediaItem
import com.aether.tv.data.db.GameShortcutEntity
import com.aether.tv.data.db.ProfileEntity
import com.aether.tv.data.devices.DeviceOverview
import com.aether.tv.data.media.LinkCode
import com.aether.tv.data.media.MediaSnapshot
import com.aether.tv.platform.NetworkState
import com.aether.tv.platform.PeripheralSnapshot
import com.aether.tv.platform.SystemShortcut
import com.aether.tv.platform.TvNotification

data class LauncherUiState(
    val ready: Boolean = false,
    val profileId: String = "default",
    val profiles: List<ProfileEntity> = emptyList(),
    val settings: LauncherSettings = LauncherSettings(),
    val apps: List<AppInfo>? = null,
    val favorites: List<Favorite> = emptyList(),
    val categories: List<String> = emptyList(),
    val devices: DeviceOverview = DeviceOverview(emptyList(), emptyList(), PeripheralSnapshot(false, false, false, emptyList(), emptyList()), false),
    val games: List<GameShortcutEntity> = emptyList(),
    val media: MediaSnapshot = MediaSnapshot(),
    val network: NetworkState = NetworkState.Unknown,
    val notifications: List<TvNotification> = emptyList(),
    val notificationAccess: Boolean = false,
) {
    val appsByKey: Map<String, AppInfo> by lazy { apps.orEmpty().associateBy { it.key } }
    val activeProfile: ProfileEntity? get() = profiles.firstOrNull { it.id == profileId }

    /** Favorite apps, in favorites order, that are still installed. */
    val favoriteApps: List<Pair<Favorite, AppInfo>> by lazy {
        favorites.filter { it.type == FavoriteType.App }.mapNotNull { f -> appsByKey[f.targetKey]?.let { f to it } }
    }

    val visibleTabs: List<LauncherTab> get() = settings.layout.tabs.filter { it.visible || it.tab == LauncherTab.Settings || it.tab == LauncherTab.Home }.map { it.tab }

    fun device(id: String): ConnectedDevice? = devices.devices.firstOrNull { it.id == id }
}

/** Modal UI layered over the launcher. Only one is shown at a time; Back closes it. */
sealed interface Overlay {
    data class AppOptions(val appKey: String, val moveScope: MoveScope? = null) : Overlay
    data class FavoriteOptions(val favoriteId: Long) : Overlay
    data class DeviceOptions(val deviceId: String) : Overlay
    data class DeviceEditor(val deviceId: String?, val presetInputId: String? = null, val presetBluetooth: String? = null, val presetName: String? = null) : Overlay
    data class GameEditor(val gameId: String?) : Overlay
    data class GameOptions(val gameId: String) : Overlay
    data class MediaOptions(val item: MediaItem) : Overlay
    data class TextInput(val title: String, val initial: String, val hint: String?, val password: Boolean = false, val onDone: (String) -> Unit) : Overlay
    data class Choice(val title: String, val options: List<String>, val selected: Int, val onPick: (Int) -> Unit) : Overlay
    data class Confirm(val title: String, val message: String, val confirmLabel: String, val onConfirm: () -> Unit) : Overlay
    data class Message(val title: String, val message: String) : Overlay
    data class PinEntry(val title: String, val onPin: (String) -> Unit) : Overlay
    data class LinkCodeDisplay(val providerName: String, val code: LinkCode) : Overlay
    data class AddFavorite(val dummy: Int = 0) : Overlay
    data class ProviderSetup(val providerId: String) : Overlay
    data object QuickControls : Overlay
    data object Notifications : Overlay
    data object Search : Overlay
    data class SystemShortcutPicker(val onPick: (SystemShortcut) -> Unit) : Overlay
    data class AppPicker(val title: String, val gamesFirst: Boolean = false, val onPick: (AppInfo) -> Unit) : Overlay
}

/** Remote-friendly reordering: while active, Left/Right moves the item; OK or Back finishes. */
data class MoveMode(val favoriteId: Long, val scope: MoveScope)
enum class MoveScope { HomeApps, AllFavorites }
