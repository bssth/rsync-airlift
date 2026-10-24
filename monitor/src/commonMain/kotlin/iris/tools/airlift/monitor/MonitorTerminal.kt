package iris.tools.airlift.monitor

/** Terminal-independent palette. ANSI colors follow the user's terminal theme. */
sealed interface MonitorColor {
    enum class ANSI : MonitorColor { BLACK, RED, GREEN, YELLOW, BLUE, CYAN, WHITE }
    data class RGB(val red: Int, val green: Int, val blue: Int) : MonitorColor {
        init { require(listOf(red, green, blue).all { it in 0..255 }) }
    }
}

enum class MonitorKey { EOF, ArrowUp, ArrowDown, ArrowLeft, ArrowRight, PageUp, PageDown, Home, End, F2, Enter, Tab, Character, Other }
enum class MonitorMouseAction { CLICK_DOWN, SCROLL_UP, SCROLL_DOWN, OTHER }
sealed interface MonitorEvent {
    data class Key(val key: MonitorKey, val character: Char? = null) : MonitorEvent
    data class Mouse(val actionType: MonitorMouseAction, val button: Int, val x: Int, val y: Int) : MonitorEvent
}

data class MonitorSize(val columns: Int, val rows: Int)
data class TextRun(val x: Int, val y: Int, val text: String, val foreground: MonitorColor,
    val background: MonitorColor, val columns: Int, val pad: Boolean)

/** A complete frame of styled text runs over a blank background.
 * The terminal adapter clips text by display columns, including wide characters.
 */
class MonitorFrame(val size: MonitorSize) {
    private val drawing = mutableListOf<TextRun>()
    val runs: List<TextRun> get() = drawing.toList()
    internal var foregroundColor: MonitorColor = MonitorColor.ANSI.WHITE
    internal var backgroundColor: MonitorColor = MonitorColor.ANSI.BLACK
    internal fun putString(x: Int, y: Int, text: String, columns: Int = size.columns - x, pad: Boolean = false) {
        if (x < 0 || y !in 0 until size.rows || columns <= 0 || x >= size.columns) return
        val safe = text.map { if (it.code < 32 || it.code in 127..159) ' ' else it }.joinToString("")
        drawing += TextRun(x, y, safe, foregroundColor, backgroundColor, minOf(columns, size.columns - x), pad)
    }
}

/** Lifecycle and presentation belong to the platform, not to the monitor model. */
interface MonitorTerminal {
    fun start()
    fun size(): MonitorSize
    fun present(frame: MonitorFrame)
    fun pollEvent(): MonitorEvent?
    fun close()
}
