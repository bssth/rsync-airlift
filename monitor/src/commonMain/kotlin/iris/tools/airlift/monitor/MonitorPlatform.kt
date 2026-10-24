package iris.tools.airlift.monitor

internal expect fun monitorMonotonicNanos(): Long
internal expect fun monitorWallTimeMillis(): Long
internal expect fun createMonitorTerminal(title: String, swing: Boolean): MonitorTerminal

/** Locale-independent fixed precision for terminal statistics. */
internal fun fixedOne(value: Double): String {
    if (!value.isFinite()) return value.toString()
    val rounded = kotlin.math.floor(kotlin.math.abs(value) * 10 + 0.5).toLong()
    return (if (value < 0) "-" else "") + (rounded / 10) + "." + (rounded % 10)
}
