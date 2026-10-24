package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.*

class MonitorWireTest {
    private class MemorySocket : MonitorSocket {
        val data = Buffer()
        override suspend fun read(bytes: ByteArray): Int {
            if (data.size == 0L) return -1
            // Exercise every primitive/string across fragmented transport reads.
            bytes[0] = data.readByte()
            return 1
        }
        override suspend fun write(bytes: ByteArray) { data.write(bytes) }
        override suspend fun flush() {}
        override fun close() {}
    }

    @Test fun `protocol three modified UTF preserves NUL and surrogate pairs`() = runTest {
        val socket = MemorySocket()
        val output = MonitorOutput(socket)
        output.writeUTF("A\u0000Ж😀")
        output.flush()
        val expected = byteArrayOf(0, 11, 65, -64, -128, -48, -106, -19, -96, -67, -19, -72, -128)
        val actual = socket.data.copy().readByteArray()
        assertContentEquals(expected, actual)
        assertEquals("A\u0000Ж😀", MonitorInput(socket).readUTF())
    }

    @Test fun `snapshot roundtrip retains nullable fields statistics and file batches`() = runTest {
        val socket = MemorySocket()
        val task = TaskState(TransferTask("папка/a😀", 42, files = listOf("папка/a😀", "b")),
            TaskStatus.SUCCEEDED, 2, TransferProgress(42, 12.5, 0, 100, 30, 12, "b", 2), "message")
        val snapshot = AirliftSnapshot(listOf(task), listOf(WorkerState(7, WorkerStatus.FINISHED, task.task.id)),
            false, false, 8, 10, 20)
        val output = MonitorOutput(socket)
        Wire.write(output, snapshot)
        output.flush()
        assertEquals(snapshot, Wire.read(MonitorInput(socket)))
    }

    @Test fun `invalid text length is rejected before allocating payload`() = runTest {
        val socket = MemorySocket()
        val output = MonitorOutput(socket)
        output.writeInt(1_048_577)
        output.flush()
        assertFailsWith<IllegalArgumentException> { Wire.text(MonitorInput(socket)) }
    }
}
