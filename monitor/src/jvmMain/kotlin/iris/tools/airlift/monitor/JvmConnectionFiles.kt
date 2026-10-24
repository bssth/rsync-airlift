package iris.tools.airlift.monitor

import kotlinx.io.files.Path
import java.nio.file.FileAlreadyExistsException
import java.util.Properties
import java.util.UUID

internal actual fun monitorToken(): String = UUID.randomUUID().toString()
internal actual fun readConnectionFile(path: Path): Map<String, String> = Properties().apply {
    MonitorFiles.inputStream(path).use { load(it) }
}.let { properties -> properties.stringPropertyNames().associateWith { properties.getProperty(it) } }

internal actual fun writeConnectionFile(path: Path, values: Map<String, String>) {
    path.absolutePath().parent?.let { MonitorFiles.createDirectories(it) }
    try { MonitorFiles.createPrivateFile(path) }
    catch (error: FileAlreadyExistsException) {
        throw IllegalStateException("Connection file already exists: ${path.absolutePath()}. " +
            "Use --attach to view that run, or --endpoint with a different file for a new run. " +
            "If it is stale, remove it only after verifying the old dispatcher has stopped.", error)
    }
    try {
        val properties = Properties().apply { values.forEach { (key, value) -> setProperty(key, value) } }
        MonitorFiles.outputStream(path).use { properties.store(it, "Rsync Airlift local monitor connection") }
    } catch (error: Exception) { MonitorFiles.deleteIfExists(path); throw error }
}
internal actual fun deleteConnectionFile(path: Path) { MonitorFiles.deleteIfExists(path) }
