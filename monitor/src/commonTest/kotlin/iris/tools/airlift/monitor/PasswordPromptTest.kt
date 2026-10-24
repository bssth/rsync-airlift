package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class PasswordPromptTest {
    private val config = AirliftConfig("user@host", "/source", kotlinx.io.files.Path("dest"))
    private val denied = SshCheckResult(255, "Permission denied (publickey,password).")

    @Test fun `key failure asks once and retries using password without arguments containing it`() = kotlinx.coroutines.test.runTest {
        var installed = false
        var prompts = 0
        var calls = 0
        val entered = "test password".toCharArray()
        checkSshConnection(config, interactive = true,
            authenticate = { if (installed) listOf("sshpass", "-e") + it else it },
            passwordAvailable = { installed },
            readPassword = { prompts++; entered },
            installPassword = { assertEquals("test password", it.concatToString()); installed = true },
        ) { command, interactive ->
            assertFalse(interactive)
            assertFalse(command.any { "test password" in it })
            if (calls++ == 0) denied else { assertEquals("sshpass", command.first()); SshCheckResult(0, "") }
        }
        assertEquals(1, prompts)
        assertEquals(2, calls)
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test fun `wrong password is not requested again`() = kotlinx.coroutines.test.runTest {
        var prompts = 0
        var calls = 0
        assertFailsWith<IllegalStateException> {
            checkSshConnection(config, true, authenticate = { it }, passwordAvailable = { false },
                readPassword = { prompts++; charArrayOf('x') }, installPassword = {}) { _, _ -> calls++; denied }
        }
        assertEquals(1, prompts)
        assertEquals(2, calls)
    }

    @Test fun `rejected existing password asks once and replaces inherited password`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        var prompts = 0
        try {
            checkSshConnection(config, true, passwordAvailable = { true },
                readPassword = { prompts++; "replacement".toCharArray() }) { command, _ ->
                val spec = SshAuthentication.configure(iris.tools.airlift.process.ProcessSpec(command,
                    environment = mapOf("SSHPASS" to "other server password")))
                if (calls++ == 0) denied else {
                    assertEquals("replacement", spec.environment["SSHPASS"])
                    SshCheckResult(0, "")
                }
            }
            assertEquals(1, prompts)
            assertEquals(2, calls)
        } finally { SshAuthentication.clearPassword() }
    }

    @Test fun `network failures never prompt`() = kotlinx.coroutines.test.runTest {
        for (available in listOf(true, false)) {
            assertFailsWith<IllegalStateException> {
                checkSshConnection(config, true, authenticate = { it }, passwordAvailable = { available },
                    readPassword = { fail("Unexpected password prompt") }) { _, _ -> SshCheckResult(255, "Connection refused") }
            }
        }
    }

    @Test fun `headless authentication failure explains keys or SSHPASS`() = kotlinx.coroutines.test.runTest {
        val error = assertFailsWith<IllegalStateException> {
            checkSshConnection(config, false, authenticate = { it }, passwordAvailable = { false },
                readPassword = { fail("Unexpected password prompt") }) { _, _ -> denied }
        }
        assertContains(error.message.orEmpty(), "SSHPASS")
    }

}
