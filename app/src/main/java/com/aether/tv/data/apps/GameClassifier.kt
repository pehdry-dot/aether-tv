package com.aether.tv.data.apps

import android.content.pm.ApplicationInfo
import android.os.Build
import com.aether.tv.core.model.GameKind

/** Classifies apps as games and distinguishes local play, streaming and emulation. */
object GameClassifier {
    val streaming = mapOf(
        "com.valvesoftware.steamlink" to "Steam Link",
        "com.limelight" to "Moonlight",
        "com.limelight.noobs" to "Moonlight",
        "com.microsoft.xcloud" to "Xbox Cloud Gaming",
        "com.microsoft.xboxone.smartglass" to "Xbox",
        "com.microsoft.xboxone.smartglass.beta" to "Xbox",
        "com.gamepass" to "Xbox Game Pass",
        "com.nvidia.geforcenow" to "GeForce NOW",
        "com.amazon.luna" to "Amazon Luna",
        "com.amazon.lunaclient" to "Amazon Luna",
        "com.parsec.app" to "Parsec",
        "com.playstation.remoteplay" to "PS Remote Play",
        "com.boosteroid.streaming" to "Boosteroid",
        "com.rainway" to "Rainway",
    )
    val emulators = setOf(
        "com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32", "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold",
        "org.dolphinemu.dolphinemu", "com.explusalpha.Snes9xPlus", "com.github.stenzek.duckstation", "xyz.aethersx2.android",
        "org.mupen64plusae.v3.fzurita", "it.dbtecno.pizzaboypro", "com.dsemu.drastic", "info.cemu.cemu", "org.citra.citra_emu",
    )

    fun classify(pkg: String, ai: ApplicationInfo?): GameKind? {
        if (pkg in streaming) return GameKind.Streaming
        if (pkg in emulators) return GameKind.Emulator
        ai ?: return null
        val isGame = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ai.category == ApplicationInfo.CATEGORY_GAME) ||
            @Suppress("DEPRECATION") (ai.flags and ApplicationInfo.FLAG_IS_GAME != 0)
        return if (isGame) GameKind.Local else null
    }
}
