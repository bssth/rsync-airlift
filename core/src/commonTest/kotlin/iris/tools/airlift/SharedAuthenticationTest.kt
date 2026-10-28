package iris.tools.airlift

import iris.tools.airlift.process.ProcessSpec
import kotlin.test.*

class SharedAuthenticationTest {
    @Test fun `preflight wrapping followed by process configuration adds sshpass only once`() {
        SshAuthentication.clearPassword()
        val environment = mapOf("SSHPASS" to "test secret")
        val wrapped = SshAuthentication.command(listOf("ssh", "-o", "BatchMode=yes", "user@host"), environment)
        val configured = SshAuthentication.configure(ProcessSpec(wrapped, environment = environment, inheritEnvironment = false))
        assertEquals(wrapped, configured.command)
        assertEquals(1, configured.command.count { it == "sshpass" })
        assertEquals("test secret", configured.environment["SSHPASS"])
        assertEquals(configured, SshAuthentication.configure(configured))
    }

    @Test fun `prompted password configuration is idempotent and keeps replacement environment`() {
        try {
            SshAuthentication.usePassword("replacement".toCharArray())
            val wrapped = SshAuthentication.command(listOf("ssh", "-o", "BatchMode=yes"), emptyMap())
            val configured = SshAuthentication.configure(ProcessSpec(wrapped, inheritEnvironment = false))
            assertEquals(listOf("sshpass", "-e", "ssh"), configured.command.take(3))
            assertEquals(1, configured.command.count { it == "sshpass" })
            assertEquals("replacement", configured.environment["SSHPASS"])
            assertEquals(configured, SshAuthentication.configure(configured))
        } finally { SshAuthentication.clearPassword() }
    }

    @Test fun `password input is copied and clearing removes the shared credential`() {
        val password = "new password".toCharArray()
        try {
            SshAuthentication.usePassword(password)
            password.fill('\u0000')
            assertTrue(SshAuthentication.hasPassword(emptyMap()))
            val spec = SshAuthentication.configure(ProcessSpec(listOf("ssh", "-o", "BatchMode=yes"), inheritEnvironment = false))
            assertEquals("new password", spec.environment["SSHPASS"])
            assertEquals("sshpass", spec.command.first())
            assertFalse(spec.toString().contains("new password"))
            SshAuthentication.clearPassword()
            assertFalse(SshAuthentication.hasPassword(emptyMap()))
            assertEquals(listOf("ssh"), SshAuthentication.command(listOf("ssh"), emptyMap()))
        } finally { SshAuthentication.clearPassword() }
    }

    @Test fun `prompted password overrides supplied values but never enters plain SSH environment`() {
        try {
            SshAuthentication.usePassword("replacement".toCharArray())
            assertEquals("replacement", SshAuthentication.environmentFor(listOf("sshpass", "-e", "ssh"), mapOf("SSHPASS" to "old"))["SSHPASS"])
            assertFalse(SshAuthentication.environmentFor(listOf("ssh"), emptyMap()).containsKey("SSHPASS"))
            val spec = SshAuthentication.configure(ProcessSpec(listOf("ssh"), environment = mapOf("SSHPASS" to "old")))
            assertEquals("replacement", spec.environment["SSHPASS"])
        } finally { SshAuthentication.clearPassword() }
    }

    @Test fun `explicit process environment selects authentication without requiring inherited variables`() {
        SshAuthentication.clearPassword()
        val plain = SshAuthentication.configure(ProcessSpec(listOf("ssh"), inheritEnvironment = false))
        assertEquals(listOf("ssh"), plain.command)
        val password = SshAuthentication.configure(ProcessSpec(listOf("ssh"), environment = mapOf("SSHPASS" to ""), inheritEnvironment = false))
        assertEquals("sshpass", password.command.first())
        val removed = SshAuthentication.configure(ProcessSpec(listOf("ssh"), environment = mapOf("SSHPASS" to null)))
        assertEquals(listOf("ssh"), removed.command)
    }
}
