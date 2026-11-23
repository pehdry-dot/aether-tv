package com.aether.tv.platform

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import com.aether.tv.core.model.DeviceStatus
import com.aether.tv.core.model.TvInput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of an input switch request. Aether cannot observe whether the TV actually switched. */
sealed interface InputSwitchResult {
    /** A platform component accepted the request. */
    data class Requested(val method: String) : InputSwitchResult
    /** Direct switching is not available; a system screen was opened instead. */
    data class OpenedFallback(val screen: String, val hint: String) : InputSwitchResult
    data class Failed(val reason: String) : InputSwitchResult
}

/**
 * HDMI / TV input integration through public APIs plus Amazon's input-switch service where present.
 *
 * Order of attempts when switching:
 *  1. Fire TV: Amazon's input switch service (community-documented, not an official API).
 *  2. Android TV input framework: ACTION_VIEW on the passthrough channel URI (handled by the TV app).
 *  3. Open the closest system screen (Live TV / Settings) and explain what to do.
 */
@Singleton
class TvInputController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val platform: PlatformInfo,
    private val shortcuts: SystemShortcuts,
) {
    private val tim: TvInputManager? by lazy {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV) || platform.isFireTv || platform.isLeanback)
            runCatching { context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager }.getOrNull() else null
    }

    val frameworkAvailable: Boolean get() = tim != null

    fun inputs(): List<TvInput> {
        val m = tim ?: return emptyList()
        return runCatching { m.tvInputList }.getOrDefault(emptyList())
            .filter { it.isPassthroughInput || it.type == TvInputInfo.TYPE_HDMI }
            .map { info -> info.toModel(m) }
            .sortedWith(compareBy({ it.hdmiPort ?: 99 }, { it.label }))
    }

    private fun TvInputInfo.toModel(m: TvInputManager): TvInput {
        val label = (runCatching { loadCustomLabel(context) }.getOrNull() ?: runCatching { loadLabel(context) }.getOrNull())?.toString() ?: id
        val state = when (runCatching { m.getInputState(id) }.getOrDefault(-1)) {
            TvInputManager.INPUT_STATE_CONNECTED -> DeviceStatus.Connected
            TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> DeviceStatus.Standby
            TvInputManager.INPUT_STATE_DISCONNECTED -> DeviceStatus.NoSignal
            else -> DeviceStatus.Shortcut
        }
        return TvInput(id, label, type, parseHdmiPort(label, id), state, isPassthroughInput)
    }

    /** Emits whenever the platform reports inputs added/removed/changing state. */
    fun observe(): Flow<List<TvInput>> = callbackFlow {
        val m = tim
        trySend(inputs())
        if (m == null) { awaitClose { }; return@callbackFlow }
        val cb = object : TvInputManager.TvInputCallback() {
            override fun onInputAdded(inputId: String) { trySend(inputs()) }
            override fun onInputRemoved(inputId: String) { trySend(inputs()) }
            override fun onInputUpdated(inputId: String) { trySend(inputs()) }
            override fun onInputStateChanged(inputId: String, state: Int) { trySend(inputs()) }
        }
        runCatching { m.registerCallback(cb, Handler(Looper.getMainLooper())) }
        awaitClose { runCatching { m.unregisterCallback(cb) } }
    }.conflate()

    fun switchTo(inputId: String): InputSwitchResult {
        if (platform.isFireTv) {
            parseHardwareIndex(inputId)?.let { hw ->
                val svc = Intent().setComponent(ComponentName(AMAZON_INPUT_PKG, AMAZON_INPUT_SERVICE)).putExtra("inputType", hw)
                val started = runCatching { context.startService(svc) }
                EventLog.log("Input $inputId → Fire TV input service (inputType=$hw): " + started.fold({ if (it != null) "accepted" else "service not found" }, { "refused: ${it.javaClass.simpleName}" }))
                if (started.getOrNull() != null) return InputSwitchResult.Requested("Fire TV input service")
            }
        }
        val view = Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (shortcuts.isUsable(view)) {
            try {
                context.startActivity(view)
                EventLog.log("Input $inputId → passthrough ACTION_VIEW started")
                return InputSwitchResult.Requested("TV input framework")
            } catch (e: ActivityNotFoundException) { EventLog.log("Input $inputId → passthrough failed: not found") } catch (e: SecurityException) { EventLog.log("Input $inputId → passthrough failed: ${e.message}") }
        } else EventLog.log("Input $inputId → no handler for passthrough URI")
        return openInputsFallback()
    }

    fun openInputsFallback(): InputSwitchResult {
        val hint = if (platform.isFireTv) "Press and hold the Home button, then choose Inputs, or use the Inputs entry in Fire TV Settings."
        else "Use the Inputs button on your remote, or choose the input from the TV's Live TV app."
        return when (val r = shortcuts.open(SystemShortcut.LiveTv)) {
            is LaunchResult.Opened -> InputSwitchResult.OpenedFallback("Live TV", hint)
            is LaunchResult.Unavailable -> when (shortcuts.open(SystemShortcut.AllSettings)) {
                is LaunchResult.Opened -> InputSwitchResult.OpenedFallback("System settings", hint)
                is LaunchResult.Unavailable -> InputSwitchResult.Failed(hint)
            }
        }
    }

    companion object {
        const val AMAZON_INPUT_PKG = "com.amazon.tv.inputpreference.service"
        const val AMAZON_INPUT_SERVICE = "com.amazon.tv.inputpreference.service.KaminoInputSwitchService"

        /** Hardware index from ids like "com.mediatek.tvinput/.hdmi.HDMIInputService/HW2". */
        fun parseHardwareIndex(inputId: String): Int? = Regex("/HW(\\d+)$").find(inputId)?.groupValues?.get(1)?.toIntOrNull()

        /** HDMI port number from the input's label ("HDMI 1", "HDMI2 (ARC)") or, failing that, from the id. */
        fun parseHdmiPort(label: String, id: String): Int? =
            Regex("HDMI\\s*(\\d)", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("hdmi[^0-9]*(\\d)", RegexOption.IGNORE_CASE).find(id.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()
    }
}
