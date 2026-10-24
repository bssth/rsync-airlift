package iris.tools.airlift.io

import kotlinx.io.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Portable file contents. Platform adapters serialize filesystem calls, not stream I/O. */
internal object FileContent {
    fun source(path: String): Source = SystemFileSystem.source(Path(path)).buffered()
    fun sink(path: String): Sink = SystemFileSystem.sink(Path(path)).buffered()
    fun readText(source: Source): String = source.use { it.readByteArray().decodeToString(throwOnInvalidSequence = true) }
    fun createDirectories(path: String) = SystemFileSystem.createDirectories(Path(path))
    fun deleteIfExists(path: String) = SystemFileSystem.delete(Path(path), mustExist = false)
}
