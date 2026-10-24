package iris.tools.airlift

import iris.tools.airlift.io.JvmFileAccess
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.test.*

class FileAccessTest {
    @Test fun `UTF8 logs preserve unicode CR LF and flush and truncate old contents`() {
        val root = JvmFileAccess.temporaryDirectory("airlift-io-")
        try {
            val file = root.resolve("журнал с пробелами.log")
            Files.writeString(file, "old contents that must disappear")
            val content = "Файл 😀\r42%\nготово\u0000"
            JvmFileAccess.bufferedWriter(file).use { writer ->
                content.forEach { writer.write(it.code) }
                writer.flush()
                assertEquals(content, Files.readString(file))
            }
            assertEquals(content, JvmFileAccess.readText(file))
            JvmFileAccess.bufferedWriter(file).use { it.write("x") }
            assertEquals("x", Files.readString(file))
        } finally { JvmFileAccess.deleteOwnedDirectory(root) }
    }

    @Test fun `malformed UTF8 is rejected instead of silently changing paths`() {
        val root = JvmFileAccess.temporaryDirectory("airlift-io-")
        try {
            val file = root.resolve("bad")
            Files.write(file, byteArrayOf(0xc3.toByte(), 0x28))
            assertFails { JvmFileAccess.readText(file) }
        } finally { JvmFileAccess.deleteOwnedDirectory(root) }
    }

    @Test fun `connection properties remain compatible with java streams`() {
        val root = JvmFileAccess.temporaryDirectory("airlift-io-")
        try {
            val file = root.resolve("connection")
            JvmFileAccess.createPrivateFile(file)
            val expected = Properties().apply {
                setProperty("from", "user@host:/данные/with space")
                setProperty("token", "dummy=test\\token")
                setProperty("protocol", "3")
            }
            JvmFileAccess.outputStream(file).use { expected.store(it, "test") }
            val legacy = Properties().apply { Files.newInputStream(file).use { load(it) } }
            val current = Properties().apply { JvmFileAccess.inputStream(file).use { load(it) } }
            assertEquals(expected, legacy)
            assertEquals(expected, current)
            assertFailsWith<FileAlreadyExistsException> { JvmFileAccess.createPrivateFile(file) }
            assertEquals(expected, Properties().apply { JvmFileAccess.inputStream(file).use { load(it) } })
            if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
            }
        } finally { JvmFileAccess.deleteOwnedDirectory(root) }
    }

    @Test fun `separate file streams can write concurrently without sharing buffers`() {
        val root = JvmFileAccess.temporaryDirectory("airlift-io-")
        try {
            val futures = (1..16).map { id ->
                java.util.concurrent.CompletableFuture.runAsync {
                    val file = root.resolve("$id")
                    val content = "worker $id 😀\r\n".repeat(1000)
                    JvmFileAccess.bufferedWriter(file).use { it.write(content) }
                    assertEquals(content, JvmFileAccess.readText(file))
                }
            }
            futures.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        } finally { JvmFileAccess.deleteOwnedDirectory(root) }
    }

    @Test fun `owned directory cleanup does not follow a link outside its root`() {
        val root = JvmFileAccess.temporaryDirectory("airlift-io-")
        val outside = JvmFileAccess.temporaryDirectory("airlift-outside-")
        try {
            Files.writeString(outside.resolve("keep"), "untouched")
            try { Files.createSymbolicLink(root.resolve("link"), outside) }
            catch (_: java.nio.file.FileSystemException) { return } // Windows may require symlink privileges.
            catch (_: UnsupportedOperationException) { return }
            JvmFileAccess.deleteOwnedDirectory(root)
            assertEquals("untouched", Files.readString(outside.resolve("keep")))
        } finally {
            if (Files.exists(root)) JvmFileAccess.deleteOwnedDirectory(root)
            JvmFileAccess.deleteOwnedDirectory(outside)
        }
    }
}
