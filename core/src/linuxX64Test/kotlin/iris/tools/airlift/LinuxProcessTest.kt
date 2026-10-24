package iris.tools.airlift

import iris.tools.airlift.process.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlin.test.*

class LinuxProcessTest {
    private val launcher = LinuxProcessLauncher()
    private suspend fun ProcessByteSource.text(): String {
        val result = mutableListOf<Byte>()
        val bytes = ByteArray(4096)
        while (true) { val count = read(bytes); if (count < 0) return result.toByteArray().decodeToString(); repeat(count) { result += bytes[it] } }
    }

    @Test fun literalArgumentsEnvironmentAndCwd(): Unit = runBlocking {
        val child = launcher.start(ProcessSpec(listOf("/bin/sh", "-c", "printf '%s|%s|%s' \"${'$'}1\" \"${'$'}AIRLIFT_TEST\" \"${'$'}PWD\"", "test", "a;echo nope"),
            environment = mapOf("AIRLIFT_TEST" to "value"), directory = Path("/tmp")))
        try {
            child.stdin!!.close()
            assertEquals("a;echo nope|value|/tmp", child.stdout!!.text())
            assertEquals(0, child.awaitExit())
        } finally { child.close() }
    }

    @Test fun duplexPipesAndMergedErrors(): Unit = runBlocking {
        val child = launcher.start(ProcessSpec(listOf("/bin/sh", "-c", "cat; printf error >&2"), mergeError = true))
        try { coroutineScope {
            val bytes = ByteArray(262144) { 'x'.code.toByte() }
            val writing = launch { child.stdin!!.write(bytes); child.stdin!!.close() }
            val output = child.stdout!!.text()
            writing.join()
            assertEquals(262149, output.length)
            assertTrue(output.endsWith("error"))
            assertNull(child.stderr)
            assertEquals(0, child.awaitExit())
        } } finally { child.close() }
    }

    @Test fun timeoutDoesNotKillAndCloseReaps(): Unit = runBlocking {
        val child = launcher.start(ProcessSpec(listOf("/bin/sleep", "30")))
        assertNull(child.awaitExit(20))
        assertNull(child.exitCode())
        child.close()
        assertNotNull(child.exitCode())
        child.close()
    }

    @Test fun cancelledPipeReadAbortsChild(): Unit = runBlocking {
        val child = launcher.start(ProcessSpec(listOf("/bin/sleep", "30")))
        try {
            val reading = launch { child.stdout!!.read(ByteArray(32)) }
            delay(20)
            reading.cancelAndJoin()
            assertNotNull(child.awaitExit(2000))
        } finally { child.close() }
    }

    @Test fun execFailureAndInvalidDirectoryAreReported(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> { launcher.start(ProcessSpec(listOf("/no/such/airlift-command"))) }
        assertFailsWith<IllegalStateException> { launcher.start(ProcessSpec(listOf("/bin/true"), directory = Path("/no/such/airlift-directory"))) }
    }

    @Test fun fileRedirectionAppendAndSeparateStderr(): Unit = runBlocking {
        val directory = platformSshTemporaryDirectory("airlift-process-test-")
        val file = Path(directory, "out")
        try {
            for (append in listOf(false, true)) {
                val child = launcher.start(ProcessSpec(listOf("/bin/sh", "-c", "printf output; printf error >&2"),
                    output = ProcessRedirect.File(file, append)))
                try {
                    child.stdin!!.close()
                    assertNull(child.stdout)
                    assertEquals("error", child.stderr!!.text())
                    assertEquals(0, child.awaitExit())
                } finally { child.close() }
            }
            val reader = launcher.start(ProcessSpec(listOf("/bin/cat"), input = ProcessRedirect.File(file)))
            try {
                assertNull(reader.stdin)
                assertEquals("outputoutput", reader.stdout!!.text())
                assertEquals(0, reader.awaitExit())
            } finally { reader.close() }
        } finally { platformDeleteSshDirectory(directory) }
    }

    @Test fun environmentCanBeClearedAndCommandResolvedThroughExplicitPath(): Unit = runBlocking {
        val child = launcher.start(ProcessSpec(listOf("env"), inheritEnvironment = false,
            environment = mapOf("PATH" to "/bin:/usr/bin", "AIRLIFT_TEST" to "literal", "REMOVED" to null)))
        try {
            child.stdin!!.close()
            val lines = child.stdout!!.text().trim().lines().toSet()
            assertEquals(setOf("PATH=/bin:/usr/bin", "AIRLIFT_TEST=literal"), lines)
            assertEquals(0, child.awaitExit())
        } finally { child.close() }
    }

    @Test fun concurrentCloseAndCancelledWaitAreSafe(): Unit = runBlocking {
        val child = launcher.start(ProcessSpec(listOf("/bin/sleep", "30")))
        val waiting = launch { child.awaitExit() }
        delay(20); waiting.cancelAndJoin()
        assertNull(child.exitCode())
        coroutineScope { repeat(4) { launch { child.close() } } }
        assertNotNull(child.exitCode())
    }
}
