package iris.tools.airlift.monitor

internal data class DirectoryInventory(val sourceRoot: String?, val scope: String, val files: Set<String>,
    val directories: Set<String>, val otherEntries: Set<String>)
