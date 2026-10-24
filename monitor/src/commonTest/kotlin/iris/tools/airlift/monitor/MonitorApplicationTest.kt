package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.io.files.Path
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorApplicationTest {
    private class Platform : MonitorApplicationPlatform {
        var shutdown: (suspend () -> Unit)? = null
        var served = false
        var closed = false
        var removed = false
        var rejectHook = false
        override fun remoteSource(endpoint: Path): MonitorSource = error("not attaching")
        override suspend fun temporaryDirectory(prefix: String): Path = error("not creating demo")
        override fun serve(source: MonitorSource, endpoint: Path): MonitorService {
            served = true
            return object : MonitorService { override fun close() { closed = true } }
        }
        override fun onShutdown(action: suspend () -> Unit): ShutdownRegistration {
            if (rejectHook) error("hook registration failed")
            shutdown = action
            return object : ShutdownRegistration { override fun remove() { removed = true } }
        }
    }

    private fun TestScope.dispatcher() = AirliftDispatcher(
        AirliftConfig("user@host", "/source", Path("destination"), workers = 1), emptyList(),
        transports = TransferSessionFactory { error("No work to transfer") },
        logs = object : DispatcherLogStore {
            override suspend fun createRunDirectory() = Path("logs")
            override fun attemptPath(run: Path, worker: Int, taskIndex: Int, attempt: Int) = Path(run, "attempt")
        }, dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test fun `service stays attachable after completion until shutdown`() = runTest {
        val dispatcher = dispatcher()
        val platform = Platform()
        val job = launch { runMonitorService(dispatcher, DispatcherMonitorSource(dispatcher), Path("endpoint"),
            false, false, false, platform) }
        runCurrent()
        assertTrue(platform.served)
        assertNotNull(dispatcher.awaitCompletion().finishedAtMillis)
        assertTrue(job.isActive)
        assertFalse(platform.closed)
        platform.shutdown!!()
        job.join()
        assertTrue(platform.closed && platform.removed)
    }

    @Test fun `cancelling service closes server and removes shutdown handler`() = runTest {
        val dispatcher = dispatcher()
        val platform = Platform()
        val job = launch { runMonitorService(dispatcher, DispatcherMonitorSource(dispatcher), Path("endpoint"),
            false, false, false, platform) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(platform.closed && platform.removed)
        assertNotNull(dispatcher.snapshot().finishedAtMillis)
    }

    @Test fun `hook registration failure closes previously opened server`() = runTest {
        val dispatcher = dispatcher()
        val platform = Platform().apply { rejectHook = true }
        assertFailsWith<IllegalStateException> {
            runMonitorService(dispatcher, DispatcherMonitorSource(dispatcher), Path("endpoint"), false, false, false, platform)
        }
        assertTrue(platform.closed)
        assertNotNull(dispatcher.snapshot().finishedAtMillis)
    }
}
