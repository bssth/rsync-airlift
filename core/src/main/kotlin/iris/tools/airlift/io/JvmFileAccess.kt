package iris.tools.airlift.io

import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** JVM boundary for existing Path/stream APIs; not a portable public filesystem API. */
object JvmFileAccess {
    private val fileSystemLock = Any()

    fun inputStream(path: Path): InputStream = synchronized(fileSystemLock) {
        FileContent.source(path.toString()).asInputStream()
    }

    fun outputStream(path: Path): OutputStream = synchronized(fileSystemLock) {
        FileContent.sink(path.toString()).asOutputStream()
    }

    fun readText(path: Path): String = FileContent.readText(synchronized(fileSystemLock) { FileContent.source(path.toString()) })

    fun bufferedWriter(path: Path): BufferedWriter = OutputStreamWriter(outputStream(path), Charsets.UTF_8.newEncoder()).buffered()

    fun createDirectories(path: Path) = synchronized(fileSystemLock) { FileContent.createDirectories(path.toString()) }
    fun deleteIfExists(path: Path) = synchronized(fileSystemLock) { FileContent.deleteIfExists(path.toString()) }

    // Atomic creation, permissions and no-follow traversal stay in the JVM adapter.
    fun temporaryFile(prefix: String, suffix: String): Path = Files.createTempFile(prefix, suffix)
    fun temporaryDirectory(prefix: String): Path = Files.createTempDirectory(prefix)
    fun temporaryDirectory(parent: Path, prefix: String): Path = Files.createTempDirectory(parent, prefix)

    fun createPrivateFile(path: Path) {
        try { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
        catch (_: UnsupportedOperationException) { Files.createFile(path) }
    }

    /** Only for a unique directory created and owned by this process; never follows links. */
    fun deleteOwnedDirectory(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
