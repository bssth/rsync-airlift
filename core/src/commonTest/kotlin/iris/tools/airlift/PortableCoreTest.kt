package iris.tools.airlift

import kotlin.test.*

/** These checks live in commonTest and can also run on future Native targets. */
class PortableCoreTest {
    @Test fun `task validation and batch accounting need no JVM types`() {
        val task = TransferTask("folder/a", 123, files = listOf("folder/a", "folder/b"), entries = listOf("folder/"))
        assertEquals(2, task.fileCount)
        assertEquals(1, TransferTask("empty", 0).fileCount)
        assertFailsWith<IllegalArgumentException> { TransferTask("../outside") }
        assertFailsWith<IllegalArgumentException> { TransferTask("a", files = listOf("a", "a")) }
        assertFailsWith<IllegalArgumentException> { TransferTask("a", entries = listOf("../")) }
    }

    @Test fun `quoted rsync arguments are portable and never execute shell expressions`() {
        assertEquals(listOf("--exclude", "with space/*", "--suffix=\$(command)"),
            RsyncArguments.parse("--exclude 'with space/*' --suffix='\$(command)'"))
        for (text in listOf("--files-from=other", "--rsh=ssh", "-e ssh", "--", "'unfinished")) {
            assertFailsWith<IllegalArgumentException> { RsyncArguments.parse(text) }
        }
    }

    @Test fun `portable progress parser preserves binary rates and completion semantics`() {
        val progress = ProgressParser.parse(" 1,234 12% 1.50MB/s 0:01:02")!!
        assertEquals(1234, progress.logicalBytes)
        assertEquals(1_572_864.0, progress.bytesPerSecond)
        assertEquals(62, progress.remainingSeconds)
        assertNull(ProgressParser.parse(" 1,234 100% 1.50MB/s 0:00:01 (xfr#1, to-chk=0/1)")!!.remainingSeconds)
        assertNull(ProgressParser.parse("not progress"))
    }
}
