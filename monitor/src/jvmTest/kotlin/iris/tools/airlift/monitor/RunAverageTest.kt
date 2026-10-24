package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import iris.tools.airlift.*
import kotlin.test.*

class RunAverageTest {
    @Test fun `completed run replaces live speed with stable average and clears ETA`() {
        val started = System.currentTimeMillis() - 5000
        val task = TaskState(TransferTask("file", 1048576), TaskStatus.RUNNING,
            progress = TransferProgress(524288, 1048576.0, 1), progressUpdatedAtNanos = System.nanoTime())
        val running = AirliftSnapshot(listOf(task), emptyList(), false, false, startedAtMillis = started)
        val source = object : MonitorSource {
            override suspend fun snapshot() = running
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setWorkerCount(count: Int) {}
        }
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(120, 24))).use { screen ->
            screen.startScreen()
            val monitor = AirliftMonitor(source)
            val model = MonitorModel()
            fun summary() = (0 until 120).map { screen.getBackCharacter(it, 5).character }.joinToString("")
            monitor.render(screen, running, model)
            assertContains(summary(), "ETA")
            val finished = running.copy(tasks = listOf(task.copy(status = TaskStatus.SUCCEEDED)), finishedAtMillis = started + 2000)
            repeat(2) {
                monitor.render(screen, finished, model)
                assertContains(summary(), "512.0KiB/s")
                assertFalse("ETA" in summary())
                assertFalse("--/s" in summary())
            }
        }
    }
}
