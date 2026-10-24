package iris.tools.airlift

import java.nio.file.Path
import kotlin.test.*

class TransferLocationsTest {
    @Test fun `upload and download descriptions reflect actual source and destination`() {
        val local = Path.of("destination").toAbsolutePath().normalize()
        val config = AirliftConfig("user@server", "/srv/data", local)
        AirliftDispatcher(config, emptyList()).use {
            assertEquals("user@server:/srv/data", it.sourceDescription)
            assertEquals(local.toString(), it.destinationDescription)
        }
        AirliftDispatcher(config.copy(direction = TransferDirection.UPLOAD), emptyList()).use {
            assertEquals(local.toString(), it.sourceDescription)
            assertEquals("user@server:/srv/data", it.destinationDescription)
        }
    }
}
