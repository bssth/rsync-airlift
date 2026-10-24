package iris.tools.airlift.monitor

internal actual fun hasInteractiveConsole(): Boolean = System.console() != null
internal actual fun readConsolePassword(prompt: String): CharArray? = System.console()?.readPassword("%s", prompt)
