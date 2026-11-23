package com.aether.tv.platform

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class PlatformFamily(val label: String) { FireTv("Fire TV (Fire OS)"), GoogleTv("Google TV"), AndroidTv("Android TV"), Other("Android (non-TV)") }

/** A single diagnostics line. [verified] = read directly from an API, false = inferred. */
data class DiagnosticEntry(val label: String, val value: String, val verified: Boolean = true)

/**
 * Reads what the platform actually exposes. Nothing here is assumed: values that are inferred (e.g. the Fire OS
 * version derived from the API level when the build property is unavailable) are flagged as such.
 */
@Singleton
class PlatformInfo @Inject constructor(@ApplicationContext private val context: Context) {
    private val pm: PackageManager = context.packageManager

    val isFireTv: Boolean by lazy {
        pm.hasSystemFeature(AMAZON_FIRE_TV_FEATURE) || Build.MANUFACTURER.equals("Amazon", true) || Build.MODEL.startsWith("AFT")
    }
    val isLeanback: Boolean by lazy { pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val isTelevision: Boolean by lazy {
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
    val hasLiveTv: Boolean by lazy { pm.hasSystemFeature(PackageManager.FEATURE_LIVE_TV) }
    val isGoogleTv: Boolean by lazy { pm.hasSystemFeature("com.google.android.feature.GOOGLE_EXPERIENCE") && isLeanback && !isFireTv }
    val family: PlatformFamily by lazy {
        when { isFireTv -> PlatformFamily.FireTv; isGoogleTv -> PlatformFamily.GoogleTv; isLeanback || isTelevision -> PlatformFamily.AndroidTv; else -> PlatformFamily.Other }
    }
    val isLowRam: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.isLowRamDevice || totalRamMb() < 1600
    }

    /** "Fire OS x.y.z" from the build property when readable, else inferred from the API level. */
    val fireOsVersion: Pair<String, Boolean>? by lazy {
        if (!isFireTv) null else {
            val prop = systemProperty("ro.build.version.name")
            if (!prop.isNullOrBlank()) prop to true else inferFireOs(Build.VERSION.SDK_INT) to false
        }
    }

    fun totalRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    /** True when Aether is the system's default HOME activity. */
    fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
        return ri.activityInfo?.packageName == context.packageName
    }

    /** Names of the activities that answer the HOME intent (to explain why Aether isn't the default). */
    fun homeCandidates(): List<String> = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .map { it.activityInfo.packageName }.distinct()

    fun diagnostics(): List<DiagnosticEntry> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val metrics = context.resources.displayMetrics
        return buildList {
            add(DiagnosticEntry("Platform", family.label))
            add(DiagnosticEntry("Manufacturer", Build.MANUFACTURER))
            add(DiagnosticEntry("Model", Build.MODEL))
            add(DiagnosticEntry("Device / product", "${Build.DEVICE} / ${Build.PRODUCT}"))
            add(DiagnosticEntry("Android version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
            fireOsVersion?.let { add(DiagnosticEntry("Fire OS", it.first + if (it.second) "" else " (inferred from API level)", it.second)) }
            add(DiagnosticEntry("Build", Build.DISPLAY))
            add(DiagnosticEntry("Display mode", mode?.let { "${it.physicalWidth}×${it.physicalHeight} @ ${"%.0f".format(it.refreshRate)} Hz" } ?: "Unknown"))
            add(DiagnosticEntry("UI resolution", "${metrics.widthPixels}×${metrics.heightPixels} px, ${metrics.densityDpi} dpi"))
            add(DiagnosticEntry("Memory", "${totalRamMb()} MB" + if (isLowRam) " (low-RAM profile)" else ""))
            add(DiagnosticEntry("CPU ABI", Build.SUPPORTED_ABIS.joinToString()))
            add(DiagnosticEntry("Leanback feature", yesNo(isLeanback)))
            add(DiagnosticEntry("TV input framework (live_tv)", yesNo(hasLiveTv)))
            add(DiagnosticEntry("Amazon Fire TV feature", yesNo(pm.hasSystemFeature(AMAZON_FIRE_TV_FEATURE))))
            add(DiagnosticEntry("Bluetooth", yesNo(pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH))))
            add(DiagnosticEntry("Default Home app", if (isDefaultHome()) "Aether" else homeCandidates().joinToString().ifEmpty { "None reported" }))
        }
    }

    private fun yesNo(b: Boolean) = if (b) "Yes" else "No"

    companion object {
        const val AMAZON_FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

        fun inferFireOs(sdk: Int): String = when {
            sdk >= 35 -> "Fire OS 16 (likely)"
            sdk >= 34 -> "Fire OS 14 (likely)"
            sdk >= 30 -> "Fire OS 8 (likely)"
            sdk >= 28 -> "Fire OS 7 (likely)"
            sdk >= 25 -> "Fire OS 6 (likely)"
            else -> "Fire OS 5 or older"
        }

        @Suppress("PrivateApi")
        fun systemProperty(name: String): String? = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
        }.getOrNull()
    }
}
