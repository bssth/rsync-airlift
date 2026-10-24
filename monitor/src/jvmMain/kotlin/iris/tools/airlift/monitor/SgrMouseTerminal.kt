package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.input.*
import com.googlecode.lanterna.terminal.Terminal
import com.googlecode.lanterna.terminal.ansi.ANSITerminal

/** SGR uses ASCII coordinates, avoiding legacy X10 bytes breaking UTF-8 after column 95. */
internal fun withSgrMouse(terminal: Terminal): Terminal {
    if (terminal !is ANSITerminal) return terminal // Swing has native mouse events.
    terminal.inputDecoder.addProfile(KeyDecodingProfile { listOf(SgrMousePattern()) })
    return object : Terminal by terminal {
        private var enabled = false
        private fun mode(on: Boolean) {
            terminal.putString("\u001b[?1006${if (on) 'h' else 'l'}")
            terminal.flush()
            enabled = on
        }
        override fun enterPrivateMode() { terminal.enterPrivateMode(); mode(true) }
        override fun exitPrivateMode() {
            try { if (enabled) mode(false) } finally { terminal.exitPrivateMode() }
        }
        override fun close() {
            try { if (enabled) mode(false) } finally { terminal.close() }
        }
    }
}

internal class SgrMousePattern : CharacterPattern {
    override fun match(sequence: List<Char>): CharacterPattern.Matching? {
        val text = sequence.joinToString("")
        val prefix = "\u001b[<"
        if (text.length < prefix.length) return if (prefix.startsWith(text)) CharacterPattern.Matching.NOT_YET else null
        if (!text.startsWith(prefix) || text.length > 64) return null
        val body = text.substring(prefix.length)
        val terminator = body.lastOrNull()
        if (terminator != 'M' && terminator != 'm') {
            return if (body.all { it in '0'..'9' || it == ';' }) CharacterPattern.Matching.NOT_YET else null
        }
        val fields = body.dropLast(1).split(';').map { it.toIntOrNull() ?: return null }
        if (fields.size != 3 || fields[0] < 0 || fields[1] <= 0 || fields[2] <= 0) return null
        val code = fields[0]
        val button = (code and 3) + 1
        val type = when {
            code and 64 != 0 -> if (code and 1 == 0) MouseActionType.SCROLL_UP else MouseActionType.SCROLL_DOWN
            terminator == 'm' -> MouseActionType.CLICK_RELEASE
            code and 32 != 0 -> if (button == 4) MouseActionType.MOVE else MouseActionType.DRAG
            else -> MouseActionType.CLICK_DOWN
        }
        return CharacterPattern.Matching(MouseAction(type, if (code and 64 != 0) if (code and 1 == 0) 4 else 5 else button,
            TerminalPosition(fields[1] - 1, fields[2] - 1)))
    }
}
