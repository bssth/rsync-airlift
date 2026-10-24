package iris.tools.airlift

/** Password stays in the inherited environment; never put its value in arguments. */
object SshAuthentication {
    fun command(arguments: List<String>, environment: Map<String, String> = System.getenv()): List<String> =
        if (environment.containsKey("SSHPASS")) listOf("sshpass", "-e") + arguments.map {
            if (it == "BatchMode=yes") "BatchMode=no" else it
        } else arguments
}
