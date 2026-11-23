package com.aether.tv.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.aether.tv.MainActivity
import com.aether.tv.data.settings.SettingsRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Home button returns to Aether" on Fire TV, where Fire OS always sends Home to Amazon's launcher.
 *
 * When Amazon's home window appears, this brings Aether back to the front (Amazon's screen still shows for a
 * moment: the system draws it before any app hears about it). It reads window *events* only: it never retrieves
 * screen content, filters no keys and takes no screenshots. Focus events are subscribed to only during a
 * settings session, to notice a Home press inside Amazon's settings (see [HomeRedirectPolicy]).
 *
 * Not needed when Amazon's launcher is disabled ("Aether only" mode): then Home opens Aether directly and Amazon's
 * home never appears, so this service has nothing to do.
 *
 * Fire TV has no Accessibility screen for third-party services; enable over adb:
 *   adb shell settings put secure enabled_accessibility_services com.aether.tv/com.aether.tv.platform.HomeButtonService
 *   adb shell settings put secure accessibility_enabled 1
 */
class HomeButtonService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var paused = false

    override fun onServiceConnected() {
        running = this
        EventLog.log("Home button service connected")
        val repo = EntryPointAccessors.fromApplication(applicationContext, HomeServiceEntryPoint::class.java).settings()
        scope.launch { repo.observeFlag(FLAG_PAUSED).collect { paused = it } }
        setFocusEvents(false)
    }

    override fun onDestroy() {
        if (running === this) running = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        val decision = when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> policy.onWindow(pkg, e.className?.toString(), now)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> policy.onFocus(pkg, e.contentDescription ?: e.text.firstOrNull(), now)
            else -> HomeRedirectPolicy.Decision.Ignore
        }
        if (!policy.inSettingsSession) setFocusEvents(false)
        if (policy.takeLoopTripped()) EventLog.log("Home button: Amazon's home keeps reopening; pausing 60 s")
        if (decision == HomeRedirectPolicy.Decision.Redirect && !paused) openAether()
    }

    override fun onInterrupt() {}

    private fun openAether() {
        val home = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(home) }.onFailure { EventLog.log("Home button: could not open Aether (${it.javaClass.simpleName})") }
    }

    /** Focus events only while a settings session needs them; otherwise window changes alone. */
    private fun setFocusEvents(on: Boolean) {
        val info = serviceInfo ?: return
        val want = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or (if (on) AccessibilityEvent.TYPE_VIEW_FOCUSED else 0)
        if (info.eventTypes != want) { info.eventTypes = want; serviceInfo = info }
    }

    companion object {
        const val FLAG_PAUSED = "home_redirect_paused"
        @Volatile var running: HomeButtonService? = null
            private set
        private val policy = HomeRedirectPolicy("com.aether.tv")

        /** Called just before Aether opens one of Amazon's settings screens, so the service doesn't cover it. */
        fun beginSettingsSession() {
            policy.beginSettingsSession(SystemClock.uptimeMillis())
            running?.setFocusEvents(true)
        }

        /** True when Android lists this service as enabled (it may take a moment to bind). */
        fun isEnabled(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = "${context.packageName}/${HomeButtonService::class.java.name}"
            return list.split(':').any { it.equals(me, ignoreCase = true) || it.equals("${context.packageName}/.platform.HomeButtonService", true) }
        }

        const val ENABLE_COMMANDS =
            "adb shell settings put secure enabled_accessibility_services com.aether.tv/com.aether.tv.platform.HomeButtonService\n" +
                "adb shell settings put secure accessibility_enabled 1"
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface HomeServiceEntryPoint {
    fun settings(): SettingsRepository
}
