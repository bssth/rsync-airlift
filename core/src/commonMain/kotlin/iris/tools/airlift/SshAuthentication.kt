package iris.tools.airlift

import iris.tools.airlift.process.ProcessSpec

internal expect fun platformEnvironment(): Map<String, String>

/** Shared key/password policy. Passwords are passed only through child-process environments. */
object SshAuthentication {
    private val lock = platformLock()
    private var promptedPassword: CharArray? = null

    fun hasPassword(environment: Map<String, String> = platformEnvironment()): Boolean = lock.locked {
        environment.containsKey("SSHPASS") || promptedPassword != null
    }

    fun usePassword(password: CharArray) = lock.locked {
        promptedPassword?.fill('\u0000')
        promptedPassword = password.copyOf()
    }

    fun clearPassword() = lock.locked {
        promptedPassword?.fill('\u0000')
        promptedPassword = null
    }

    fun command(arguments: List<String>, environment: Map<String, String> = platformEnvironment()): List<String> =
        lock.locked { commandLocked(arguments, environment) }

    private fun commandLocked(arguments: List<String>, environment: Map<String, String>): List<String> =
        if (environment.containsKey("SSHPASS") || promptedPassword != null) listOf("sshpass", "-e") + arguments.map {
            if (it == "BatchMode=yes") "BatchMode=no" else it
        } else arguments

    /** Overlay the prompted password only for an already selected sshpass command. */
    fun environmentFor(arguments: List<String>, environment: Map<String, String>): Map<String, String> = lock.locked {
        environment.toMutableMap().apply {
            if (arguments.firstOrNull() == "sshpass") promptedPassword?.let { this["SSHPASS"] = it.concatToString() }
        }
    }

    fun configure(spec: ProcessSpec): ProcessSpec = lock.locked {
        val effective = if (spec.inheritEnvironment) platformEnvironment().toMutableMap() else mutableMapOf()
        spec.environment.forEach { (key, value) -> if (value == null) effective.remove(key) else effective[key] = value }
        val command = commandLocked(spec.command, effective)
        val overrides = spec.environment.toMutableMap()
        if (command.firstOrNull() == "sshpass") promptedPassword?.let { overrides["SSHPASS"] = it.concatToString() }
        spec.copy(command = command, environment = overrides)
    }
}
