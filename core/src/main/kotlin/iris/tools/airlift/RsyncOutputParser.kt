package iris.tools.airlift

/** One parser per rsync attempt; --stats counters are final, not real-time network counters. */
class RsyncOutputParser(private val files: Set<String> = emptySet()) {
    private var latest = TransferProgress(0, null, null)
    private var matched: Long? = null
    private var literal: Long? = null
    private val statistic = Regex("""^(Literal data|Matched data):\s+([\d,]+) bytes\s*$""")

    fun parse(record: String): TransferProgress? {
        val name = Regex("""\\#([0-7]{3})""").replace(record.removePrefix("AIRLIFT_FILE:").removePrefix("./")) { it.groupValues[1].toInt(8).toChar().toString() }
        if (record.startsWith("AIRLIFT_FILE:") && name in files) {
            latest = latest.copy(currentFile = name)
            return latest
        }
        ProgressParser.parse(record)?.let {
            val transferred = Regex("xfr#(\\d+)").find(record)?.groupValues?.get(1)?.toIntOrNull()
            latest = it.copy(matchedBytes = matched, literalBytes = literal, currentFile = latest.currentFile,
                transferredFiles = transferred ?: latest.transferredFiles)
            return latest
        }
        val match = statistic.matchEntire(record.trim()) ?: return null
        val bytes = match.groupValues[2].replace(",", "").toLongOrNull() ?: return null
        if (match.groupValues[1] == "Matched data") matched = bytes else literal = bytes
        latest = latest.copy(matchedBytes = matched, literalBytes = literal)
        return latest
    }
}
