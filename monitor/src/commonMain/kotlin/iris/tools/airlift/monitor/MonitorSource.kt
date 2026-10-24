package iris.tools.airlift.monitor

import iris.tools.airlift.*

/** UI owns neither the dispatcher nor its lifecycle. A remote adapter can implement this contract. */
interface MonitorSource {
    val title: String get() = "RSYNC AIRLIFT"
    val from: String get() = "?"
    val to: String get() = "?"
    suspend fun snapshot(): AirliftSnapshot
    suspend fun pause()
    suspend fun resume()
    suspend fun setWorkerCount(count: Int)
}

class DispatcherMonitorSource(private val dispatcher: AirliftDispatcher, override val title: String = "RSYNC AIRLIFT",
    override val from: String = dispatcher.sourceDescription) : MonitorSource {
    override val to: String get() = dispatcher.destinationDescription
    override suspend fun snapshot() = dispatcher.snapshot()
    override suspend fun pause() = dispatcher.pause()
    override suspend fun resume() = dispatcher.resume()
    override suspend fun setWorkerCount(count: Int) = dispatcher.setWorkerCount(count)
}
