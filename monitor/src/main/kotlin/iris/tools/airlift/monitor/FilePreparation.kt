package iris.tools.airlift.monitor

import iris.tools.airlift.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal data class FileInventory(val singleFile: Boolean, val sizes: Map<String, Long>,
    val directories: Set<String> = emptySet(), val otherEntries: Set<String> = emptySet())
internal data class DirectoryInventory(val sourceRoot: String?, val scope: String, val files: Set<String>,
    val directories: Set<String>, val otherEntries: Set<String>)
internal data class PreparedTransfer(val config: AirliftConfig, val tasks: List<TransferTask>,
    val inventories: List<DirectoryInventory> = emptyList())

internal fun prepareSources(config: AirliftConfig, roots: List<TransferRoots>, manifest: Path? = null, nulSeparated: Boolean = false): PreparedTransfer {
    if (roots.size == 1) return prepareTransfer(config, manifest, nulSeparated,
        source = if (config.direction == TransferDirection.DOWNLOAD) roots.single().remoteRoot else roots.single().localRoot.replace('\\', '/'))
    val tasks = linkedMapOf<String, TransferTask>()
    val found = mutableSetOf<String>()
    val inventories = mutableListOf<DirectoryInventory>()
    for (root in roots) {
        val source = if (config.direction == TransferDirection.DOWNLOAD) root.remoteRoot else Path.of(sourceBase(root.localRoot.replace('\\', '/'))).toAbsolutePath().toString().replace('\\', '/').let { base ->
            val raw = root.localRoot.replace('\\', '/')
            if (raw.any { it in "*?[" }) base.trimEnd('/') + "/" + raw.removePrefix(sourceBase(raw).takeUnless { it == "." } ?: "")
            else Path.of(raw).toAbsolutePath().toString().replace('\\', '/') + if (raw.endsWith('/')) "/" else ""
        }
        val perConfig = if (config.direction == TransferDirection.DOWNLOAD) config.copy(remoteRoot = source)
            else config.copy(localRoot = Path.of(sourceBase(source)))
        val plan = prepareTransfer(perConfig, manifest, nulSeparated, source, allowMissing = true)
        var sourceRoot = if (config.direction == TransferDirection.DOWNLOAD) plan.config.remoteRoot else plan.config.localRoot.toString().replace('\\', '/')
        // A directory argument without a trailing slash retains its directory name, as in rsync.
        val directory = source.none { it in "*?[" } && source.trimEnd('/') == sourceRoot.trimEnd('/')
        val prefix = if (directory && !source.endsWith('/')) sourceRoot.trimEnd('/').substringAfterLast('/') + "/" else ""
        if (prefix.isNotEmpty()) sourceRoot = sourceRoot.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
        inventories.addAll(plan.inventories.map { inventory ->
            fun mapped(paths: Set<String>) = paths.map { (prefix + it).trimEnd('/') }.toSet()
            DirectoryInventory(sourceRoot, prefix.trimEnd('/'), mapped(inventory.files), mapped(inventory.directories), mapped(inventory.otherEntries))
        })
        for (task in plan.tasks) {
            found.add(task.path)
            val path = prefix + task.path
            val prepared = task.copy(path = path, id = path, sourceRoot = sourceRoot)
            val previous = tasks[path]
            require(previous == null || previous == prepared) { "Different sources target the same destination: $path" }
            tasks[path] = prepared
        }
    }
    if (manifest != null) require(manifestPaths(manifest, nulSeparated).all { it in found }) { "Some --files entries were not found in any source" }
    return PreparedTransfer(config, tasks.values.sortedWith(compareByDescending<TransferTask> { it.size }.thenBy { it.path }), inventories)
}

private fun manifestPaths(manifest: Path, nulSeparated: Boolean): List<String> {
    val content = Files.readString(manifest)
    return (if (nulSeparated) content.split('\u0000') else content.split('\n').map { it.removeSuffix("\r") })
        .filter { it.isNotEmpty() }.onEach { TransferTask(it) }
}

/** Discovery belongs to the CLI. The dispatcher still accepts a caller-prepared list. */
internal fun prepareTransfer(
    config: AirliftConfig, manifest: Path? = null, nulSeparated: Boolean = false,
    source: String = if (config.direction == TransferDirection.DOWNLOAD) config.remoteRoot else config.localRoot.toString().replace('\\', '/'),
    inventory: (String) -> FileInventory = { readInventory(config, it) },
    allowMissing: Boolean = false,
): PreparedTransfer {
    val wildcard = source.indexOfFirst { it in "*?[" }
    val base = sourceBase(source)
    val mask = if (wildcard >= 0) source.removePrefix(base.takeUnless { it == "." } ?: "") else null
    val listing = inventory(base)
    require(mask == null || !listing.singleFile) { "Mask base must be a directory: $base" }
    val root = if (listing.singleFile) base.substringBeforeLast('/', ".").ifEmpty { "/" }
        .let { if (Regex("^[A-Za-z]:$").matches(it)) "$it/" else it } else base
    val selected = linkedSetOf<String>()
    if (manifest == null || mask != null) {
        val matcher = mask?.let(::fileMask)
        selected.addAll(listing.sizes.keys.filter { matcher == null || matcher.matches(it) })
    }
    if (manifest != null) {
        selected.addAll(manifestPaths(manifest, nulSeparated))
    }
    val tasks = selected.filter { !allowMissing || it in listing.sizes }.map { path ->
        TransferTask(path, listing.sizes[path] ?: error("Source file is missing or is not a file: $path"))
    }.sortedWith(compareByDescending<TransferTask> { it.size }.thenBy { it.path })
    val preparedConfig = if (config.direction == TransferDirection.DOWNLOAD) config.copy(remoteRoot = root)
        else config.copy(localRoot = Path.of(root))
    return PreparedTransfer(preparedConfig, tasks, if (listing.singleFile) emptyList() else listOf(
        DirectoryInventory(null, "", listing.sizes.keys, listing.directories, listing.otherEntries)))
}

internal fun sourceBase(source: String): String {
    val wildcard = source.indexOfFirst { it in "*?[" }
    return if (wildcard >= 0) source.substring(0, source.lastIndexOf('/', wildcard) + 1).ifEmpty { "." } else source
}

/** POSIX path masks: * and ? stay in one component; ** crosses directories. */
internal fun fileMask(mask: String): Regex {
    val result = StringBuilder("^")
    var i = 0
    while (i < mask.length) {
        when (val c = mask[i++]) {
            '*' -> if (i < mask.length && mask[i] == '*') {
                i++
                if (i < mask.length && mask[i] == '/') { i++; result.append("(?:.*/)?") } else result.append(".*")
            } else result.append("[^/]*")
            '?' -> result.append("[^/]")
            '[' -> {
                val end = mask.indexOf(']', i)
                require(end > i) { "Invalid file mask: $mask" }
                val chars = mask.substring(i, end)
                require('/' !in chars && '[' !in chars && '\\' !in chars) { "Invalid file mask: $mask" }
                result.append('[').append(if (chars.startsWith('!')) "^" + chars.drop(1) else chars).append(']')
                i = end + 1
            }
            else -> result.append(Regex.escape(c.toString()))
        }
    }
    return Regex(result.append('$').toString())
}

private fun readInventory(config: AirliftConfig, source: String): FileInventory {
    if (config.direction == TransferDirection.UPLOAD) {
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
    val command = mutableListOf(config.sshExecutable, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
        "-o", "ConnectTimeout=${config.connectTimeoutSeconds}", "-o", "ServerAliveInterval=30", "-o", "ServerAliveCountMax=3", "-p", config.port.toString())
    config.identityFile?.let { command.addAll(listOf("-i", it.toString())) }
    command.addAll(listOf(config.host, inventoryCommand(source)))
    val errors = Files.createTempFile("airlift-inventory-", ".log")
    var process: Process? = null
    try {
        process = SshAuthentication.configure(ProcessBuilder(SshAuthentication.command(command))).redirectError(errors.toFile()).start()
        val records = mutableListOf<String>()
        process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val record = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c < 0) { check(record.isEmpty()) { "Incomplete SSH file inventory" }; break }
                if (c == 0) { records.add(record.toString()); record.setLength(0) }
                else { record.append(c.toChar()); require(record.length <= 1024 * 1024) { "Invalid SSH inventory record" } }
            }
        }
        check(process.waitFor() == 0) { "Cannot collect source files over SSH: ${Files.readString(errors).takeLast(4096)}" }
        require(records.firstOrNull() in listOf("D", "F")) { "Invalid SSH file inventory" }
        val sizes = linkedMapOf<String, Long>()
        val directories = linkedSetOf<String>()
        val others = linkedSetOf<String>()
        records.drop(1).forEach { record ->
            if (records.first() == "D") {
                val fields = record.split('\t', limit = 3)
                require(fields.size == 3) { "Invalid SSH file metadata" }
                when (fields[0]) {
                    "f", "l" -> sizes[fields[2]] = fields[1].toLong()
                    "d" -> directories.add(fields[2])
                    else -> others.add(fields[2])
                }
                return@forEach
            }
            val split = record.indexOf('\t')
            require(split > 0) { "Invalid SSH file metadata" }
            sizes[record.substring(split + 1)] = record.substring(0, split).toLong()
        }
        return FileInventory(records.first() == "F", sizes, directories, others)
    } finally { process?.let { if (it.isAlive) it.destroyForcibly() }; Files.deleteIfExists(errors) }
}

internal fun inventoryCommand(source: String): String {
    val path = "'" + source.replace("'", "'\"'\"'") + "'"
    // GNU find, NUL records: paths may contain spaces, tabs and newlines.
    return "if [ -L $path ] || [ -f $path ]; then printf 'F\\0'; find $path -maxdepth 0 -printf '%s\\t%f\\0'; " +
        "elif [ -d $path ]; then printf 'D\\0'; find $path -printf '%y\\t%s\\t%P\\0'; " +
        "else printf '%s\\n' 'Source does not exist' >&2; exit 1; fi"
}
