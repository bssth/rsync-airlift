package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class OverallRateTest {
    private fun task(id: String, rate: Double?, time: Long) = TaskState(TransferTask(id, 1000), TaskStatus.RUNNING,
        progress = rate?.let { TransferProgress(10, it, 1) }, progressUpdatedAtNanos = rate?.let { time })
    private fun snapshot(vararg tasks: TaskState) = AirliftSnapshot(tasks.toList(), emptyList(), false, false, startedAtMillis = 1)

    @Test fun `file handover retains total briefly then uses available rates`() {
        val estimator = OverallRate()
        val now = 10_000_000_000L
        assertEquals(30.0, estimator.value(snapshot(task("one", 10.0, now), task("two", 20.0, now)), now))
        assertEquals(30.0, estimator.value(snapshot(task("next", null, now), task("two", 20.0, now)), now + 1_000_000_000))
        assertEquals(20.0, estimator.value(snapshot(task("next", null, now), task("two", 20.0, now + 4_000_000_000)), now + 4_000_000_000))
    }

    @Test fun `real stall expires speed and completion clears cache`() {
        val estimator = OverallRate()
        val now = 10_000_000_000L
        val running = snapshot(task("one", 10.0, now))
        assertEquals(10.0, estimator.value(running, now))
        assertNull(estimator.value(running, now + 4_000_000_000))
        assertNull(estimator.value(running.copy(finishedAtMillis = 2), now))
        assertNull(estimator.value(snapshot(task("next", null, now)), now))
    }

    @Test fun `initial partial readings still provide an estimate`() {
        val now = 10_000_000_000L
        assertEquals(20.0, OverallRate().value(snapshot(task("one", null, now), task("two", 20.0, now)), now))
    }
}
