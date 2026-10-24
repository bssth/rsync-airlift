package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlin.test.*

class PasswordEnvironmentJvmTest {
    @Test fun `prompted password reaches only sshpass process environment and can be cleared`() = kotlinx.coroutines.test.runTest {
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
