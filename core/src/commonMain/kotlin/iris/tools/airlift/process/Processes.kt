package iris.tools.airlift.process

import kotlinx.io.files.Path

sealed interface ProcessRedirect {
    data object Pipe : ProcessRedirect
    data object Inherit : ProcessRedirect
    data class File(val path: Path, val append: Boolean = false) : ProcessRedirect
}

/** Arguments are passed literally, without shell evaluation. Null environment values remove inherited keys. */
data class ProcessSpec(
    val command: List<String>,
    val environment: Map<String, String?> = emptyMap(),
    val inheritEnvironment: Boolean = true,
    val directory: Path? = null,
    val input: ProcessRedirect = ProcessRedirect.Pipe,
    val output: ProcessRedirect = ProcessRedirect.Pipe,
    val error: ProcessRedirect = ProcessRedirect.Pipe,
    val mergeError: Boolean = false,
) {
    init {
        require(command.isNotEmpty() && command.first().isNotEmpty() && command.none { '\u0000' in it })
        require(environment.all { (key, value) -> key.isNotEmpty() && key.none { it == '=' || it == '\u0000' } && (value == null || '\u0000' !in value) })
        require(!mergeError || error == ProcessRedirect.Pipe) { "Merged stderr cannot also be redirected" }
        require(input !is ProcessRedirect.File || !input.append) { "Input files cannot use append mode" }
    }
    override fun toString() = "ProcessSpec(command=$command, environmentKeys=${environment.keys}, directory=$directory)"
}

/** A pipe has a single reader or writer; stdin and stdout may be used concurrently. */
interface ProcessByteSource {
    /** Returns -1 on EOF. A nonempty buffer never produces a zero-byte read. */
    suspend fun read(buffer: ByteArray): Int
    suspend fun close()
}

interface ProcessByteSink {
    suspend fun write(bytes: ByteArray)
    suspend fun flush()
    suspend fun close()
}

/** Cancelling pipe I/O aborts the child to release blocked OS pipes; awaitExit cancellation does not.
 * The owner must always close() the child, including after timeout or cancellation. */
interface ChildProcess {
    val stdin: ProcessByteSink?
    val stdout: ProcessByteSource?
    val stderr: ProcessByteSource?
    suspend fun exitCode(): Int?
    suspend fun awaitExit(): Int
    /** A timeout does not terminate the process. */
    suspend fun awaitExit(timeoutMillis: Long): Int?
    suspend fun terminate(force: Boolean = false)
    /** Idempotent: terminate, reap and release pipes. Concurrent close callers wait for the same cleanup. */
    suspend fun close()
}

fun interface ProcessLauncher {
    suspend fun start(spec: ProcessSpec): ChildProcess
}
