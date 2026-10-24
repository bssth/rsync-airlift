package iris.tools.airlift

import kotlin.math.pow

/** Parses C-locale rsync progress2 records; these describe logical file progress, not wire traffic. */
object ProgressParser {
    private val record = Regex("""^\s*([\d,]+)(B?)\s+(\d+)%\s+([\d.]+)([kKMGT]?)B/s\s+([\d?]+):([\d?]{2}):([\d?]{2}).*$""")

    fun parse(text: String): TransferProgress? {
        val match = record.matchEntire(text) ?: return null
        val (bytes, byteSuffix, percent, speed, unit, hours, minutes, seconds) = match.destructured
        val power = when (unit.uppercase()) { "K" -> 1; "M" -> 2; "G" -> 3; "T" -> 4; else -> 0 }
        // A completed rsync record reports elapsed time, not time remaining.
        val completed = text.contains("(xfr#")
        return TransferProgress(
            bytes.replace(",", "").toLongOrNull() ?: return null,
            // Traditional rsync uses binary rate units. Newer output with a B suffix has
            // configurable scaling; leave rate unknown instead of guessing its base.
            if (byteSuffix.isEmpty()) (speed.toDoubleOrNull() ?: return null) * 1024.0.pow(power.toDouble()) else null,
            if (completed || '?' in hours) null else hours.toLong() * 3600 + minutes.toLong() * 60 + seconds.toLong(),
            percent.toIntOrNull()?.coerceIn(0, 100),
        )
    }
}
