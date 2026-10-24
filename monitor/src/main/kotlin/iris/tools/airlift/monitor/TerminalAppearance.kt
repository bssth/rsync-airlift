package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.swing.AWTTerminalFontConfiguration
import com.googlecode.lanterna.terminal.swing.SwingTerminalFontConfiguration
import java.awt.Font
import java.awt.GraphicsEnvironment

/** Swing settings do not affect the font of a real terminal or SSH client. */
internal object TerminalAppearance {
    fun createScreen(title: String, swing: Boolean = false): Screen {
        return try {
            if (swing) factory(title, true).createScreen()
            else if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
                TerminalScreen(withSgrMouse(NativeConsoleTerminal.open()))
            else TerminalScreen(withSgrMouse(factory(title, false).createTerminal()))
        } catch (e: Exception) {
            throw IllegalStateException("Cannot open ${if (swing) "Swing window" else "interactive terminal"}. " +
                "Run the installed launcher in a terminal (SSH: ssh -t). " +
                "For a graphical preview, add --swing. ${e.message}", e)
        }
    }

    fun factory(title: String, swing: Boolean = false): DefaultTerminalFactory {
        val factory = DefaultTerminalFactory().setForceTextTerminal(!swing).setPreferTerminalEmulator(swing)
            .setMouseCaptureMode(com.googlecode.lanterna.terminal.MouseCaptureMode.CLICK)
        if (!swing) return factory
        val size = (System.getenv("AIRLIFT_FONT_SIZE")?.toIntOrNull() ?: 18).coerceIn(12, 40)
        val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val candidates = listOfNotNull(System.getenv("AIRLIFT_FONT"), "Consolas", "Cascadia Mono", "DejaVu Sans Mono", Font.MONOSPACED)
        val family = candidates.firstOrNull { it in installed } ?: Font.MONOSPACED
        val font = Font(family, Font.PLAIN, size)
        val fonts = SwingTerminalFontConfiguration(
            true, AWTTerminalFontConfiguration.BoldMode.EVERYTHING_BUT_SYMBOLS,
            font, Font(Font.MONOSPACED, Font.PLAIN, size),
        )
        return factory
            .setTerminalEmulatorTitle(title)
            .setInitialTerminalSize(TerminalSize(110, 30))
            .setTerminalEmulatorFontConfiguration(fonts)
    }
}
