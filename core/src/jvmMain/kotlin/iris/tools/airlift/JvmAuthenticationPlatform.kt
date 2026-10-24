package iris.tools.airlift

internal actual fun platformLock(): PlatformLock = object : PlatformLock {
    private val monitor = Any()
    override fun <T> locked(block: () -> T): T = synchronized(monitor, block)
}

internal actual fun platformEnvironment(): Map<String, String> = System.getenv()

/** Java builder interop only; policy and password state belong to commonMain. */
fun SshAuthentication.configure(builder: ProcessBuilder): ProcessBuilder = builder.apply {
    environment().putAll(environmentFor(command(), environment()))
}
