package com.aether.tv

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aether.tv.core.designsystem.AppIconLoader
import com.aether.tv.core.model.GameKind
import com.aether.tv.data.apps.AppRepository
import com.aether.tv.data.apps.FavoritesRepository
import com.aether.tv.data.apps.InstalledApp
import com.aether.tv.data.apps.InstalledAppSource
import com.aether.tv.data.backup.BackupManager
import com.aether.tv.data.db.AetherDatabase
import com.aether.tv.data.devices.DeviceRepository
import com.aether.tv.data.media.JellyfinProvider
import com.aether.tv.data.media.KodiProvider
import com.aether.tv.data.media.MediaRepository
import com.aether.tv.data.media.MediaSessionProvider
import com.aether.tv.data.media.PlexProvider
import com.aether.tv.data.media.ProviderStore
import com.aether.tv.data.profiles.ProfileRepository
import com.aether.tv.data.security.SecretStorage
import com.aether.tv.data.settings.SettingsRepository
import com.aether.tv.platform.AppLauncher
import com.aether.tv.platform.DeviceScanner
import com.aether.tv.platform.NetworkMonitor
import com.aether.tv.platform.PlatformInfo
import com.aether.tv.platform.SystemShortcuts
import com.aether.tv.platform.TvInputController
import com.aether.tv.ui.LauncherViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.UUID

class MemorySecrets : SecretStorage {
    private val map = mutableMapOf<String, String>()
    override fun put(name: String, value: String?) { if (value == null) map.remove(name) else map[name] = value }
    override fun get(name: String): String? = map[name]
}

class FakeAppSource(var apps: List<InstalledApp>) : InstalledAppSource {
    override fun query() = apps
}

/** Neutral demo apps (generic names, original placeholder icons) — no third-party branding in tests. */
object DemoApps {
    data class Demo(val label: String, val pkg: String, val color: Int, val glyph: String, val tv: Boolean = true, val game: GameKind? = null)

    val list = listOf(
        Demo("Aurora Play", "demo.aurora", 0xFF7B5CFF.toInt(), "▶"),
        Demo("Cinema+", "demo.cinema", 0xFFE5484D.toInt(), "C"),
        Demo("StarStream", "demo.starstream", 0xFF1F6FEB.toInt(), "S"),
        Demo("Prime Reel", "demo.primereel", 0xFF38BDF8.toInt(), "P"),
        Demo("TubeTV", "demo.tubetv", 0xFFEF4444.toInt(), "T"),
        Demo("Soundwave", "demo.soundwave", 0xFF22C55E.toInt(), "≋"),
        Demo("MediaHub", "demo.mediahub", 0xFFF59E0B.toInt(), "›"),
        Demo("Steam Link", "com.valvesoftware.steamlink", 0xFF2A475E.toInt(), "◎", game = GameKind.Streaming),
        Demo("Moonlight", "com.limelight", 0xFF64748B.toInt(), "☾", game = GameKind.Streaming),
        Demo("RetroArch", "com.retroarch", 0xFF334155.toInt(), "R", game = GameKind.Emulator),
        Demo("Kodi", "org.xbmc.kodi", 0xFF17B2E7.toInt(), "K"),
        Demo("VLC", "org.videolan.vlc", 0xFFF97316.toInt(), "V"),
        Demo("Twitchy", "demo.twitchy", 0xFF9146FF.toInt(), "t"),
        Demo("Photos", "demo.photos", 0xFFFBBF24.toInt(), "✿"),
        Demo("File Manager", "demo.files", 0xFFEAB308.toInt(), "F"),
        Demo("Downloader", "demo.downloader", 0xFFF97316.toInt(), "↓", tv = false),
        Demo("Stellar Racer", "demo.racer", 0xFF0EA5E9.toInt(), "R", game = GameKind.Local),
        Demo("Play Store", "demo.store", 0xFF10B981.toInt(), "▷"),
    )

    fun installed(): List<InstalledApp> = list.mapIndexed { i, d ->
        InstalledApp("${d.pkg}/${d.pkg}.Main", d.pkg, "${d.pkg}.Main", d.label, d.tv, false, d.game, 1_700_000_000_000 + i * 1000L)
    }

    fun icon(d: Demo, size: Int = 192): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = RectF(size * 0.08f, size * 0.08f, size * 0.92f, size * 0.92f)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), d.color, darker(d.color), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, size * 0.22f, size * 0.22f, p)
        p.shader = null; p.color = 0xFFFFFFFF.toInt(); p.textSize = size * 0.46f; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(d.glyph, size / 2f, size / 2f + p.textSize * 0.36f, p)
        return b
    }

    private fun darker(c: Int): Int {
        val r = (c shr 16 and 255) * 6 / 10; val g = (c shr 8 and 255) * 6 / 10; val bl = (c and 255) * 6 / 10
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Lets AppIconLoader render demo icons for the fake packages. */
    fun installIconResolver(context: Context) {
        val byPkg = list.associateBy { it.pkg }
        AppIconLoader.iconPackResolver = { _, cn -> byPkg[cn.packageName]?.let { BitmapDrawable(context.resources, icon(it)) } }
        AppIconLoader.invalidate()
    }
}

/** Real repositories wired to in-memory storage. */
class TestEnv(val apps: List<InstalledApp> = DemoApps.installed()) {
    val context: Application = ApplicationProvider.getApplicationContext()
    val db: AetherDatabase = Room.inMemoryDatabaseBuilder(context, AetherDatabase::class.java).allowMainThreadQueries().build()
    val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        File(context.cacheDir, "ds-${UUID.randomUUID()}.preferences_pb")
    }
    val secrets = MemorySecrets()
    val settings = SettingsRepository(store)
    val profiles = ProfileRepository(db, store, settings)
    val platform = PlatformInfo(context)
    val shortcuts = SystemShortcuts(context, platform)
    val launcher = AppLauncher(context, shortcuts)
    val source = FakeAppSource(apps)
    val appRepo = AppRepository(context, source, db, launcher)
    val favorites = FavoritesRepository(db)
    val inputs = TvInputController(context, platform, shortcuts)
    val scanner = DeviceScanner(context)
    val devices = DeviceRepository(db, inputs, scanner, launcher)
    val providerStore = ProviderStore(secrets)
    val media = MediaRepository(MediaSessionProvider(context), com.aether.tv.data.media.TvWatchNextProvider(context), PlexProvider(providerStore, launcher), JellyfinProvider(providerStore, launcher), KodiProvider(providerStore, launcher))
    val assets = com.aether.tv.data.assets.AssetStore(context, db, settings)
    val backup = BackupManager(context, db, settings, assets)

    /** Weather with a fixed reading: tests never touch the network. */
    val weather = com.aether.tv.data.weather.WeatherRepository(object : com.aether.tv.data.weather.WeatherSource {
        override suspend fun geocode(city: String, countryHint: String?) = com.aether.tv.data.weather.Place(city, 38.72, -9.14)
        override suspend fun current(place: com.aether.tv.data.weather.Place, fahrenheit: Boolean) =
            com.aether.tv.data.weather.Conditions(if (fahrenheit) 57.0 else 14.0, 3, isDay = true)
    })
    val installer = com.aether.tv.platform.ApkInstaller(context)
    val storage = com.aether.tv.data.storage.StorageInspector(context, assets, backup)
    val stores = com.aether.tv.data.store.AppStores(context, shortcuts, installer)
    val updates = com.aether.tv.data.update.UpdateManager(context, settings, installer).also { it.apiBase = "http://127.0.0.1:1" }
    fun viewModel() = LauncherViewModel(context, profiles, settings, appRepo, favorites, devices, media, db, NetworkMonitor(context), shortcuts, platform, inputs, launcher, backup, weather, assets, updates, installer, storage, stores)

    fun close() = db.close()
}
