package iris.tools.airlift.monitor

internal actual fun monitorMonotonicNanos(): Long = System.nanoTime()
internal actual fun monitorWallTimeMillis(): Long = System.currentTimeMillis()
