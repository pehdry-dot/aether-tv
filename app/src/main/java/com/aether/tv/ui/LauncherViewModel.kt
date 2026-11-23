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
) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()

    private val _overlay = MutableStateFlow<List<Overlay>>(emptyList())
    val overlay: StateFlow<Overlay?> = _overlay.map { it.lastOrNull() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
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
    fun onHomePressed() { _overlay.value = emptyList(); _moveMode.value = null; homePresses.tryEmit(Unit) }

    fun onResume() {
        appRepo.refresh()
        mediaRepo.refresh()
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

    fun onPause() { weatherLoop?.cancel(); weatherLoop = null }

    // ---------- overlays & notices ----------
    fun show(o: Overlay) { _overlay.value = _overlay.value + o }
    fun dismissOverlay() { _overlay.value = _overlay.value.dropLast(1) }
    fun replaceOverlay(o: Overlay) { _overlay.value = _overlay.value.dropLast(1) + o }
    fun notice(text: String) { _notices.tryEmit(text) }

    private fun handle(r: LaunchResult) { if (r is LaunchResult.Unavailable) show(Overlay.Message("Not available", r.reason)) }

    // ---------- settings ----------
    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch { settingsRepo.update(state.value.profileId, transform) }
    }

    // ---------- apps ----------
    /** Settings category the Settings page should open next (e.g. from the Home "Inputs" tile). */
    val settingsTarget = MutableStateFlow<String?>(null)

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
        val f = copyToPrivate(uri, "icons", app.packageName) ?: return@launch notice("Couldn't read that image")
        appRepo.setIcon(state.value.profileId, app.key, "file:${f.path}")
    }
    fun setAppIconFromUrl(app: AppInfo, url: String) = viewModelScope.launch {
        val f = download(url, "icons", app.packageName) ?: return@launch notice("Couldn't download that image")
        appRepo.setIcon(state.value.profileId, app.key, "file:${f.path}")
    }
    fun resetAppIcon(app: AppInfo) = viewModelScope.launch { appRepo.setIcon(state.value.profileId, app.key, null) }

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
        val file = download(url, "art", "fav-${f.id}") ?: return@launch notice("Couldn't download that image")
        favoritesRepo.edit(f.id, f.title, "file:${file.path}", f.group)
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
        val f = download(url, "devices", "device")
        if (f == null) notice("Couldn't download that image — check the address points directly to a JPG/PNG/WebP")
        onDone(f?.let { "file:${it.path}" })
    }
    fun importPictureFromUri(uri: Uri, onDone: (String?) -> Unit) = viewModelScope.launch {
        val f = copyToPrivate(uri, "devices", "device")
        if (f == null) notice("Couldn't read that image")
        onDone(f?.let { "file:${it.path}" })
    }
    /** Pictures copied into Android/data/<pkg>/files/devices (adb push), no storage permission needed. */
    fun devicePictureFolder(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "devices").apply { mkdirs() }
    fun devicePictureFiles(): List<File> = devicePictureFolder()
        .listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }?.sortedByDescending { it.lastModified() }.orEmpty()
    fun setDevicePicture(id: String, uri: String?) = viewModelScope.launch {
        deviceRepo.get(id)?.let { deviceRepo.save(it.copy(artworkUri = uri)) }
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
        val f = download(url, "art", "game-${g.id}") ?: return@launch notice("Couldn't download that image")
        db.games().upsert(g.copy(artworkUri = "file:${f.path}"))
    }

    // ---------- media ----------
    fun resumeMedia(item: MediaItem) = viewModelScope.launch {
        when (val r = mediaRepo.resume(item)) { ResumeResult.Started -> Unit; is ResumeResult.Message -> notice(r.text) }
    }
    fun refreshMedia() = mediaRepo.refresh(force = true)

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
    fun importWallpaper(uri: Uri) = viewModelScope.launch {
        val f = copyToPrivate(uri, "wallpapers", "wp-${System.currentTimeMillis()}") ?: return@launch notice("Couldn't read that image")
        updateSettings { it.copy(appearance = it.appearance.copy(wallpaper = "file:${f.path}")) }
    }
    fun wallpaperFromUrl(url: String) = viewModelScope.launch {
        val f = download(url, "wallpapers", "wp-${System.currentTimeMillis()}") ?: return@launch notice("Couldn't download that image")
        updateSettings { it.copy(appearance = it.appearance.copy(wallpaper = "file:${f.path}")) }
    }
    /** Images the user copied into the app's folder via adb or a file manager (no storage permission needed). */
    fun dropFolderImages(): List<File> = File(context.getExternalFilesDir(null) ?: context.filesDir, "wallpapers").apply { mkdirs() }
        .listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }?.sortedByDescending { it.lastModified() }.orEmpty()
    fun useWallpaperFile(f: File) = updateSettings { it.copy(appearance = it.appearance.copy(wallpaper = "file:${f.path}")) }

    // ---------- backup ----------
    fun exportBackup() = viewModelScope.launch {
        runCatching { backup.exportToFolder() }
            .onSuccess { show(Overlay.Message("Backup saved", "Saved to ${it.path}\n\nCopy it off the TV with:\nadb pull ${it.path}")) }
            .onFailure { show(Overlay.Message("Backup failed", it.message ?: "Unknown error")) }
    }
    fun exportBackupTo(uri: Uri) = viewModelScope.launch {
        runCatching { context.contentResolver.openOutputStream(uri)?.let { backup.exportTo(it) } ?: error("Can't write there") }
            .onSuccess { notice("Backup saved") }.onFailure { show(Overlay.Message("Backup failed", it.message ?: "Unknown error")) }
    }
    fun restoreBackup(file: File) = viewModelScope.launch {
        runCatching { backup.restore(file.readText()) }.onSuccess { AppIconLoader.invalidate(); notice("Restored ${file.name}") }
            .onFailure { show(Overlay.Message("Restore failed", it.message ?: "The file isn't a valid Aether backup.")) }
    }
    fun restoreBackupFrom(uri: Uri) = viewModelScope.launch {
        runCatching { backup.restore(context.contentResolver.openInputStream(uri) ?: error("Can't read file")) }
            .onSuccess { AppIconLoader.invalidate(); notice("Backup restored") }
            .onFailure { show(Overlay.Message("Restore failed", it.message ?: "The file isn't a valid Aether backup.")) }
    }
    fun resetProfileSettings() = viewModelScope.launch { settingsRepo.replace(state.value.profileId, LauncherSettings()); notice("Settings reset to defaults") }

    // ---------- files ----------
    private suspend fun copyToPrivate(uri: Uri, dir: String, name: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val out = File(File(context.filesDir, dir).apply { mkdirs() }, "$name-${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
            out
        }.getOrNull()
    }

    private suspend fun download(url: String, dir: String, name: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val req = okhttp3.Request.Builder().url(url.trim()).build()
            com.aether.tv.data.media.JsonHttp.defaultClient.newCall(req).execute().use { r ->
                require(r.isSuccessful)
                val type = r.header("Content-Type").orEmpty()
                require(type.startsWith("image/")) { "Not an image" }
                val bytes = r.body!!.bytes()
                require(bytes.size < 15 * 1024 * 1024)
                File(File(context.filesDir, dir).apply { mkdirs() }, "$name-${System.currentTimeMillis()}.img").apply { writeBytes(bytes) }
            }
        }.getOrNull()
    }

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
