package iris.tools.airlift.monitor

import kotlinx.io.files.Path

internal interface MonitorSocket {
    suspend fun read(bytes: ByteArray): Int
    suspend fun write(bytes: ByteArray)
    suspend fun flush()
    fun close()
}
internal interface MonitorListener {
    val port: Int
    suspend fun accept(): MonitorSocket
    fun close()
}
internal interface MonitorTransport {
    fun listen(): MonitorListener
    suspend fun connect(port: Int): MonitorSocket
}
internal expect fun monitorTransport(): MonitorTransport
internal expect fun monitorToken(): String
internal expect fun readConnectionFile(path: Path): Map<String, String>
internal expect fun writeConnectionFile(path: Path, values: Map<String, String>)
internal expect fun deleteConnectionFile(path: Path)
