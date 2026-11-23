package com.aether.tv.platform

/**
 * Decides, for each window the system reports, whether Aether should come back to the front. Pure logic (no Android
 * types) so it can be unit tested. Times are milliseconds on any monotonic clock.
 *
 * Measured on the Toshiba 43M450SU (Fire OS 14, 2026-11-21):
 *  - A Home press from an app makes Amazon's launcher report `HomeActivity_vNext` twice, ~150–300 ms apart.
 *  - Opening Fire TV Settings reports the *same* window (Settings is drawn inside Amazon's launcher), so the two can
 *    only be told apart by who started it: Aether marks a settings session before it opens Amazon's settings.
 *  - A Home press while Amazon's launcher is already showing reports no window change at all; the launcher only moves
 *    focus to its "Home" tab. During a settings session [onFocus] watches for that.
 */
class HomeRedirectPolicy(private val ownPackage: String) {
    sealed interface Decision {
        data object Ignore : Decision
        data object Redirect : Decision
    }

    sealed interface KeyDecision {
        /** Let the key through to the app (everything except the tail of a held ☰ with the shortcut on). */
        data object PassThrough : KeyDecision
        /** Swallow it: the rest of a held ☰ that opened the TV's quick panel. */
        data object Consume : KeyDecision
        /** ☰ has been held long enough: open the TV's quick panel (and swallow this key). */
        data object OpenQuickSettings : KeyDecision
    }

    private var lastRedirectAt = NEVER
    private var panelSessionUntil = NEVER
    private var menuDownAt = NEVER
    private var menuFired = false
    private var cooldownUntil = NEVER
    private val recent = ArrayDeque<Long>()
    private var settingsSessionUntil = NEVER
    private var loopTripped = false

    /** True once after the loop guard stopped redirecting (so the caller logs it once). */
    fun takeLoopTripped(): Boolean = loopTripped.also { loopTripped = false }

    val inSettingsSession: Boolean get() = settingsSessionUntil != NEVER

    /** The TV's own quick panel (⚙ on the remote, or Aether's shortcut) is opening over an app or HDMI input. */
    val inPanelSession: Boolean get() = panelSessionUntil != NEVER

    /**
     * The TV's quick panel is about to show. Some Fire OS builds draw it from Amazon's launcher, whose window looks like
     * Amazon's home: don't bring Aether over it until another app's window shows (the panel closed) or a minute passes.
     */
    fun beginPanelSession(now: Long) { panelSessionUntil = now + PANEL_SESSION_MS }

    /**
     * Keys seen by the Home button service (only when "Remote shortcuts" is on). Home, Back, volume, playback and every
     * other key pass through untouched; ⚙ only marks a panel session. With [menuShortcut], ☰ held for [MENU_HOLD_MS]
     * opens the TV's quick panel; a short ☰ press still reaches the app as usual.
     */
    fun onKey(code: Int, down: Boolean, repeat: Int, now: Long, menuShortcut: Boolean): KeyDecision {
        if (code == KEYCODE_SETTINGS && down && repeat == 0) { beginPanelSession(now); return KeyDecision.PassThrough }
        if (code != KEYCODE_MENU || !menuShortcut) return KeyDecision.PassThrough
        if (down) {
            if (repeat == 0) { menuDownAt = now; menuFired = false; return KeyDecision.PassThrough }
            if (menuFired) return KeyDecision.Consume
            if (menuDownAt != NEVER && now - menuDownAt >= MENU_HOLD_MS) {
                menuFired = true
                beginPanelSession(now)
                return KeyDecision.OpenQuickSettings
            }
            return KeyDecision.PassThrough
        }
        val fired = menuFired
        menuDownAt = NEVER; menuFired = false
        return if (fired) KeyDecision.Consume else KeyDecision.PassThrough
    }

    /** Aether is about to open one of Amazon's settings screens. */
    fun beginSettingsSession(now: Long) { settingsSessionUntil = now + SETTINGS_SESSION_MAX_MS }

    fun endSettingsSession() { settingsSessionUntil = NEVER }

    fun onWindow(pkg: String, cls: String?, now: Long): Decision {
        if (settingsSessionUntil != NEVER && now > settingsSessionUntil) endSettingsSession() // fail-safe
        if (panelSessionUntil != NEVER && now > panelSessionUntil) panelSessionUntil = NEVER
        return when {
            pkg == ownPackage -> { endSettingsSession(); panelSessionUntil = NEVER; Decision.Ignore }
            isAmazonHome(pkg, cls) -> if (inSettingsSession || inPanelSession) Decision.Ignore else redirect(now)
            pkg in NEUTRAL || pkg in AMAZON_SETTINGS_SURFACES || pkg == AMAZON_LAUNCHER || pkg in TV_SURFACES -> Decision.Ignore
            // An app (or the HDMI input's player) is showing again: the settings visit or quick panel is over.
            else -> { endSettingsSession(); panelSessionUntil = NEVER; Decision.Ignore }
        }
    }

    /** Only consulted during a settings session: Amazon's launcher focusing its "Home" tab means Home was pressed. */
    fun onFocus(pkg: String, description: CharSequence?, now: Long): Decision {
        if (!inSettingsSession || pkg != AMAZON_LAUNCHER) return Decision.Ignore
        if (description?.toString()?.trim()?.equals("Home", ignoreCase = true) != true) return Decision.Ignore
        endSettingsSession()
        return redirect(now)
    }

    private fun redirect(now: Long): Decision {
        if (now - lastRedirectAt < DEDUPE_MS) return Decision.Ignore // one Home press sends several events
        if (now < cooldownUntil) return Decision.Ignore
        while (recent.isNotEmpty() && now - recent.first() >= LOOP_WINDOW_MS) recent.removeFirst()
        if (recent.size >= LOOP_MAX) { // something keeps reopening Amazon's home: stop fighting it for a while
            cooldownUntil = now + COOLDOWN_MS
            recent.clear()
            loopTripped = true
            return Decision.Ignore
        }
        recent.addLast(now)
        lastRedirectAt = now
        return Decision.Redirect
    }

    companion object {
        const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        const val DEDUPE_MS = 1_000L
        const val LOOP_MAX = 4
        const val LOOP_WINDOW_MS = 10_000L
        const val COOLDOWN_MS = 60_000L
        const val SETTINGS_SESSION_MAX_MS = 30 * 60_000L
        const val PANEL_SESSION_MS = 60_000L
        const val MENU_HOLD_MS = 700L
        const val KEYCODE_SETTINGS = 176
        const val KEYCODE_MENU = 82
        private const val NEVER = Long.MIN_VALUE / 4

        /** System surfaces that appear over anything and say nothing about where the user is going. */
        val NEUTRAL = setOf("android", "com.android.systemui", "com.amazon.tvsysuiextensions")

        /** Amazon screens reached from Fire TV Settings (PIN prompts, consent dialogs, quick settings...). */
        val AMAZON_SETTINGS_SURFACES = setOf(
            "com.amazon.tv.settings.v2", "com.amazon.tv.settings.core", "com.amazon.tv.quicksettings.ui",
            "com.amazon.tv.parentalcontrols", "com.amazon.tv.consents", "com.amazon.tv.notificationcenter",
            "com.amazon.tv.ime", "com.android.permissioncontroller",
        )

        /**
         * The TV's picture/sound panels and the HDMI player: showing one says nothing about Home. The quick panel was
         * reported as `com.amazon.tv.settings.v2/.hud.HudActivity` (Fire OS 8 TV); the picture-quality apps differ by
         * chipset (MediaTek on the M450).
         */
        val TV_SURFACES = setOf("com.amazon.tv.pqsettings", "com.amazon.tv.settings.mediatek", "com.amazon.tv.settings.amlogic")

        fun isAmazonHome(pkg: String, cls: String?): Boolean =
            pkg == AMAZON_LAUNCHER && cls != null && cls.startsWith("$AMAZON_LAUNCHER.ui.HomeActivity")
    }
}
