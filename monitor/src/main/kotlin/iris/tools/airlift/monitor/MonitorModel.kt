package iris.tools.airlift.monitor

import iris.tools.airlift.*
import java.util.Locale
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType

enum class MonitorView { WORKERS, QUEUE, ERRORS }

data class RowSegment(val text: String, val color: TextColor = TextColor.ANSI.WHITE)
data class MonitorRow(val key: String, val text: String, val task: TaskState? = null, val error: String? = null,
    val segments: List<RowSegment> = emptyList(), val pendingReduction: Boolean = false, val workerId: Int? = null)

class MonitorModel {
    private val dismissedFailedWorkers = mutableSetOf<Int>()
    internal fun visibleWorkers(snapshot: AirliftSnapshot) = snapshot.workers.filter {
        it.status != WorkerStatus.STOPPED && !(it.status == WorkerStatus.FAILED && it.id in dismissedFailedWorkers)
    }
    internal data class MouseRow(val x: Int, val y: Int, val width: Int, val index: Int)
    internal val mouseRows = mutableListOf<MouseRow>()
    internal var bodyBottom = 10

    internal fun handleMouse(action: MouseAction, snapshot: AirliftSnapshot) {
        val x = action.position.column
        val y = action.position.row
        when (action.actionType) {
            MouseActionType.CLICK_DOWN -> if (action.button == 1) {
                if (y == 8) {
                    val tab = when (x) {
                        in 0 until 9 -> MonitorView.WORKERS
                        in 12 until 19 -> MonitorView.QUEUE
                        in 22 until 30 -> MonitorView.ERRORS
                        else -> null
                    }
                    if (tab != null) { view = tab; selected = 0; mouseRows.clear() }
                } else mouseRows.firstOrNull { y == it.y && x >= it.x && x < it.x + it.width }?.let { selected = it.index }
            }
            MouseActionType.SCROLL_UP, MouseActionType.SCROLL_DOWN -> if (y in 10 until bodyBottom) {
                val step = if (action.actionType == MouseActionType.SCROLL_UP) -3 else 3
                selected = (selected + step).coerceIn(0, (rows(snapshot).size - 1).coerceAtLeast(0))
            }
            else -> Unit
        }
    }
    var view = MonitorView.WORKERS
    var selected = 0
    var details = true
    var message = ""
    var gridRows = 1
    var pageCapacity = 1

    fun rows(snapshot: AirliftSnapshot, cellWidth: Int = 55): List<MonitorRow> = when (view) {
        MonitorView.WORKERS -> {
            val tasks = snapshot.tasks.associateBy { it.task.id }
            val live = snapshot.workers.filter { it.status in setOf(WorkerStatus.CONNECTING, WorkerStatus.IDLE, WorkerStatus.TRANSFERRING) }.sortedBy { it.id }
            // A visual quota only: the dispatcher still retires the first workers to finish.
            val reduction = live.takeLast((live.size - snapshot.targetWorkers).coerceIn(0, live.size)).map { it.id }.toSet()
            visibleWorkers(snapshot).sortedBy { it.id }.map { worker ->
                val task = tasks[worker.taskId]
                val segments = workerSegments(worker, task, cellWidth)
                MonitorRow("worker-${worker.id}", segments.joinToString("") { it.text }, task, worker.error ?: task?.error, segments,
                    pendingReduction = worker.id in reduction, workerId = worker.id)
            }
        }
        MonitorView.QUEUE -> snapshot.tasks.filter { it.status == TaskStatus.QUEUED || it.status == TaskStatus.RUNNING }.map {
            MonitorRow(it.task.id, "%-10s %8s %s".format(it.status, bytes(it.task.size), it.task.path), it)
        }
        MonitorView.ERRORS -> snapshot.tasks.filter { it.error != null }.map {
            MonitorRow(it.task.id, "${it.status} attempt ${it.attempts}: ${it.task.path} | ${it.error}", it, it.error)
        } + snapshot.workers.filter { it.error != null }.map {
            MonitorRow("worker-${it.id}", "SSH worker ${it.id}: ${it.error}", error = it.error)
        }
    }

    fun handle(command: Char, source: MonitorSource) {
        val snapshot = source.snapshot()
        when (command.lowercaseChar()) {
            '1' -> { view = MonitorView.WORKERS; selected = 0 }
            '2' -> { view = MonitorView.QUEUE; selected = 0 }
            '3' -> { view = MonitorView.ERRORS; selected = 0 }
            'p' -> if (snapshot.finishedAtMillis == null) {
                if (snapshot.paused) source.resume() else source.pause()
            }
            '+', '=' -> if (snapshot.finishedAtMillis == null) source.setWorkerCount(snapshot.targetWorkers + 1)
            '-' -> {
                val failed = visibleWorkers(snapshot).filter { it.status == WorkerStatus.FAILED }.minByOrNull { it.id }
                if (failed != null) {
                    dismissedFailedWorkers.add(failed.id)
                    mouseRows.clear()
                } else if (snapshot.finishedAtMillis == null) source.setWorkerCount((snapshot.targetWorkers - 1).coerceAtLeast(1))
            }
        }
    }

    companion object {
        fun compactDuration(seconds: Long?): String {
            if (seconds == null) return "--"
            val value = seconds.coerceAtLeast(0)
            return when {
                value >= 1209600 -> "${value / 604800}w"
                value >= 86400 -> "${value / 86400}d"
                value >= 3600 -> "${value / 3600}h"
                value >= 60 -> "${value / 60}m"
                else -> "${value}s"
            }
        }

        fun workerSegments(worker: WorkerState, task: TaskState?, width: Int = 55): List<RowSegment> =
            WorkerLineLayout(width).segments(worker, task)

        /** Completed attempts consume their whole weight, including final failures, but not cancellations. */
        fun overallProgress(snapshot: AirliftSnapshot): Double {
            if (snapshot.tasks.isEmpty()) return 1.0
            val total = snapshot.tasks.sumOf { it.task.size ?: 0 }.toDouble()
            val weighted = snapshot.tasks.all { it.task.size != null } && total > 0
            val done = snapshot.tasks.sumOf { task ->
                val weight = if (weighted) task.task.size!!.toDouble() else 1.0
                val fraction = when (task.status) {
                    TaskStatus.SUCCEEDED, TaskStatus.FAILED -> 1.0
                    TaskStatus.RUNNING -> {
                        val size = task.task.size
                        if (size != null && size > 0) (task.progress?.logicalBytes ?: 0).toDouble() / size
                        else (task.progress?.percent ?: 0) / 100.0
                    }
                    else -> 0.0
                }
                weight * fraction.coerceIn(0.0, 1.0)
            }
            return (done / if (weighted) total else snapshot.tasks.size.toDouble()).coerceIn(0.0, 1.0)
        }

        fun bytes(value: Long?): String {
            if (value == null) return "?"
            var number = value.toDouble()
            val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
            var unit = 0
            while (number >= 1024 && unit < units.lastIndex) { number /= 1024; unit++ }
            return String.format(Locale.ROOT, "%.1f%s", number, units[unit])
        }
        fun duration(seconds: Long?): String = if (seconds == null) "--:--:--" else
            "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        fun percent(task: TaskState): String {
            val size = task.task.size
            val progress = task.progress
            return when {
                task.status == TaskStatus.SUCCEEDED -> "100%"
                progress == null -> " --%"
                size == null || size == 0L -> progress.percent?.let { "%3d%%".format(it) } ?: " --%"
                else -> "%3d%%".format((100.0 * progress.logicalBytes / size).toInt().coerceIn(0, 100))
            }
        }
        fun freshRate(task: TaskState, now: Long = System.nanoTime()): Double? =
            if (task.status == TaskStatus.RUNNING && task.progressUpdatedAtNanos?.let { now - it < 3_000_000_000 } == true)
                task.progress?.bytesPerSecond else null
        fun rate(task: TaskState): String {
            val speed = if (task.status == TaskStatus.SUCCEEDED) task.progress?.bytesPerSecond else freshRate(task)
            return speed?.let { bytes(it.toLong()) + "/s" } ?: "--/s"
        }
    }
}
