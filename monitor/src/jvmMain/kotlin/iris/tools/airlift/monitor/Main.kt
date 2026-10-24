package iris.tools.airlift.monitor

import iris.tools.airlift.*

fun main(args: Array<String>) = kotlinx.coroutines.runBlocking {
    var exitCode = 0
    try { launchMonitor(args.toList()) }
    catch (e: Exception) { System.err.println("Airlift: ${e.message}"); exitCode = 1 }
    finally { SshAuthentication.clearPassword() }
    if (exitCode != 0) kotlin.system.exitProcess(exitCode)
}

