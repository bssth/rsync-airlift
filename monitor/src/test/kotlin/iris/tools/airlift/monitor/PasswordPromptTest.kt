package iris.tools.airlift.monitor

import iris.tools.airlift.*
import java.nio.file.Path
import kotlin.test.*

class PasswordPromptTest {
    private val config = AirliftConfig("user@host", "/source", Path.of("dest"))
    private val denied = SshCheckResult(255, "Permission denied (publickey,password).")

    @Test fun `key failure asks once and retries using password without arguments containing it`() {
        var installed = false
        var prompts = 0
        var calls = 0
        val entered = "test password".toCharArray()
        checkSshConnection(config, interactive = true,
            authenticate = { if (installed) listOf("sshpass", "-e") + it else it },
            passwordAvailable = { installed },
            readPassword = { prompts++; entered },
            installPassword = { assertEquals("test password", String(it)); installed = true },
        ) { command, interactive ->
            assertFalse(interactive)
            assertFalse(command.any { "test password" in it })
            if (calls++ == 0) denied else { assertEquals("sshpass", command.first()); SshCheckResult(0, "") }
        }
        assertEquals(1, prompts)
        assertEquals(2, calls)
        assertTrue(entered.all { it == '\u0000' })
    }

    @Test fun `wrong password is not requested again`() {
        var prompts = 0
        var calls = 0
        assertFailsWith<IllegalStateException> {
            checkSshConnection(config, true, authenticate = { it }, passwordAvailable = { false },
                readPassword = { prompts++; charArrayOf('x') }, installPassword = {}) { _, _ -> calls++; denied }
        }
        assertEquals(1, prompts)
        assertEquals(2, calls)
    }

    @Test fun `rejected existing password asks once and replaces inherited password`() {
        var calls = 0
        var prompts = 0
        try {
            checkSshConnection(config, true, passwordAvailable = { true },
                readPassword = { prompts++; "replacement".toCharArray() }) { command, _ ->
                val builder = ProcessBuilder(command)
                builder.environment()["SSHPASS"] = "other server password"
                SshAuthentication.configure(builder)
                if (calls++ == 0) denied else {
                    assertEquals("replacement", builder.environment()["SSHPASS"])
                    SshCheckResult(0, "")
                }
            }
            assertEquals(1, prompts)
            assertEquals(2, calls)
        } finally { SshAuthentication.clearPassword() }
    }

    @Test fun `network failures never prompt`() {
        for (available in listOf(true, false)) {
            assertFailsWith<IllegalStateException> {
                checkSshConnection(config, true, authenticate = { it }, passwordAvailable = { available },
                    readPassword = { fail("Unexpected password prompt") }) { _, _ -> SshCheckResult(255, "Connection refused") }
            }
        }
    }

    @Test fun `headless authentication failure explains keys or SSHPASS`() {
        val error = assertFailsWith<IllegalStateException> {
            checkSshConnection(config, false, authenticate = { it }, passwordAvailable = { false },
                readPassword = { fail("Unexpected password prompt") }) { _, _ -> denied }
        }
        assertContains(error.message.orEmpty(), "SSHPASS")
    }

    @Test fun `prompted password reaches only sshpass process environment and can be cleared`() {
        try {
            SshAuthentication.usePassword("test password".toCharArray())
            val command = SshAuthentication.command(listOf("ssh", "-o", "BatchMode=yes"), emptyMap())
            assertEquals(listOf("sshpass", "-e", "ssh", "-o", "BatchMode=no"), command)
            val builder = ProcessBuilder(command)
            builder.environment().remove("SSHPASS")
            SshAuthentication.configure(builder)
            assertEquals("test password", builder.environment()["SSHPASS"])
            val plain = ProcessBuilder("ssh")
            plain.environment().remove("SSHPASS")
            assertNull(SshAuthentication.configure(plain).environment()["SSHPASS"])
        } finally { SshAuthentication.clearPassword() }
        assertEquals(listOf("ssh"), SshAuthentication.command(listOf("ssh"), emptyMap()))
    }
}
