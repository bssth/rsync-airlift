package iris.tools.airlift.monitor

import iris.tools.airlift.*
import java.io.*
import java.net.*
import iris.tools.airlift.io.JvmFileAccess
import java.nio.file.Path
import java.nio.file.FileAlreadyExistsException
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Local authenticated monitor endpoint. Access remotely by running attach through SSH. */
class MonitorServer(private val source: MonitorSource, val endpoint: Path) : AutoCloseable {
    private val token = UUID.randomUUID().toString()
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val handlers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    @Volatile private var closed = false

    init {
        try {
            endpoint.toAbsolutePath().parent?.let { JvmFileAccess.createDirectories(it) }
            // Do not overwrite another dispatcher's connection file.
            JvmFileAccess.createPrivateFile(endpoint)
            val properties = Properties().apply {
                setProperty("port", server.localPort.toString())
                setProperty("token", token)
                setProperty("title", source.title)
                setProperty("from", source.from)
                setProperty("to", source.to)
                setProperty("protocol", "3")
            }
            JvmFileAccess.outputStream(endpoint).use { properties.store(it, "Rsync Airlift local monitor connection") }
        } catch (e: Exception) {
            server.close()
            handlers.shutdownNow()
            if (e is FileAlreadyExistsException) {
                throw IllegalStateException("Connection file already exists: ${endpoint.toAbsolutePath()}. " +
                    "Use --attach to view that run, or --endpoint with a different file for a new run. " +
                    "If it is stale, remove it only after verifying the old dispatcher has stopped.", e)
            }
            throw e
        }
        Thread({
            while (!closed) {
                try {
                    val socket = server.accept()
                    try { handlers.submit { handle(socket) } } catch (e: Exception) { socket.close() }
                } catch (_: SocketException) { break }
            }
        }, "airlift-monitor-server").apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) = kotlinx.coroutines.runBlocking {
        runCatching {
            socket.use {
                it.soTimeout = 3000
                val input = DataInputStream(it.getInputStream())
                val output = DataOutputStream(it.getOutputStream())
                val version = input.readInt()
                if (input.readUTF() != token) return@runCatching
                if (version != 3) {
                    output.writeBoolean(false)
                    Wire.text(output, "Monitor protocol changed. Restart dispatcher and monitor together.")
                    output.flush()
                    return@runCatching
                }
                try {
                    when (input.readUTF()) {
                        "snapshot" -> Unit
                        "pause" -> source.pause()
                        "resume" -> source.resume()
                        "workers" -> source.setWorkerCount(input.readInt())
                        else -> error("Unknown monitor command")
                    }
                    val snapshot = source.snapshot()
                    output.writeBoolean(true)
                    Wire.write(output, snapshot)
                } catch (e: Exception) {
                    output.writeBoolean(false)
                    Wire.text(output, e.message ?: "Monitor command failed")
                }
                output.flush()
            }
        }
    }

    override fun close() {
        closed = true
        server.close()
        handlers.shutdownNow()
        JvmFileAccess.deleteIfExists(endpoint)
    }
}

class RemoteMonitorSource(endpoint: Path) : MonitorSource {
    private val properties = Properties().apply { JvmFileAccess.inputStream(endpoint).use { load(it) } }
    init {
        require(properties.getProperty("protocol") == "3") { "Monitor protocol changed. Restart dispatcher and monitor together." }
    }
    private val port = properties.getProperty("port").toInt()
    private val token = properties.getProperty("token") ?: error("Missing monitor token")
    override val title: String = properties.getProperty("title", "RSYNC AIRLIFT")
    override val from: String = properties.getProperty("from", "?")
    override val to: String = properties.getProperty("to", "?")

    private fun request(command: String, count: Int? = null): AirliftSnapshot {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 1500)
            socket.soTimeout = 3000
            val output = DataOutputStream(socket.getOutputStream())
            output.writeInt(3)
            output.writeUTF(token)
            output.writeUTF(command)
            count?.let { output.writeInt(it) }
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            check(input.readBoolean()) { Wire.text(input) }
            return Wire.read(input)
        }
    }

    override suspend fun snapshot() = request("snapshot")
    override suspend fun pause() { request("pause") }
    override suspend fun resume() { request("resume") }
    override suspend fun setWorkerCount(count: Int) { request("workers", count) }
}

/** Explicit versioned data format; never deserializes Java objects or executable types. */
private object Wire {
    fun text(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_048_576)
        output.writeInt(bytes.size); output.write(bytes)
    }
    fun text(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..1_048_576)
        return ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }
    private fun nullable(output: DataOutputStream, value: String?) { output.writeBoolean(value != null); value?.let { text(output, it) } }
    private fun nullable(input: DataInputStream) = if (input.readBoolean()) text(input) else null
    private fun number(output: DataOutputStream, value: Long?) { output.writeBoolean(value != null); value?.let { output.writeLong(it) } }
    private fun number(input: DataInputStream) = if (input.readBoolean()) input.readLong() else null
    private fun count(input: DataInputStream): Int = input.readInt().also { require(it in 0..1_000_000) }

    fun write(output: DataOutputStream, s: AirliftSnapshot) {
        output.writeBoolean(s.paused); output.writeBoolean(s.cancelled); output.writeInt(s.targetWorkers)
        number(output, s.startedAtMillis); number(output, s.finishedAtMillis)
        output.writeInt(s.tasks.size)
        for (t in s.tasks) {
            text(output, t.task.path); number(output, t.task.size); text(output, t.task.id)
            output.writeInt(t.task.files.size); t.task.files.forEach { text(output, it) }
            text(output, t.status.name); output.writeInt(t.attempts)
            nullable(output, t.error); nullable(output, t.logFile?.toString())
            // nanoTime origins differ between processes; transmit age instead.
            number(output, t.progressUpdatedAtNanos?.let { (System.nanoTime() - it).coerceAtLeast(0) })
            output.writeBoolean(t.progress != null)
            t.progress?.let {
                output.writeLong(it.logicalBytes)
                output.writeBoolean(it.bytesPerSecond != null)
                it.bytesPerSecond?.let(output::writeDouble)
                number(output, it.remainingSeconds)
                number(output, it.percent?.toLong())
                number(output, it.matchedBytes)
                number(output, it.literalBytes)
                nullable(output, it.currentFile); number(output, it.transferredFiles?.toLong())
            }
        }
        output.writeInt(s.workers.size)
        for (w in s.workers) {
            output.writeInt(w.id); text(output, w.status.name); nullable(output, w.taskId); nullable(output, w.error)
        }
    }

    fun read(input: DataInputStream): AirliftSnapshot {
        val paused = input.readBoolean(); val cancelled = input.readBoolean(); val target = input.readInt()
        val started = number(input); val finished = number(input)
        val tasks = List(count(input)) {
            val path = text(input); val size = number(input); val id = text(input)
            val task = TransferTask(path, size, id, files = List(count(input)) { text(input) })
            val status = TaskStatus.valueOf(text(input)); val attempts = input.readInt()
            val error = nullable(input); val log = nullable(input)?.let { kotlinx.io.files.Path(it) }
            val updated = number(input)?.let { System.nanoTime() - it }
            val progress = if (input.readBoolean()) TransferProgress(input.readLong(),
                if (input.readBoolean()) input.readDouble() else null, number(input), number(input)?.toInt(), number(input), number(input), nullable(input), number(input)?.toInt()) else null
            TaskState(task, status, attempts, progress, error, log, updated)
        }
        val workers = List(count(input)) {
            WorkerState(input.readInt(), WorkerStatus.valueOf(text(input)), nullable(input), nullable(input))
        }
        return AirliftSnapshot(tasks, workers, paused, cancelled, target, started, finished)
    }
}
