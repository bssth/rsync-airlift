package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.io.files.Path

internal data class FileInventory(val singleFile: Boolean, val sizes: Map<String, Long>,
    val directories: Set<String> = emptySet(), val otherEntries: Set<String> = emptySet())
internal data class PreparedTransfer(val config: AirliftConfig, val tasks: List<TransferTask>,
    val inventories: List<DirectoryInventory> = emptyList())

internal suspend fun prepareSources(config: AirliftConfig, roots: List<TransferRoots>, manifest: Path? = null, nulSeparated: Boolean = false): PreparedTransfer {
    if (roots.size == 1) return prepareTransfer(config, manifest, nulSeparated,
        source = if (config.direction == TransferDirection.DOWNLOAD) roots.single().remoteRoot else roots.single().localRoot.replace('\\', '/'))
    val tasks = linkedMapOf<String, TransferTask>()
    val found = mutableSetOf<String>()
    val inventories = mutableListOf<DirectoryInventory>()
    for (root in roots) {
        val source = if (config.direction == TransferDirection.DOWNLOAD) root.remoteRoot else Path(sourceBase(root.localRoot.replace('\\', '/'))).absolutePath().toString().replace('\\', '/').let { base ->
            val raw = root.localRoot.replace('\\', '/')
            if (raw.any { it in "*?[" }) base.trimEnd('/') + "/" + raw.removePrefix(sourceBase(raw).takeUnless { it == "." } ?: "")
            else Path(raw).absolutePath().toString().replace('\\', '/') + if (raw.endsWith('/')) "/" else ""
        }
        val perConfig = if (config.direction == TransferDirection.DOWNLOAD) config.copy(remoteRoot = source)
            else config.copy(localRoot = Path(sourceBase(source)))
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

private suspend fun manifestPaths(manifest: Path, nulSeparated: Boolean): List<String> {
    val content = readManifestText(manifest)
    return (if (nulSeparated) content.split('\u0000') else content.split('\n').map { it.removeSuffix("\r") })
        .filter { it.isNotEmpty() }.onEach { TransferTask(it) }
}

/** Discovery belongs to the CLI. The dispatcher still accepts a caller-prepared list. */
internal suspend fun prepareTransfer(
    config: AirliftConfig, manifest: Path? = null, nulSeparated: Boolean = false,
    source: String = if (config.direction == TransferDirection.DOWNLOAD) config.remoteRoot else config.localRoot.toString().replace('\\', '/'),
    inventory: suspend (String) -> FileInventory = { readInventory(config, it) },
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
        else config.copy(localRoot = Path(root))
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

internal fun inventoryCommand(source: String): String {
    val path = "'" + source.replace("'", "'\"'\"'") + "'"
    // GNU find, NUL records: paths may contain spaces, tabs and newlines.
    return "if [ -L $path ] || [ -f $path ]; then printf 'F\\0'; find $path -maxdepth 0 -printf '%s\\t%f\\0'; " +
        "elif [ -d $path ]; then printf 'D\\0'; find $path -printf '%y\\t%s\\t%P\\0'; " +
        "else printf '%s\\n' 'Source does not exist' >&2; exit 1; fi"
}

internal fun decodeInventory(records: List<String>): FileInventory {
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
}
