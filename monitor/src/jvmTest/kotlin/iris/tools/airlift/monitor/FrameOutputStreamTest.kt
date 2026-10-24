package iris.tools.airlift.monitor

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.test.*

class FrameOutputStreamTest {
    @Test fun `ANSI and UTF8 fragments are submitted together at frame boundary`() {
        val received = ByteArrayOutputStream()
        var writes = 0
        var closed = false
        val destination = object : OutputStream() {
            override fun write(value: Int) { fail("Console received a single-byte write") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writes++
                received.write(bytes, offset, length)
            }
            override fun close() { closed = true }
        }
        val output = FrameOutputStream(destination)
        val frame = "\u001B[0m\u001B[37m\u001B[40mЖурнал\u001B[2;3Hfile.log"
        val bytes = frame.toByteArray(Charsets.UTF_8)
        for (byte in bytes) output.write(byte.toInt())
        assertEquals(0, writes)
        output.flush()
        assertEquals(1, writes)
        assertEquals(frame, received.toString(Charsets.UTF_8))
        output.flush()
        assertEquals(1, writes)
        output.close()
        assertFalse(closed)
    }
}
