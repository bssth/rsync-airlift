package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class FinishingLabelTest {
    private val worker = WorkerState(1, WorkerStatus.TRANSFERRING, "file")
    private val running = TaskState(TransferTask("file", 1000), TaskStatus.RUNNING,
        progress = TransferProgress(1000, 200.0, 0, 100))

    @Test fun `completed byte progress keeps full bar with quiet finishing label and blank ETA`() {
        val layout = WorkerLineLayout(88)
        val segments = layout.segments(worker, running)
        assertTrue(MonitorModel.finishing(running))
        assertEquals(" finishing", segments[1].text)
        assertEquals(MonitorColor.ANSI.WHITE, segments[1].color)
        assertEquals("|".repeat(layout.barWidth - segments[1].text.length), segments[2].text)
        assertContains(segments.last().text, "100%")
        assertTrue(segments.last().text.endsWith("    "))
        assertEquals(TaskStatus.RUNNING, running.status)
    }

    @Test fun `label only appears while completed data still waits for task result`() {
        val incomplete = running.copy(progress = running.progress!!.copy(logicalBytes = 999))
        assertFalse(MonitorModel.finishing(incomplete))
        assertFalse(MonitorModel.finishing(running.copy(status = TaskStatus.SUCCEEDED)))
        assertFalse(MonitorModel.finishing(running.copy(status = TaskStatus.FAILED)))
        assertFalse(MonitorModel.finishing(running.copy(progress = null)))
        assertTrue(MonitorModel.finishing(running.copy(task = TransferTask("file"))))
        val succeeded = WorkerLineLayout(88).segments(worker.copy(status = WorkerStatus.FINISHED), running.copy(status = TaskStatus.SUCCEEDED))
        assertFalse(succeeded.any { "finishing" in it.text })
        val failed = WorkerLineLayout(88).segments(worker, running.copy(status = TaskStatus.FAILED))
        assertTrue(failed.any { "FAILED" in it.text })
    }

    @Test fun `details shows finishing while overall run remains running`() {
        val snapshot = AirliftSnapshot(listOf(running), listOf(worker), false, false, 1, startedAtMillis = 1)
        val source = object : MonitorSource {
            override suspend fun snapshot() = snapshot
            override suspend fun pause() {}
            override suspend fun resume() {}
            override suspend fun setWorkerCount(count: Int) {}
        }
        val frame = MonitorRenderer(source).render(MonitorSize(100, 24), snapshot, MonitorModel().apply { details = true })
        assertTrue(frame.runs.any { "FINISHING" in it.text })
        assertTrue(frame.runs.any { "RUNNING" in it.text })
    }
}
