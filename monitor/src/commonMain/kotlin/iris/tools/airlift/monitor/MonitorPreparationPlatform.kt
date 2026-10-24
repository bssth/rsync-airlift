package iris.tools.airlift.monitor

import iris.tools.airlift.AirliftConfig
import kotlinx.io.files.Path

internal expect fun hasInteractiveConsole(): Boolean
internal expect fun readConsolePassword(prompt: String): CharArray?
internal expect fun confirmationColorsEnabled(): Boolean
internal expect fun askTransferConfirmation(text: String, swing: Boolean): Boolean

internal expect fun preparationProcesses(): iris.tools.airlift.process.ProcessLauncher
internal expect fun preparationDispatcher(): kotlinx.coroutines.CoroutineDispatcher
internal expect suspend fun readLocalInventory(source: String): FileInventory
