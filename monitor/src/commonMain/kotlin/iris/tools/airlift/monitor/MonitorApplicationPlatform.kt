package iris.tools.airlift.monitor

import kotlinx.io.files.Path

internal interface MonitorService { fun close() }
internal interface ShutdownRegistration { fun remove() }
internal interface MonitorApplicationPlatform {
    fun remoteSource(endpoint: Path): MonitorSource
    fun serve(source: MonitorSource, endpoint: Path): MonitorService
    fun onShutdown(action: suspend () -> Unit): ShutdownRegistration
    suspend fun temporaryDirectory(prefix: String): Path
}
internal expect fun monitorApplicationPlatform(): MonitorApplicationPlatform
