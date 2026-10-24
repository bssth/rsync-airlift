package iris.tools.airlift

import kotlinx.io.files.Path

data class AirliftConfig(
    val host: String,
    val remoteRoot: String,
    val localRoot: Path,
    val workers: Int = 16,
    val direction: TransferDirection = TransferDirection.DOWNLOAD,
    val port: Int = 22,
    val identityFile: Path? = null,
    val maxAttempts: Int = 1,
    val retryDelayMillis: Long = 1000,
    val connectTimeoutSeconds: Int = 15,
    val logDirectory: Path = Path("airlift-logs"),
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

data class TaskState(
    val task: TransferTask,
    val status: TaskStatus = TaskStatus.QUEUED,
    val attempts: Int = 0,
    val progress: TransferProgress? = null,
    val error: String? = null,
    val logFile: Path? = null,
    val progressUpdatedAtNanos: Long? = null,
)

data class AirliftSnapshot(
    val tasks: List<TaskState>, val workers: List<WorkerState>, val paused: Boolean, val cancelled: Boolean,
    val targetWorkers: Int = 0, val startedAtMillis: Long? = null, val finishedAtMillis: Long? = null,
) {
    val queued: Int get() = tasks.filter { it.status == TaskStatus.QUEUED }.sumOf { it.task.fileCount }
    val completed: Int get() = tasks.filter { it.status == TaskStatus.SUCCEEDED }.sumOf { it.task.fileCount }
    val failed: Int get() = tasks.filter { it.status == TaskStatus.FAILED }.sumOf { it.task.fileCount }
}
