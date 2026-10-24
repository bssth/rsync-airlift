package iris.tools.airlift

import iris.tools.airlift.process.ProcessSpec
import kotlinx.coroutines.*
import kotlin.test.*

class ConcurrentAuthenticationTest {
    @Test fun `parallel password replacement clearing and reads produce consistent snapshots`() = runBlocking<Unit> {
        try {
            coroutineScope {
                (1..8).map { id -> async(Dispatchers.Default) {
                    repeat(300) {
                        SshAuthentication.usePassword("worker-$id-secret".toCharArray())
                        val spec = SshAuthentication.configure(ProcessSpec(listOf("ssh"), inheritEnvironment = false))
                        if (spec.command.first() == "sshpass") {
                            assertTrue(Regex("worker-[1-8]-secret").matches(spec.environment.getValue("SSHPASS")!!))
                        } else assertFalse(spec.environment.containsKey("SSHPASS"))
                        SshAuthentication.hasPassword(emptyMap())
                        SshAuthentication.command(listOf("ssh"), emptyMap())
                        SshAuthentication.clearPassword()
                    }
                } }.awaitAll()
            }
        } finally { SshAuthentication.clearPassword() }
    }
}
