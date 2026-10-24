package iris.tools.airlift

/** Synchronous platform lock for short, non-suspending critical sections. */
internal interface PlatformLock {
    fun <T> locked(block: () -> T): T
}

internal expect fun platformLock(): PlatformLock
