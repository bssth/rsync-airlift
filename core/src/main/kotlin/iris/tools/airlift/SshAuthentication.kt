package iris.tools.airlift

/** Password stays in the inherited environment; never put its value in arguments. */
object SshAuthentication {
    @Volatile private var promptedPassword: CharArray? = null

    fun hasPassword(): Boolean = System.getenv().containsKey("SSHPASS") || promptedPassword != null

    @Synchronized fun usePassword(password: CharArray) {
        promptedPassword?.fill('\u0000')
        promptedPassword = password.copyOf()
    }

    @Synchronized fun clearPassword() {
        promptedPassword?.fill('\u0000')
        promptedPassword = null
    }

    /** Only sshpass receives the prompted password; it is not exported to the parent shell. */
    @Synchronized fun configure(builder: ProcessBuilder): ProcessBuilder {
        if (builder.command().firstOrNull() == "sshpass") {
            promptedPassword?.let { builder.environment()["SSHPASS"] = String(it) }
        }
        return builder
    }

    fun command(arguments: List<String>, environment: Map<String, String> = System.getenv()): List<String> =
        if (environment.containsKey("SSHPASS") || promptedPassword != null) listOf("sshpass", "-e") + arguments.map {
            if (it == "BatchMode=yes") "BatchMode=no" else it
        } else arguments
}
