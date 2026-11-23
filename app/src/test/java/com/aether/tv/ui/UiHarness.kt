package com.aether.tv.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.aether.tv.DemoApps
import com.aether.tv.TestEnv
import com.aether.tv.core.model.ConnectedDevice
import com.aether.tv.core.model.DeviceKind
import com.aether.tv.core.model.DeviceLaunchAction
import com.aether.tv.core.model.DeviceStatus
import com.aether.tv.core.model.FavoriteType
import com.aether.tv.core.model.GameKind
import com.aether.tv.core.model.MediaItem
import com.aether.tv.core.model.TvInput
import com.aether.tv.data.db.DeviceEntity
import com.aether.tv.data.db.GameShortcutEntity
import com.aether.tv.data.media.ProviderState
import com.aether.tv.data.media.ProviderStatus
import com.aether.tv.data.media.SessionArtCache
import com.aether.tv.platform.BluetoothPeer
import com.aether.tv.platform.Controller
import com.aether.tv.platform.PeripheralSnapshot
import kotlinx.coroutines.runBlocking

/** Seeds a realistic launcher state for UI tests and screenshots. */
object UiHarness {
    fun seed(env: TestEnv) = runBlocking {
        env.profiles.ensureDefault()
        env.settings.setFlag("seeded_default", true)
        env.appRepo.refreshNow()
        val byPkg = env.apps.associateBy { it.packageName }
        listOf("demo.aurora", "demo.cinema", "demo.starstream", "demo.primereel", "demo.tubetv", "demo.soundwave", "demo.mediahub", "com.valvesoftware.steamlink")
            .forEach { env.favorites.add("default", FavoriteType.App, byPkg[it]!!.key) }
        env.db.devices().upsert(DeviceEntity("xbox-series-x", "Xbox Series X", DeviceKind.Xbox.name, inputId = "hdmi/HW2", hdmiPort = 1, artworkUri = "builtin:xbox_series_x", sortOrder = 0))
        env.db.devices().upsert(DeviceEntity("ps5", "PlayStation 5", DeviceKind.PlayStation.name, inputId = "hdmi/HW3", hdmiPort = 2, sortOrder = 1))
        env.db.devices().upsert(DeviceEntity("switch", "Nintendo Switch", DeviceKind.NintendoSwitch.name, inputId = "hdmi/HW4", hdmiPort = 3, sortOrder = 2))
        listOf("Ashen Crown" to 0, "Neon Courier" to 1, "Frontier 1899" to 2, "Gate of Realms" to 3).forEach { (t, i) ->
            SessionArtCache.put("g$i", art(i + 4, 480, 270))
            env.db.games().upsert(GameShortcutEntity("g$i", "default", t, "session:g$i", GameKind.Console.name, deviceId = "xbox-series-x", sortOrder = i))
            env.favorites.add("default", FavoriteType.Game, "g$i")
        }
        env.favorites.add("default", FavoriteType.Input, "hdmi/HW2", "HDMI 1")
        env.favorites.add("default", FavoriteType.Input, "hdmi/HW3", "HDMI 2")
        env.favorites.add("default", FavoriteType.System, "LiveTv")
        env.favorites.add("default", FavoriteType.Device, "xbox-series-x")
        env.favorites.add("default", FavoriteType.Device, "ps5")
        DemoApps.installIconResolver(env.context)
    }

    private val inputs = listOf(
        TvInput("hdmi/HW2", "HDMI 1", 1007, 1, DeviceStatus.Connected, true),
        TvInput("hdmi/HW3", "HDMI 2", 1007, 2, DeviceStatus.Standby, true),
        TvInput("hdmi/HW4", "HDMI 3", 1007, 3, DeviceStatus.NoSignal, true),
    )

    /** What a TV that reports its inputs and Bluetooth state would show. */
    fun transform(state: LauncherUiState): LauncherUiState {
        val peripherals = PeripheralSnapshot(true, true, true,
            listOf(BluetoothPeer("WH-1000XM5", "AA:BB", DeviceKind.Headphones, connected = true)),
            listOf(Controller("Wireless Controller", "ctrl1", 1118, 2835)))
        val configured = state.devices.devices.filter { it.configured }.map { d ->
            val input = inputs.firstOrNull { it.id == d.inputId }
            if (input != null) d.copy(status = input.state, detail = "HDMI ${input.hdmiPort}") else d
        }
        val discovered = listOf(
            ConnectedDevice("bt:AA:BB", "WH-1000XM5", DeviceKind.Headphones, DeviceStatus.Connected, "Bluetooth", bluetoothAddress = "AA:BB", configured = false),
            ConnectedDevice("ctrl:1", "Controller", DeviceKind.Controller, DeviceStatus.Connected, "Bluetooth", configured = false, launchAction = DeviceLaunchAction.None),
        )
        val media = listOf(
            MediaItem("m1", "jellyfin", "The Long Road", "S2 E4 • Endure and Survive", "session:m1", 0.22f),
            MediaItem("m2", "jellyfin", "Dune Sea", null, "session:m2", 0.6f),
            MediaItem("m3", "plex", "Neon Harbor", "S3 E7 • The Conflict", "session:m3", 0.56f),
            MediaItem("m4", "plex", "Arcadia", "S2 E3 • Finally Got the Name", "session:m4", 0.21f),
        )
        media.forEachIndexed { i, m -> if (SessionArtCache.get(m.artworkUrl) == null) SessionArtCache.put(m.id, art(i, 640, 360)) }
        val providers = state.media.providers.ifEmpty { emptyList() }.map {
            if (it.provider.id == "jellyfin" || it.provider.id == "plex") ProviderState(it.provider, ProviderStatus.Connected("Demo server")) else it
        }
        return state.copy(
            devices = state.devices.copy(devices = configured + discovered, inputs = inputs, peripherals = peripherals, inputFrameworkAvailable = true),
            media = state.media.copy(continueWatching = media, providers = providers),
            network = com.aether.tv.platform.NetworkState(com.aether.tv.platform.NetworkType.Wifi, 4, true, ipAddress = DEMO_IP),
        )
    }

    /** A private-range address for demo screens (RFC 1918). */
    const val DEMO_IP = "192.168.1.23"

    /** Original abstract "cinematic" artwork for demo cards (no copyrighted imagery). */
    fun art(seed: Int, w: Int, h: Int): Bitmap {
        val palettes = listOf(
            intArrayOf(0xFF3B4A3F.toInt(), 0xFF9AA58A.toInt(), 0xFF1A2219.toInt()),
            intArrayOf(0xFF2A0E06.toInt(), 0xFFE0702A.toInt(), 0xFF3A1407.toInt()),
            intArrayOf(0xFF2B0B12.toInt(), 0xFFB3324A.toInt(), 0xFF120509.toInt()),
            intArrayOf(0xFF161A3A.toInt(), 0xFF5B4BB5.toInt(), 0xFF0A0C1E.toInt()),
            intArrayOf(0xFF1C2430.toInt(), 0xFFC8A060.toInt(), 0xFF0B0F14.toInt()),
            intArrayOf(0xFF0D1B2A.toInt(), 0xFFE83F8A.toInt(), 0xFF050A10.toInt()),
            intArrayOf(0xFF3A1D0A.toInt(), 0xFFF2A65A.toInt(), 0xFF140A04.toInt()),
            intArrayOf(0xFF0F2027.toInt(), 0xFF7FB3C8.toInt(), 0xFF071014.toInt()),
        )
        val p = palettes[seed % palettes.size]
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), p[0], p[2], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val sx = w * (0.55f + (seed % 3) * 0.12f); val sy = h * 0.38f
        paint.shader = RadialGradient(sx, sy, h * 0.55f, intArrayOf(p[1], 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, h * 0.55f, paint)
        paint.shader = null
        val rnd = java.util.Random(seed.toLong())
        for (layer in 0 until 3) {
            val path = Path(); path.moveTo(0f, h.toFloat())
            var x = 0f; val base = h * (0.55f + layer * 0.12f)
            path.lineTo(0f, base)
            while (x < w) { x += w / 12f; path.lineTo(x, base - rnd.nextFloat() * h * (0.22f - layer * 0.05f)) }
            path.lineTo(w.toFloat(), h.toFloat()); path.close()
            paint.color = blend(p[2], p[1], 0.25f - layer * 0.08f) and 0x00FFFFFF or ((230 - layer * 10) shl 24)
            c.drawPath(path, paint)
        }
        // Two small figures for a cinematic feel.
        paint.color = 0xF0080808.toInt()
        for (i in 0..1) {
            val fx = w * (0.42f + i * 0.06f); val fy = h * 0.70f
            c.drawRoundRect(fx, fy - h * 0.16f, fx + w * 0.025f, fy, 6f, 6f, paint)
            c.drawCircle(fx + w * 0.0125f, fy - h * 0.19f, h * 0.028f, paint)
        }
        return b
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 255
        fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
