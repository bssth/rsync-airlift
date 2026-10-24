package iris.tools.airlift.monitor

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes

/** JVM-only inventory preserves lstat sizes and never descends into directory symlinks. */
internal fun readLocalInventory(source: String): FileInventory {
    val root = Path.of(source).toAbsolutePath().normalize()
    val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!attributes.isDirectory) {
        require(attributes.isRegularFile || attributes.isSymbolicLink) { "Not a file: $source" }
        return FileInventory(true, mapOf(root.fileName.toString() to attributes.size()))
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
    return FileInventory(false, sizes, directories, others)
}
