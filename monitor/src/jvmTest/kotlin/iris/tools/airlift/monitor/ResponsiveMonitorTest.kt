package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import iris.tools.airlift.*
import kotlin.test.*
import java.awt.Font
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class ResponsiveMonitorTest {
    @Test fun `grid expands from one to four columns and keeps headers aligned`() {
        for ((width, columns) in listOf(80 to 1, 110 to 2, 165 to 3, 220 to 4)) {
            val grid = WorkerGrid(width, 16, 4)
            assertEquals(columns, grid.columns)
            assertTrue(grid.cellWidth <= 88)
            val layout = WorkerLineLayout(grid.cellWidth)
            val task = TaskState(TransferTask("file", 1048576), TaskStatus.RUNNING,
                progress = TransferProgress(524288, 1024.0, 10), progressUpdatedAtNanos = System.nanoTime())
            val text = layout.segments(WorkerState(1, WorkerStatus.TRANSFERRING, "file"), task).joinToString("") { it.text }
            assertEquals(layout.header().indexOf("SIZE") + 4, text.indexOf("1.0MiB") + 6)
            assertTrue(text.length <= grid.cellWidth)
            for (index in 0 until 16) {
                val (x, y) = grid.position(index)
                assertTrue(x + grid.cellWidth <= width)
                assertTrue(y < grid.rows)
            }
        }
    }

    @Test fun `overall statistics accumulate completed files while a worker handles another file`() {
        val done = TaskState(TransferTask("previous", 100), TaskStatus.SUCCEEDED,
            progress = TransferProgress(100, null, null, 100, 75, 25))
        val next = TaskState(TransferTask("next", 100), TaskStatus.RUNNING,
            progress = TransferProgress(50, 10.0, 5))
        val failed = TaskState(TransferTask("failed", 100), TaskStatus.FAILED)
        val snapshot = AirliftSnapshot(listOf(done, next, failed), listOf(WorkerState(1, WorkerStatus.TRANSFERRING, "next")), false, false)
        val breakdown = OverallBreakdown.from(snapshot)
        assertEquals(75, breakdown.matchedBytes)
        assertEquals(25, breakdown.literalBytes)
        assertEquals(0.25, breakdown.matched)
        assertEquals(1.0 / 3, breakdown.failed)
        val segments = breakdown.segments(60)
        assertEquals(15, segments.filter { it.color == MonitorColor.ANSI.CYAN }.sumOf { it.text.length })
        assertEquals(60, segments.sumOf { it.text.length })
        assertTrue(segments.all { part -> part.text.all { it == '|' || it == ' ' } })
        val final = snapshot.copy(tasks = listOf(done, next.copy(status = TaskStatus.SUCCEEDED), failed))
        assertTrue(OverallBreakdown.from(final).segments(60).all { part -> ' ' !in part.text })
        // Repeated rendering derives the same totals; it never counts an event twice.
        assertEquals(breakdown, OverallBreakdown.from(snapshot))
    }

    @Test fun `vertical first render and resize preserve worker details without stale cells`() {
        val tasks = (1..16).map { id -> TaskState(TransferTask("folder/file-$id.bin", 104857600),
            if (id <= 4) TaskStatus.SUCCEEDED else TaskStatus.RUNNING,
            progress = TransferProgress(if (id <= 4) 104857600 else id * 5242880L, 1048576.0, id * 10L,
                matchedBytes = if (id <= 4) 62914560 else null, literalBytes = if (id <= 4) 41943040 else null),
            progressUpdatedAtNanos = System.nanoTime()) }
        val snapshot = AirliftSnapshot(tasks, tasks.mapIndexed { i, task -> WorkerState(i + 1,
            if (i < 4) WorkerStatus.FINISHED else WorkerStatus.TRANSFERRING, task.task.id) }, false, false, 16)
        val source = object : MonitorSource {
            override suspend fun snapshot() = snapshot
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setWorkerCount(count: Int) {}
        }
        val terminal = DefaultVirtualTerminal(TerminalSize(220, 28))
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            val model = MonitorModel()
            val monitor = AirliftMonitor(source)
            monitor.render(screen, snapshot, model)
            assertEquals(13, model.gridRows)
            val text = (0 until 28).joinToString("\n") { y -> (0 until 220).joinToString("") { x -> screen.getBackCharacter(x, y).characterString } }
            assertTrue(text.lineSequence().any { it.contains("001 [") && it.contains("014 [") })
            assertContains(text, "DETAILS WORKER 001")
            assertContains(text, "File: folder/file-1.bin")
            assertFalse(text.lineSequence().drop(9).take(4).any { it.contains("FINISHED") || it.contains("folder/") })
            val image = BufferedImage(220 * 9, 28 * 19, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.font = Font("Consolas", Font.PLAIN, 14)
            for (y in 0 until 28) for (x in 0 until 220) {
                val cell = screen.getBackCharacter(x, y)
                graphics.color = cell.backgroundColor.let { java.awt.Color(it.red, it.green, it.blue) }
                graphics.fillRect(x * 9, y * 19, 9, 19)
                graphics.color = cell.foregroundColor.let { java.awt.Color(it.red, it.green, it.blue) }
                graphics.drawString(cell.characterString, x * 9, y * 19 + 15)
            }
            graphics.dispose()
            ImageIO.write(image, "png", java.io.File("build/monitor-grid-preview.png"))
            terminal.terminalSize = TerminalSize(80, 24)
            screen.doResizeIfNecessary()
            monitor.render(screen, snapshot, model)
            assertEquals(9, model.pageCapacity)
            model.selected = 15
            monitor.render(screen, snapshot, model)
            assertEquals(15, model.selected)
        }
    }
}
