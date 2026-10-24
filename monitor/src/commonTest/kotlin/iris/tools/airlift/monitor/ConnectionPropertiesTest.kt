package iris.tools.airlift.monitor

import kotlin.test.*

class ConnectionPropertiesTest {
    @Test fun `properties roundtrip preserves paths Unicode and escaped delimiters`() {
        val values = mapOf("from" to "user@host:/папка/😀", "to" to " C:\\path\nwith\ttabs ", "a=b:c" to "#!\\")
        assertEquals(values, decodeConnectionProperties(encodeConnectionProperties(values)))
    }

    @Test fun `legacy properties comments continuation separators and Latin1 decode`() {
        val properties = "# comment\r\nfrom : root@host\\:/source\\\r\n  /nested\r\nto=\\u0416\\uD83D\\uDE00\n"
        assertEquals(mapOf("from" to "root@host:/source/nested", "to" to "Ж😀"), decodeConnectionProperties(properties.encodeToByteArray()))
        assertEquals("é", decodeConnectionProperties(byteArrayOf(107, 61, -23))["k"])
        assertFailsWith<IllegalArgumentException> { decodeConnectionProperties("k=\\uXX".encodeToByteArray()) }
    }
}
