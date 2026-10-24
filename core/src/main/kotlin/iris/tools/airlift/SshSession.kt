package iris.tools.airlift

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class SshSessionFactory(private val config: AirliftConfig) : TransferSessionFactory {
    override fun create(workerId: Int): TransferSession = SshSession(config)
}

private class SshSession(private val config: AirliftConfig) : TransferSession {
    private val lock = Any()
    private var closed = false
    private val processes = mutableSetOf<Process>()
    // Short paths avoid the small Unix-domain socket path limit.
    private val directory = Files.createTempDirectory("airlift-")
    private val socket = directory.resolve("s")
    private val base get() = buildList {
        add(config.sshExecutable)
        addAll(listOf("-p", config.port.toString(), "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "ConnectTimeout=${config.connectTimeoutSeconds}", "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3"))
        config.identityFile?.let { addAll(listOf("-i", it.toAbsolutePath().toString())) }
    }

    private fun launch(command: List<String>, output: Path? = null): Process = synchronized(lock) {
        check(!closed) { "SSH session is closed" }
        val builder = SshAuthentication.configure(ProcessBuilder(command)).redirectErrorStream(true)
        builder.environment()["LC_ALL"] = "C"
        if (output != null) builder.redirectOutput(output.toFile())
        builder.start().also { processes.add(it) }
    }

    private fun forget(process: Process) = synchronized(lock) { processes.remove(process); Unit }

    override fun connect() {
        val master = launch(SshAuthentication.command(base + listOf("-M", "-N", "-S", socket.toString(), "-o", "ControlPersist=no", config.host)), directory.resolve("master.log"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.connectTimeoutSeconds.toLong() + 2)
        while (System.nanoTime() < deadline) {
            check(master.isAlive) { "SSH master exited: ${Files.readString(directory.resolve("master.log")).takeLast(4096)}" }
            val probe = launch(base + listOf("-S", socket.toString(), "-O", "check", config.host), directory.resolve("probe.log"))
            val finished = probe.waitFor(1, TimeUnit.SECONDS)
            if (!finished) { probe.destroyForcibly(); probe.waitFor() }
            forget(probe)
            if (finished && probe.exitValue() == 0) return
            Thread.sleep(100)
        }
        error("SSH ControlMaster connection timed out")
    }

    override fun transfer(task: TransferTask, logFile: Path, progress: (TransferProgress) -> Unit): Int {
        Files.createDirectories(config.localRoot)
        val transport = (base + listOf("-S", socket.toString(), "-o", "ControlMaster=no", "-o", "ProxyCommand=false"))
            .joinToString(" ") { quote(it) }
        val address = config.host.substringAfterLast('@')
        val rsyncHost = if (':' in address) config.host.removeSuffix(address) + "[$address]" else config.host
        val remoteRoot = if (config.direction == TransferDirection.DOWNLOAD) task.sourceRoot ?: config.remoteRoot else config.remoteRoot
        val localRoot = if (config.direction == TransferDirection.UPLOAD) task.sourceRoot?.let(Path::of) ?: config.localRoot else config.localRoot
        val remote = "$rsyncHost:${remoteRoot.trimEnd('/')}/"
        val local = localRoot.toAbsolutePath().toString().trimEnd('/', '\\') + "/"
        val source = if (config.direction == TransferDirection.DOWNLOAD) remote else local
        val destination = if (config.direction == TransferDirection.DOWNLOAD) local else remote
        val entries = task.entries
        val command = RsyncCommand.build(config, transport, source, destination,
            recursive = entries.any { it == "." || it.endsWith('/') })
        Files.newBufferedWriter(logFile).use { log ->
            val process = launch(command)
            val inputFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
            // Feed the list while draining output: long lists must not deadlock
            // when rsync produces output before it has consumed all stdin.
            val writer = kotlin.concurrent.thread(name = "airlift-rsync-list", isDaemon = true) {
                try {
                    process.outputStream.use { output ->
                        (entries.ifEmpty { task.files.ifEmpty { listOf(task.path) } }).forEach { output.write((it + '\u0000').toByteArray(Charsets.UTF_8)) }
                    }
                } catch (e: Exception) { inputFailure.set(e) }
            }
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val parser = RsyncOutputParser(task.files.ifEmpty { listOf(task.path) }.toSet())
                    val record = StringBuilder()
                    while (true) {
                        val value = reader.read()
                        if (value == -1) break
                        val ch = value.toChar()
                        log.write(value)
                        if (ch == '\r' || ch == '\n') {
                            parser.parse(record.toString())?.let(progress)
                            record.setLength(0)
                            log.flush()
                        } else {
                            if (record.length < 16384) record.append(ch)
                        }
                    }
                    parser.parse(record.toString())?.let(progress)
                }
                val code = process.waitFor()
                writer.join()
                if (code == 0) inputFailure.get()?.let { throw it }
                return code
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
                forget(process)
            }
        }
    }

    override fun close() {
        val owned = synchronized(lock) {
            if (closed) return
            closed = true
            processes.toList()
        }
        owned.forEach { it.destroy() }
        owned.forEach {
            if (!it.waitFor(2, TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor() }
        }
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
