package com.aether.tv.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aether.tv.core.designsystem.AppIconLoader
import com.aether.tv.core.model.AppInfo
import com.aether.tv.core.model.ConnectedDevice
import com.aether.tv.core.model.DeviceKind
import com.aether.tv.core.model.DeviceLaunchAction
import com.aether.tv.core.model.Favorite
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.core.model.GameKind
import com.aether.tv.core.model.LauncherSettings
import com.aether.tv.core.model.MediaItem
import com.aether.tv.data.apps.AppRepository
import com.aether.tv.data.apps.FavoritesRepository
import com.aether.tv.data.backup.BackupManager
import com.aether.tv.data.db.AetherDatabase
import com.aether.tv.data.db.CategoryEntity
import com.aether.tv.data.db.DeviceEntity
import com.aether.tv.data.db.GameShortcutEntity
import com.aether.tv.data.devices.DeviceActionResult
import com.aether.tv.data.devices.DeviceRepository
import com.aether.tv.data.media.MediaRepository
import com.aether.tv.data.media.ResumeResult
import com.aether.tv.data.profiles.ProfileRepository
import com.aether.tv.data.settings.SettingsRepository
import com.aether.tv.platform.AppLauncher
import com.aether.tv.platform.LaunchResult
import com.aether.tv.platform.NetworkMonitor
import com.aether.tv.platform.NotificationHub
import com.aether.tv.platform.PlatformInfo
import com.aether.tv.platform.SystemShortcut
import com.aether.tv.platform.SystemShortcuts
import com.aether.tv.platform.TvInputController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class LauncherViewModel @Inject constructor(
    app: Application,
    private val profilesRepo: ProfileRepository,
    private val settingsRepo: SettingsRepository,
    private val appRepo: AppRepository,
    private val favoritesRepo: FavoritesRepository,
    private val deviceRepo: DeviceRepository,
    val mediaRepo: MediaRepository,
    private val db: AetherDatabase,
    private val network: NetworkMonitor,
    val shortcuts: SystemShortcuts,
    val platform: PlatformInfo,
    val inputs: TvInputController,
    private val launcher: AppLauncher,
    val backup: BackupManager,
    private val weatherRepo: com.aether.tv.data.weather.WeatherRepository,
    val assets: com.aether.tv.data.assets.AssetStore,
    val updates: com.aether.tv.data.update.UpdateManager,
    private val updatesInstaller: com.aether.tv.platform.ApkInstaller,
) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()

    private val _overlays = MutableStateFlow<List<OverlayEntry>>(emptyList())
    private var nextOverlayId = 1L
    /** Every open overlay, bottom first. All stay composed (so a dialog keeps its edits under a picker); only the top shows. */
    val overlays: StateFlow<List<OverlayEntry>> = _overlays.asStateFlow()
    val overlay: StateFlow<Overlay?> = _overlays.map { it.lastOrNull()?.overlay }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices
    private val _moveMode = MutableStateFlow<MoveMode?>(null)
    val moveMode: StateFlow<MoveMode?> = _moveMode.asStateFlow()

    private val profileId = profilesRepo.activeProfileId

    private data class ProfileData(
        val id: String,
        val settings: LauncherSettings,
        val apps: List<AppInfo>?,
        val favorites: List<Favorite>,
        val categories: List<String>,
        val games: List<GameShortcutEntity>,
    )

    private val profileData = profileId.flatMapLatest { id ->
        combine(
            settingsRepo.observe(id), appRepo.apps(id), favoritesRepo.observe(id),
            db.categories().observe(id).map { l -> l.map { it.name } }, db.games().observe(id),
        ) { s, a, f, c, g -> ProfileData(id, s, a, f, c, g) }
    }

    private val system = combine(
        deviceRepo.observe(), mediaRepo.state, network.observe(), NotificationHub.notifications, NotificationHub.connected,
    ) { d, m, n, notes, conn -> arrayOf<Any>(d, m, n, notes, conn) }

    /**
     * Test-only hook: screenshot tests use it to inject device/media states that Robolectric can't produce
     * (no TV input service, no keystore for provider credentials). Never set in production code.
     */
    internal val testStateTransform = MutableStateFlow<((LauncherUiState) -> LauncherUiState)?>(null)

    val state: StateFlow<LauncherUiState> = combine(profileData, profilesRepo.profiles, system, testStateTransform) { pd, profiles, sys, transform ->
        @Suppress("UNCHECKED_CAST")
        LauncherUiState(
            ready = true, profileId = pd.id, profiles = profiles, settings = pd.settings, apps = pd.apps,
            favorites = pd.favorites, categories = pd.categories, games = pd.games,
            devices = sys[0] as com.aether.tv.data.devices.DeviceOverview,
            media = sys[1] as com.aether.tv.data.media.MediaSnapshot,
            network = sys[2] as com.aether.tv.platform.NetworkState,
            notifications = sys[3] as List<com.aether.tv.platform.TvNotification>,
            notificationAccess = (sys[4] as Boolean) || NotificationHub.isAccessGranted(context),
        ).let { transform?.invoke(it) ?: it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherUiState())

    /** Current weather for the top bar; null when off, unknown or not yet read. */
    val weather: StateFlow<com.aether.tv.data.weather.WeatherNow?> get() = weatherRepo.now
    private var weatherLoop: kotlinx.coroutines.Job? = null

    init {
        // Weather settings changed (on/off, city, unit): read again right away.
        viewModelScope.launch {
            state.map { s -> if (!s.ready) null else s.settings.layout.let { Triple(it.showWeather, it.weatherCity, it.temperatureUnit) } }
                .distinctUntilChanged()
                .collect { key ->
                    val (show, city, unit) = key ?: return@collect
                    if (show) weatherRepo.update(city, unit) else weatherRepo.clear()
                }
        }
        // A wallpaper preview can't be open at start: whatever is left in the preview folder was abandoned.
        viewModelScope.launch(Dispatchers.IO) { assets.clearPreviews() }
        // A restore that was interrupted (power cut, crash) is completed from its private copy.
        viewModelScope.launch { backup.finishInterruptedRestore()?.let { afterRestore(it, "Restore completed") } }
        viewModelScope.launch {
            profilesRepo.ensureDefault()
            deviceRepo.seedDefaults()
            appRepo.start()
            seedFavoritesWhenReady()
        }
    }

    private suspend fun seedFavoritesWhenReady() {
        val id = profilesRepo.activeId()
        if (settingsRepo.flag("seeded_$id")) return
        val installed = appRepo.installed.first { it != null }!!
        val preferred = PREFERRED_PACKAGES.mapNotNull { pkg -> installed.firstOrNull { it.packageName == pkg && it.isTvApp } ?: installed.firstOrNull { it.packageName == pkg } }
        val rest = installed.filter { it.isTvApp && !it.isSystem && it !in preferred }.sortedBy { it.label.lowercase() }
        val picks = (preferred + rest).distinctBy { it.packageName }.take(8)
        favoritesRepo.seedIfEmpty(id, picks.map { FavoriteType.App to it.key })
        settingsRepo.setFlag("seeded_$id", true)
    }

    // ---------- lifecycle ----------
    /** Emits when the Home button is pressed while Aether is the default Home app. */
    val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun onHomePressed() { skipFocusRestore = true; closeAllOverlays(); _moveMode.value = null; homePresses.tryEmit(Unit) }

    /** Set when overlays close because of the Home button: focus goes to Home, not back to what opened them. */
    @Volatile var skipFocusRestore = false

    fun onResume() {
        appRepo.refresh()
        // Continue Watching: read everything now and follow changes while Aether is in front.
        mediaRepo.startWatching()
        // Updates: finish an install that waited for "Install unknown apps"; the daily check when it's due.
        updates.onResume()
        // Weather: on coming to the front, then every 30 minutes while Aether stays there (never in the background).
        weatherLoop?.cancel()
        weatherLoop = viewModelScope.launch {
            state.first { it.ready }
            while (true) {
                val l = state.value.settings.layout
                if (l.showWeather) weatherRepo.update(l.weatherCity, l.temperatureUnit)
                kotlinx.coroutines.delay(com.aether.tv.data.weather.WeatherRules.STALE_MS)
            }
        }
    }

    fun onPause() { weatherLoop?.cancel(); weatherLoop = null; mediaRepo.stopWatching() }

    // ---------- overlays & notices ----------
    fun show(o: Overlay) { _overlays.value = _overlays.value + OverlayEntry(nextOverlayId++, o) }
    fun dismissOverlay() { _overlays.value = _overlays.value.dropLast(1) }
    fun replaceOverlay(o: Overlay) { _overlays.value = _overlays.value.dropLast(1) + OverlayEntry(nextOverlayId++, o) }
    fun closeAllOverlays() { _overlays.value = emptyList() }
    /** Closes the wallpaper preview and the picker under it (Apply). */
    fun closeWallpaperFlow() {
        _overlays.value = _overlays.value.dropLastWhile { it.overlay is Overlay.WallpaperPreview || it.overlay is Overlay.WallpaperPicker }
    }
    fun notice(text: String) { _notices.tryEmit(text) }

    private fun handle(r: LaunchResult) { if (r is LaunchResult.Unavailable) show(Overlay.Message("Not available", r.reason)) }

    // ---------- settings ----------
    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch { settingsRepo.update(state.value.profileId, transform) }
    }

    // ---------- navigation ----------
    /** Settings category the Settings page should open next (e.g. from the Home "Inputs" tile). */
    val settingsTarget = MutableStateFlow<String?>(null)
    private val _navigation = MutableSharedFlow<com.aether.tv.core.model.LauncherTab>(extraBufferCapacity = 4)
    /** Requests to show a page, collected by the launcher scaffold. */
    val navigation: SharedFlow<com.aether.tv.core.model.LauncherTab> = _navigation

    /**
     * Opens Settings at [target] (a category key, optionally "key#anchor"), whether or not the Settings tab is shown.
     * The Settings page consumes the target only while it is the page on screen.
     */
    fun openSettings(target: String? = null) {
        settingsTarget.value = target
        _navigation.tryEmit(com.aether.tv.core.model.LauncherTab.Settings)
    }

    // ---------- apps ----------

    fun onAppLongPress(app: AppInfo, scope: MoveScope?) {
        when (state.value.settings.behavior.longPressAction) {
            com.aether.tv.core.model.LongPressAction.AppInfo -> appInfo(app)
            else -> show(Overlay.AppOptions(app.key, scope))
        }
    }
    fun launchApp(app: AppInfo) { viewModelScope.launch { handle(appRepo.launch(state.value.profileId, app)) } }
    fun launchAppKey(key: String) { state.value.appsByKey[key]?.let { launchApp(it) } ?: notice("That app is no longer installed") }
    fun appInfo(app: AppInfo) = handle(launcher.appInfo(app.packageName))
    fun uninstall(app: AppInfo) = handle(launcher.uninstall(app.packageName))

    fun toggleFavoriteApp(app: AppInfo) = viewModelScope.launch {
        val now = favoritesRepo.toggle(state.value.profileId, FavoriteType.App, app.key)
        notice(if (now) "Added ${app.label} to Favorites" else "Removed ${app.label} from Favorites")
    }

    fun setHidden(app: AppInfo, hidden: Boolean) = viewModelScope.launch {
        appRepo.setHidden(state.value.profileId, app.key, hidden)
        notice(if (hidden) "${app.label} hidden — show it again from Settings ▸ Applications" else "${app.label} is visible again")
    }

    fun renameApp(app: AppInfo, label: String?) = viewModelScope.launch { appRepo.rename(state.value.profileId, app.key, label) }
    fun setAppCategory(app: AppInfo, category: String?) = viewModelScope.launch { appRepo.setCategory(state.value.profileId, app.key, category) }
    fun setGameOverride(app: AppInfo, isGame: Boolean?) = viewModelScope.launch { appRepo.setGameOverride(state.value.profileId, app.key, isGame) }

    fun setAppIconFromFile(app: AppInfo, uri: Uri) = viewModelScope.launch {
        val f = importAsset(com.aether.tv.data.assets.AssetKind.Icon, app.packageName) { assets.importUri(uri, it) } ?: return@launch
        appRepo.setIcon(state.value.profileId, app.key, "file:${f.path}")
        assets.deleteUnused(listOf(com.aether.tv.data.assets.AssetKind.Icon))
    }
    fun setAppIconFromUrl(app: AppInfo, url: String) = viewModelScope.launch {
        val f = importAsset(com.aether.tv.data.assets.AssetKind.Icon, app.packageName) { assets.download(url, it) } ?: return@launch
        appRepo.setIcon(state.value.profileId, app.key, "file:${f.path}")
        assets.deleteUnused(listOf(com.aether.tv.data.assets.AssetKind.Icon))
    }
    fun resetAppIcon(app: AppInfo) = viewModelScope.launch {
        appRepo.setIcon(state.value.profileId, app.key, null)
        assets.deleteUnused(listOf(com.aether.tv.data.assets.AssetKind.Icon))
    }

    fun addCategory(name: String) = viewModelScope.launch {
        val n = name.trim(); if (n.isEmpty()) return@launch
        db.categories().upsert(CategoryEntity(state.value.profileId, n, state.value.categories.size))
    }
    fun deleteCategory(name: String) = viewModelScope.launch {
        val p = state.value.profileId
        db.categories().unassign(p, name); db.categories().delete(CategoryEntity(p, name))
    }

    // ---------- favorites ----------
    fun addFavorite(type: FavoriteType, key: String, title: String? = null, art: String? = null) = viewModelScope.launch {
        favoritesRepo.add(state.value.profileId, type, key, title, art); notice("Added to Favorites")
    }
    fun removeFavorite(id: Long) = viewModelScope.launch { favoritesRepo.remove(id) }
    fun toggleFavorite(type: FavoriteType, key: String, title: String? = null) = viewModelScope.launch {
        val now = favoritesRepo.toggle(state.value.profileId, type, key)
        if (now && title != null) state.value.favorites.firstOrNull { it.type == type && it.targetKey == key }?.let { favoritesRepo.edit(it.id, title, it.artworkUri, it.group) }
        notice(if (now) "Added to Favorites" else "Removed from Favorites")
    }
    fun editFavorite(f: Favorite, title: String?, art: String?, group: String?) = viewModelScope.launch { favoritesRepo.edit(f.id, title, art, group) }
    fun setFavoriteArtFromUrl(f: Favorite, url: String) = viewModelScope.launch {
        val file = importAsset(com.aether.tv.data.assets.AssetKind.Art, "fav-${f.id}") { assets.download(url, it) } ?: return@launch
        favoritesRepo.edit(f.id, f.title, "file:${file.path}", f.group)
        assets.deleteUnused(listOf(com.aether.tv.data.assets.AssetKind.Art))
    }

    fun startMove(favoriteId: Long, scope: MoveScope) { _moveMode.value = MoveMode(favoriteId, scope); notice("Use ◀ ▶ to move, OK to finish") }
    fun endMove() { _moveMode.value = null }
    fun moveBy(delta: Int) {
        val m = _moveMode.value ?: return
        viewModelScope.launch {
            val p = state.value.profileId
            when (m.scope) {
                MoveScope.HomeApps -> favoritesRepo.move(p, m.favoriteId, delta) { it.type == FavoriteType.App.name }
                MoveScope.AllFavorites -> {
                    val type = state.value.favorites.firstOrNull { it.id == m.favoriteId }?.type?.name
                    favoritesRepo.move(p, m.favoriteId, delta) { it.type == type }
                }
            }
        }
    }

    fun openFavorite(f: Favorite) {
        when (f.type) {
            FavoriteType.App -> launchAppKey(f.targetKey)
            FavoriteType.Device -> state.value.device(f.targetKey)?.let { activateDevice(it) } ?: notice("That device shortcut was removed")
            FavoriteType.Input -> switchInput(f.targetKey)
            FavoriteType.System -> runCatching { SystemShortcut.valueOf(f.targetKey) }.getOrNull()?.let { openSystem(it) }
            FavoriteType.Game -> state.value.games.firstOrNull { it.id == f.targetKey }?.let { playGame(it) } ?: notice("That game shortcut was removed")
            FavoriteType.Media -> {
                val parts = f.targetKey.split("|", limit = 2)
                if (parts.size == 2) handle(launcher.openUri(parts[1], parts[0].ifBlank { null })) else notice("Can't open this item")
            }
        }
    }

    // ---------- system ----------
    fun openSystem(s: SystemShortcut) = handle(shortcuts.open(s))

    // ---------- Home button (Fire TV) ----------
    /** Device-wide switch for [com.aether.tv.platform.HomeButtonService]; true = the service leaves Amazon's home alone. */
    val homeRedirectPaused: StateFlow<Boolean> = settingsRepo.observeFlag(com.aether.tv.platform.HomeButtonService.FLAG_PAUSED)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setHomeRedirectPaused(paused: Boolean) = viewModelScope.launch {
        settingsRepo.setFlag(com.aether.tv.platform.HomeButtonService.FLAG_PAUSED, paused)
    }

    // ---------- devices ----------
    fun activateDevice(d: ConnectedDevice) = viewModelScope.launch {
        when (val r = deviceRepo.activate(d)) {
            is DeviceActionResult.Done -> r.message?.let { notice(it) }
            is DeviceActionResult.NeedsSetup -> show(Overlay.DeviceEditor(r.deviceId))
            is DeviceActionResult.Info -> show(Overlay.Message(d.name, r.message))
        }
    }

    fun switchInput(inputId: String) = viewModelScope.launch {
        val label = state.value.devices.inputs.firstOrNull { it.id == inputId }?.label ?: "input"
        activateDevice(ConnectedDevice(inputId, label, DeviceKind.Other, com.aether.tv.core.model.DeviceStatus.Shortcut, label, inputId = inputId, launchAction = DeviceLaunchAction.SwitchInput, configured = false))
    }

    fun saveDevice(d: DeviceEntity) = viewModelScope.launch { deviceRepo.save(d); notice("Saved ${d.name}") }
    fun deleteDevice(id: String) = viewModelScope.launch {
        deviceRepo.delete(id); favoritesRepo.remove(state.value.profileId, FavoriteType.Device, id)
    }
    suspend fun deviceEntity(id: String) = deviceRepo.get(id)
    fun newDeviceId() = deviceRepo.newId()
    fun probeHost(host: String) = viewModelScope.launch {
        notice("Checking $host…")
        val ok = deviceRepo.probe(host)
        show(Overlay.Message("Network check", if (ok) "$host answered on port 80. This only shows the device is reachable on your network — not that it's on or playing." else "$host didn't answer on port 80. It may be off, asleep, or not expose a web port."))
    }

    /** Set when the user asked for the system file picker; MainScreen launches it and clears this. */
    val pendingPicturePick = MutableStateFlow<((String?) -> Unit)?>(null)

    /** Downloads a device picture once and returns its local "file:" uri (null on failure). */
    fun importPictureFromUrl(url: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        val f = importAsset(com.aether.tv.data.assets.AssetKind.Device, "device") { assets.download(url, it) }
        onDone(f?.let { "file:${it.path}" })
    }
    fun importPictureFromUri(uri: Uri, onDone: (String?) -> Unit) = viewModelScope.launch {
        val f = importAsset(com.aether.tv.data.assets.AssetKind.Device, "device") { assets.importUri(uri, it) }
        onDone(f?.let { "file:${it.path}" })
    }
    /** Pictures copied into Android/data/<pkg>/files/devices (adb push), no storage permission needed. */
    fun devicePictureFolder(): File = assets.tvFolder(com.aether.tv.data.assets.AssetKind.Device)
    fun devicePictureFiles(): List<File> = assets.tvFolderImages(com.aether.tv.data.assets.AssetKind.Device)
    fun setDevicePicture(id: String, uri: String?) = viewModelScope.launch {
        deviceRepo.get(id)?.let { deviceRepo.save(it.copy(artworkUri = uri)) }
        assets.deleteUnused(listOf(com.aether.tv.data.assets.AssetKind.Device))
    }

    /** Imports a picture into a new private file of [kind]; explains what went wrong and returns null on failure. */
    private suspend fun importAsset(kind: com.aether.tv.data.assets.AssetKind, name: String, load: suspend (File) -> com.aether.tv.data.assets.ImportResult): File? {
        val target = File(assets.dir(kind), "$name-${System.currentTimeMillis()}.img")
        return when (val r = load(target)) {
            is com.aether.tv.data.assets.ImportResult.Ok -> r.file
            is com.aether.tv.data.assets.ImportResult.Failed -> { show(Overlay.Message("Couldn't use that picture", r.reason)); null }
        }
    }

    // ---------- games ----------
    fun saveGame(g: GameShortcutEntity) = viewModelScope.launch { db.games().upsert(g.copy(profileId = state.value.profileId)); notice("Saved ${g.title}") }
    fun deleteGame(id: String) = viewModelScope.launch { db.games().delete(id); favoritesRepo.remove(state.value.profileId, FavoriteType.Game, id) }
    fun newGameId() = UUID.randomUUID().toString()
    fun playGame(g: GameShortcutEntity) = viewModelScope.launch {
        db.games().touch(g.id, System.currentTimeMillis())
        when {
            g.deviceId != null -> state.value.device(g.deviceId)?.let { activateDevice(it) } ?: notice("The console for ${g.title} was removed")
            g.appKey != null -> launchAppKey(g.appKey)
            else -> show(Overlay.GameEditor(g.id))
        }
    }
    fun setGameArtFromUrl(g: GameShortcutEntity, url: String) = viewModelScope.launch {
        val f = importAsset(com.aether.tv.data.assets.AssetKind.Art, "game-${g.id}") { assets.download(url, it) } ?: return@launch
        db.games().upsert(g.copy(artworkUri = "file:${f.path}"))
        assets.deleteUnused(listOf(com.aether.tv.data.assets.AssetKind.Art))
    }

    // ---------- media ----------
    fun resumeMedia(item: MediaItem) = viewModelScope.launch {
        when (val r = mediaRepo.resume(item)) { ResumeResult.Started -> Unit; is ResumeResult.Message -> notice(r.text) }
    }
    fun refreshMedia() = mediaRepo.refresh(force = true)

    // ---------- updates ----------
    fun installedApp() = updatesInstaller.installed()
    fun canInstallApps() = updatesInstaller.canInstall()
    /** Opens "Install unknown apps" for Aether where this TV has a screen for it; otherwise explains the path. */
    fun openUnknownAppsSettings() {
        val i = updatesInstaller.unknownSourcesIntent(shortcuts)
        if (i != null && runCatching { context.startActivity(i) }.isSuccess) return
        show(Overlay.Message("Install unknown apps", "This TV has no shortcut to that switch. " + com.aether.tv.ui.settings.UNKNOWN_APPS_PATH))
    }

    // ---------- profiles ----------
    fun switchProfile(id: String) = viewModelScope.launch {
        val p = state.value.profiles.firstOrNull { it.id == id } ?: return@launch
        if (p.pinHash != null) show(Overlay.PinEntry("Enter PIN for ${p.name}") { pin ->
            viewModelScope.launch { if (profilesRepo.switchTo(id, pin)) { dismissOverlay(); notice("Switched to ${p.name}") } else notice("Incorrect PIN") }
        }) else if (profilesRepo.switchTo(id, null)) { notice("Switched to ${p.name}"); seedFavoritesWhenReady() }
    }
    fun createProfile(name: String) = viewModelScope.launch {
        val colors = listOf(0xFFBFDFFF, 0xFFFFD9A6, 0xFFB6EFD0, 0xFFFFC2CC, 0xFFD6CCFF)
        profilesRepo.create(name, colors[state.value.profiles.size % colors.size], copySettingsFrom = state.value.profileId)
        notice("Created profile $name")
    }
    fun renameProfile(id: String, name: String) = viewModelScope.launch { profilesRepo.rename(id, name) }
    fun setProfilePin(id: String, pin: String?) = viewModelScope.launch { profilesRepo.setPin(id, pin); notice(if (pin.isNullOrBlank()) "PIN removed" else "PIN set") }
    fun deleteProfile(id: String) = viewModelScope.launch { profilesRepo.delete(id); notice("Profile deleted") }

    // ---------- wallpaper ----------
    /** Images the user copied into the app's folder via adb or a file manager (no storage permission needed). */
    fun dropFolderImages(): List<File> = assets.tvFolderImages(com.aether.tv.data.assets.AssetKind.Wallpaper)
    fun wallpaperFolder(): File = assets.tvFolder(com.aether.tv.data.assets.AssetKind.Wallpaper)

    /** Shows [c] full screen. Nothing changes until Apply. */
    fun previewWallpaper(c: WallpaperCandidate) = show(Overlay.WallpaperPreview(c))

    /** Downloads [url] into the preview folder, then previews it. The current wallpaper is untouched either way. */
    fun previewWallpaperUrl(url: String) = viewModelScope.launch {
        notice("Loading the picture…")
        val f = assets.newPreviewFile()
        when (val r = assets.download(url, f)) {
            is com.aether.tv.data.assets.ImportResult.Ok -> previewWallpaper(WallpaperCandidate("file:${r.file.path}", Uri.parse(url.trim()).host ?: "Web picture", WallpaperSource.Web))
            is com.aether.tv.data.assets.ImportResult.Failed -> show(Overlay.Message("Couldn't load that picture", r.reason + "\n\nYour wallpaper hasn't changed."))
        }
    }

    /** A picture chosen with the system file picker, copied into the preview folder first. */
    fun previewWallpaperUri(uri: Uri) = viewModelScope.launch {
        val f = assets.newPreviewFile()
        when (val r = assets.importUri(uri, f)) {
            is com.aether.tv.data.assets.ImportResult.Ok -> previewWallpaper(WallpaperCandidate("file:${r.file.path}", "Picture from storage", WallpaperSource.Picked))
            is com.aether.tv.data.assets.ImportResult.Failed -> show(Overlay.Message("Couldn't open that picture", r.reason + "\n\nYour wallpaper hasn't changed."))
        }
    }

    /** The preview closed without Apply: a downloaded or picked picture is removed; originals are never touched. */
    fun discardWallpaperPreview(c: WallpaperCandidate) {
        if (c.source != WallpaperSource.Web && c.source != WallpaperSource.Picked) return
        val f = File(c.spec.removePrefix("file:"))
        if (assets.isPreview(f)) f.delete()
    }

    /**
     * Saves [c] as this profile's wallpaper. Pictures from the TV folder are copied (the original stays), previews are
     * moved into Aether's storage. Aether's copies no profile uses any more are then removed.
     */
    fun applyWallpaper(c: WallpaperCandidate) = viewModelScope.launch {
        val spec = if (c.spec.startsWith("file:")) {
            val src = File(c.spec.removePrefix("file:"))
            runCatching { "file:" + assets.adopt(com.aether.tv.data.assets.AssetKind.Wallpaper, src, "wp").path }
                .getOrElse { show(Overlay.Message("Couldn't save the wallpaper", (it.message ?: "The picture couldn't be copied.") + "\n\nYour wallpaper hasn't changed.")); return@launch }
        } else c.spec
        settingsRepo.update(state.value.profileId) { it.copy(appearance = it.appearance.copy(wallpaper = spec)) }
        assets.deleteUnused(listOf(com.aether.tv.data.assets.AssetKind.Wallpaper))
        notice("Wallpaper applied")
    }

    // ---------- backup ----------
    fun exportBackup() = viewModelScope.launch {
        runCatching { backup.exportToFolder() }
            .onSuccess { show(Overlay.Message("Backup saved", "Profiles, settings, favorites, devices and their pictures (no passwords) in:\n${it.path}\n\nThis folder is removed if Aether is uninstalled. Copy it off the TV with:\nadb pull ${it.path}")) }
            .onFailure { show(Overlay.Message("Backup failed", it.message ?: "Unknown error")) }
    }
    fun exportBackupTo(uri: Uri) = viewModelScope.launch {
        runCatching { context.contentResolver.openOutputStream(uri)?.let { backup.exportTo(it) } ?: error("Can't write there") }
            .onSuccess { notice("Backup saved") }.onFailure { show(Overlay.Message("Backup failed", it.message ?: "Unknown error")) }
    }
    fun restoreBackup(file: File) = viewModelScope.launch {
        restoreWith(file.name) { backup.restore(file) }
    }
    fun restoreBackupFrom(uri: Uri) = viewModelScope.launch {
        restoreWith("the backup") { backup.restore(context.contentResolver.openInputStream(uri) ?: throw com.aether.tv.data.backup.BackupException("The file couldn't be opened.")) }
    }
    /** Puts back the data as it was just before the last restore. */
    fun undoRestore() = viewModelScope.launch {
        if (!backup.preRestoreBackup.exists()) return@launch show(Overlay.Message("Nothing to undo", "There's no restore to undo on this TV."))
        restoreWith("the data from before the restore") { backup.restore(backup.preRestoreBackup.copyTo(File(context.cacheDir, "undo.zip"), overwrite = true)) }
    }

    private suspend fun restoreWith(name: String, run: suspend () -> com.aether.tv.data.backup.RestoreReport) {
        runCatching { run() }
            .onSuccess { r -> afterRestore(r, "Restored $name") }
            .onFailure { show(Overlay.Message("Restore failed — nothing was changed", it.message ?: "The file isn't a valid Aether backup.")) }
    }

    private suspend fun afterRestore(r: com.aether.tv.data.backup.RestoreReport, title: String) {
        profilesRepo.ensureDefault()
        AppIconLoader.invalidate()
        val missing = if (r.missingPictures.isEmpty()) "" else "\n\n${r.missingPictures.size} picture(s) weren't in the backup and aren't on this TV; choose them again: ${r.missingPictures.take(5).joinToString()}"
        show(Overlay.Message(title, "${r.profiles} profile(s), ${r.favorites} favorite(s), ${r.devices} device(s) and ${r.pictures} picture(s) restored.$missing" +
            if (r.recovered) "\n\nThe restore had been interrupted; it has now been completed." else ""))
    }
    fun resetProfileSettings() = viewModelScope.launch { settingsRepo.replace(state.value.profileId, LauncherSettings()); notice("Settings reset to defaults") }

    fun gameKindsFor(app: AppInfo): GameKind? = app.gameKind

    companion object {
        /** Popular TV apps used to pre-fill Favorites on first run, only if installed. */
        val PREFERRED_PACKAGES = listOf(
            "com.netflix.ninja", "com.netflix.mediaclient", "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom", "com.amazon.avod",
            "com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.spotify.tv.android", "com.plexapp.android",
            "com.valvesoftware.steamlink", "org.jellyfin.androidtv", "org.xbmc.kodi", "com.stremio.one", "com.wbd.stream",
            "tv.twitch.android.app", "com.crunchyroll.crunchyroid",
        )
    }
}
