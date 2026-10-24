@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.posix.*
import iris.tools.airlift.monitor.posix.*

private class LinuxMonitorSocket(private var fd: Int) : MonitorSocket {
    private val lock = LinuxLock()
    private fun <T> withFd(block: (Int) -> T): T = lock.locked { check(fd >= 0) { "Monitor socket is closed" }; block(fd) }
    suspend fun connected() {
        val deadline = monitorMonotonicNanos() + 1_500_000_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            val ready = withFd { airlift_connected(it).also { result -> if (result < 0 && errno != EINTR) linuxError("connect monitor") } }
            if (ready > 0) return
            check(monitorMonotonicNanos() < deadline) { "Monitor connection timed out" }
            delay(5)
        }
    }
    override suspend fun read(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val deadline = monitorMonotonicNanos() + 3_000_000_000L
        try { while (true) {
            currentCoroutineContext().ensureActive()
            val count = withFd { socket -> bytes.usePinned { read(socket, it.addressOf(0), bytes.size.toULong()) }.also {
                if (it < 0 && errno != EAGAIN && errno != EINTR) linuxError("read monitor")
            } }
            if (count >= 0) return if (count == 0L) -1 else count.toInt()
            check(monitorMonotonicNanos() < deadline) { "Monitor read timed out" }
            delay(5)
        } } catch (error: Exception) { close(); throw error }
    }
    override suspend fun write(bytes: ByteArray) {
        var offset = 0
        var deadline = monitorMonotonicNanos() + 3_000_000_000L
        try { while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = withFd { socket -> bytes.usePinned { airlift_send(socket, it.addressOf(offset), (bytes.size - offset).toULong()) }.also {
                if (it < 0 && errno != EAGAIN && errno != EINTR) linuxError("write monitor")
            } }
            if (count > 0) { offset += count.toInt(); deadline = monitorMonotonicNanos() + 3_000_000_000L }
            else { check(monitorMonotonicNanos() < deadline) { "Monitor write timed out" }; delay(5) }
        } } catch (error: Exception) { close(); throw error }
    }
    override suspend fun flush() {}
    override fun close() { lock.locked { if (fd >= 0) { shutdown(fd, SHUT_RDWR); platform.posix.close(fd); fd = -1 } } }
}

internal actual fun monitorTransport(): MonitorTransport = object : MonitorTransport {
    override fun listen(): MonitorListener = memScoped {
        val port = alloc<IntVar>()
        val descriptor = airlift_listen(port.ptr)
        if (descriptor < 0) linuxError("listen monitor")
        val boundPort = port.value
        object : MonitorListener {
            private val lock = LinuxLock()
            private var fd = descriptor
            override val port: Int = boundPort
            override suspend fun accept(): MonitorSocket {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val accepted = lock.locked {
                        check(fd >= 0) { "Monitor listener is closed" }
                        airlift_accept(fd).also { if (it < 0 && errno != EAGAIN && errno != EINTR) linuxError("accept monitor") }
                    }
                    if (accepted >= 0) return LinuxMonitorSocket(accepted)
                    delay(5)
                }
            }
            override fun close() { lock.locked { if (fd >= 0) { platform.posix.close(fd); fd = -1 } } }
        }
    }
    override suspend fun connect(port: Int): MonitorSocket {
        require(port in 1..65535)
        val fd = airlift_connect(port)
        if (fd < 0) linuxError("connect monitor")
        val socket = LinuxMonitorSocket(fd)
        try { socket.connected(); return socket } catch (error: Exception) { socket.close(); throw error }
    }
}
