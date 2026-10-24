package iris.tools.airlift.monitor

import com.googlecode.lanterna.TextColor
import iris.tools.airlift.*
import kotlin.test.*

class WorkerDisplayTest {
    @Test fun `completed row retains average speed and leaves ETA blank`() {
        val task = TaskState(TransferTask("file", 10485760), TaskStatus.SUCCEEDED,
            progress = TransferProgress(10485760, 2097152.0, null, 100), progressUpdatedAtNanos = 0)
        val text = WorkerLineLayout(55).segments(WorkerState(1, WorkerStatus.FINISHED, "file"), task).joinToString("") { it.text }
        assertContains(text, "2.0MiB/s")
        assertTrue(text.endsWith("    "))
        assertFalse(text.contains("0s"))
        assertNull(MonitorModel.freshRate(task)) // Finished average must not inflate live aggregate speed.
    }
    @Test fun `stopped workers disappear while finished and failed workers remain`() {
        val snapshot = AirliftSnapshot(emptyList(), listOf(
            WorkerState(1, WorkerStatus.STOPPED),
            WorkerState(2, WorkerStatus.TRANSFERRING),
            WorkerState(3, WorkerStatus.FINISHED),
            WorkerState(4, WorkerStatus.FAILED, error = "Connection failed"),
        ), false, false)
        assertEquals(listOf("worker-2", "worker-3", "worker-4"), MonitorModel().rows(snapshot).map { it.key })
        assertEquals(4, snapshot.workers.size) // Keep dispatcher history intact.
    }

    @Test fun `compact time selects weeks days hours minutes seconds`() {
        for ((seconds, text) in listOf(10L to "10s", 600L to "10m", 36000L to "10h", 864000L to "10d", 6048000L to "10w", 0L to "0s", 61L to "1m")) {
            assertEquals(text, MonitorModel.compactDuration(seconds))
        }
        assertEquals("--", MonitorModel.compactDuration(null))
    }

    @Test fun `worker row replaces state with bar size and compact ETA without path`() {
        val task = TaskState(TransferTask("private/folder/data.bin", 1000), TaskStatus.RUNNING,
            progress = TransferProgress(770, 20.0, 10), progressUpdatedAtNanos = System.nanoTime())
        val row = MonitorModel().rows(AirliftSnapshot(listOf(task), listOf(WorkerState(1, WorkerStatus.TRANSFERRING, task.task.id)), false, false)).single()
        assertContains(row.text, "[||||||||||||    ]")
        assertContains(row.text, "77%")
        assertContains(row.text, "1000.0B")
        assertContains(row.text, "10s")
        assertFalse(row.text.contains("TRANSFERRING"))
        assertFalse(row.text.contains(task.task.path))
        assertEquals(task, row.task)
    }

    @Test fun `worker bar splits final matched and new data and omits finished label`() {
        val task = TaskState(TransferTask("file", 1000), TaskStatus.SUCCEEDED,
            progress = TransferProgress(1000, null, null, 100, matchedBytes = 750, literalBytes = 250))
        val segments = MonitorModel.workerSegments(WorkerState(1, WorkerStatus.FINISHED, "file"), task)
        assertEquals(12, segments.filter { it.color == TextColor.ANSI.CYAN }.sumOf { it.text.length })
        assertEquals(4, segments.filter { it.color == TextColor.ANSI.GREEN }.sumOf { it.text.length })
        assertFalse(segments.joinToString("") { it.text }.contains("FINISHED"))
        val unknown = MonitorModel.workerSegments(WorkerState(1, WorkerStatus.FINISHED, "file"), task.copy(progress = null))
        assertFalse(unknown.any { it.text.isNotEmpty() && it.color == TextColor.ANSI.CYAN })
    }
}
