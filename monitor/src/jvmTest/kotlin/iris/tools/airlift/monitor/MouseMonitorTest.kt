package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import iris.tools.airlift.*
import kotlin.test.*

class MouseMonitorTest {
    private val tasks = (1..40).map { TaskState(TransferTask("file-$it", 100), TaskStatus.RUNNING) }
    private val snapshot = AirliftSnapshot(tasks, tasks.mapIndexed { i, task -> WorkerState(i + 1, WorkerStatus.TRANSFERRING, task.task.id) }, false, false, 40)
    private val source = object : MonitorSource {
        override suspend fun snapshot() = snapshot
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun setWorkerCount(count: Int) {}
    }
    private fun click(x: Int, y: Int, button: Int = 1) = MouseAction(MouseActionType.CLICK_DOWN, button, TerminalPosition(x, y))

    @Test fun `click selects correct worker in every grid column and on later pages`() {
        for (width in listOf(80, 110, 220)) {
            TerminalScreen(DefaultVirtualTerminal(TerminalSize(width, 24))).use { screen ->
                screen.startScreen()
                val model = MonitorModel()
                val monitor = AirliftMonitor(source)
                for (page in listOf(0, 35)) {
                    model.selected = page
                    monitor.render(screen, snapshot, model)
                    for (row in model.mouseRows.toList()) {
                        model.handleMouse(click(row.x + row.width / 2, row.y), snapshot)
                        assertEquals(row.index, model.selected)
                    }
                }
                val selected = model.selected
                model.handleMouse(click(1, 8), snapshot) // Header is not a worker.
                model.handleMouse(click(1, 23), snapshot) // Footer is not a worker.
                assertEquals(selected, model.selected)
            }
        }
    }

    @Test fun `tabs switch on left click only and list selection respects scrolling offset`() {
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(80, 24))).use { screen ->
            screen.startScreen()
            val model = MonitorModel()
            val monitor = AirliftMonitor(source)
            monitor.render(screen, snapshot, model)
            model.handleMouse(click(14, 7, 3), snapshot)
            assertEquals(MonitorView.WORKERS, model.view)
            model.handleMouse(click(14, 7), snapshot)
            assertEquals(MonitorView.QUEUE, model.view)
            model.selected = 20
            monitor.render(screen, snapshot, model)
            val first = model.mouseRows.first()
            model.handleMouse(click(1, first.y), snapshot)
            assertEquals(first.index, model.selected)
            model.handleMouse(click(24, 7), snapshot)
            assertEquals(MonitorView.ERRORS, model.view)
            assertEquals(0, model.selected)
            model.handleMouse(click(2, 7), snapshot)
            assertEquals(MonitorView.WORKERS, model.view)
        }
    }

    @Test fun `wheel navigation clamps to available rows and ignores details panel`() {
        val model = MonitorModel().apply { bodyBottom = 20 }
        fun wheel(type: MouseActionType, y: Int = 11) = model.handleMouse(MouseAction(type, 0, TerminalPosition(5, y)), snapshot)
        wheel(MouseActionType.SCROLL_UP)
        assertEquals(0, model.selected)
        wheel(MouseActionType.SCROLL_DOWN)
        assertEquals(1, model.selected)
        wheel(MouseActionType.SCROLL_DOWN, 22)
        assertEquals(1, model.selected)
        model.selected = 39
        wheel(MouseActionType.SCROLL_DOWN)
        assertEquals(39, model.selected)
    }
}
