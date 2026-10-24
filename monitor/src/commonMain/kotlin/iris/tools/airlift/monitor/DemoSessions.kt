package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.buffered
import kotlinx.io.writeString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal fun demoSessions() = TransferSessionFactory {
    object : TransferSession {
        private val closed = MutableStateFlow(false)
        override suspend fun connect() { check(!closed.value) }
        override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit): Int =
            withContext(preparationDispatcher()) {
                val size = requireNotNull(task.size)
                SystemFileSystem.sink(logFile).buffered().use { log ->
                    log.writeString("DEMO: simulated transfer ${task.path}\n"); log.flush()
                    for (step in 1..100) {
                        if (closed.value) return@withContext 20
                        delay(80)
                        val matched = if (step == 100) size * 3 / 5 else null
                        progress(TransferProgress(size * step / 100, size / 8.0, (100 - step) * 8L / 100,
                            percent = step, matchedBytes = matched, literalBytes = matched?.let { size - it }))
                        if (step % 20 == 0) { log.writeString("DEMO logical progress: $step%\n"); log.flush() }
                        if (task.path.endsWith("file-11.bin") && step == 40) {
                            log.writeString("DEMO ERROR: simulated permission denied\n")
                            return@withContext 23
                        }
                    }
                    log.writeString("DEMO complete\n")
                }
                0
            }
        override suspend fun close() { closed.value = true }
    }
}
