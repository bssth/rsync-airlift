package iris.tools.airlift

import iris.tools.airlift.io.JvmFileAccess
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.asCompletableFuture

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
    // Public controls and progress callbacks remain synchronous on JVM.
    // This lock protects only short state changes; no suspension or transfer holds it.
    private val lock = Any()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val wakeup = MutableStateFlow(0L)
    private val states = tasks.associate { it.id to TaskState(it) }.toMutableMap()
    private val taskIndexes = tasks.mapIndexed { index, task -> task.id to index }.toMap()
    private lateinit var runLogDirectory: java.nio.file.Path
    private val queue = ArrayDeque(tasks.map { it.id })
    private val workers = linkedMapOf<Int, WorkerState>()
    private val activeSessions = ConcurrentHashMap<Int, TransferSession>()
    private val started = AtomicBoolean()
    private val result = CompletableDeferred<AirliftSnapshot>()
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
        supervisor.complete()
    }

    fun start(): CompletableFuture<AirliftSnapshot> = synchronized(lock) {
        check(!cancelled && started.compareAndSet(false, true)) { "Dispatcher is closed or already started" }
        JvmFileAccess.createDirectories(config.logDirectory)
        runLogDirectory = JvmFileAccess.temporaryDirectory(config.logDirectory, "run-")
        startedAtMillis = System.currentTimeMillis()
        if (queue.isEmpty()) completeLocked() else replenishLocked()
        // Return a dependent future so a caller cannot complete/cancel internal bookkeeping.
        result.asCompletableFuture().thenApply { it }
    }

    fun pause() = synchronized(lock) { paused = true }
    fun resume() = synchronized(lock) { paused = false; signalLocked() }

    private fun signalLocked() { wakeup.value++ }

    /** Decreasing concurrency retires workers after their current job. */
    fun setWorkerCount(count: Int) = synchronized(lock) {
        require(count > 0)
        check(!cancelled && !result.isCompleted)
        targetWorkers = count
        if (started.get()) replenishLocked()
        signalLocked()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun replenishLocked() {
        while (!cancelled && queue.isNotEmpty() && liveWorkers < targetWorkers) {
            val id = ++nextWorker
            liveWorkers++
            workers[id] = WorkerState(id, WorkerStatus.CONNECTING)
            // Even a worker cancelled before dispatch must execute its finally bookkeeping.
            scope.launch(CoroutineName("airlift-worker-$id"), start = CoroutineStart.ATOMIC) { runWorker(id) }
        }
    }

    private suspend fun takeTask(id: Int): TransferTask? {
        while (true) {
            val observed = synchronized(lock) {
                if (cancelled || liveWorkers > targetWorkers || queue.isEmpty()) return null
                if (!paused) {
                    val taskId = queue.removeFirst()
                    val state = states.getValue(taskId)
                    states[taskId] = state.copy(status = TaskStatus.RUNNING)
                    workers[id] = WorkerState(id, WorkerStatus.TRANSFERRING, taskId)
                    return state.task
                }
                workers[id] = WorkerState(id, WorkerStatus.IDLE)
                wakeup.value
            }
            // Versioning avoids lost notifications between releasing the lock and subscribing.
            wakeup.first { it != observed }
        }
    }

    private suspend fun runWorker(id: Int) {
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
                        error = if (success) null else "rsync exited with code $code" +
                            if (task.fileCount > 1) "; batch of ${task.fileCount} files has unconfirmed outcomes; retry the batch" else ""
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    }
                    if (success) break
                    if (attempt < config.maxAttempts) delay(config.retryDelayMillis)
                }
                synchronized(lock) {
                    val current = states.getValue(task.id)
                    val elapsedSeconds = (System.nanoTime() - taskStartedAt).coerceAtLeast(1) / 1_000_000_000.0
                    val size = task.size ?: current.progress?.logicalBytes
                    val finalProgress = if (success && !cancelled) {
                        (current.progress ?: TransferProgress(size ?: 0, null, null)).copy(
                            logicalBytes = size ?: current.progress?.logicalBytes ?: 0,
                            matchedBytes = if (task.fileCount > 1 && size != null && current.progress?.matchedBytes != null && current.progress.literalBytes != null)
                                current.progress.matchedBytes + (size - current.progress.matchedBytes - current.progress.literalBytes).coerceAtLeast(0)
                                else current.progress?.matchedBytes,
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
        } catch (e: CancellationException) {
            throw e
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
                signalLocked()
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
            signalLocked()
            if (liveWorkers == 0 && !result.isCompleted) completeLocked()
        }
        supervisor.cancel()
        activeSessions.values.forEach { runCatching { it.close() } }
    }
}
