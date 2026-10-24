@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import platform.posix.*
import kotlinx.coroutines.*
import iris.tools.airlift.monitor.posix.*
import kotlin.test.*

/** These two lifecycle checks require a PTY; headless runs still execute shared ANSI tests. */
class LinuxAnsiTerminalTest {
    @Test fun terminalRestoresRawModeAndAlternateScreen() {
        if (!hasInteractiveConsole()) return
        memScoped {
            val before = alloc<termios>()
            val during = alloc<termios>()
            val after = alloc<termios>()
            assertEquals(0, tcgetattr(STDIN_FILENO, before.ptr))
            val terminal = LinuxAnsiTerminal()
            try {
                terminal.start()
                assertEquals(0, tcgetattr(STDIN_FILENO, during.ptr))
                assertEquals(0u, during.c_lflag and (ECHO or ICANON).toUInt())
                val size = terminal.size()
                assertTrue(size.columns > 0 && size.rows > 0)
                val frame = MonitorFrame(size).apply { putString(0, 0, "Native terminal lifecycle test") }
                terminal.present(frame)
                terminal.present(frame)
            } finally { terminal.close(); terminal.close() }
            assertEquals(0, tcgetattr(STDIN_FILENO, after.ptr))
            assertEquals(before.c_iflag, after.c_iflag)
            assertEquals(before.c_oflag, after.c_oflag)
            assertEquals(before.c_cflag, after.c_cflag)
            assertEquals(before.c_lflag, after.c_lflag)
            repeat(NCCS) { assertEquals(before.c_cc[it], after.c_cc[it]) }
        }
    }

    @Test fun termSignalClosesTerminalAndRestoresMode() {
        if (!hasInteractiveConsole()) return
        memScoped {
            val before = alloc<termios>()
            val after = alloc<termios>()
            assertEquals(0, tcgetattr(STDIN_FILENO, before.ptr))
            val terminal = LinuxAnsiTerminal()
            try {
                terminal.start()
                assertEquals(0, kill(getpid(), SIGTERM))
                assertEquals(MonitorEvent.Key(MonitorKey.EOF), terminal.pollEvent())
            } finally { terminal.close() }
            assertEquals(0, tcgetattr(STDIN_FILENO, after.ptr))
            assertEquals(before.c_lflag, after.c_lflag)
        }
    }

    @Test fun openingTerminalPreservesPendingServiceShutdown(): Unit = runBlocking {
        if (!hasInteractiveConsole()) return@runBlocking
        val shutdown = CompletableDeferred<Unit>()
        val registration = monitorApplicationPlatform().onShutdown { shutdown.complete(Unit) }
        val terminal = LinuxAnsiTerminal()
        try {
            airlift_request_stop()
            terminal.start()
            assertEquals(MonitorEvent.Key(MonitorKey.EOF), terminal.pollEvent())
            terminal.close()
            withTimeout(2000) { shutdown.await() }
        } finally { terminal.close(); registration.remove() }
    }
}
