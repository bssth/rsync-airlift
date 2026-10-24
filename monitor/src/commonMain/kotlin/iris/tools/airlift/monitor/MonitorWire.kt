package iris.tools.airlift.monitor

import iris.tools.airlift.*

internal object Wire {
    suspend fun text(output: MonitorOutput, text: String) {
        val bytes = text.encodeToByteArray()
        require(bytes.size <= 1_048_576)
        output.writeInt(bytes.size); output.write(bytes)
    }
    suspend fun text(input: MonitorInput): String {
        val size = input.readInt()
        require(size in 0..1_048_576)
        return ByteArray(size).also { input.readFully(it) }.decodeToString()
    }
    private suspend fun nullable(output: MonitorOutput, value: String?) { output.writeBoolean(value != null); value?.let { text(output, it) } }
    private suspend fun nullable(input: MonitorInput) = if (input.readBoolean()) text(input) else null
    private suspend fun number(output: MonitorOutput, value: Long?) { output.writeBoolean(value != null); value?.let { output.writeLong(it) } }
    private suspend fun number(input: MonitorInput) = if (input.readBoolean()) input.readLong() else null
    private suspend fun count(input: MonitorInput): Int = input.readInt().also { require(it in 0..1_000_000) }

    suspend fun write(output: MonitorOutput, s: AirliftSnapshot) {
        output.writeBoolean(s.paused); output.writeBoolean(s.cancelled); output.writeInt(s.targetWorkers)
        number(output, s.startedAtMillis); number(output, s.finishedAtMillis)
        output.writeInt(s.tasks.size)
        for (t in s.tasks) {
            text(output, t.task.path); number(output, t.task.size); text(output, t.task.id)
            output.writeInt(t.task.files.size); t.task.files.forEach { text(output, it) }
            text(output, t.status.name); output.writeInt(t.attempts)
            nullable(output, t.error); nullable(output, t.logFile?.toString())
            // nanoTime origins differ between processes; transmit age instead.
            number(output, t.progressUpdatedAtNanos?.let { (monitorMonotonicNanos() - it).coerceAtLeast(0) })
            output.writeBoolean(t.progress != null)
            t.progress?.let {
                output.writeLong(it.logicalBytes)
                output.writeBoolean(it.bytesPerSecond != null)
                it.bytesPerSecond?.let { value -> output.writeDouble(value) }
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

    suspend fun read(input: MonitorInput): AirliftSnapshot {
        val paused = input.readBoolean(); val cancelled = input.readBoolean(); val target = input.readInt()
        val started = number(input); val finished = number(input)
        val tasks = List(count(input)) {
            val path = text(input); val size = number(input); val id = text(input)
            val task = TransferTask(path, size, id, files = List(count(input)) { text(input) })
            val status = TaskStatus.valueOf(text(input)); val attempts = input.readInt()
            val error = nullable(input); val log = nullable(input)?.let { kotlinx.io.files.Path(it) }
            val updated = number(input)?.let { monitorMonotonicNanos() - it }
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
