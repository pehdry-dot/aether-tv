package com.aether.tv.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.aether.tv.MainActivity
import com.aether.tv.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Optional: brings Aether to the front after the TV boots. Android 10+ blocks activity starts from the background
 * unless the app holds "Display over other apps"; without it this receiver does nothing (and says so in Settings).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!readLaunchOnBoot(context)) return@launch
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)) return@launch
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            } finally { pending.finish() }
        }
    }

    private suspend fun readLaunchOnBoot(context: Context): Boolean {
        val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, BootEntryPoint::class.java)
        val id = ep.profiles().activeId()
        return ep.settings().get(id).behavior.launchOnBoot
    }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface BootEntryPoint {
    fun settings(): SettingsRepository
    fun profiles(): com.aether.tv.data.profiles.ProfileRepository
}
