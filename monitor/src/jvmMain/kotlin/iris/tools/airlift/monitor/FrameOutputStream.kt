package iris.tools.airlift.monitor

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Submit each Lanterna flush as one bulk write, including all of its ANSI sequences. */
internal class FrameOutputStream(private val destination: OutputStream) : OutputStream() {
    private val pending = ByteArrayOutputStream(16 * 1024)

    @Synchronized override fun write(value: Int) { pending.write(value) }
    @Synchronized override fun write(bytes: ByteArray, offset: Int, length: Int) { pending.write(bytes, offset, length) }

    @Synchronized override fun flush() {
        if (pending.size() > 0) {
            pending.writeTo(destination)
            pending.reset()
        }
        destination.flush()
    }

    // The JLine terminal owns the destination and restores its modes on close.
    override fun close() { flush() }
}
