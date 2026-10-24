package iris.tools.airlift

/** Paths are relative to the roots. sourceRoot optionally overrides only the source for this task.
 * Input order is preserved; id and destination path must be unique across the transfer. */
data class TransferTask(val path: String, val size: Long? = null, val id: String = path, val sourceRoot: String? = null,
    val files: List<String> = emptyList(), val entries: List<String> = emptyList()) {
    val fileCount: Int get() = files.size.coerceAtLeast(1)
    init {
        require(id.isNotBlank()) { "Task id must not be blank" }
        require(path.isNotEmpty() && !path.startsWith('/') && '\u0000' !in path && path.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
            "Expected a relative POSIX file path without traversal: $path"
        }
        require(size == null || size >= 0)
        require(sourceRoot == null || (sourceRoot.isNotEmpty() && '\u0000' !in sourceRoot))
        files.forEach { require(it.isNotEmpty() && !it.startsWith('/') && '\u0000' !in it && it.split('/').none { part -> part in listOf("", ".", "..") }) }
        require(files.distinct().size == files.size)
        entries.forEach { entry ->
            val relative = entry.removeSuffix("/")
            require(entry == "." || (relative.isNotEmpty() && !relative.startsWith('/') && '\u0000' !in relative && relative.split('/').none { it in listOf("", ".", "..") }))
        }
    }
}

enum class TransferDirection { DOWNLOAD, UPLOAD }
enum class TaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
enum class WorkerStatus { CONNECTING, IDLE, TRANSFERRING, FAILED, STOPPED, FINISHED }

data class TransferProgress(
    val logicalBytes: Long, val bytesPerSecond: Double?, val remainingSeconds: Long?, val percent: Int? = null,
    val matchedBytes: Long? = null, val literalBytes: Long? = null,
    val currentFile: String? = null, val transferredFiles: Int? = null,
)
data class WorkerState(val id: Int, val status: WorkerStatus, val taskId: String? = null, val error: String? = null)
