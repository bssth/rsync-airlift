package iris.tools.airlift

/** Splits a quoted argument string, without evaluating shell expressions or expanding variables. */
object RsyncArguments {
    fun parse(text: String): List<String> {
        require('\u0000' !in text) { "NUL is not allowed in rsync arguments" }
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var present = false
        var i = 0
        while (i < text.length) {
            val ch = text[i++]
            when {
                quote == '\'' -> if (ch == '\'') quote = null else token.append(ch)
                ch == '\\' && quote != '\'' -> {
                    require(i < text.length) { "Trailing escape in --rsync" }
                    val next = text[i]
                    if (quote == '"' && next !in charArrayOf('"', '\\', '$', '`', '\n')) token.append(ch)
                    else { token.append(next); i++ }
                    present = true
                }
                quote == '"' -> if (ch == '"') quote = null else token.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; present = true }
                ch.isWhitespace() -> if (present) { result.add(token.toString()); token.setLength(0); present = false }
                else -> { token.append(ch); present = true }
            }
        }
        require(quote == null) { "Unclosed quote in --rsync" }
        if (present) result.add(token.toString())
        require(result.none { it == "--" || it == "-e" || it.startsWith("-e") && !it.startsWith("--") ||
            it.substringBefore('=') in setOf("--rsh", "--files-from") }) {
            "Airlift manages -e/--rsh, --files-from and source/destination; configure SSH with --port and --identity"
        }
        return result
    }
}

/** Copy policy comes exclusively from the caller; Airlift owns transport, task list and telemetry. */
object RsyncCommand {
    fun build(config: AirliftConfig, transport: String, source: String, destination: String, recursive: Boolean = false): List<String> = buildList {
        add(config.rsyncExecutable)
        addAll(config.rsyncOptions?.let(RsyncArguments::parse).orEmpty())
        addAll(listOf("--relative", "--protect-args", "--from0", "--files-from=-", "--info=progress2", "--outbuf=L", "--stats"))
        add("--info=name1")
        add("--out-format=AIRLIFT_FILE:%n")
        if (recursive) add("--recursive")
        addAll(listOf("-e", transport, "--", source, destination))
    }
}
