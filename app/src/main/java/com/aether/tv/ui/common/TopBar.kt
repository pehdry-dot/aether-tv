package com.aether.tv.ui.common

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.GlassTile
import com.aether.tv.core.model.ClockFormat
import com.aether.tv.core.model.HomeLayoutSettings
import com.aether.tv.platform.NetworkState
import com.aether.tv.platform.NetworkType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Current time, updated on each minute boundary only (no per-second polling). */
@Composable
fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(60_000 - value % 60_000 + 50)
    }
}

fun formatTime(millis: Long, use24h: Boolean): String =
    SimpleDateFormat(if (use24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(millis))

fun formatDate(millis: Long): String = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(millis))

fun com.aether.tv.data.weather.WeatherNow.icon(): ImageVector = when (kind) {
    com.aether.tv.data.weather.WeatherKind.Clear -> if (isDay) AetherIcons.Sun else AetherIcons.Moon
    com.aether.tv.data.weather.WeatherKind.PartlyCloudy -> if (isDay) AetherIcons.CloudSun else AetherIcons.CloudMoon
    com.aether.tv.data.weather.WeatherKind.Cloudy -> AetherIcons.Cloud
    com.aether.tv.data.weather.WeatherKind.Fog -> AetherIcons.CloudFog
    com.aether.tv.data.weather.WeatherKind.Drizzle -> AetherIcons.CloudDrizzle
    com.aether.tv.data.weather.WeatherKind.Rain -> AetherIcons.CloudRain
    com.aether.tv.data.weather.WeatherKind.Snow -> AetherIcons.CloudSnow
    com.aether.tv.data.weather.WeatherKind.Storm -> AetherIcons.CloudLightning
}

fun NetworkState.icon(): ImageVector = when (type) {
    NetworkType.Ethernet -> AetherIcons.Ethernet
    NetworkType.None -> AetherIcons.WifiOff
    NetworkType.Other -> AetherIcons.Globe
    NetworkType.Wifi -> when (signalLevel) { null, 4, 3 -> AetherIcons.Wifi; 2 -> AetherIcons.WifiHigh; 1 -> AetherIcons.WifiLow; else -> AetherIcons.WifiZero }
}

@Composable
fun TopBar(
    layout: HomeLayoutSettings,
    network: NetworkState,
    hasNotifications: Boolean,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    weather: com.aether.tv.data.weather.WeatherNow? = null,
    /** Settings is the page on screen: the ⚙ button shows as selected (it is the only way in when the tab is hidden). */
    settingsOpen: Boolean = false,
    settingsRequester: androidx.compose.ui.focus.FocusRequester? = null,
    /** "Update available" notice (Settings ▸ Updates); null hides the button. */
    update: String? = null,
    onUpdate: () -> Unit = {},
) {
    val context = LocalContext.current
    val now by rememberMinuteClock()
    val use24 = when (layout.clockFormat) { ClockFormat.System -> DateFormat.is24HourFormat(context); ClockFormat.H24 -> true; ClockFormat.H12 -> false }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            if (layout.showClock) AText(formatTime(now, use24), Aether.type.clock)
            val w = weather?.takeIf { layout.showWeather }
            if (layout.showDate || w != null) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (layout.showDate) AText(formatDate(now), Aether.type.date, color = Aether.colors.textSecondary)
                if (w != null) {
                    if (layout.showDate) Spacer(Modifier.width(12.dp))
                    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "Weather: ${w.description}" }, verticalAlignment = Alignment.CenterVertically) {
                        AIcon(w.icon(), size = 15.dp, tint = Aether.colors.textSecondary)
                        Spacer(Modifier.width(5.dp))
                        AText(w.text, Aether.type.date, color = Aether.colors.textSecondary)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.offset(x = 8.dp)) {
            if (update != null) TopIcon(AetherIcons.Download, update, onUpdate, badge = true)
            TopIcon(AetherIcons.Search, "Search everything", onSearch)
            if (layout.showNotifications) TopIcon(AetherIcons.Bell, if (hasNotifications) "Notifications (new)" else "Notifications", onNotifications, badge = hasNotifications)
            if (layout.showNetworkStatus) TopIcon(network.icon(), "${network.label}. Quick controls", onNetwork)
            TopIcon(AetherIcons.Settings, "Open settings", onSettings, selected = settingsOpen, requester = settingsRequester)
        }
    }
}

@Composable
private fun TopIcon(icon: ImageVector, label: String, onClick: () -> Unit, badge: Boolean = false, selected: Boolean = false, requester: androidx.compose.ui.focus.FocusRequester? = null) {
    GlassTile(onClick = onClick, shape = CircleShape, modifier = Modifier.size(34.dp).semantics { contentDescription = label; this.selected = selected },
        scaleOnFocus = true, restingSurface = selected, selected = selected, focusRequester = requester) { focused ->
        Box(Modifier.fillMaxWidth().padding(7.dp), contentAlignment = Alignment.Center) {
            AIcon(icon, size = 19.dp, tint = if (focused) Aether.colors.textPrimary else Aether.colors.textPrimary.copy(alpha = 0.88f))
        }
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 7.dp).size(6.dp).clip(CircleShape).background(Aether.colors.warning))
    }
}

