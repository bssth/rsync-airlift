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

    private fun TestScope.dispatcher(tasks: List<TransferTask> = emptyList()) = AirliftDispatcher(
        AirliftConfig("user@host", "/source", Path("destination"), workers = 1), tasks,
        transports = TransferSessionFactory {
            object : TransferSession {
                override suspend fun connect() {}
                override suspend fun transfer(task: TransferTask, logFile: Path, progress: suspend (TransferProgress) -> Unit): Int {
                    delay(10_000)
                    return 0
                }
                override suspend fun close() {}
            }
        },
        logs = object : DispatcherLogStore {
            override suspend fun createRunDirectory() = Path("logs")
            override fun attemptPath(run: Path, worker: Int, taskIndex: Int, attempt: Int) = Path(run, "attempt")
        }, dispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test fun `closing owned panel after completion exits service and cleans resources`() = runTest {
        val dispatcher = dispatcher()
        val platform = Platform()
        val job = launch {
            runMonitorService(dispatcher, DispatcherMonitorSource(dispatcher), Path("endpoint"),
                false, true, false, platform, runPanel = { dispatcher.awaitCompletion() })
        }
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(platform.closed && platform.removed)
    }

    @Test fun `detaching owned panel during transfer leaves service running`() = runTest {
        val dispatcher = dispatcher(listOf(TransferTask("a", 1)))
        val platform = Platform()
        val job = launch {
            runMonitorService(dispatcher, DispatcherMonitorSource(dispatcher), Path("endpoint"),
                false, true, false, platform, runPanel = {
                    assertNull(dispatcher.snapshot().finishedAtMillis)
                })
        }
        runCurrent()
        assertTrue(job.isActive)
        assertFalse(platform.closed)
        advanceUntilIdle()
        assertNotNull(dispatcher.snapshot().finishedAtMillis)
        assertTrue(job.isActive)
        platform.shutdown!!()
        job.join()
        assertTrue(platform.closed && platform.removed)
    }

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
