package iris.tools.airlift

/** Paths are relative to the roots. sourceRoot optionally overrides only the source for this task.
 * Input order is preserved; id and destination path must be unique across the transfer. */
data class TransferTask(val path: String, val size: Long? = null, val id: String = path, val sourceRoot: String? = null) {
    init {
        require(id.isNotBlank()) { "Task id must not be blank" }
        require(path.isNotEmpty() && !path.startsWith('/') && '\u0000' !in path && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
            "Expected a relative POSIX file path without traversal: $path"
        }
        require(size == null || size >= 0)
        require(sourceRoot == null || (sourceRoot.isNotEmpty() && '\u0000' !in sourceRoot))
    }
}

enum class TransferDirection { DOWNLOAD, UPLOAD }
enum class TaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
enum class WorkerStatus { CONNECTING, IDLE, TRANSFERRING, FAILED, STOPPED, FINISHED }

data class AirliftConfig(
    val host: String,
    val remoteRoot: String,
    val localRoot: java.nio.file.Path,
    val workers: Int = 16,
    val direction: TransferDirection = TransferDirection.DOWNLOAD,
    val port: Int = 22,
    val identityFile: java.nio.file.Path? = null,
    val maxAttempts: Int = 1,
    val retryDelayMillis: Long = 1000,
    val connectTimeoutSeconds: Int = 15,
    val logDirectory: java.nio.file.Path = java.nio.file.Path.of("airlift-logs"),
    val sshExecutable: String = "ssh",
    val rsyncExecutable: String = "rsync",
    val rsyncOptions: String? = null,
) {
    init {
        require(host.isNotBlank() && !host.startsWith('-') && host.none { it.isWhitespace() || it.isISOControl() })
        require(remoteRoot.startsWith('/') && remoteRoot.none { it.isISOControl() })
        require(workers > 0 && maxAttempts > 0 && retryDelayMillis >= 0 && connectTimeoutSeconds > 0)
        require(port in 1..65535)
        rsyncOptions?.let(RsyncArguments::parse)
    }
}

data class TransferProgress(
    val logicalBytes: Long, val bytesPerSecond: Double?, val remainingSeconds: Long?, val percent: Int? = null,
    val matchedBytes: Long? = null, val literalBytes: Long? = null,
)
data class TaskState(
    val task: TransferTask,
    val status: TaskStatus = TaskStatus.QUEUED,
    val attempts: Int = 0,
    val progress: TransferProgress? = null,
    val error: String? = null,
    val logFile: java.nio.file.Path? = null,
    val progressUpdatedAtNanos: Long? = null,
)
data class WorkerState(val id: Int, val status: WorkerStatus, val taskId: String? = null, val error: String? = null)
data class AirliftSnapshot(
    val tasks: List<TaskState>, val workers: List<WorkerState>, val paused: Boolean, val cancelled: Boolean,
    val targetWorkers: Int = 0,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
) {
    val queued: Int get() = tasks.count { it.status == TaskStatus.QUEUED }
    val completed: Int get() = tasks.count { it.status == TaskStatus.SUCCEEDED }
    val failed: Int get() = tasks.count { it.status == TaskStatus.FAILED }
}

/** One session per worker. Implementations must unblock transfer/connect when close is called. */
interface TransferSession : AutoCloseable {
    fun connect()
    fun transfer(task: TransferTask, logFile: java.nio.file.Path, progress: (TransferProgress) -> Unit): Int
}

fun interface TransferSessionFactory {
    fun create(workerId: Int): TransferSession
}
