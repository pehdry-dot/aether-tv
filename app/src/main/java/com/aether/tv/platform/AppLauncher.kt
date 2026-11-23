package com.aether.tv.platform

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Starts other apps and their system management screens. */
@Singleton
class AppLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shortcuts: SystemShortcuts,
) {
    private val pm get() = context.packageManager

    fun launchIntent(component: ComponentName, isTvApp: Boolean): Intent {
        val category = if (isTvApp) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER
        return Intent(Intent.ACTION_MAIN).addCategory(category).setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    fun launch(component: ComponentName, isTvApp: Boolean): LaunchResult {
        val attempts = listOfNotNull(
            launchIntent(component, isTvApp),
            pm.getLeanbackLaunchIntentForPackage(component.packageName),
            pm.getLaunchIntentForPackage(component.packageName),
        )
        for (i in attempts) {
            try {
                context.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return LaunchResult.Opened(component.flattenToShortString())
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        EventLog.log("Launch failed: ${component.flattenToShortString()}")
        return LaunchResult.Unavailable("This app couldn't be opened. It may have been uninstalled or disabled.")
    }

    fun launchPackage(pkg: String): LaunchResult {
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            ?: return LaunchResult.Unavailable("$pkg is not installed.")
        return try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); LaunchResult.Opened(pkg) }
        catch (e: Exception) { LaunchResult.Unavailable("Couldn't open $pkg.") }
    }

    fun openUri(uri: String, pkg: String?): LaunchResult {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        return try { context.startActivity(i); LaunchResult.Opened(uri) } catch (_: Exception) {
            if (pkg != null) launchPackage(pkg) else LaunchResult.Unavailable("No app can open this item.")
        }
    }

    fun appInfo(pkg: String): LaunchResult {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (shortcuts.isUsable(details)) {
            try { context.startActivity(details); return LaunchResult.Opened("app details") } catch (_: Exception) { }
        }
        return shortcuts.open(SystemShortcut.Applications)
    }

    fun uninstall(pkg: String): LaunchResult {
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (shortcuts.isUsable(i)) {
            try { context.startActivity(i); return LaunchResult.Opened("uninstaller") } catch (_: Exception) { }
        }
        return appInfo(pkg)
    }
}
