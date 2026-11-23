package com.aether.tv.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * The TV's own quick panel (Picture, Sound, Inputs … for whatever is on screen), as Fire OS opens it from the remote's
 * ⚙ button. Aether never draws its own: it only opens the native one where this TV lets it.
 */
object TvQuickSettings {
    /** Reported on a Fire OS 8 Fire TV Edition TV (long-press Home). Unverified on the M450: checked at run time. */
    val CANDIDATES = listOf(ComponentName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.hud.HudActivity"))

    /** An intent that opens the panel on this TV, or null when none resolves to a real, exported screen. */
    fun intent(shortcuts: SystemShortcuts): Intent? = CANDIDATES
        .map { Intent(Intent.ACTION_MAIN).setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { shortcuts.isUsable(it) }

    /** Opens the panel from Aether (diagnostics); true when Android accepted the request. */
    fun open(context: Context, shortcuts: SystemShortcuts): Boolean {
        val i = intent(shortcuts) ?: return false
        HomeButtonService.beginPanelSession()
        return runCatching { context.startActivity(i) }.onFailure { EventLog.log("Quick panel: ${i.component?.shortClassName} refused (${it.javaClass.simpleName})") }.isSuccess
    }
}

/** The last keys that reached Aether (and, with Remote shortcuts on, the Home button service): key codes only. */
object RemoteKeyLog {
    data class Entry(val code: Int, val down: Boolean, val repeat: Int, val where: String, val at: Long)
    private val entries = ArrayDeque<Entry>()
    @Volatile var listener: (() -> Unit)? = null

    @Synchronized fun record(code: Int, down: Boolean, repeat: Int, where: String) {
        if (!down && code != KeyEvent.KEYCODE_SETTINGS) return
        if (repeat > 0 && entries.lastOrNull()?.let { it.code == code && it.repeat > 0 } == true) entries.removeLast()
        entries.addLast(Entry(code, down, repeat, where, System.currentTimeMillis()))
        while (entries.size > 12) entries.removeFirst()
        listener?.invoke()
    }

    @Synchronized fun recent(): List<Entry> = entries.toList()
    @Synchronized fun clear() = entries.clear()

    fun label(code: Int): String = when (code) {
        KeyEvent.KEYCODE_SETTINGS -> "⚙ Settings"
        KeyEvent.KEYCODE_MENU -> "☰ Menu"
        KeyEvent.KEYCODE_HOME -> "Home"
        KeyEvent.KEYCODE_BACK -> "Back"
        KeyEvent.KEYCODE_DPAD_CENTER -> "OK"
        KeyEvent.KEYCODE_TV_INPUT -> "Input"
        KeyEvent.KEYCODE_TV -> "TV"
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_INFO -> "Info"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() }
    }
}
