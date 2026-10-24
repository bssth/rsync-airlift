@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import platform.posix.*

internal actual suspend fun readLocalInventory(source: String): FileInventory = withContext(preparationDispatcher()) {
    val root = Path(source).absolutePath(normalize = true)
    val sizes = linkedMapOf<String, Long>()
    val directories = linkedSetOf<String>()
    val others = linkedSetOf<String>()
    fun metadata(path: Path): Pair<Int, Long> = memScoped {
        val info = alloc<stat>()
        if (lstat(path.toString(), info.ptr) != 0) linuxError("lstat $path")
        (info.st_mode.toInt() and S_IFMT) to info.st_size
    }
    val (mode, size) = metadata(root)
    if (mode != S_IFDIR) {
        require(mode == S_IFREG || mode == S_IFLNK) { "Not a file: $source" }
        return@withContext FileInventory(true, mapOf(root.name to size))
    }
    suspend fun visit(path: Path, relative: String) {
        currentCoroutineContext().ensureActive()
        val (type, length) = metadata(path)
        when (type) {
            S_IFREG, S_IFLNK -> sizes[relative] = length
            S_IFDIR -> {
                directories += relative
                val descriptor = open(path.toString(), O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
                if (descriptor < 0) linuxError("open directory $path")
                val directory = fdopendir(descriptor) ?: run { close(descriptor); linuxError("fdopendir $path") }
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        iris.tools.airlift.monitor.posix.airlift_clear_errno()
                        val entry = readdir(directory)
                        if (entry == null) { if (errno != 0) linuxError("readdir $path"); break }
                        val name = entry.pointed.d_name.toKString()
                        if (name != "." && name != "..") visit(Path(path, name), if (relative.isEmpty()) name else "$relative/$name")
                    }
                } finally { closedir(directory) }
            }
            else -> others += relative
        }
    }
    visit(root, "")
    FileInventory(false, sizes, directories, others)
}
