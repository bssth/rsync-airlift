package iris.tools.airlift.monitor

import iris.tools.airlift.*
import iris.tools.airlift.process.*
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal suspend fun readManifestText(path: Path): String = withContext(preparationDispatcher()) {
    SystemFileSystem.source(path).buffered().use { it.readByteArray().decodeToString(throwOnInvalidSequence = true) }
}

internal suspend fun readInventory(config: AirliftConfig, source: String,
    launcher: ProcessLauncher = preparationProcesses()): FileInventory {
    if (config.direction == TransferDirection.UPLOAD) return readLocalInventory(source)
    val command = buildList {
        add(config.sshExecutable)
        addAll(listOf("-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "ConnectTimeout=${config.connectTimeoutSeconds}", "-o", "ServerAliveInterval=30",
            "-o", "ServerAliveCountMax=3", "-p", config.port.toString()))
        config.identityFile?.let { addAll(listOf("-i", it.toString())) }
        addAll(listOf(config.host, inventoryCommand(source)))
    }
    // Register the resource before cancellation can interrupt the startup handover.
    var process: ChildProcess? = null
    try {
        withContext(NonCancellable) { process = launcher.start(SshAuthentication.configure(ProcessSpec(command))) }
        currentCoroutineContext().ensureActive()
        val child = requireNotNull(process)
        child.stdin?.close()
        return coroutineScope {
            // Drain stderr concurrently so a verbose SSH failure cannot block stdout.
            val errors = async { child.stderr?.readTail(4096).orEmpty() }
            val records = readInventoryRecords(requireNotNull(child.stdout))
            val exit = child.awaitExit()
            val error = errors.await()
            check(exit == 0) { "Cannot collect source files over SSH: $error" }
            decodeInventory(records)
        }
    } finally {
        withContext(NonCancellable) { process?.close() }
    }
}

internal suspend fun readInventoryRecords(source: ProcessByteSource): List<String> {
    val records = mutableListOf<String>()
    val record = Buffer()
    val chunk = ByteArray(8192)
    while (true) {
        val count = source.read(chunk)
        if (count < 0) { check(record.size == 0L) { "Incomplete SSH file inventory" }; return records }
        for (index in 0 until count) {
            val byte = chunk[index]
            if (byte == 0.toByte()) {
                val text = record.readByteArray().decodeToString(throwOnInvalidSequence = true)
                require(text.length <= 1024 * 1024) { "Invalid SSH inventory record" }
                records += text
            } else {
                record.writeByte(byte)
                require(record.size <= 4L * 1024 * 1024) { "Invalid SSH inventory record" }
            }
        }
    }
}

internal suspend fun ProcessByteSource.readTail(limit: Int): String {
    var tail = ByteArray(0)
    val chunk = ByteArray(8192)
    while (true) {
        val count = read(chunk)
        if (count < 0) return tail.decodeToString()
        val retained = minOf(tail.size, (limit - count).coerceAtLeast(0))
        val incoming = minOf(count, limit)
        tail = ByteArray(retained + incoming).also {
            tail.copyInto(it, 0, tail.size - retained)
            chunk.copyInto(it, retained, count - incoming, count)
        }
    }
}
