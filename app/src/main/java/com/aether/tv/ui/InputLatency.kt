package com.aether.tv.ui

import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent

/**
 * Remote response time, measured on the TV itself (Settings ▸ Advanced ▸ Remote response): from the moment the remote's
 * key reached Android (KeyEvent.eventTime) to the start of the next frame, i.e. the first frame that can show the result.
 * A long main-thread job (building a page) shows up directly as a long delay. Samples are tagged with whether a page was
 * being built in the background and whether building pages in advance was on, so both settings can be compared.
 */
object InputLatency {
    data class Sample(val ms: Long, val duringBuild: Boolean, val prewarm: Boolean)
    data class Stats(val count: Int, val median: Long, val p95: Long, val worst: Long, val worstDuringBuild: Long?, val builds: Int)

    private val samples = ArrayDeque<Sample>()
    private const val MAX = 300

    fun onKey(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return
        val at = e.eventTime
        val building = PagePrewarm.building != null
        val prewarm = PagePrewarm.enabled
        runCatching {
            Choreographer.getInstance().postFrameCallback {
                add(Sample((SystemClock.uptimeMillis() - at).coerceAtLeast(0), building || PagePrewarm.building != null, prewarm))
            }
        }
    }

    @Synchronized fun add(s: Sample) { samples.addLast(s); while (samples.size > MAX) samples.removeFirst() }
    @Synchronized fun clear() = samples.clear()
    @Synchronized fun all(): List<Sample> = samples.toList()

    /** Statistics for the samples taken with building pages in advance [prewarm] on or off; null when there are none. */
    fun stats(prewarm: Boolean): Stats? = summarize(all().filter { it.prewarm == prewarm })

    fun summarize(list: List<Sample>): Stats? {
        if (list.isEmpty()) return null
        val sorted = list.map { it.ms }.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        val during = list.filter { it.duringBuild }
        return Stats(list.size, pct(0.5), pct(0.95), sorted.last(), during.maxOfOrNull { it.ms }, during.size)
    }
}
