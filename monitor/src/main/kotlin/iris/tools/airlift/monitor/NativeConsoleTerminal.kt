package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.terminal.ansi.ANSITerminal
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.Attributes
import java.io.FilterInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** JLine owns Windows console modes; Lanterna owns ANSI rendering and key decoding. */
internal class NativeConsoleTerminal(private val console: Terminal) : ANSITerminal(
    object : FilterInputStream(console.input()) { override fun close() {} },
    FrameOutputStream(console.output()),
    console.encoding(),
) {
    private val original: Attributes = console.enterRawMode()
    private val closed = AtomicBoolean()
    private val shutdownHook = Thread({ runCatching { close() } }, "airlift-console-restore")
    private var reportedSize: TerminalSize? = null

    init {
        setMouseCaptureMode(com.googlecode.lanterna.terminal.MouseCaptureMode.CLICK)
        // Keep Ctrl+C as a process signal while accepting other keys without Enter.
        console.attributes = console.attributes.apply { setLocalFlag(Attributes.LocalFlag.ISIG, true) }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    private fun currentSize() = TerminalSize(console.width.coerceAtLeast(1), console.height.coerceAtLeast(1))
    override fun findTerminalSize() = currentSize().also { reportedSize = it }

    override fun pollInput(): com.googlecode.lanterna.input.KeyStroke? {
        // ANSITerminal emits a resize event on every size query, even if unchanged.
        if (currentSize() != reportedSize) terminalSize
        return super.pollInput()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { super.close() }
        finally {
            try { console.attributes = original }
            finally {
                console.close()
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            }
        }
    }

    companion object {
        fun open(): NativeConsoleTerminal {
            // A parent launcher may export TERM=dumb even when Windows has a real console.
            val console = TerminalBuilder.builder().system(true).type("xterm-256color").dumb(false).jni(true).build()
            if (console.type.startsWith("dumb") || console.width <= 0 || console.height <= 0) {
                console.close()
                error("No usable Windows console. Start the installed .bat launcher directly in PowerShell or Windows Terminal.")
            }
            return try { NativeConsoleTerminal(console) }
            catch (e: Exception) { console.close(); throw e }
        }
    }
}
