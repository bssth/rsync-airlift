package iris.tools.airlift.monitor

/** Java properties-compatible ASCII encoding, independent of platform streams. */
internal fun encodeConnectionProperties(values: Map<String, String>): ByteArray {
    fun escaped(value: String): String = buildString {
        for (char in value) when (char) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            ' ', '=', ':', '#', '!' -> { append('\\'); append(char) }
            else -> if (char.code < 32 || char.code > 126) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
        }
    }
    return values.entries.joinToString("\n", postfix = "\n") { escaped(it.key) + "=" + escaped(it.value) }.encodeToByteArray()
}

internal fun decodeConnectionProperties(bytes: ByteArray): Map<String, String> {
    val text = bytes.map { (it.toInt() and 255).toChar() }.joinToString("")
    val lines = mutableListOf<String>()
    var pending = ""
    for (raw in text.lineSequence()) {
        pending += if (pending.isEmpty()) raw else raw.trimStart(' ', '\t', '\u000c')
        if (pending.takeLastWhile { it == '\\' }.length % 2 == 1) pending = pending.dropLast(1)
        else { lines += pending; pending = "" }
    }
    if (pending.isNotEmpty()) lines += pending
    fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val char = value[i++]
            if (char != '\\') { append(char); continue }
            if (i == value.length) break
            when (val next = value[i++]) {
                't' -> append('\t'); 'n' -> append('\n'); 'r' -> append('\r'); 'f' -> append('\u000c')
                'u' -> { require(i + 4 <= value.length); append(value.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                else -> append(next)
            }
        }
    }
    return buildMap {
        for (raw in lines) {
            val line = raw.trimStart(' ', '\t', '\u000c')
            if (line.isEmpty() || line[0] == '#' || line[0] == '!') continue
            var end = 0
            while (end < line.length) {
                if (line[end] == '\\') { end += 2; continue }
                if (line[end] in "=: \t\u000c") break
                end++
            }
            end = end.coerceAtMost(line.length)
            var start = end
            while (start < line.length && line[start] in " \t\u000c") start++
            if (start < line.length && line[start] in "=:") start++
            while (start < line.length && line[start] in " \t\u000c") start++
            put(unescape(line.substring(0, end)), unescape(line.substring(start)))
        }
    }
}
