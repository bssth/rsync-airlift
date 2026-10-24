package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class CompletionProgressTest {
    private fun state(status: TaskStatus, size: Long? = 100, bytes: Long = 0) =
        TaskState(TransferTask(status.name, size), status, progress = TransferProgress(bytes, null, null))
    private fun snapshot(vararg tasks: TaskState) = AirliftSnapshot(tasks.toList(), emptyList(), false, false)

    @Test fun `final failure contributes full file size while successful bytes stay separate`() {
        val snapshot = snapshot(state(TaskStatus.SUCCEEDED, 97), state(TaskStatus.FAILED, 3, 1))
        assertEquals(1.0, MonitorModel.overallProgress(snapshot))
        assertEquals(1, snapshot.completed)
        assertEquals(1, snapshot.failed)
    }

    @Test fun `failure advances progress before other transfers finish`() {
        assertEquals(0.75, MonitorModel.overallProgress(snapshot(
            state(TaskStatus.FAILED), state(TaskStatus.RUNNING, bytes = 50))))
        assertEquals(0.25, MonitorModel.overallProgress(snapshot(
            state(TaskStatus.QUEUED), state(TaskStatus.RUNNING, bytes = 50))))
    }

    @Test fun `unknown and zero sizes still finish at one hundred percent`() {
        for (size in listOf(null, 0L)) {
            assertEquals(1.0, MonitorModel.overallProgress(snapshot(
                state(TaskStatus.SUCCEEDED, size), state(TaskStatus.FAILED, size))))
        }
        assertEquals(0.0, MonitorModel.overallProgress(snapshot(state(TaskStatus.CANCELLED))))
    }
}
