package iris.tools.airlift.monitor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.util.concurrent.atomic.AtomicBoolean

internal actual fun monitorApplicationPlatform(): MonitorApplicationPlatform = object : MonitorApplicationPlatform {
    override fun remoteSource(endpoint: Path): MonitorSource = RemoteMonitorSource(endpoint)
    override fun serve(source: MonitorSource, endpoint: Path): MonitorService {
        val server = MonitorServer(source, endpoint)
        val closed = AtomicBoolean()
        return object : MonitorService {
            override fun close() { if (closed.compareAndSet(false, true)) server.close() }
        }
    }
    override fun onShutdown(action: suspend () -> Unit): ShutdownRegistration {
        val hook = Thread { runBlocking { action() } }
        Runtime.getRuntime().addShutdownHook(hook)
        return object : ShutdownRegistration {
            override fun remove() {
                try { Runtime.getRuntime().removeShutdownHook(hook) }
                catch (_: IllegalStateException) { /* Shutdown is already in progress. */ }
            }
        }
    }
    override suspend fun temporaryDirectory(prefix: String): Path = withContext(preparationDispatcher()) {
        MonitorFiles.temporaryDirectory(prefix)
    }
}
