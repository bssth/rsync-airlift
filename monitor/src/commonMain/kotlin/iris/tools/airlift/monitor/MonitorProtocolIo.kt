package iris.tools.airlift.monitor

import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal class MonitorOutput(private val socket: MonitorSocket) {
    private val buffer = Buffer()
    fun writeBoolean(value: Boolean) { buffer.writeByte(if (value) 1 else 0) }
    fun writeInt(value: Int) { buffer.writeInt(value) }
    fun writeLong(value: Long) { buffer.writeLong(value) }
    fun writeDouble(value: Double) { buffer.writeLong(value.toBits()) }
    fun write(bytes: ByteArray) { buffer.write(bytes) }
    /** Protocol 3 uses Java's modified UTF-8 for its short handshake strings. */
    fun writeUTF(text: String) {
        val encoded = Buffer()
        for (char in text) {
            val code = char.code
            when {
                code in 1..127 -> encoded.writeByte(code.toByte())
                code <= 2047 -> { encoded.writeByte((192 or (code shr 6)).toByte()); encoded.writeByte((128 or (code and 63)).toByte()) }
                else -> { encoded.writeByte((224 or (code shr 12)).toByte()); encoded.writeByte((128 or ((code shr 6) and 63)).toByte()); encoded.writeByte((128 or (code and 63)).toByte()) }
            }
        }
        require(encoded.size <= 65535)
        buffer.writeShort(encoded.size.toShort())
        buffer.write(encoded, encoded.size)
    }
    suspend fun flush() {
        while (buffer.size > 0) socket.write(buffer.readByteArray(minOf(buffer.size, 65536).toInt()))
        socket.flush()
    }
}

internal class MonitorInput(private val socket: MonitorSocket) {
    private val bytes = ByteArray(8192)
    private var position = 0
    private var size = 0
    private suspend fun byte(): Int {
        if (position == size) {
            size = socket.read(bytes); position = 0
            check(size > 0) { "Unexpected end of monitor connection" }
        }
        return bytes[position++].toInt() and 255
    }
    suspend fun readBoolean(): Boolean = byte() != 0
    suspend fun readInt(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
    suspend fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)
    suspend fun readDouble(): Double = Double.fromBits(readLong())
    suspend fun readFully(destination: ByteArray) { for (i in destination.indices) destination[i] = byte().toByte() }
    suspend fun readUTF(): String {
        val count = (byte() shl 8) or byte()
        val data = ByteArray(count); readFully(data)
        var index = 0
        fun next(): Int { require(index < count) { "Malformed modified UTF-8" }; return data[index++].toInt() and 255 }
        fun continuation(): Int = next().also { require(it and 192 == 128) { "Malformed modified UTF-8" } } and 63
        return buildString {
            while (index < count) {
                val first = next()
                val code = when {
                    first < 128 -> first
                    first and 224 == 192 -> ((first and 31) shl 6) or continuation()
                    first and 240 == 224 -> ((first and 15) shl 12) or (continuation() shl 6) or continuation()
                    else -> error("Malformed modified UTF-8")
                }
                append(code.toChar())
            }
        }
    }
}
