package iris.tools.airlift.monitor

import iris.tools.airlift.AirliftConfig
import iris.tools.airlift.SshAuthentication
import java.nio.file.Files

internal data class SshCheckResult(val exitCode: Int, val output: String)

/** Trust prompts happen once, before discovery and before any worker threads or TUI. */
internal fun checkSshConnection(
    config: AirliftConfig,
    interactive: Boolean = System.console() != null,
    authenticate: (List<String>) -> List<String> = { SshAuthentication.command(it) },
    execute: (List<String>, Boolean) -> SshCheckResult = ::runSshCheck,
) {
    val base = buildList {
        add(config.sshExecutable)
        addAll(listOf("-p", config.port.toString(), "-o", "ConnectTimeout=${config.connectTimeoutSeconds}",
            "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3"))
        config.identityFile?.let { addAll(listOf("-i", it.toAbsolutePath().toString())) }
    }
    val strict = authenticate(base + listOf("-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", config.host, "true"))
    val first = execute(strict, false)
    if (first.exitCode == 0) return
    if (!unknownHostKey(first.output)) error("SSH connection failed: ${first.output}")
    check(interactive) {
        "SSH host key is unknown for ${config.host}. Run Airlift in an interactive terminal to review and accept the key, or connect with ssh first. ${first.output}"
    }
    println("SSH host key is unknown for ${config.host}. Review the fingerprint below; enter yes to save it, or no to cancel.")
    // OpenSSH itself displays the fingerprint and writes to its configured known_hosts.
    // No authentication is attempted here: password mode remains handled by sshpass.
    val prompt = base + listOf("-o", "BatchMode=no", "-o", "StrictHostKeyChecking=ask",
        "-o", "PreferredAuthentications=none", "-o", "NumberOfPasswordPrompts=0", config.host, "true")
    execute(prompt, true)
    // Authentication failure in the trust-only invocation is expected. A strict retry
    // verifies both that the user accepted the key and that authentication succeeds.
    val retry = execute(strict, false)
    check(retry.exitCode == 0) { "SSH connection failed after host-key confirmation: ${retry.output}" }
}

internal fun unknownHostKey(output: String): Boolean =
    "REMOTE HOST IDENTIFICATION HAS CHANGED" !in output &&
        "Offending " !in output &&
        Regex("No .* host key is known for .* and you have requested strict checking", RegexOption.IGNORE_CASE).containsMatchIn(output)

private fun runSshCheck(command: List<String>, interactive: Boolean): SshCheckResult {
    val output = if (interactive) null else Files.createTempFile("airlift-ssh-check-", ".log")
    var process: Process? = null
    try {
        val builder = ProcessBuilder(command)
        builder.environment()["LC_ALL"] = "C"
        if (interactive) {
            builder.environment().remove("SSHPASS")
            builder.inheritIO()
        } else builder.redirectErrorStream(true).redirectOutput(requireNotNull(output).toFile())
        process = builder.start()
        if (!interactive) process.outputStream.close()
        return SshCheckResult(process.waitFor(), output?.let { Files.readString(it).takeLast(8192) }.orEmpty())
    } finally {
        process?.let { if (it.isAlive) it.destroyForcibly() }
        output?.let(Files::deleteIfExists)
    }
}
