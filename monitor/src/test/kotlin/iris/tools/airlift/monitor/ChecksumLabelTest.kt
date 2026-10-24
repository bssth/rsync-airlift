package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class ChecksumLabelTest {
    private val worker = WorkerState(1, WorkerStatus.TRANSFERRING, "file")
    private val task = TaskState(TransferTask("file", 1000), TaskStatus.RUNNING)

    @Test fun `initial file phase shows long or compact label inside bar`() {
        val wide = WorkerLineLayout(100).segments(worker, task)
        val narrow = WorkerLineLayout(52).segments(worker, task)
        assertContains(wide[1].text, "computing checksums")
        assertContains(narrow[1].text, "checksums")
        assertFalse("computing" in narrow[1].text)
        assertEquals(13, narrow[1].text.length)
        assertTrue(narrow.last().text.endsWith("    "))
    }

    @Test fun `zero byte progress still shows preparation but transferred data restores bar`() {
        val zero = task.copy(progress = TransferProgress(0, 0.0, 0))
        assertContains(WorkerLineLayout(100).segments(worker, zero)[1].text, "computing checksums")
        val active = task.copy(progress = TransferProgress(100, 20.0, 45))
        val bar = WorkerLineLayout(100).segments(worker, active)[1].text
        assertFalse("checksums" in bar)
        assertTrue('|' in bar)
        assertFalse("checksums" in WorkerLineLayout(100).segments(worker.copy(status = WorkerStatus.FINISHED), zero.copy(status = TaskStatus.SUCCEEDED))[1].text)
    }
}
