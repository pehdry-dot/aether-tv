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

    private var lastRedirectAt = NEVER
    private var cooldownUntil = NEVER
    private val recent = ArrayDeque<Long>()
    private var settingsSessionUntil = NEVER
    private var loopTripped = false

    /** True once after the loop guard stopped redirecting (so the caller logs it once). */
    fun takeLoopTripped(): Boolean = loopTripped.also { loopTripped = false }

    val inSettingsSession: Boolean get() = settingsSessionUntil != NEVER

    /** Aether is about to open one of Amazon's settings screens. */
    fun beginSettingsSession(now: Long) { settingsSessionUntil = now + SETTINGS_SESSION_MAX_MS }

    fun endSettingsSession() { settingsSessionUntil = NEVER }

    fun onWindow(pkg: String, cls: String?, now: Long): Decision {
        if (settingsSessionUntil != NEVER && now > settingsSessionUntil) endSettingsSession() // fail-safe
        return when {
            pkg == ownPackage -> { endSettingsSession(); Decision.Ignore }
            isAmazonHome(pkg, cls) -> if (inSettingsSession) Decision.Ignore else redirect(now)
            pkg in NEUTRAL || pkg in AMAZON_SETTINGS_SURFACES || pkg == AMAZON_LAUNCHER -> Decision.Ignore
            else -> { endSettingsSession(); Decision.Ignore } // an app opened: whatever settings visit there was is over
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
        private const val NEVER = Long.MIN_VALUE / 4

        /** System surfaces that appear over anything and say nothing about where the user is going. */
        val NEUTRAL = setOf("android", "com.android.systemui", "com.amazon.tvsysuiextensions")

        /** Amazon screens reached from Fire TV Settings (PIN prompts, consent dialogs, quick settings...). */
        val AMAZON_SETTINGS_SURFACES = setOf(
            "com.amazon.tv.settings.v2", "com.amazon.tv.settings.core", "com.amazon.tv.quicksettings.ui",
            "com.amazon.tv.parentalcontrols", "com.amazon.tv.consents", "com.amazon.tv.notificationcenter",
            "com.amazon.tv.ime", "com.android.permissioncontroller",
        )

        fun isAmazonHome(pkg: String, cls: String?): Boolean =
            pkg == AMAZON_LAUNCHER && cls != null && cls.startsWith("$AMAZON_LAUNCHER.ui.HomeActivity")
    }
}
