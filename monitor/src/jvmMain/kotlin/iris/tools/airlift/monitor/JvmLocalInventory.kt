package iris.tools.airlift.monitor

import java.nio.file.Files
import kotlinx.io.files.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

/** JVM-only inventory preserves lstat sizes and never descends into directory symlinks. */
internal actual suspend fun readLocalInventory(source: String): FileInventory = kotlinx.coroutines.withContext(preparationDispatcher()) {
    val root = Path(source).absolutePath(normalize = true).toNioPath()
    val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!attributes.isDirectory) {
        require(attributes.isRegularFile || attributes.isSymbolicLink) { "Not a file: $source" }
        return@withContext FileInventory(true, mapOf(root.fileName.toString() to attributes.size()))
    }
    val sizes = linkedMapOf<String, Long>()
    val directories = linkedSetOf<String>()
    val others = linkedSetOf<String>()
    Files.walk(root).use { paths -> paths.forEach { path ->
        val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val relative = root.relativize(path).toString().replace('\\', '/')
        when {
            attrs.isRegularFile || attrs.isSymbolicLink -> sizes[relative] = attrs.size()
            attrs.isDirectory -> directories.add(relative)
            else -> others.add(relative)
        }
    } }
    return@withContext FileInventory(false, sizes, directories, others)
}
