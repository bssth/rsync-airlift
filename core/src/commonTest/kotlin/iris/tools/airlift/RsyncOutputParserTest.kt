package iris.tools.airlift

import kotlin.test.*

class RsyncOutputParserTest {
    @Test fun `collect final counters without pretending progress is network traffic`() {
        val parser = RsyncOutputParser()
        val running = parser.parse(" 1,000  50%  1.00MB/s  0:00:01")!!
        assertNull(running.matchedBytes)
        assertNull(running.literalBytes)
        assertEquals(1234, parser.parse("Literal data: 1,234 bytes")!!.literalBytes)
        val complete = parser.parse("Matched data: 766 bytes")!!
        assertEquals(766, complete.matchedBytes)
        assertEquals(1234, complete.literalBytes)
        assertEquals(1000, complete.logicalBytes)
        assertNull(RsyncOutputParser().parse("permission denied"))
        assertNull(RsyncOutputParser().parse("Literal data: nonsense bytes"))
    }
}
