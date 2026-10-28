package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import iris.tools.airlift.*
import java.nio.file.Files
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.*

class MonitorTest {
    private class Source : MonitorSource {
        override val from = "user@source:/srv/files"
        override val to = "/srv/destination"
        var value = AirliftSnapshot(
            listOf(TaskState(TransferTask("folder/a.bin", 100), TaskStatus.RUNNING, 1,
                TransferProgress(40, 20.0, 3), progressUpdatedAtNanos = System.nanoTime()),
                TaskState(TransferTask("bad.bin", 50), TaskStatus.FAILED, 1, error = "permission denied"),
                TaskState(TransferTask("queued.bin", 80))),
            listOf(WorkerState(1, WorkerStatus.TRANSFERRING, "folder/a.bin")),
            false, false, 2, System.currentTimeMillis(),
        )
        override suspend fun snapshot() = value
        override suspend fun pause() { value = value.copy(paused = true) }
        override suspend fun resume() { value = value.copy(paused = false) }
        override suspend fun setWorkerCount(count: Int) { value = value.copy(targetWorkers = count) }
    }

    @Test fun `controls pause resize and switch views`(): Unit = kotlinx.coroutines.runBlocking {
        val source = Source()
        val model = MonitorModel()
        model.handle('p', source); assertTrue(source.value.paused)
        model.handle('p', source); assertFalse(source.value.paused)
        model.handle('+', source); assertEquals(3, source.value.targetWorkers)
        repeat(5) { model.handle('-', source) }; assertEquals(1, source.value.targetWorkers)
        model.handle('2', source); assertEquals(2, model.rows(source.value).size)
        model.handle('3', source); assertTrue(model.rows(source.value).single().text.contains("permission denied"))
    }

    @Test fun `completed runs reject no UI commands and stale speed is unknown`(): Unit = kotlinx.coroutines.runBlocking {
        val source = Source()
        source.value = source.value.copy(finishedAtMillis = System.currentTimeMillis())
        MonitorModel().handle('+', source)
        assertEquals(2, source.value.targetWorkers)
        assertNull(MonitorModel.freshRate(source.value.tasks.first(), System.nanoTime() + 4_000_000_000))
    }

    @Test fun `render workers queue errors and narrow terminal using real Lanterna screen`(): Unit = kotlinx.coroutines.runBlocking {
        val source = Source()
        val terminal = DefaultVirtualTerminal(TerminalSize(110, 28))
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            val monitor = AirliftMonitor(source)
            val model = MonitorModel()
            fun text() = (0 until screen.terminalSize.rows).joinToString("\n") { y ->
                (0 until screen.terminalSize.columns).joinToString("") { x -> screen.getBackCharacter(x, y).characterString }
            }
            monitor.render(screen, source.value, model)
            assertContains(text(), "[|||||")
            assertContains(text(), "40%")
            assertContains(text(), "Files 0/3 | Failed 1")
            assertContains(text(), "Workers 1 | Files 0/3 | Failed 1 | Elapsed")
            assertFalse(text().contains("Workers target"))
            assertFalse(text().contains("| Queue "))
            assertFalse(text().contains("| Running "))
            // Capture the actual Lanterna cell buffer for visual inspection.
            val image = BufferedImage(110 * 9, 28 * 19, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 14)
            for (y in 0 until 28) for (x in 0 until 110) {
                val cell = screen.getBackCharacter(x, y)
                val background = cell.backgroundColor.let { Color(it.red, it.green, it.blue) }
                val foreground = cell.foregroundColor.let { Color(it.red, it.green, it.blue) }
                graphics.color = background
                graphics.fillRect(x * 9, y * 19, 9, 19)
                graphics.color = foreground
                graphics.drawString(cell.characterString, x * 9, y * 19 + 15)
            }
            graphics.dispose()
            ImageIO.write(image, "png", java.io.File("build/monitor-preview.png"))
            model.handle('3', source)
            monitor.render(screen, source.value, model)
            assertContains(text(), "permission denied")
            model.handle('2', source)
            monitor.render(screen, source.value, model)
            assertContains(text(), "queued.bin")
            val unknown = source.value.copy(tasks = listOf(TaskState(TransferTask("size-unknown"), TaskStatus.RUNNING,
                progress = TransferProgress(1234, 1.0, null, 12))))
            monitor.render(screen, unknown, model)
            assertContains(text(), "1.2KiB / unknown total")
        }
        TerminalScreen(DefaultVirtualTerminal(TerminalSize(30, 8))).use { screen ->
            screen.startScreen()
            AirliftMonitor(source).render(screen, source.value, MonitorModel())
            val text = (0..29).joinToString("") { screen.getBackCharacter(it, 2).characterString }
            assertContains(text, "Resize")
        }
    }

    @Test fun `separate client reads snapshot controls server and reconnects`(): Unit = kotlinx.coroutines.runBlocking {
        val endpoint = Files.createTempDirectory("airlift-monitor-test-").resolve("connection")
        val source = Source()
        MonitorServer(source, endpoint.toPortablePath()).use {
            val client = RemoteMonitorSource(endpoint.toPortablePath())
            assertEquals(source.from, client.from)
            assertEquals(source.to, client.to)
            source.value = source.value.copy(tasks = source.value.tasks.map { state ->
                state.copy(progress = state.progress?.copy(matchedBytes = 10, literalBytes = 30))
            })
            val snapshot = client.snapshot()
            assertEquals(10, snapshot.tasks.first().progress!!.matchedBytes)
            assertEquals(30, snapshot.tasks.first().progress!!.literalBytes)
            assertEquals(source.value.tasks.map { it.task }, snapshot.tasks.map { it.task })
            assertEquals(40, snapshot.tasks.first().progress!!.logicalBytes)
            assertEquals(source.value.workers, snapshot.workers)
            client.pause(); assertTrue(client.snapshot().paused)
            client.setWorkerCount(5); assertEquals(5, client.snapshot().targetWorkers)
            client.resume()
            assertFalse(RemoteMonitorSource(endpoint.toPortablePath()).snapshot().paused)
        }
        assertFalse(Files.exists(endpoint))
    }

    @Test fun `Q detaches panel without pausing or cancelling source`(): Unit = kotlinx.coroutines.runBlocking {
        val source = Source()
        val terminal = DefaultVirtualTerminal(TerminalSize(100, 28))
        terminal.addInput(KeyStroke('q', false, false))
        AirliftMonitor(source).run(TerminalScreen(terminal))
        assertFalse(source.value.paused)
        assertFalse(source.value.cancelled)
        assertNull(source.value.finishedAtMillis)
    }

    @Test fun `worker percentage uses rsync progress when caller omits size`(): Unit = kotlinx.coroutines.runBlocking {
        val progress = ProgressParser.parse(" 1,234  12%  1.50MB/s  0:01:02")!!
        assertEquals(" 12%", MonitorModel.percent(TaskState(TransferTask("unknown-size"), TaskStatus.RUNNING, progress = progress)))
    }

    @Test fun `demo defaults use independent run directories and preserve explicit endpoint`(): Unit = kotlinx.coroutines.runBlocking {
        val first = Files.createTempDirectory("demo-run-")
        val second = Files.createTempDirectory("demo-run-")
        assertNotEquals(monitorEndpoint(null, first.toPortablePath()), monitorEndpoint(null, second.toPortablePath()))
        assertEquals(first.resolve("airlift-monitor.connection").toAbsolutePath().toPortablePath(), monitorEndpoint(null, first.toPortablePath()))
        assertEquals(first.resolve("custom").toAbsolutePath().toPortablePath(), monitorEndpoint(first.resolve("custom").toString(), second.toPortablePath()))
    }

    @Test fun `duplicate endpoint leaves original live server intact and reports recovery options`(): Unit = kotlinx.coroutines.runBlocking {
        val endpoint = Files.createTempDirectory("airlift-conflict-test-").resolve("connection")
        MonitorServer(Source(), endpoint.toPortablePath()).use {
            val original = Files.readAllBytes(endpoint)
            val error = assertFailsWith<IllegalStateException> { MonitorServer(Source(), endpoint.toPortablePath()) }
            assertContains(error.message!!, "--attach")
            assertContains(error.message!!, "--endpoint")
            assertContentEquals(original, Files.readAllBytes(endpoint))
            assertEquals(2, RemoteMonitorSource(endpoint.toPortablePath()).snapshot().targetWorkers)
        }
    }

    @Test fun `buffered frames do not clear terminal and unchanged frames write no characters`(): Unit = kotlinx.coroutines.runBlocking {
        var clears = 0
        var characters = 0
        val terminal = object : DefaultVirtualTerminal(TerminalSize(110, 28)) {
            override fun clearScreen() { clears++; super.clearScreen() }
            override fun putString(string: String) { characters += string.length; super.putString(string) }
        }
        val source = Source()
        // Freeze elapsed time so identical frames really are identical.
        source.value = source.value.copy(finishedAtMillis = source.value.startedAtMillis)
        TerminalScreen(terminal).use { screen ->
            screen.startScreen()
            screen.cursorPosition = null
            val monitor = AirliftMonitor(source)
            val model = MonitorModel()
            monitor.render(screen, source.value, model)
            screen.refresh()
            val baselineClears = clears
            characters = 0
            monitor.render(screen, source.value, model)
            // AUTOMATIC catches accidental use of Screen.clear(), too.
            screen.refresh()
            assertEquals(baselineClears, clears)
            assertEquals(0, characters)
            model.handle('3', source)
            monitor.render(screen, source.value, model)
            screen.refresh(com.googlecode.lanterna.screen.Screen.RefreshType.DELTA)
            assertEquals(baselineClears, clears)
            val visible = (0 until 28).joinToString("\n") { y ->
                (0 until 110).joinToString("") { x -> screen.getFrontCharacter(x, y).characterString }
            }
            assertContains(visible, "permission denied")
            assertFalse(visible.contains("TRANSFERRING"))
        }
    }
}
