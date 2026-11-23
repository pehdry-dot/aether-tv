package com.aether.tv.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Long,
    /** PBKDF2 hash ("iterations:saltB64:hashB64") or null when the profile has no PIN. */
    val pinHash: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Per-profile metadata layered on top of an installed app (keyed by flattened ComponentName). */
@Serializable
@Entity(tableName = "app_meta", primaryKeys = ["profileId", "componentKey"])
data class AppMetaEntity(
    val profileId: String,
    val componentKey: String,
    val hidden: Boolean = false,
    val customLabel: String? = null,
    val customIconUri: String? = null,
    val category: String? = null,
    /** null = auto-detect, true/false = user override. */
    val gameOverride: Boolean? = null,
    val launchCount: Int = 0,
    val lastLaunchedAt: Long = 0,
)

@Serializable
@Entity(tableName = "favorites", indices = [Index(value = ["profileId", "type", "targetKey"], unique = true)])
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String,
    /** FavoriteType name. */
    val type: String,
    val targetKey: String,
    val title: String? = null,
    val artworkUri: String? = null,
    val groupName: String? = null,
    val sortOrder: Int = 0,
)

@Serializable
@Entity(tableName = "categories", primaryKeys = ["profileId", "name"])
data class CategoryEntity(
    val profileId: String,
    val name: String,
    val sortOrder: Int = 0,
)

/** A user-configured device shortcut (global across profiles — devices are physical). */
@Serializable
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** DeviceKind name. */
    val kind: String,
    val inputId: String? = null,
    val hdmiPort: Int? = null,
    val bluetoothAddress: String? = null,
    val artworkUri: String? = null,
    /** DeviceLaunchAction name. */
    val launchAction: String = "SwitchInput",
    val launchPackage: String? = null,
    val networkHost: String? = null,
    val showOnHome: Boolean = true,
    val sortOrder: Int = 0,
)

/** A custom game entry pointing at an installed app, a streaming app or a console on an HDMI input. */
@Serializable
@Entity(tableName = "game_shortcuts")
data class GameShortcutEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val title: String,
    val artworkUri: String? = null,
    /** GameKind name. */
    val kind: String,
    /** Component key for app targets. */
    val appKey: String? = null,
    /** Device id for console targets. */
    val deviceId: String? = null,
    val sortOrder: Int = 0,
    val lastPlayedAt: Long = 0,
)
