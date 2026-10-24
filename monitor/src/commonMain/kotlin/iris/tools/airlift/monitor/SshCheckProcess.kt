package iris.tools.airlift.monitor

import iris.tools.airlift.SshAuthentication
import iris.tools.airlift.process.*
import kotlinx.coroutines.*

internal suspend fun runSshCheck(command: List<String>, interactive: Boolean,
    launcher: ProcessLauncher = preparationProcesses()): SshCheckResult {
    val spec = if (interactive) {
        // Trust-only confirmation must reach the terminal, without sshpass or a password in its environment.
        ProcessSpec(command, environment = mapOf("LC_ALL" to "C", "SSHPASS" to null),
            input = ProcessRedirect.Inherit, output = ProcessRedirect.Inherit, error = ProcessRedirect.Inherit)
    } else SshAuthentication.configure(ProcessSpec(command, environment = mapOf("LC_ALL" to "C"), mergeError = true))
    var process: ChildProcess? = null
    try {
        withContext(NonCancellable) { process = launcher.start(spec) }
        currentCoroutineContext().ensureActive()
        val child = requireNotNull(process)
        if (!interactive) child.stdin?.close()
        val output = if (interactive) "" else requireNotNull(child.stdout).readTail(8192)
        return SshCheckResult(child.awaitExit(), output)
    } finally {
        withContext(NonCancellable) { process?.close() }
    }
}
