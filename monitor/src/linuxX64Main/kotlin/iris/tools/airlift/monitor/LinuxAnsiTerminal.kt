@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import platform.posix.*
import iris.tools.airlift.monitor.posix.*

/** POSIX terminal lifecycle only; frame diffing and input decoding are shared. */
internal class LinuxAnsiTerminal : MonitorTerminal {
    private var terminal: CPointer<airlift_terminal>? = null
    private var signals: CPointer<airlift_signals>? = null
    private val frames = AnsiFrames { code -> airlift_column_width(code.toUInt()).let { if (it < 0) ansiColumnWidth(code) else it } }
    private val input = AnsiInput()
    private var lastInputAt = 0L
    private var entered = false
    override fun start() {
        check(terminal == null) { "Terminal already started" }
        check(getenv("TERM")?.toKString() != "dumb") { "Native TUI needs an ANSI-capable terminal" }
        terminal = airlift_terminal_open() ?: linuxError("open terminal (SSH requires ssh -t)")
        signals = airlift_install_signals() ?: linuxError("install terminal signal handlers")
        entered = true
        writeText("\u001b[?1049h\u001b[?25l\u001b[?7l\u001b[?1000h\u001b[?1006h\u001b[?2004h")
    }
    override fun size(): MonitorSize = memScoped {
        val fd = requireNotNull(terminal).pointed.fd
        val columns = alloc<IntVar>(); val rows = alloc<IntVar>()
        if (airlift_terminal_size(fd, columns.ptr, rows.ptr) != 0) return@memScoped MonitorSize(80, 24)
        MonitorSize(columns.value.coerceIn(1, 1000), rows.value.coerceIn(1, 500))
    }
    override fun present(frame: MonitorFrame) { writeText(frames.encode(frame)) }
    override fun pollEvent(): MonitorEvent? {
        if (airlift_stopping() != 0) return MonitorEvent.Key(MonitorKey.EOF)
        val fd = requireNotNull(terminal).pointed.fd
        val bytes = ByteArray(4096)
        var total = 0
        while (total < 65536) {
            val count = bytes.usePinned { read(fd, it.addressOf(0), bytes.size.toULong()) }
            if (count < 0 && errno == EINTR) continue
            if (count < 0 && errno == EAGAIN) break
            if (count < 0) linuxError("read terminal")
            if (count == 0L) return MonitorEvent.Key(MonitorKey.EOF)
            val received = bytes.copyOf(count.toInt())
            if (received.any { it == 3.toByte() }) { airlift_request_stop(); return MonitorEvent.Key(MonitorKey.EOF) }
            input.feed(received)
            lastInputAt = monitorMonotonicNanos()
            total += count.toInt()
        }
        return input.poll(input.waitingForEscape && monitorMonotonicNanos() - lastInputAt > 100_000_000L)
    }
    private fun writeText(text: String) {
        if (text.isEmpty()) return
        val fd = requireNotNull(terminal).pointed.fd
        val bytes = text.encodeToByteArray()
        var offset = 0
        var deadline = monitorMonotonicNanos() + 3_000_000_000L
        while (offset < bytes.size) {
            val count = bytes.usePinned { write(fd, it.addressOf(offset), (bytes.size - offset).toULong()) }
            if (count < 0 && errno == EINTR) continue
            if (count < 0 && errno == EAGAIN) {
                check(monitorMonotonicNanos() < deadline) { "Terminal write timed out" }
                usleep(1000u); continue
            }
            if (count <= 0) linuxError("write terminal")
            offset += count.toInt()
            deadline = monitorMonotonicNanos() + 3_000_000_000L
        }
    }
    override fun close() {
        val current = terminal ?: return
        try {
            if (entered) runCatching { writeText("\u001b[0m\u001b[?2004l\u001b[?1006l\u001b[?1000l\u001b[?7h\u001b[?25h\u001b[?1049l") }
        } finally {
            airlift_terminal_close(current)
            terminal = null
            signals?.let { airlift_restore_signals(it) }; signals = null
            entered = false
        }
    }
}
