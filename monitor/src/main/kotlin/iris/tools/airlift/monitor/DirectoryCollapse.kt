package iris.tools.airlift.monitor

import iris.tools.airlift.RsyncArguments
import iris.tools.airlift.TransferTask

private fun parents(path: String): List<String> {
    val result = mutableListOf<String>()
    var parent = path.substringBeforeLast('/', "")
    while (true) {
        result.add(parent)
        if (parent.isEmpty()) return result
        parent = parent.substringBeforeLast('/', "")
    }
}

/** Complete inventories prove coverage; selected files alone are never enough. */
internal class DirectoryCollapser(inventories: List<DirectoryInventory>, rsyncOptions: String? = null) {
    private data class Coverage(val inventory: DirectoryInventory, val counts: Map<String, Int>, val blocked: Set<String>)
    private val enabled = RsyncArguments.parse(rsyncOptions.orEmpty()).none { option ->
        option.startsWith("--delete") || option.substringBefore('=') in setOf("--remove-source-files", "--copy-links", "--copy-dirlinks", "--keep-dirlinks", "--no-recursive") ||
            (option.startsWith('-') && !option.startsWith("--") && option.drop(1).any { it in "LkK" })
    }
    private val coverage = inventories.map { inventory ->
        val counts = mutableMapOf<String, Int>()
        inventory.files.forEach { file -> parents(file).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val blocked = mutableSetOf<String>()
        // Avoid introducing unselected empty directories or special filesystem entries.
        (inventory.otherEntries + inventory.directories.filter { it.isNotEmpty() && it !in counts }).forEach { entry ->
            blocked.add(entry); blocked.addAll(parents(entry))
        }
        Coverage(inventory, counts, blocked)
    }

    fun optimize(job: TransferTask): TransferTask {
        if (!enabled) return job
        val files = job.files.ifEmpty { listOf(job.path) }
        if (files.size < 2) return job
        val selectedCounts = mutableMapOf<String, Int>()
        files.forEach { file -> parents(file).forEach { selectedCounts[it] = (selectedCounts[it] ?: 0) + 1 } }
        val candidates = linkedSetOf<String>()
        for (cover in coverage.filter { it.inventory.sourceRoot == job.sourceRoot }) {
            val coveredCounts = mutableMapOf<String, Int>()
            files.filter { it in cover.inventory.files }.forEach { file -> parents(file).forEach { coveredCounts[it] = (coveredCounts[it] ?: 0) + 1 } }
            for ((directory, count) in selectedCounts) {
                val scope = cover.inventory.scope
                if (scope.isNotEmpty() && directory != scope && !directory.startsWith("$scope/")) continue
                if (directory !in cover.blocked && cover.counts[directory] == count && coveredCounts[directory] == count && count > 1) candidates.add(directory)
            }
        }
        val chosen = mutableSetOf<String>()
        for (directory in candidates.sortedWith(compareBy<String> { if (it.isEmpty()) 0 else it.count { c -> c == '/' } + 1 }.thenBy { it })) {
            if (chosen.none { it.isEmpty() || directory.startsWith("$it/") }) chosen.add(directory)
        }
        val entries = mutableListOf<String>()
        val emitted = mutableSetOf<String>()
        for (file in files.sorted()) {
            val directory = parents(file).firstOrNull { it in chosen }
            if (directory == null) entries.add(file)
            else if (emitted.add(directory)) entries.add(if (directory.isEmpty()) "." else "$directory/")
        }
        return if (entries.size < files.size) job.copy(entries = entries) else job
    }
}
