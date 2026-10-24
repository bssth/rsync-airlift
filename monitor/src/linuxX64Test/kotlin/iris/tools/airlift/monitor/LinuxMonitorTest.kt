@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.*
import kotlin.test.*

class LinuxMonitorTest {
    private class Source : MonitorSource {
        override val from = "user@host:/папка/😀"
        override val to = "/target with space"
        var value = AirliftSnapshot(emptyList(), emptyList(), false, false, 4)
        override suspend fun snapshot() = value
        override suspend fun pause() { value = value.copy(paused = true) }
        override suspend fun resume() { value = value.copy(paused = false) }
        override suspend fun setWorkerCount(count: Int) { value = value.copy(targetWorkers = count) }
    }

    @Test fun loopbackServerClientAndProtectedEndpoint(): Unit = runBlocking {
        val directory = monitorApplicationPlatform().temporaryDirectory("airlift-monitor-test-")
        val endpoint = Path(directory, "connection")
        val source = Source()
        var server: MonitorServer? = null
        try {
            server = MonitorServer(source, endpoint)
            memScoped {
                val info = alloc<stat>()
                assertEquals(0, stat(endpoint.toString(), info.ptr))
                assertEquals(384u, info.st_mode and 511u)
            }
            val original = readConnectionFile(endpoint)
            assertTrue(original.getValue("token").length >= 32)
            assertFailsWith<IllegalStateException> { MonitorServer(source, endpoint) }
            assertEquals(original, readConnectionFile(endpoint))
            val client = RemoteMonitorSource(endpoint)
            assertEquals(source.from, client.from)
            assertEquals(source.to, client.to)
            assertEquals(4, client.snapshot().targetWorkers)
            client.pause(); assertTrue(client.snapshot().paused)
            client.resume(); assertFalse(client.snapshot().paused)
            client.setWorkerCount(7); assertEquals(7, client.snapshot().targetWorkers)
            server.close(); server.close()
            assertFalse(SystemFileSystem.exists(endpoint))
        } finally {
            server?.close()
            SystemFileSystem.delete(directory)
        }
    }

    @Test fun cancellationClosesSocketAndListener(): Unit = runBlocking {
        val listener = monitorTransport().listen()
        val client = monitorTransport().connect(listener.port)
        val peer = listener.accept()
        try {
            val reading = launch { client.read(ByteArray(32)) }
            delay(20); reading.cancelAndJoin()
            assertEquals(-1, withTimeout(2000) { peer.read(ByteArray(32)) })
            val accepting = launch { listener.accept().close() }
            delay(20); accepting.cancelAndJoin()
        } finally { client.close(); peer.close(); listener.close() }
    }

    @Test fun localInventoryDoesNotFollowDirectoryLinks(): Unit = runBlocking {
        val directory = monitorApplicationPlatform().temporaryDirectory("airlift-inventory-test-")
        val outside = monitorApplicationPlatform().temporaryDirectory("airlift-outside-test-")
        val link = Path(directory, "link")
        try {
            assertEquals(0, symlink(outside.toString(), link.toString()))
            val inventory = readLocalInventory(directory.toString())
            assertEquals(setOf("link"), inventory.sizes.keys)
            assertEquals(setOf(""), inventory.directories)
            assertTrue(readLocalInventory(link.toString()).singleFile)
        } finally {
            unlink(link.toString()); SystemFileSystem.delete(directory); SystemFileSystem.delete(outside)
        }
    }

    @Test fun termSignalSchedulesCoroutineShutdown(): Unit = runBlocking {
        val called = CompletableDeferred<Unit>()
        val registration = monitorApplicationPlatform().onShutdown { called.complete(Unit) }
        try {
            assertEquals(0, kill(getpid(), SIGTERM))
            withTimeout(2000) { called.await() }
        } finally { registration.remove() }
    }
}
