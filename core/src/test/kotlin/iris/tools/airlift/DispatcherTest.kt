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

    @Test fun `closing during long retry delay promptly cancels the batch and queue`() {
        val attempted = CountDownLatch(1)
        val calls = AtomicInteger()
        val dispatcher = AirliftDispatcher(config(workers = 1, attempts = 3).copy(retryDelayMillis = 60_000),
            listOf(TransferTask("a", 2, files = listOf("a", "b")), TransferTask("c", 1)),
            factory { _, _ -> calls.incrementAndGet(); attempted.countDown(); 23 })
        dispatcher.use {
            val result = dispatcher.start()
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            dispatcher.close()
            val final = result.get(5, TimeUnit.SECONDS)
            assertTrue(final.cancelled)
            assertTrue(final.tasks.all { it.status == TaskStatus.CANCELLED })
            assertTrue(final.workers.all { it.status == WorkerStatus.STOPPED })
            assertEquals(1, calls.get())
        }
    }

    @Test fun `retirement stops the first finishing worker without interrupting other jobs`() {
        val entered = CountDownLatch(2)
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val assignments = java.util.concurrent.ConcurrentHashMap<Int, MutableList<String>>()
        val sessions = TransferSessionFactory { id ->
            object : TransferSession {
                override fun connect() {}
                override fun transfer(task: TransferTask, logFile: Path, progress: (TransferProgress) -> Unit): Int {
                    assignments.computeIfAbsent(id) { mutableListOf() }.add(task.id)
                    if (task.id != "c") {
                        entered.countDown()
                        check((if (id == 1) first else second).await(5, TimeUnit.SECONDS))
                    }
                    return 0
                }
                override fun close() { if (id == 2) retired.countDown() }
            }
        }
        AirliftDispatcher(config(), listOf(TransferTask("a"), TransferTask("b"), TransferTask("c")), sessions).use { dispatcher ->
            try {
                val result = dispatcher.start()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                dispatcher.setWorkerCount(1)
                second.countDown()
                assertTrue(retired.await(5, TimeUnit.SECONDS))
                awaitCondition { dispatcher.snapshot().workers.single { it.id == 2 }.status == WorkerStatus.STOPPED }
                first.countDown()
                assertEquals(3, result.get(5, TimeUnit.SECONDS).completed)
                assertEquals(1, assignments.getValue(2).size)
                assertEquals(2, assignments.getValue(1).size)
                assertEquals(WorkerStatus.STOPPED, dispatcher.snapshot().workers.single { it.id == 2 }.status)
            } finally { first.countDown(); second.countDown() }
        }
    }

    @Test fun `immediate cancellation always finalizes workers even before coroutine dispatch`() {
        repeat(30) {
            AirliftDispatcher(config(workers = 8), (1..20).map { TransferTask("$it") }, factory { _, _ -> 0 }).use { dispatcher ->
                dispatcher.pause()
                val result = dispatcher.start()
                dispatcher.close()
                assertTrue(result.get(5, TimeUnit.SECONDS).cancelled)
                assertTrue(dispatcher.snapshot().workers.all { it.status == WorkerStatus.STOPPED })
            }
        }
    }

    @Test fun `cancelling a returned future cannot cancel internal completion`() {
        AirliftDispatcher(config(workers = 1), listOf(TransferTask("a")), factory { _, _ -> 0 }).use { dispatcher ->
            dispatcher.pause()
            val result = dispatcher.start()
            result.cancel(true)
            dispatcher.resume()
            awaitCondition { dispatcher.snapshot().finishedAtMillis != null }
            assertEquals(1, dispatcher.snapshot().completed)
            assertFalse(dispatcher.snapshot().cancelled)
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for dispatcher state" }
            Thread.sleep(1)
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
