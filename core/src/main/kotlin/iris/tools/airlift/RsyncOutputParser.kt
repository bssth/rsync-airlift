package iris.tools.airlift

/** One parser per rsync attempt; --stats counters are final, not real-time network counters. */
class RsyncOutputParser {
    private var latest = TransferProgress(0, null, null)
    private var matched: Long? = null
    private var literal: Long? = null
    private val statistic = Regex("""^(Literal data|Matched data):\s+([\d,]+) bytes\s*$""")

    fun parse(record: String): TransferProgress? {
        ProgressParser.parse(record)?.let {
            latest = it.copy(matchedBytes = matched, literalBytes = literal)
            return latest
        }
        val match = statistic.matchEntire(record.trim()) ?: return null
        val bytes = match.groupValues[2].replace(",", "").toLongOrNull() ?: return null
        if (match.groupValues[1] == "Matched data") matched = bytes else literal = bytes
        latest = latest.copy(matchedBytes = matched, literalBytes = literal)
        return latest
    }
}
