package com.aether.tv.core.model

import kotlinx.serialization.Serializable

/**
 * All user preferences for a single profile. Persisted as JSON in DataStore (one entry per profile),
 * so adding a field with a default value is a backwards-compatible change.
 */
@Serializable
data class LauncherSettings(
    val appearance: AppearanceSettings = AppearanceSettings(),
    val layout: HomeLayoutSettings = HomeLayoutSettings(),
    val behavior: BehaviorSettings = BehaviorSettings(),
    val library: AppLibrarySettings = AppLibrarySettings(),
)

@Serializable
data class AppearanceSettings(
    /** "builtin:<id>", "file:<absolute path in app storage>" or "solid:<argb hex>". */
    val wallpaper: String = "builtin:alpine_dusk",
    val theme: ThemeMode = ThemeMode.Dark,
    val accent: AccentColor = AccentColor.IceBlue,
    /** White tint applied to glass surfaces, 0..0.4. */
    val glassOpacity: Float = 0.08f,
    /** Backdrop blur radius in dp; 0 disables blur. */
    val blurRadiusDp: Int = 20,
    val glassQuality: GlassQuality = GlassQuality.Auto,
    /** Extra darkening of the wallpaper, 0..0.8. */
    val backgroundDim: Float = 0.2f,
    val cornerRadiusDp: Int = 14,
    /** Alpha of resting glass borders, 0..0.4. */
    val borderIntensity: Float = 0.12f,
    val iconSize: IconSize = IconSize.Medium,
    val font: FontChoice = FontChoice.Inter,
    val textScale: Float = 1.0f,
    val animation: AnimationLevel = AnimationLevel.Full,
    /** Strength of the soft halo around focused items, 0..1. 0 = border only. */
    val focusGlow: Float = 0.35f,
    /** Scale applied to focused tiles, 1.0..1.1. */
    val focusScale: Float = 1.05f,
    val showAppLabels: Boolean = true,
    /** White single-colour app logos (Home shows them in a glass dock), or the apps' own coloured icons and banners. */
    val appLogos: AppLogoStyle = AppLogoStyle.White,
    /** Very slow drift of the wallpaper. Costs continuous redraws, so off by default. */
    val animatedWallpaper: Boolean = false,
)

@Serializable enum class ThemeMode { Dark, Light }

@Serializable
enum class AccentColor(val argb: Long, val label: String) {
    IceBlue(0xFFBFDFFF, "Ice Blue"),
    White(0xFFFFFFFF, "White"),
    Glacier(0xFF9FD8E6, "Glacier"),
    Amber(0xFFFFD9A6, "Amber"),
    Rose(0xFFFFC2CC, "Rose"),
    Mint(0xFFB6EFD0, "Mint"),
    Lavender(0xFFD6CCFF, "Lavender"),
}

@Serializable enum class GlassQuality(val label: String) {
    /** Blurred backdrop sampled from a cached, downscaled wallpaper. Cheap on all devices. */
    Auto("Blurred glass"),
    /** Flat translucent tint, no backdrop sampling. Lowest cost. */
    Flat("Flat tint"),
}

@Serializable enum class IconSize(val scale: Float, val label: String) { Small(0.85f, "Small"), Medium(1f, "Medium"), Large(1.18f, "Large") }
@Serializable enum class AppLogoStyle(val label: String) { White("White logos"), Original("Original colours") }
@Serializable enum class FontChoice(val label: String) { Inter("Inter"), System("System sans-serif") }
@Serializable enum class AnimationLevel(val label: String) { Full("Full"), Reduced("Reduced"), Off("Off") }

@Serializable
enum class HomeSection(val label: String) { Apps("Favorite apps"), ContinueWatching("Continue Watching"), Devices("Connected Devices"), Games("Games") }

@Serializable
data class SectionConfig(val section: HomeSection, val visible: Boolean = true)

@Serializable
enum class LauncherTab(val label: String) { Home("Home"), Apps("Apps"), Media("Media"), Games("Games"), Favorites("Favorites"), Settings("Settings") }

@Serializable
data class TabConfig(val tab: LauncherTab, val visible: Boolean = true)

@Serializable enum class TileSize(val heightDp: Int, val label: String) { Compact(56, "Compact"), Standard(68, "Standard"), Large(84, "Large") }
@Serializable enum class TemperatureUnit(val label: String) { Auto("Automatic"), Celsius("Celsius (°C)"), Fahrenheit("Fahrenheit (°F)") }
@Serializable enum class ClockFormat(val label: String) { System("Follow system"), H12("12-hour"), H24("24-hour") }

@Serializable
data class HomeLayoutSettings(
    val sections: List<SectionConfig> = listOf(
        SectionConfig(HomeSection.Apps),
        SectionConfig(HomeSection.ContinueWatching),
        SectionConfig(HomeSection.Devices),
        SectionConfig(HomeSection.Games, visible = false),
    ),
    val tabs: List<TabConfig> = LauncherTab.entries.map { TabConfig(it) },
    val defaultTab: LauncherTab = LauncherTab.Home,
    /** Number of favorite app tiles on Home (the "All Apps" tile is extra). */
    val appShortcutCount: Int = 8,
    val showAllAppsTile: Boolean = true,
    val tileSize: TileSize = TileSize.Standard,
    val continueWatchingLimit: Int = 8,
    val deviceLimit: Int = 4,
    val showInputsTile: Boolean = true,
    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val clockFormat: ClockFormat = ClockFormat.System,
    /** Current weather next to the date (Open-Meteo: no account, sends only the city and its coordinates). */
    val showWeather: Boolean = true,
    /** City for the weather. Blank: the city of the TV's time zone. */
    val weatherCity: String = "",
    val temperatureUnit: TemperatureUnit = TemperatureUnit.Auto,
    val showNetworkStatus: Boolean = true,
    val showNotifications: Boolean = true,
    val showPageTitle: Boolean = true,
    /** Favorites page: grouped dashboard (true) or one simple grid (false). */
    val favoritesGrouped: Boolean = true,
)

@Serializable enum class LongPressAction(val label: String) { Options("Options menu"), AppInfo("App info"), Nothing("Do nothing") }
@Serializable enum class TabActivation(val label: String) { Focus("When focused"), Select("When selected (OK)") }
@Serializable enum class SearchScope(val label: String) { All("Everything"), Apps("Apps only") }

@Serializable
data class BehaviorSettings(
    val restoreFocus: Boolean = true,
    val tabActivation: TabActivation = TabActivation.Focus,
    val backGoesHome: Boolean = true,
    val longPressAction: LongPressAction = LongPressAction.Options,
    val menuKeyOpensOptions: Boolean = true,
    val defaultSearch: SearchScope = SearchScope.All,
    /** Multiplies density; lets users fit the UI to their panel / overscan. 0.85..1.15 */
    val uiScale: Float = 1.0f,
    /** Extra safe-area padding in dp for TVs with overscan. */
    val overscanDp: Int = 0,
    /** Attempt to bring Aether to front after boot (needs overlay permission on Android 10+). */
    val launchOnBoot: Boolean = false,
)

@Serializable enum class AppSort(val label: String) { NameAsc("Name (A–Z)"), NameDesc("Name (Z–A)"), Recent("Recently used"), MostUsed("Most used"), Installed("Recently installed") }
@Serializable enum class LibraryView(val label: String) { Grid("Grid"), List("List") }

@Serializable
data class AppLibrarySettings(
    val sort: AppSort = AppSort.NameAsc,
    val view: LibraryView = LibraryView.Grid,
    val showHidden: Boolean = false,
    /** Show apps that only declare a phone/tablet launcher entry (common for sideloaded apps). */
    val showNonTvApps: Boolean = true,
    val useBanners: Boolean = false,
)
