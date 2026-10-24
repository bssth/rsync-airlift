package iris.tools.airlift.io

import kotlinx.io.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import iris.tools.airlift.platformLock

/** Serialize filesystem entry points for all adapters; independent stream I/O does not hold this lock. */
internal object FileContent {
    private val lock = platformLock()
    fun source(path: String): Source = lock.locked { SystemFileSystem.source(Path(path)).buffered() }
    fun sink(path: String): Sink = lock.locked { SystemFileSystem.sink(Path(path)).buffered() }
    fun readText(source: Source): String = source.use { it.readByteArray().decodeToString(throwOnInvalidSequence = true) }
    fun createDirectories(path: String) = lock.locked { SystemFileSystem.createDirectories(Path(path)) }
    fun deleteIfExists(path: String) = lock.locked { SystemFileSystem.delete(Path(path), mustExist = false) }
}
