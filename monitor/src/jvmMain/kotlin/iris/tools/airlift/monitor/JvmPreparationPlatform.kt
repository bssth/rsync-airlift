package iris.tools.airlift.monitor

import iris.tools.airlift.process.JvmProcessLauncher
import iris.tools.airlift.process.ProcessLauncher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun preparationProcesses(): ProcessLauncher = JvmProcessLauncher()
internal actual fun preparationDispatcher(): CoroutineDispatcher = Dispatchers.IO
