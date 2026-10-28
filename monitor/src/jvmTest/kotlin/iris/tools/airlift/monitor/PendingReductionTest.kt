package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import iris.tools.airlift.*
import kotlin.test.*

class PendingReductionTest {
    private suspend fun snapshot(target: Int) = AirliftSnapshot(emptyList(), (1..4).map {
        WorkerState(it, WorkerStatus.TRANSFERRING)
    }, false, false, target)

    @Test fun `last N rows indicate quota without selecting which workers stop`(): Unit = kotlinx.coroutines.runBlocking {
        val model = MonitorModel()
        assertEquals(listOf("worker-3", "worker-4"), model.rows(snapshot(2)).filter { it.pendingReduction }.map { it.key })
        // Worker 1 finishes first and disappears; only the last remaining row stays orange.
        val reduced = snapshot(2).copy(workers = snapshot(2).workers.map {
            if (it.id == 1) it.copy(status = WorkerStatus.STOPPED) else it
        })
        assertEquals(listOf("worker-4"), model.rows(reduced).filter { it.pendingReduction }.map { it.key })
        assertTrue(model.rows(snapshot(4)).none { it.pendingReduction })
        assertTrue(model.rows(snapshot(1).copy(workers = listOf(WorkerState(1, WorkerStatus.FINISHED)))).none { it.pendingReduction })
    }

    @Test fun `orange background appears immediately and clears after increasing target`(): Unit = kotlinx.coroutines.runBlocking {
        var value = snapshot(2)
        val source = object : MonitorSource {
            override suspend fun snapshot() = value
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setWorkerCount(count: Int) { value = value.copy(targetWorkers = count) }
        }
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(80, 24))).use { screen ->
            screen.startScreen()
            val model = MonitorModel()
            val monitor = AirliftMonitor(source)
            monitor.render(screen, value, model)
            assertEquals(TextColor.RGB(135, 88, 44), screen.getBackCharacter(0, 11).backgroundColor)
            assertEquals(TextColor.RGB(135, 88, 44), screen.getBackCharacter(0, 12).backgroundColor)
            model.handle('+', source)
            model.handle('+', source)
            monitor.render(screen, value, model)
            assertEquals(TextColor.ANSI.BLACK, screen.getBackCharacter(0, 11).backgroundColor)
            assertEquals(TextColor.ANSI.BLACK, screen.getBackCharacter(0, 12).backgroundColor)
        }
    }
}
