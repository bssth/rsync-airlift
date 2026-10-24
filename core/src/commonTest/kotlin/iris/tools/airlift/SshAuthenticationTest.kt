package iris.tools.airlift

import kotlin.test.*

class SshAuthenticationTest {
    private val ssh = listOf("ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "user@host")

    @Test fun `key authentication remains noninteractive without SSHPASS`() {
        assertEquals(ssh, SshAuthentication.command(ssh, emptyMap()))
    }

    @Test fun `password mode uses inherited environment without including the secret in arguments`() {
        val command = SshAuthentication.command(ssh, mapOf("SSHPASS" to "dummy test password"))
        assertEquals(listOf("sshpass", "-e", "ssh"), command.take(3))
        assertContains(command, "BatchMode=no")
        assertContains(command, "StrictHostKeyChecking=yes")
        assertFalse(command.any { it.contains("dummy test password") })
        assertContains(ssh, "BatchMode=yes")
    }

    @Test fun `presence of an empty SSHPASS still selects password mode`() {
        assertEquals("sshpass", SshAuthentication.command(ssh, mapOf("SSHPASS" to "")).first())
    }
}
