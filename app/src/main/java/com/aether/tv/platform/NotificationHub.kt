package com.aether.tv.platform

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TvNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String?,
    val postedAt: Long,
    val clearable: Boolean,
)

/**
 * Process-wide holder for data that only the notification listener can see. When the user hasn't granted
 * notification access, [connected] stays false and the UI explains how to grant it — nothing is invented.
 */
object NotificationHub {
    private val _notifications = MutableStateFlow<List<TvNotification>>(emptyList())
    val notifications: StateFlow<List<TvNotification>> = _notifications.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    /** Bumped whenever media sessions may have changed. */
    private val _mediaTick = MutableStateFlow(0L)
    val mediaTick: StateFlow<Long> = _mediaTick.asStateFlow()

    @Volatile internal var service: AetherNotificationListener? = null

    fun isAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun listenerComponent(context: Context) = ComponentName(context, AetherNotificationListener::class.java)

    /** Active media sessions (requires notification access). Returns null when access is missing. */
    fun activeSessions(context: Context): List<MediaController>? {
        if (!isAccessGranted(context)) return null
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return runCatching { msm.getActiveSessions(listenerComponent(context)) }.getOrNull()
    }

    fun dismiss(key: String) { runCatching { service?.cancelNotification(key) } }

    internal fun publish(list: List<StatusBarNotification>) {
        _notifications.value = list.mapNotNull { it.toTv() }.sortedByDescending { it.postedAt }
        _mediaTick.value = System.currentTimeMillis()
    }

    internal fun setConnected(c: Boolean) { _connected.value = c; if (!c) _notifications.value = emptyList() }

    private fun StatusBarNotification.toTv(): TvNotification? {
        val n = notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_SERVICE) return null
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        return TvNotification(key, packageName, title, extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(), postTime, isClearable)
    }
}

/** Enabled by the user (or via adb) to provide notifications and media-session access. */
class AetherNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        NotificationHub.service = this
        NotificationHub.setConnected(true)
        refresh()
    }
    override fun onListenerDisconnected() {
        NotificationHub.service = null
        NotificationHub.setConnected(false)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() { runCatching { NotificationHub.publish(activeNotifications?.toList().orEmpty()) } }
}
