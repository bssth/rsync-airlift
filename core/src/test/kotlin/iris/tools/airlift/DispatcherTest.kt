package iris.tools.airlift

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DispatcherTest {
    @Test fun `finished speed is measured from task duration instead of last progress rate`() {
        var transferNanos = 0L
        val factory = factory { _, progress ->
            val begin = System.nanoTime()
            progress(TransferProgress(100, Double.MAX_VALUE, 0))
            transferNanos = (System.nanoTime() - begin).coerceAtLeast(1)
            0
        }
        AirliftDispatcher(config(workers = 1), listOf(TransferTask("file", 100)), factory).use { dispatcher ->
            val state = dispatcher.start().get(5, TimeUnit.SECONDS).tasks.single()
            val average = state.progress!!.bytesPerSecond!!
            assertTrue(average > 0 && average <= 100 / (transferNanos / 1_000_000_000.0))
            assertNull(state.progress.remainingSeconds)
        }
    }

    private fun config(workers: Int = 2, attempts: Int = 1): AirliftConfig {
        val root = Files.createTempDirectory("airlift-test-")
        return AirliftConfig("user@example", "/source", root.resolve("destination"), workers = workers,
            maxAttempts = attempts, retryDelayMillis = 0, logDirectory = root.resolve("logs"))
    }

    private fun factory(action: (TransferTask, (TransferProgress) -> Unit) -> Int) = TransferSessionFactory {
        object : TransferSession {
            override fun connect() {}
            override fun transfer(task: TransferTask, logFile: Path, progress: (TransferProgress) -> Unit) = action(task, progress)
            override fun close() {}
        }
    }

    @Test fun `workers consume every task exactly once`() {
        val calls = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val tasks = (1..50).map { TransferTask("nested/file $it", 100) }
        AirliftDispatcher(config(), tasks, factory { task, progress ->
            calls.computeIfAbsent(task.id) { AtomicInteger() }.incrementAndGet()
            progress(TransferProgress(100, 10.0, null)); 0
        }).use { dispatcher ->
            val result = dispatcher.start().get(5, TimeUnit.SECONDS)
            assertEquals(50, result.completed)
            assertTrue(result.workers.all { it.status == WorkerStatus.FINISHED })
            assertTrue(calls.values.all { it.get() == 1 })
            assertTrue(result.tasks.all { it.progress?.logicalBytes == 100L })
            assertTrue(result.tasks.all { it.progress!!.bytesPerSecond!! > 0 && it.progress.bytesPerSecond!!.isFinite() })
        }
    }

    @Test fun `retry failure is visible and does not stop the queue`() {
        val attempts = AtomicInteger()
        AirliftDispatcher(config(workers = 1, attempts = 2), listOf(TransferTask("bad"), TransferTask("good")),
            factory { task, _ -> if (task.path == "bad") { attempts.incrementAndGet(); 23 } else 0 }).use { dispatcher ->
            val result = dispatcher.start().get(5, TimeUnit.SECONDS)
            assertEquals(1, result.completed)
            assertEquals(1, result.failed)
            assertEquals(WorkerStatus.FINISHED, result.workers.single().status)
            assertEquals(2, attempts.get())
            assertTrue(result.tasks.first().error!!.contains("23"))
        }
    }

    @Test fun `pause and concurrency increase control task dispatch`() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        AirliftDispatcher(config(workers = 1), listOf(TransferTask("a"), TransferTask("b")), factory { _, _ ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); 0
        }).use { dispatcher ->
            dispatcher.pause()
            val result = dispatcher.start()
            assertEquals(2, dispatcher.snapshot().queued)
            dispatcher.setWorkerCount(2)
            dispatcher.resume()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(2, result.get(5, TimeUnit.SECONDS).completed)
        }
    }

    @Test fun `closing paused dispatcher cancels pending tasks`() {
        AirliftDispatcher(config(), listOf(TransferTask("a")), factory { _, _ -> fail("Unexpected transfer") }).use { dispatcher ->
            dispatcher.pause()
            val result = dispatcher.start()
            dispatcher.close()
            assertEquals(TaskStatus.CANCELLED, result.get(5, TimeUnit.SECONDS).tasks.single().status)
        }
    }

    @Test fun `connection failure terminates without endlessly replacing workers`() {
        val failing = TransferSessionFactory {
            object : TransferSession {
                override fun connect() { error("Cannot connect") }
                override fun transfer(task: TransferTask, logFile: Path, progress: (TransferProgress) -> Unit) = 0
                override fun close() {}
            }
        }
        AirliftDispatcher(config(workers = 1), listOf(TransferTask("a")), failing).use { dispatcher ->
            assertEquals(1, dispatcher.start().get(5, TimeUnit.SECONDS).failed)
            assertEquals("Cannot connect", dispatcher.snapshot().workers.single().error)
        }
    }

    @Test fun `progress distinguishes completion time from ETA`() {
        val running = ProgressParser.parse(" 1,234  12%  1.50MB/s  0:01:02")!!
        assertEquals(1234, running.logicalBytes)
        assertEquals(1_572_864.0, running.bytesPerSecond)
        assertEquals(62, running.remainingSeconds)
        assertNull(ProgressParser.parse(" 1,234 100% 1.50MB/s 0:00:01 (xfr#1, to-chk=0/1)")!!.remainingSeconds)
        assertNull(ProgressParser.parse("rsync: permission denied"))
    }

    @Test fun `reject traversal and duplicate identifiers`() {
        assertFailsWith<IllegalArgumentException> { TransferTask("../secret") }
        assertFailsWith<IllegalArgumentException> { AirliftDispatcher(config(), listOf(TransferTask("a"), TransferTask("a"))) }
    }
}
