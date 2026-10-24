package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalSize
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.Attributes
import org.jline.terminal.Size
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.*

class NativeConsoleTest {
    @Test fun `ANSI adapter uses raw input detects resize and restores console attributes`() {
        val input = PipedInputStream()
        PipedOutputStream(input).use {
            val output = ByteArrayOutputStream()
            val console = TerminalBuilder.builder().system(false).type("xterm")
                .streams(input, output).build()
            console.size = Size(100, 30)
            console.attributes = console.attributes.apply {
                setLocalFlag(Attributes.LocalFlag.ICANON, true)
                setLocalFlag(Attributes.LocalFlag.ECHO, true)
            }
            val original = console.attributes
            val terminal = NativeConsoleTerminal(console)
            var resizeEvents = 0
            terminal.addResizeListener { _, _ -> resizeEvents++ }
            assertFalse(console.attributes.getLocalFlag(Attributes.LocalFlag.ICANON))
            assertFalse(console.attributes.getLocalFlag(Attributes.LocalFlag.ECHO))
            assertEquals(TerminalSize(100, 30), terminal.terminalSize)
            val initialEvents = resizeEvents
            terminal.pollInput()
            terminal.pollInput()
            assertEquals(initialEvents, resizeEvents)
            console.size = Size(120, 40)
            terminal.pollInput()
            assertEquals(initialEvents + 1, resizeEvents)
            assertEquals(TerminalSize(120, 40), terminal.terminalSize)
            terminal.enterPrivateMode()
            terminal.putString("console test")
            terminal.close()
            terminal.close() // Shutdown hooks and normal cleanup may both request close.
            assertEquals(original.localFlags, console.attributes.localFlags)
            val rendered = output.toString(Charsets.UTF_8)
            assertContains(rendered, "\u001B[?1049h")
            assertContains(rendered, "\u001B[?1049l")
            assertContains(rendered, "console test")
        }
    }
}
