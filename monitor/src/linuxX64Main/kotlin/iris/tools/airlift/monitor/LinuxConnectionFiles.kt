@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.*

internal actual fun monitorToken(): String {
    val fd = open("/dev/urandom", O_RDONLY or O_CLOEXEC)
    if (fd < 0) linuxError("open random source")
    val bytes = ByteArray(32)
    try {
        var offset = 0
        while (offset < bytes.size) {
            val count = bytes.usePinned { read(fd, it.addressOf(offset), (bytes.size - offset).toULong()) }
            if (count < 0 && errno == EINTR) continue
            if (count <= 0) linuxError("read random source")
            offset += count.toInt()
        }
    } finally { close(fd) }
    return bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
internal actual fun readConnectionFile(path: Path): Map<String, String> {
    val fd = open(path.toString(), O_RDONLY or O_CLOEXEC or O_NOFOLLOW)
    if (fd < 0) linuxError("open connection file")
    val buffer = Buffer()
    val bytes = ByteArray(4096)
    try { while (true) {
        val count = bytes.usePinned { read(fd, it.addressOf(0), bytes.size.toULong()) }
        if (count < 0 && errno == EINTR) continue
        if (count < 0) linuxError("read connection file")
        if (count == 0L) break
        buffer.write(bytes, 0, count.toInt())
        require(buffer.size <= 16L * 1024 * 1024) { "Connection file is too large" }
    } } finally { close(fd) }
    return decodeConnectionProperties(buffer.readByteArray())
}
internal actual fun writeConnectionFile(path: Path, values: Map<String, String>) {
    path.absolutePath().parent?.let { SystemFileSystem.createDirectories(it) }
    val fd = open(path.toString(), O_WRONLY or O_CREAT or O_EXCL or O_CLOEXEC or O_NOFOLLOW, 384u)
    if (fd < 0) {
        if (errno == EEXIST) error("Connection file already exists: ${path.absolutePath()}. Use --attach or --endpoint with a different file.")
        linuxError("create connection file")
    }
    try {
        if (fchmod(fd, 384u) != 0) linuxError("protect connection file")
        val bytes = encodeConnectionProperties(values)
        var offset = 0
        while (offset < bytes.size) {
            val count = bytes.usePinned { write(fd, it.addressOf(offset), (bytes.size - offset).toULong()) }
            if (count < 0 && errno == EINTR) continue
            if (count <= 0) linuxError("write connection file")
            offset += count.toInt()
        }
    } catch (error: Exception) { unlink(path.toString()); throw error }
    finally { close(fd) }
}
internal actual fun deleteConnectionFile(path: Path) {
    if (unlink(path.toString()) != 0 && errno != ENOENT) linuxError("delete connection file")
}
