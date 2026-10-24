package iris.tools.airlift

import iris.tools.airlift.io.JvmFileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.io.files.Path
import iris.tools.airlift.process.JvmProcessLauncher
import iris.tools.airlift.process.ProcessLauncher

internal actual fun platformProcessLauncher(): ProcessLauncher = JvmProcessLauncher()

internal actual fun platformWorkerDispatcher() = Dispatchers.IO
internal actual fun platformClock(): DispatcherClock = object : DispatcherClock {
    override fun wallTimeMillis() = System.currentTimeMillis()
    override fun monotonicNanos() = System.nanoTime()
}
internal actual fun platformPaths(): DispatcherPaths = object : DispatcherPaths {
    override fun absolute(path: Path) = Path(java.nio.file.Path.of(path.toString()).toAbsolutePath().normalize().toString())
}
internal actual fun platformLogs(config: AirliftConfig): DispatcherLogStore = object : DispatcherLogStore {
    override suspend fun createRunDirectory(): Path {
        JvmFileAccess.createDirectories(config.logDirectory)
        return Path(JvmFileAccess.temporaryDirectory(java.nio.file.Path.of(config.logDirectory.toString()), "run-").toString())
    }
    override fun attemptPath(run: Path, worker: Int, taskIndex: Int, attempt: Int) =
        Path(java.nio.file.Path.of(run.toString()).resolve("worker-$worker-task-$taskIndex-attempt-$attempt.log").toString())
}
