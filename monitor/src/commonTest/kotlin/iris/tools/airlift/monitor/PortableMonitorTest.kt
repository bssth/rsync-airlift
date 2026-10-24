package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PortableMonitorTest {
    @Test fun `formatting is portable and fixed precision`() {
        assertEquals("1.5MiB", MonitorModel.bytes(1572864))
        assertEquals("01:02:03", MonitorModel.duration(3723))
        assertEquals("100.0", fixedOne(100.0))
        assertEquals("12.4", fixedOne(12.35))
    }

    @Test fun `common renderer and terminal loop handle mouse resize and detach`() = runTest {
        val tasks = (1..3).map { TaskState(TransferTask("file-$it", 1024), TaskStatus.RUNNING) }
        val snapshot = AirliftSnapshot(tasks, tasks.mapIndexed { index, task ->
            WorkerState(index + 1, WorkerStatus.TRANSFERRING, task.task.id)
        }, false, false, 3)
        val source = object : MonitorSource {
            override suspend fun snapshot() = snapshot
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setWorkerCount(count: Int) {}
        }
        val model = MonitorModel()
        val renderer = MonitorRenderer(source)
        val frame = renderer.render(MonitorSize(80, 24), snapshot, model)
        assertTrue(frame.runs.any { "computing checksums" in it.text })
        val row = model.mouseRows.last()
        model.handleMouse(MonitorEvent.Mouse(MonitorMouseAction.CLICK_DOWN, 1, row.x, row.y), snapshot)
        assertEquals(2, model.selected)
        assertTrue(renderer.render(MonitorSize(80, 24), snapshot, model).runs.any { "DETAILS WORKER 003" in it.text })
        var closed = false
        var presented = false
        AirliftMonitor(source).run(object : MonitorTerminal {
            override fun start() {}
            override fun size() = MonitorSize(120, 30)
            override fun present(frame: MonitorFrame) { presented = true; assertEquals(120, frame.size.columns) }
            override fun pollEvent() = MonitorEvent.Key(MonitorKey.Character, 'q')
            override fun close() { closed = true }
        })
        assertTrue(presented && closed)
    }
}
