package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class ActiveOverallProgressTest {
    private fun task(id: String, status: TaskStatus, size: Long?, bytes: Long = 0, percent: Int? = null) =
        TaskState(TransferTask(id, size), status, progress = TransferProgress(bytes, null, null, percent))
    private fun breakdown(vararg tasks: TaskState) = OverallBreakdown.from(AirliftSnapshot(tasks.toList(), emptyList(), false, false))
    private fun count(parts: List<RowSegment>, color: MonitorColor) = parts.filter { it.color == color }.sumOf { it.text.count { c -> c == '|' } }

    @Test fun `active size is reserved from zero progress and becomes green as bytes advance`() {
        for (bytes in listOf(0L, 50L, 100L)) {
            val result = breakdown(task("active", TaskStatus.RUNNING, 100, bytes), task("queued", TaskStatus.QUEUED, 900))
            val bar = result.segments(100)
            assertEquals((bytes / 10).toInt(), count(bar, MonitorColor.ANSI.GREEN))
            assertEquals(10 - (bytes / 10).toInt(), count(bar, MonitorColor.ANSI.YELLOW))
            assertEquals(90, bar.sumOf { it.text.count { c -> c == ' ' } })
            assertEquals(0, result.matchedBytes)
            assertEquals(0, result.literalBytes)
        }
    }

    @Test fun `confirmed breakdown errors active remainder and queue never overlap`() {
        val done = task("done", TaskStatus.SUCCEEDED, 40).copy(progress = TransferProgress(40, null, null, 100, 20, 20))
        val result = breakdown(done, task("failed", TaskStatus.FAILED, 10),
            task("active", TaskStatus.RUNNING, 20, 10), task("queued", TaskStatus.QUEUED, 30))
        val parts = result.segments(100)
        assertEquals(20, count(parts, MonitorColor.ANSI.CYAN))
        assertEquals(30, count(parts, MonitorColor.ANSI.GREEN))
        assertEquals(10, count(parts, MonitorColor.ANSI.RED))
        assertEquals(10, count(parts, MonitorColor.ANSI.YELLOW))
        assertEquals(30, parts.sumOf { it.text.count { c -> c == ' ' } })
        assertEquals(20, result.matchedBytes)
        assertEquals(20, result.literalBytes)
        val unknownStats = breakdown(task("done", TaskStatus.SUCCEEDED, 100))
        assertEquals(100, count(unknownStats.segments(100), MonitorColor.ANSI.GREEN))
        assertEquals(0.0, unknownStats.other)
    }

    @Test fun `unknown size uses percent and changed sizes stay within bounds`() {
        val unknown = breakdown(task("active", TaskStatus.RUNNING, null, percent = 50), task("queued", TaskStatus.QUEUED, null))
        assertEquals(25, count(unknown.segments(100), MonitorColor.ANSI.GREEN))
        assertEquals(25, count(unknown.segments(100), MonitorColor.ANSI.YELLOW))
        for (bytes in listOf(-10L, 200L)) {
            val result = breakdown(task("active", TaskStatus.RUNNING, 100, bytes))
            assertTrue(result.other in 0.0..1.0)
            assertTrue(result.processing in 0.0..1.0)
            assertEquals(100, result.segments(100).sumOf { it.text.length })
        }
    }
}
