package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.screen.Screen
import iris.tools.airlift.*
import kotlin.math.ceil
import iris.tools.airlift.monitor.MonitorModel.Companion.bytes
import iris.tools.airlift.monitor.MonitorModel.Companion.duration

class AirliftMonitor(private val source: MonitorSource, private val title: String = source.title, private val swing: Boolean = false) {
    private val overallRate = OverallRate()
    /** Q detaches only. This method never closes the source or dispatcher. */
    fun run(screen: Screen = TerminalAppearance.createScreen(title, swing)) {
        screen.use {
            it.startScreen()
            it.cursorPosition = null
            val model = MonitorModel()
            var firstFrame = true
            while (true) {
                val resized = it.doResizeIfNecessary() != null
                render(it, source.snapshot(), model)
                // Complete redraws clear the physical terminal. Reserve them for startup/resize.
                it.refresh(if (firstFrame || resized) Screen.RefreshType.COMPLETE else Screen.RefreshType.DELTA)
                firstFrame = false
                val key = it.pollInput()
                if (key is MouseAction) {
                    model.handleMouse(key, source.snapshot())
                    continue
                }
                when (key?.keyType) {
                    KeyType.EOF -> return
                    KeyType.ArrowUp -> model.selected--
                    KeyType.ArrowDown -> model.selected++
                    KeyType.ArrowLeft -> if (model.view == MonitorView.WORKERS) model.selected -= model.gridRows
                    KeyType.ArrowRight -> if (model.view == MonitorView.WORKERS) model.selected += model.gridRows
                    KeyType.PageUp -> model.selected -= model.pageCapacity
                    KeyType.PageDown -> model.selected += model.pageCapacity
                    KeyType.Home -> model.selected = 0
                    KeyType.End -> model.selected = Int.MAX_VALUE
                    KeyType.F2, KeyType.Enter -> model.details = !model.details
                    KeyType.Tab -> { model.view = MonitorView.entries[(model.view.ordinal + 1) % 3]; model.selected = 0 }
                    KeyType.Character -> {
                        val char = key.character ?: ' '
                        if (char.lowercaseChar() == 'q') return
                        runCatching { model.handle(char, source) }.onFailure { error -> model.message = error.message ?: "Command failed" }
                    }
                    else -> Unit
                }
                Thread.sleep(200)
            }
        }
    }

    /** Exposed for deterministic rendering checks on Lanterna's virtual terminal. */
    fun render(screen: Screen, snapshot: AirliftSnapshot, model: MonitorModel) {
        model.mouseRows.clear()
        model.bodyBottom = 10
        val width = screen.terminalSize.columns
        val height = screen.terminalSize.rows
        val graphics = screen.newTextGraphics()
        // Reset only the back buffer. Screen.clear() also requests a physical clear on refresh.
        graphics.foregroundColor = TextColor.ANSI.WHITE
        graphics.backgroundColor = TextColor.ANSI.BLACK
        graphics.fill(' ')
        fun line(y: Int, text: String, color: TextColor = TextColor.ANSI.WHITE, selected: Boolean = false) {
            if (y !in 0 until height || width <= 0) return
            graphics.foregroundColor = color
            graphics.backgroundColor = if (selected) TextColor.ANSI.BLUE else TextColor.ANSI.BLACK
            val safe = text.take(width * 2).map { if (it.isISOControl()) ' ' else it }.joinToString("")
            var clipped = safe
            while (clipped.isNotEmpty() && TerminalTextUtils.getColumnWidth(clipped) > width) clipped = clipped.dropLast(1)
            graphics.putString(0, y, clipped + " ".repeat((width - TerminalTextUtils.getColumnWidth(clipped)).coerceAtLeast(0)))
        }
        fun segments(x: Int, y: Int, parts: List<RowSegment>, limit: Int, selected: Boolean = false, reduction: Boolean = false) {
            graphics.backgroundColor = when {
                reduction && selected -> TextColor.RGB(165, 112, 62)
                reduction -> TextColor.RGB(135, 88, 44)
                selected -> TextColor.ANSI.BLUE
                else -> TextColor.ANSI.BLACK
            }
            var used = 0
            for (part in parts) {
                if (used >= limit) break
                val text = part.text.take(limit - used)
                graphics.foregroundColor = if (reduction) TextColor.ANSI.BLACK else part.color
                graphics.putString(x + used, y, text)
                used += text.length
            }
            graphics.foregroundColor = if (reduction) TextColor.ANSI.BLACK else TextColor.ANSI.WHITE
            if (used < limit) graphics.putString(x + used, y, " ".repeat(limit - used))
        }
        if (width < 64 || height < 16) {
            line(0, title, TextColor.ANSI.CYAN)
            line(2, "Resize terminal to at least 64 x 16.")
            line(height - 1, "Q Detach (transfers continue)")
            return
        }
        val state = when {
            snapshot.cancelled -> "CANCELLED"
            snapshot.finishedAtMillis != null -> if (snapshot.failed > 0) "FINISHED WITH ERRORS" else "FINISHED"
            snapshot.paused -> "PAUSED (active files continue)"
            snapshot.startedAtMillis == null -> "NOT STARTED"
            else -> "RUNNING"
        }
        val elapsed = snapshot.startedAtMillis?.let { ((snapshot.finishedAtMillis ?: System.currentTimeMillis()) - it).coerceAtLeast(0) / 1000 }
        val rate = overallRate.value(snapshot)
        val allKnown = snapshot.tasks.all { it.task.size != null }
        val total = snapshot.tasks.sumOf { it.task.size ?: 0 }
        val processed = snapshot.tasks.sumOf {
            if (it.status == TaskStatus.SUCCEEDED) it.task.size ?: it.progress?.logicalBytes ?: 0
            else if (it.status == TaskStatus.RUNNING) (it.progress?.logicalBytes ?: 0).let { count -> it.task.size?.let { size -> count.coerceAtMost(size) } ?: count }
            else 0
        }
        val eta = if (allKnown && rate != null && rate > 0 && snapshot.failed == 0 && !snapshot.paused)
            ceil((total - processed).coerceAtLeast(0) / rate).toLong() else null
        val finished = snapshot.finishedAtMillis != null
        val displayRate = if (finished) snapshot.startedAtMillis?.let { start ->
            val elapsedMillis = requireNotNull(snapshot.finishedAtMillis) - start
            if (elapsedMillis > 0) processed.toDouble() * 1000 / elapsedMillis else null
        } else rate
        val etaText = if (finished) "" else " | ETA ${duration(eta)}"
        val ratio = MonitorModel.overallProgress(snapshot)
        line(0, "$title | $state", TextColor.ANSI.CYAN)
        line(1, "From: ${source.from}")
        line(2, "To:   ${source.to}")
        val workerCount = if (finished) snapshot.targetWorkers else snapshot.workers.count {
            it.status in setOf(WorkerStatus.CONNECTING, WorkerStatus.IDLE, WorkerStatus.TRANSFERRING)
        }
        line(3, "Workers $workerCount | Elapsed ${duration(elapsed)}")
        line(4, "Done ${snapshot.completed}/${snapshot.tasks.size} | Failed ${snapshot.failed}")
        line(5, "Processed ${bytes(processed)} / ${if (allKnown) bytes(total) else "unknown total"} | ${displayRate?.let { bytes(it.toLong()) + "/s" } ?: "--/s"}$etaText")
        val breakdown = OverallBreakdown.from(snapshot)
        val barWidth = (width - 10).coerceAtMost(100)
        segments(0, 6, listOf(RowSegment("[")) + breakdown.segments(barWidth) +
            RowSegment("] ${"%.1f%%".format(java.util.Locale.ROOT, ratio * 100)}"), width)
        line(7, breakdown.summary(processed))
        line(8, "1 Workers | 2 Queue | 3 Errors | Tab switch | ${model.view}", TextColor.ANSI.CYAN)
        val detailHeight = if (model.details && height >= 22) 5 else 0
        val rowCount = (height - 12 - detailHeight).coerceAtLeast(1)
        model.bodyBottom = 10 + rowCount
        val grid = WorkerGrid(width, model.visibleWorkers(snapshot).size, rowCount)
        val rows = model.rows(snapshot, grid.cellWidth)
        model.selected = model.selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        val workersView = model.view == MonitorView.WORKERS
        model.gridRows = if (workersView) grid.rows else 1
        model.pageCapacity = if (workersView) grid.capacity else rowCount
        if (workersView) {
            val layout = WorkerLineLayout(grid.cellWidth)
            for (column in 0 until grid.columns) {
                segments(column * (grid.cellWidth + WorkerGrid.GAP), 9,
                    listOf(RowSegment(layout.header(), TextColor.ANSI.CYAN)), grid.cellWidth)
            }
        }
        val offset = if (workersView) model.selected / grid.capacity * grid.capacity else (model.selected - rowCount + 1).coerceAtLeast(0)
        if (rows.isEmpty()) line(10, "No entries.")
        if (workersView) {
            rows.drop(offset).take(grid.capacity).forEachIndexed { index, row ->
                val (x, y) = grid.position(index)
                segments(x, 10 + y, row.segments, grid.cellWidth, offset + index == model.selected, row.pendingReduction)
                model.mouseRows.add(MonitorModel.MouseRow(x, 10 + y, grid.cellWidth, offset + index))
            }
        } else {
        rows.drop(offset).take(rowCount).forEachIndexed { index, row ->
            val y = 10 + index
            val selected = offset + index == model.selected
            line(y, row.text, if (row.error != null) TextColor.ANSI.RED else TextColor.ANSI.WHITE, selected)
            model.mouseRows.add(MonitorModel.MouseRow(0, y, width, offset + index))
        }
        }
        if (detailHeight > 0) {
            val row = rows.getOrNull(model.selected)
            val task = row?.task
            val y = height - 7
            val workerId = row?.workerId ?: snapshot.workers.firstOrNull { task != null && it.taskId == task.task.id }?.id
            val worker = snapshot.workers.firstOrNull { it.id == workerId }
            line(y, "DETAILS WORKER${workerId?.let { " %03d".format(it) } ?: ""}", TextColor.ANSI.CYAN)
            line(y + 1, "File: ${task?.task?.path ?: "--"}")
            val progress = task?.progress
            val stats = "matched ${bytes(progress?.matchedBytes)} | new ${bytes(progress?.literalBytes)}"
            line(y + 2, "${task?.status ?: worker?.status ?: "--"} | Size ${bytes(task?.task?.size)} | attempt ${task?.attempts ?: 0}")
            line(y + 3, "$stats | Speed ${task?.let { MonitorModel.rate(it) } ?: "--/s"}")
            val error = task?.error ?: row?.error
            line(y + 4, error?.let { "Error: $it" } ?: "", TextColor.ANSI.RED)
        }
        segments(0, height - 2, if (model.message.isNotEmpty()) listOf(RowSegment(model.message, TextColor.ANSI.YELLOW)) else listOf(
            RowSegment("Overall: "), RowSegment("matched ", TextColor.ANSI.CYAN), RowSegment("new ", TextColor.ANSI.GREEN),
            RowSegment("failed ", TextColor.ANSI.RED), RowSegment("pending stats/progress", TextColor.ANSI.YELLOW)), width)
        line(height - 1, "Arrows Select | F2 Details | P Pause | +/- Workers | Q Detach", TextColor.ANSI.CYAN)
    }

}
