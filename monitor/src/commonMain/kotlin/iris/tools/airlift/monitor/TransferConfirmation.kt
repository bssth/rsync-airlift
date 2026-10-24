package iris.tools.airlift.monitor

/** MC shell-link paths identify a remote endpoint, not a local /sh: directory. */
internal fun normalizeEndpoint(path: String): String {
    if (!path.startsWith("/sh://")) return path
    val remainder = path.removePrefix("/sh://")
    val host = remainder.substringBefore('/')
    require(host.isNotBlank() && host.none { it.isWhitespace() || it.isISOControl() }) { "Invalid MC shell-link host" }
    require(':' !in host.substringBefore('@', "")) { "Do not embed a password in an MC shell-link path" }
    val remotePath = if ('/' in remainder) "/" + remainder.substringAfter('/') else "/"
    return "$host:$remotePath"
}

internal fun confirmTransfer(
    paths: List<String>, assumeYes: Boolean, swing: Boolean = false,
    ask: (String) -> Boolean = { text -> askTransferConfirmation(text, swing) },
): Boolean {
    if (assumeYes) return true
    fun display(path: String) = path.map { if (it.isISOControl()) ' ' else it }.joinToString("")
    val from = paths.dropLast(1).joinToString("\n      ") { display(normalizeEndpoint(it)) }
    val to = display(normalizeEndpoint(paths.last()))
    return ask("From: $from\nTo:   $to\n\nDestination files may be updated according to your rsync options.")
}

/** Style labels without changing paths; reset every span so the prompt keeps normal colors. */
internal fun emphasizeConfirmation(text: String, color: Boolean = confirmationColorsEnabled()): String {
    val heading = "CONFIRM TRANSFER DIRECTION"
    if (!color) return heading + "\n\n" + text.replace("From:", "FROM:").replace("To:   ", "TO:   ")
    return "\u001b[1;33m$heading\u001b[0m\n\n" + text.lineSequence().joinToString("\n") { line ->
        when {
            line.startsWith("From:") -> "\u001b[1;36mFROM:\u001b[0m" + line.removePrefix("From:")
            line.startsWith("To:") -> "\u001b[1;33mTO:\u001b[0m" + line.removePrefix("To:")
            line.startsWith("Destination files") -> "\u001b[1;33m$line\u001b[0m"
            else -> line
        }
    }
}
