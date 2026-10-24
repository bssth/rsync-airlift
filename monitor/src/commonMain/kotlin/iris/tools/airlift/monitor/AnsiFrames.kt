package iris.tools.airlift.monitor

/** A terminal backend can supply wcwidth; the default covers common wide glyph ranges. */
internal class AnsiFrames(private val columns: (Int) -> Int = ::ansiColumnWidth) {
    private data class Cell(val text: String = " ", val foreground: MonitorColor = MonitorColor.ANSI.WHITE,
        val background: MonitorColor = MonitorColor.ANSI.BLACK, val continuation: Boolean = false)
    private var previous: List<List<Cell>>? = null
    private var size: MonitorSize? = null

    fun encode(frame: MonitorFrame): String {
        val width = frame.size.columns
        val height = frame.size.rows
        if (width <= 0 || height <= 0) return ""
        val cells = List(height) { MutableList(width) { Cell() } }
        for (run in frame.runs) {
            if (run.y !in cells.indices) continue
            val limit = minOf(width, run.x + run.columns)
            var x = run.x
            var index = 0
            while (index < run.text.length && x < limit) {
                val first = run.text[index++]
                val text: String
                val code: Int
                if (first.isHighSurrogate() && index < run.text.length && run.text[index].isLowSurrogate()) {
                    val second = run.text[index++]
                    code = 0x10000 + ((first.code - 0xd800) shl 10) + second.code - 0xdc00
                    text = "$first$second"
                } else { code = first.code; text = if (first.isSurrogate()) "�" else first.toString() }
                val count = columns(code).coerceIn(0, 2)
                if (count == 0) {
                    var base = x - 1
                    while (base >= run.x && cells[run.y][base].continuation) base--
                    if (base >= run.x) cells[run.y][base] = cells[run.y][base].let { it.copy(text = it.text + text) }
                    continue
                }
                if (x + count > limit) break
                cells[run.y][x] = Cell(text, run.foreground, run.background)
                if (count == 2) cells[run.y][x + 1] = Cell("", run.foreground, run.background, true)
                x += count
            }
            if (run.pad) while (x < limit) cells[run.y][x++] = Cell(" ", run.foreground, run.background)
        }
        val full = size != frame.size || previous == null
        val old = previous
        val output = buildString {
            if (full) append("\u001b[0m\u001b[2J")
            var foreground: MonitorColor? = null
            var background: MonitorColor? = null
            for (y in 0 until height) {
                var x = 0
                while (x < width) {
                    if (!full && cells[y][x] == old!![y][x]) { x++; continue }
                    // A cursor must never begin a patch in the second column of a wide glyph.
                    val start = if (cells[y][x].continuation) (x - 1).coerceAtLeast(0) else x
                    var end = x + 1
                    while (end < width && (full || cells[y][end] != old!![y][end])) end++
                    if (end < width && cells[y][end].continuation) end++
                    append("\u001b[${y + 1};${start + 1}H")
                    for (column in start until end) {
                        val cell = cells[y][column]
                        if (cell.continuation) continue
                        if (cell.foreground != foreground) { append(cell.foreground.ansi(false)); foreground = cell.foreground }
                        if (cell.background != background) { append(cell.background.ansi(true)); background = cell.background }
                        append(cell.text)
                    }
                    x = end
                }
            }
        }
        previous = cells
        size = frame.size
        return output
    }
}

private fun MonitorColor.ansi(background: Boolean): String = when (this) {
    is MonitorColor.RGB -> "\u001b[${if (background) 48 else 38};2;$red;$green;${blue}m"
    is MonitorColor.ANSI -> {
        val number = when (this) {
            MonitorColor.ANSI.BLACK -> 0; MonitorColor.ANSI.RED -> 1; MonitorColor.ANSI.GREEN -> 2
            MonitorColor.ANSI.YELLOW -> 3; MonitorColor.ANSI.BLUE -> 4; MonitorColor.ANSI.CYAN -> 6; MonitorColor.ANSI.WHITE -> 7
        }
        "\u001b[${(if (background) 40 else 30) + number}m"
    }
}

internal fun ansiColumnWidth(code: Int): Int = when {
    code in 0x300..0x36f || code in 0xfe00..0xfe0f || code == 0x200d -> 0
    code in 0x1100..0x115f || code in 0x2e80..0xa4cf || code in 0xac00..0xd7a3 ||
        code in 0xf900..0xfaff || code in 0xfe10..0xfe19 || code in 0xfe30..0xfe6f ||
        code in 0xff00..0xff60 || code in 0xffe0..0xffe6 || code in 0x1f300..0x1faff || code in 0x20000..0x3fffd -> 2
    else -> 1
}
