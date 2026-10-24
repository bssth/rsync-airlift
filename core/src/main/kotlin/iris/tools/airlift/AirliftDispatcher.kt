package iris.tools.airlift

import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Single-use dispatcher. snapshot() is safe to poll from an independent monitor. */
class AirliftDispatcher(
    private val config: AirliftConfig,
    tasks: List<TransferTask>,
    private val sessions: TransferSessionFactory = SshSessionFactory(config),
) : AutoCloseable {
    val sourceDescription: String get() = if (config.direction == TransferDirection.DOWNLOAD)
        "${config.host}:${config.remoteRoot}" else config.localRoot.toAbsolutePath().normalize().toString()
    val destinationDescription: String get() = if (config.direction == TransferDirection.DOWNLOAD)
        config.localRoot.toAbsolutePath().normalize().toString() else "${config.host}:${config.remoteRoot}"
    private val lock = Object()
    private val states = tasks.associate { it.id to TaskState(it) }.toMutableMap()
    private val taskIndexes = tasks.mapIndexed { index, task -> task.id to index }.toMap()
    private lateinit var runLogDirectory: java.nio.file.Path
    private val queue = ArrayDeque(tasks.map { it.id })
    private val workers = linkedMapOf<Int, WorkerState>()
    private val activeSessions = ConcurrentHashMap<Int, TransferSession>()
    private val started = AtomicBoolean()
    private val result = CompletableFuture<AirliftSnapshot>()
    private var paused = false
    private var cancelled = false
    private var targetWorkers = config.workers
    private var nextWorker = 0
    private var liveWorkers = 0
    private var startedAtMillis: Long? = null
    private var finishedAtMillis: Long? = null

    init { require(states.size == tasks.size) { "Task ids must be unique" } }

    fun snapshot(): AirliftSnapshot = synchronized(lock) { snapshotLocked() }
    private fun snapshotLocked() = AirliftSnapshot(states.values.toList(), workers.values.toList(), paused, cancelled,
        targetWorkers, startedAtMillis, finishedAtMillis)

    private fun completeLocked() {
        finishedAtMillis = System.currentTimeMillis()
        result.complete(snapshotLocked())
    }

    fun start(): CompletableFuture<AirliftSnapshot> = synchronized(lock) {
        check(!cancelled && started.compareAndSet(false, true)) { "Dispatcher is closed or already started" }
        Files.createDirectories(config.logDirectory)
        runLogDirectory = Files.createTempDirectory(config.logDirectory, "run-")
        startedAtMillis = System.currentTimeMillis()
        if (queue.isEmpty()) completeLocked() else replenishLocked()
        // Return a dependent future so a caller cannot complete/cancel internal bookkeeping.
        result.thenApply { it }
    }

    fun pause() = synchronized(lock) { paused = true }
    fun resume() = synchronized(lock) { paused = false; lock.notifyAll() }

    /** Decreasing concurrency retires workers after their current file. */
    fun setWorkerCount(count: Int) = synchronized(lock) {
        require(count > 0)
        check(!cancelled && !result.isDone)
        targetWorkers = count
        if (started.get()) replenishLocked()
        lock.notifyAll()
    }

    private fun replenishLocked() {
        while (!cancelled && queue.isNotEmpty() && liveWorkers < targetWorkers) {
            val id = ++nextWorker
            liveWorkers++
            workers[id] = WorkerState(id, WorkerStatus.CONNECTING)
            thread(name = "airlift-worker-$id") { runWorker(id) }
        }
    }

    private fun takeTask(id: Int): TransferTask? = synchronized(lock) {
        while (true) {
            if (cancelled) return null
            if (liveWorkers > targetWorkers) return null
            if (queue.isEmpty()) return null
            if (!paused) {
                val taskId = queue.removeFirst()
                val state = states.getValue(taskId)
                states[taskId] = state.copy(status = TaskStatus.RUNNING)
                workers[id] = WorkerState(id, WorkerStatus.TRANSFERRING, taskId)
                return state.task
            }
            workers[id] = WorkerState(id, WorkerStatus.IDLE)
            lock.wait()
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    private fun runWorker(id: Int) {
        var session: TransferSession? = null
        var fatal: String? = null
        try {
            session = sessions.create(id)
            activeSessions[id] = session
            if (synchronized(lock) { cancelled }) return
            session.connect()
            while (true) {
                val task = takeTask(id) ?: break
                val taskStartedAt = System.nanoTime()
                var error: String? = null
                var success = false
                for (attempt in 1..config.maxAttempts) {
                    if (synchronized(lock) { cancelled }) break
                    val log = runLogDirectory.resolve("worker-$id-task-${taskIndexes.getValue(task.id)}-attempt-$attempt.log")
                    synchronized(lock) {
                        states[task.id] = states.getValue(task.id).copy(attempts = attempt, progress = null, logFile = log, progressUpdatedAtNanos = null)
                    }
                    try {
                        val code = session.transfer(task, log) { progress ->
                            synchronized(lock) {
                                states[task.id] = states.getValue(task.id).copy(progress = progress, progressUpdatedAtNanos = System.nanoTime())
                            }
                        }
                        success = code == 0
                        error = if (success) null else "rsync exited with code $code"
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    }
                    if (success) break
                    if (attempt < config.maxAttempts) synchronized(lock) {
                        val deadline = System.nanoTime() + config.retryDelayMillis * 1_000_000
                        while (!cancelled) {
                            val remaining = (deadline - System.nanoTime()) / 1_000_000
                            if (remaining <= 0) break
                            lock.wait(remaining)
                        }
                    }
                }
                synchronized(lock) {
                    val current = states.getValue(task.id)
                    val elapsedSeconds = (System.nanoTime() - taskStartedAt).coerceAtLeast(1) / 1_000_000_000.0
                    val size = task.size ?: current.progress?.logicalBytes
                    val finalProgress = if (success && !cancelled) {
                        (current.progress ?: TransferProgress(size ?: 0, null, null)).copy(
                            logicalBytes = size ?: current.progress?.logicalBytes ?: 0,
                            bytesPerSecond = size?.toDouble()?.div(elapsedSeconds),
                            remainingSeconds = null, percent = 100,
                        )
                    } else current.progress
                    states[task.id] = current.copy(
                        status = if (cancelled) TaskStatus.CANCELLED else if (success) TaskStatus.SUCCEEDED else TaskStatus.FAILED,
                        error = error,
                        progress = finalProgress,
                    )
                    workers[id] = WorkerState(id, WorkerStatus.IDLE, task.id)
                }
            }
        } catch (e: Exception) {
            fatal = e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { session?.close() }
            activeSessions.remove(id)
            synchronized(lock) {
                val status = when {
                    cancelled -> WorkerStatus.STOPPED
                    fatal != null -> WorkerStatus.FAILED
                    queue.isEmpty() -> WorkerStatus.FINISHED
                    else -> WorkerStatus.STOPPED // Retired by a concurrency decrease.
                }
                workers[id] = WorkerState(id, status, taskId = workers[id]?.taskId, error = fatal)
                liveWorkers--
                // A failed connection must not cause an infinite respawn loop.
                if (fatal == null) replenishLocked()
                if (liveWorkers == 0) {
                    for ((key, state) in states.toMap()) {
                        if (state.status == TaskStatus.QUEUED || state.status == TaskStatus.RUNNING) {
                            states[key] = state.copy(status = if (cancelled) TaskStatus.CANCELLED else TaskStatus.FAILED,
                                error = if (cancelled) state.error else fatal ?: "No workers available")
                        }
                    }
                    queue.clear()
                    completeLocked()
                }
                lock.notifyAll()
            }
        }
    }

    /** Cancels pending tasks and terminates owned child processes. */
    override fun close() {
        synchronized(lock) {
            cancelled = true
            queue.clear()
            for ((id, state) in states.toMap()) {
                if (state.status == TaskStatus.QUEUED) states[id] = state.copy(status = TaskStatus.CANCELLED)
            }
            lock.notifyAll()
            if (liveWorkers == 0 && !result.isDone) completeLocked()
        }
        activeSessions.values.forEach { runCatching { it.close() } }
    }
}
