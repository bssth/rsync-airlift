package iris.tools.airlift

import kotlinx.io.files.Path
import kotlin.test.*

class RsyncArgumentsTest {
    @Test fun `custom copy options replace partial-dir defaults without breaking monitoring`() {
        val config = AirliftConfig("user@host", "/source", Path("dest"),
            rsyncOptions = "-avH --partial --inplace --bwlimit=90000")
        val command = RsyncCommand.build(config, "ssh -S socket", "user@host:/source/", "/dest/")
        assertEquals(listOf("rsync", "-avH", "--partial", "--inplace", "--bwlimit=90000"), command.take(5))
        assertFalse(command.any { it.startsWith("--partial-dir=") })
        assertContains(command, "--files-from=-")
        assertContains(command, "--info=progress2")
        assertContains(command, "--stats")
        assertEquals(listOf("--", "user@host:/source/", "/dest/"), command.takeLast(3))
    }

    @Test fun `tokenizer preserves quoted values and does not evaluate shell expressions`() {
        assertEquals(listOf("--exclude", "cache dir/*", "--suffix=.backup copy", "--filter", "- *.tmp"),
            RsyncArguments.parse("--exclude 'cache dir/*' --suffix=\".backup copy\" --filter \"- *.tmp\""))
        assertEquals(listOf("--suffix=\$(touch marker)"), RsyncArguments.parse("--suffix='\$(touch marker)'"))
        assertEquals(listOf("--suffix=a b"), RsyncArguments.parse("--suffix=a\\ b"))
    }

    @Test fun `malformed quoting and owned transport options fail before starting a process`() {
        for (text in listOf("--suffix='bad", "-a\\", "--files-from=other", "-e 'other ssh'", "--rsh=other", "--")) {
            assertFailsWith<IllegalArgumentException> { RsyncArguments.parse(text) }
        }
    }

    @Test fun `no copy policy is injected when options are omitted or empty`() {
        for (options in listOf(null, "")) {
            val command = RsyncCommand.build(AirliftConfig("user@host", "/source", Path("dest"), rsyncOptions = options), "ssh", "source", "dest")
            assertEquals(listOf("rsync", "--relative", "--protect-args", "--from0", "--files-from=-", "--info=progress2", "--outbuf=L", "--stats", "--info=name1", "--out-format=AIRLIFT_FILE:%n", "-e", "ssh", "--", "source", "dest"), command)
        }
    }
}
