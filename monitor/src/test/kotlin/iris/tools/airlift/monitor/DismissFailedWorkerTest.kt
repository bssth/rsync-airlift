package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class DismissFailedWorkerTest {
    private class Source(var state: AirliftSnapshot) : MonitorSource {
        val requested = mutableListOf<Int>()
        override fun snapshot() = state
        override fun pause() {}
        override fun resume() {}
        override fun setWorkerCount(count: Int) { requested.add(count) }
    }
    private fun snapshot() = AirliftSnapshot(emptyList(), listOf(
        WorkerState(1, WorkerStatus.TRANSFERRING),
        WorkerState(2, WorkerStatus.FAILED, error = "SSH failed"),
        WorkerState(3, WorkerStatus.FAILED, error = "Connection refused"),
    ), false, false, targetWorkers = 3)

    @Test fun `minus dismisses one failed worker before reducing concurrency and preserves errors`() {
        val source = Source(snapshot())
        val model = MonitorModel()
        model.handle('-', source)
        assertEquals(listOf(1, 3), model.visibleWorkers(source.state).map { it.id })
        assertTrue(source.requested.isEmpty())
        model.handle('-', source)
        assertEquals(listOf(1), model.visibleWorkers(source.state).map { it.id })
        assertTrue(source.requested.isEmpty())
        model.handle('-', source)
        assertEquals(listOf(2), source.requested)
        model.view = MonitorView.ERRORS
        assertEquals(2, model.rows(source.state).size)
        assertEquals(3, source.state.workers.size)
    }

    @Test fun `failed worker can be dismissed after completion without changing target`() {
        val source = Source(snapshot().copy(finishedAtMillis = 1))
        val model = MonitorModel()
        repeat(3) { model.handle('-', source) }
        assertEquals(listOf(1), model.rows(source.state).map { it.workerId })
        assertTrue(source.requested.isEmpty())
    }
}
