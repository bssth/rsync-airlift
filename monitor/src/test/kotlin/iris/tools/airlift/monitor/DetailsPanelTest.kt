package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import iris.tools.airlift.*
import java.nio.file.Files
import kotlin.test.*

class DetailsPanelTest {
    @Test fun `details follow selected worker and display useful fields without log noise`() {
        val log = Files.createTempFile("airlift-details-test-", ".log")
        Files.writeString(log, "DEMO logical progress: 40%")
        val tasks = (1..2).map { id -> TaskState(TransferTask("folder/file-$id.bin", 1048576), TaskStatus.SUCCEEDED,
            attempts = 1, progress = TransferProgress(1048576, 1024.0, null, 100, 524288, 524288), logFile = log) }
        val snapshot = AirliftSnapshot(tasks, tasks.mapIndexed { i, t -> WorkerState(i + 1, WorkerStatus.FINISHED, t.task.id) }, false, false)
        val source = object : MonitorSource {
            override val from = "user@host:/source"
            override val to = "/destination"
            override fun snapshot() = snapshot
            override fun pause() {}
            override fun resume() {}
            override fun setWorkerCount(count: Int) {}
        }
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(100, 28))).use { screen ->
            screen.startScreen()
            val model = MonitorModel()
            val monitor = AirliftMonitor(source)
            fun text() = (0 until 28).joinToString("\n") { y ->
                (0 until 100).joinToString("") { x -> screen.getBackCharacter(x, y).characterString }
            }
            monitor.render(screen, snapshot, model)
            assertContains(text(), "From: user@host:/source")
            assertContains(text(), "To:   /destination")
            assertContains(text(), "DETAILS WORKER 001")
            assertContains(text(), "File: folder/file-1.bin")
            assertContains(text(), "Size 1.0MiB")
            assertFalse(text().contains("Log:"))
            assertFalse(text().contains("DEMO logical progress"))
            assertFalse(text().contains(log.toString()))
            model.selected = 1
            monitor.render(screen, snapshot, model)
            assertContains(text(), "DETAILS WORKER 002")
            assertContains(text(), "File: folder/file-2.bin")
            assertFalse(text().contains("file-1.bin"))
        }
        Files.deleteIfExists(log)
    }
}
