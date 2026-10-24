@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import iris.tools.airlift.SshAuthentication
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import platform.posix.*
import iris.tools.airlift.monitor.posix.*

fun main(args: Array<String>) = runBlocking {
    var status = 0
    try {
        if (args.isNotEmpty() && "--help" !in args) {
            val parsed = LaunchArguments.parse(args.toList())
            require("--swing" !in parsed.enabled) { "Swing is only available in the JVM version" }
            if (parsed.enabled.any { it == "--run" || it == "--demo" } || "--attach" in parsed.options) {
                check(hasInteractiveConsole()) { "Native TUI requires a terminal (SSH: ssh -t). Use --serve for headless transfers." }
            }
        }
        launchMonitor(args.toList())
    } catch (error: Exception) { fputs("Airlift: ${error.message}\n", stderr); status = 1 }
    finally { SshAuthentication.clearPassword() }
    if (status != 0) exit(status)
}
internal actual fun monitorApplicationPlatform(): MonitorApplicationPlatform = object : MonitorApplicationPlatform {
    override fun remoteSource(endpoint: Path): MonitorSource = RemoteMonitorSource(endpoint)
    override fun serve(source: MonitorSource, endpoint: Path): MonitorService {
        val server = MonitorServer(source, endpoint)
        return object : MonitorService { override fun close() = server.close() }
    }
    override fun onShutdown(action: suspend () -> Unit): ShutdownRegistration {
        val signals = airlift_install_signals() ?: linuxError("install signal handlers")
        val scope = CoroutineScope(SupervisorJob() + preparationDispatcher())
        val job = scope.launch {
            while (airlift_stopping() == 0) delay(50)
            withContext(NonCancellable) { action() }
        }
        return object : ShutdownRegistration {
            private var removed = false
            override fun remove() {
                if (!removed) { removed = true; airlift_restore_signals(signals); job.cancel(); scope.cancel() }
            }
        }
    }
    override suspend fun temporaryDirectory(prefix: String): Path = withContext(preparationDispatcher()) {
        require('/' !in prefix && '\u0000' !in prefix)
        val parent = getenv("TMPDIR")?.toKString()?.takeIf { it.isNotEmpty() } ?: "/tmp"
        val bytes = "$parent/${prefix}XXXXXX".encodeToByteArray() + byteArrayOf(0)
        bytes.usePinned { Path((mkdtemp(it.addressOf(0)) ?: linuxError("mkdtemp")).toKString()) }
    }
}
