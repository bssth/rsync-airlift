package iris.tools.airlift.monitor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import kotlin.test.*

class ConnectionPropertiesJvmTest {
    @Test fun `portable properties interoperate with Java in both directions`() {
        val values = mapOf("from" to "user@host:/папка/😀", "to" to " C:\\path\nwith\ttabs ", "a=b:c" to "#!\\")
        val java = Properties().apply { load(ByteArrayInputStream(encodeConnectionProperties(values))) }
        assertEquals(values, java.stringPropertyNames().associateWith { java.getProperty(it) })
        val output = ByteArrayOutputStream()
        java.store(output, "test")
        assertEquals(values, decodeConnectionProperties(output.toByteArray()))
    }
}
