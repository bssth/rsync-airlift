package iris.tools.airlift.monitor

import iris.tools.airlift.TransferDirection

internal data class TransferRoots(val host: String, val remoteRoot: String, val localRoot: String, val direction: TransferDirection) {
    companion object {
        fun parseSources(paths: List<String>): List<TransferRoots> {
            require(paths.size >= 2) { "Expected SOURCE [SOURCE ...] DESTINATION" }
            val roots = paths.dropLast(1).map { parse(listOf(it, paths.last())) }
            require(roots.all { it.host == roots.first().host && it.direction == roots.first().direction }) {
                "All sources must be local, or remote sources on the same SSH host"
            }
            return roots
        }
        fun parse(paths: List<String>): TransferRoots {
            require(paths.size == 2) { "Expected SOURCE DESTINATION, with exactly one remote path user@host:/directory/" }
            fun remote(path: String): Pair<String, String>? {
                if (Regex("^[A-Za-z]:[\\\\/].*").matches(path)) return null
                if (':' !in path) return null
                val match = Regex("^((?:[^@/:\\s]+@)?)(?:\\[([^]]+)\\]|([^:/\\s]+)):(/.*)$").matchEntire(path)
                    ?: error("Invalid remote path: $path. Use user@host:/absolute/directory/ (or ./ for a local name containing a colon).")
                val host = match.groupValues[1] + match.groupValues[2].ifEmpty { match.groupValues[3] }
                return host to match.groupValues[4]
            }
            val source = if (paths[0].startsWith("./") || paths[0].startsWith('/')) null else remote(paths[0])
            val destination = if (paths[1].startsWith("./") || paths[1].startsWith('/')) null else remote(paths[1])
            require((source == null) != (destination == null)) { "Exactly one endpoint must be remote (user@host:/directory/)." }
            return if (source != null) TransferRoots(source.first, source.second, paths[1], TransferDirection.DOWNLOAD)
                else TransferRoots(destination!!.first, destination.second, paths[0], TransferDirection.UPLOAD)
        }
    }
}

internal data class LaunchArguments(val options: Map<String, String>, val enabled: Set<String>, val paths: List<String>) {
    companion object {
        fun parse(args: List<String>): LaunchArguments {
            val flags = setOf("--demo", "--serve-demo", "--serve", "--run", "--null", "--swing")
            val names = setOf("--attach", "--endpoint", "--files", "--workers", "--port", "--identity", "--rsync")
            val options = mutableMapOf<String, String>()
            val enabled = mutableSetOf<String>()
            val paths = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val argument = args[i++]
                if (argument == "--") { paths.addAll(args.drop(i)); break }
                if (!argument.startsWith('-')) { paths.add(argument); continue }
                if (argument in flags) { require(enabled.add(argument)) { "Duplicate $argument" }; continue }
                val key = argument.substringBefore('=')
                require(key in names) { "Unknown option: $key. Transfer syntax: [options] SOURCE DESTINATION" }
                val value = if ('=' in argument) argument.substringAfter('=') else {
                    require(i < args.size) { "Missing value for $key" }
                    args[i++]
                }
                require(value.isNotEmpty() && options.put(key, value) == null) { "Empty or duplicate $key" }
            }
            if (paths.isNotEmpty() && enabled.none { it in setOf("--run", "--serve", "--demo", "--serve-demo") }) enabled.add("--run")
            if ("--attach" in options) {
                require(paths.isEmpty() && options.size == 1 && enabled.all { it == "--swing" }) { "--attach accepts only --swing" }
            } else {
                require(enabled.count { it in setOf("--run", "--serve", "--demo", "--serve-demo") } == 1) { "Choose one mode or provide SOURCE DESTINATION" }
                if (enabled.any { it in setOf("--demo", "--serve-demo") }) require(paths.isEmpty()) { "Demo does not accept transfer paths" }
                else {
                    TransferRoots.parseSources(paths)
                }
                require("--swing" !in enabled || enabled.any { it in setOf("--demo", "--run") }) { "--swing requires an interactive mode" }
            }
            return LaunchArguments(options, enabled, paths)
        }
    }
}
