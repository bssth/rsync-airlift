package iris.tools.airlift

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlin.test.*

class DefaultSshFilesTest {
    @Test fun `shared file implementation writes raw UTF8 buffers flushes and truncates`() = runBlocking<Unit> {
        val files = DefaultSshFiles()
        val directory = files.temporaryDirectory("airlift-shared-files-")
        try {
            val nested = Path(directory, "nested")
            files.createDirectories(nested)
            val log = Path(nested, "журнал.log")
            val bytes = "Файл 😀\r50%\nготово\u0000".encodeToByteArray()
            val sink = files.log(log)
            try {
                // Byte sinks must not decode independently written fragments of one UTF-8 character.
                bytes.forEach { sink.write(byteArrayOf(it)) }
                sink.flush()
                assertContentEquals(bytes, Files.readAllBytes(java.nio.file.Path.of(log.toString())))
                assertEquals(bytes.decodeToString().takeLast(7), files.readTail(log, 7))
            } finally { sink.close() }
            val replacement = files.log(log)
            try { replacement.write("x".encodeToByteArray()) } finally { replacement.close() }
            assertEquals("x", files.readTail(log, 100))
        } finally { files.deleteOwnedDirectory(directory) }
        assertFalse(Files.exists(java.nio.file.Path.of(directory.toString())))
    }

    @Test fun `temporary SSH directories are unique and private where POSIX permissions are available`() = runBlocking<Unit> {
        val files = DefaultSshFiles()
        val paths = coroutineScope { (1..16).map { async(Dispatchers.Default) { files.temporaryDirectory("airlift-unique-") } }.awaitAll() }
        try {
            assertEquals(paths.size, paths.toSet().size)
            paths.forEach { path ->
                val nio = java.nio.file.Path.of(path.toString())
                if (Files.getFileStore(nio).supportsFileAttributeView("posix")) {
                    assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(nio))
                }
            }
        } finally { paths.forEach { files.deleteOwnedDirectory(it) } }
    }

    @Test fun `shared SSH cleanup does not follow a directory symlink`() = runBlocking<Unit> {
        val files = DefaultSshFiles()
        val directory = files.temporaryDirectory("airlift-owned-")
        val outside = Files.createTempDirectory("airlift-outside-")
        try {
            Files.writeString(outside.resolve("keep"), "untouched")
            val link = java.nio.file.Path.of(directory.toString()).resolve("link")
            try { Files.createSymbolicLink(link, outside) }
            catch (_: java.nio.file.FileSystemException) { return@runBlocking }
            catch (_: UnsupportedOperationException) { return@runBlocking }
            files.deleteOwnedDirectory(directory)
            assertEquals("untouched", Files.readString(outside.resolve("keep")))
        } finally {
            if (Files.exists(java.nio.file.Path.of(directory.toString()))) files.deleteOwnedDirectory(directory)
            outside.toFile().deleteRecursively()
        }
    }
}
