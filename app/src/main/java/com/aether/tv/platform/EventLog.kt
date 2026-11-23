package com.aether.tv.platform

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory ring buffer of notable events (launch failures, input-switch attempts). Shown in
 * Settings ▸ Advanced ▸ Recent events to diagnose platform behaviour on real TVs. Never leaves the device.
 */
object EventLog {
    private const val MAX = 80
    private val entries = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun log(message: String) {
        entries.addFirst("${fmt.format(Date())}  $message")
        while (entries.size > MAX) entries.removeLast()
    }

    @Synchronized fun recent(): List<String> = entries.toList()
}
