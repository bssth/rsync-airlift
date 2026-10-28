package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class CompactHeaderTest {
    @Test fun `summary hides zero failures and inserts actual failures before elapsed`() {
        val active = TaskState(TransferTask("active", 100), TaskStatus.RUNNING,
            attempts = 1, progress = TransferProgress(50, null, null))
        for (failed in listOf(false, true)) {
            val tasks = listOf(active) + if (failed) listOf(TaskState(TransferTask("failed", 100), TaskStatus.FAILED)) else emptyList()
            val snapshot = AirliftSnapshot(tasks, listOf(WorkerState(1, WorkerStatus.TRANSFERRING, "active")),
                false, false, 1, startedAtMillis = 1)
            val source = object : MonitorSource {
                override suspend fun snapshot() = snapshot
                override suspend fun pause() {}
                override suspend fun resume() {}
                override suspend fun setWorkerCount(count: Int) {}
            }
            val frame = MonitorRenderer(source).render(MonitorSize(120, 28), snapshot, MonitorModel())
            val text = frame.runs.joinToString("\n") { it.text }
            val header = frame.runs.single { it.text.startsWith("Workers ") }.text
            assertContains(header, if (failed) "Workers 1 | Files 0/2 | Failed 1 | Elapsed" else "Workers 1 | Files 0/1 | Elapsed")
            if (!failed) assertFalse("Failed" in header)
            assertContains(text, "1: Workers | 2: Queue | 3: Errors | Tab: switch | WORKERS")
            assertTrue(frame.runs.any { it.text.startsWith("RUNNING | Size 100.0B | Speed --/s | attempt 1") })
            assertFalse(frame.runs.any { it.text.startsWith("matched ") && " | new " in it.text })
        }
    }
}
