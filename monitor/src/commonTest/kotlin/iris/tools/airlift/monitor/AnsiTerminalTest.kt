package iris.tools.airlift.monitor

import kotlin.test.*

class AnsiTerminalTest {
    @Test fun `identical frames produce no output and changed cells update without clearing`() {
        val encoder = AnsiFrames()
        fun frame(text: String) = MonitorFrame(MonitorSize(12, 2)).apply { putString(0, 0, text) }
        assertContains(encoder.encode(frame("abc")), "\u001b[2J")
        assertEquals("", encoder.encode(frame("abc")))
        val delta = encoder.encode(frame("axc"))
        assertFalse("\u001b[2J" in delta)
        assertContains(delta, "\u001b[1;2H")
        assertTrue(delta.endsWith("x"))
        val cleared = encoder.encode(frame("a"))
        assertFalse("\u001b[2J" in cleared)
        assertTrue(cleared.endsWith("  "))
        assertContains(encoder.encode(MonitorFrame(MonitorSize(20, 3))), "\u001b[2J")
    }

    @Test fun `wide and combining glyphs occupy display columns and preserve RGB colors`() {
        val frame = MonitorFrame(MonitorSize(8, 2)).apply {
            foregroundColor = MonitorColor.ANSI.CYAN
            backgroundColor = MonitorColor.RGB(135, 88, 44)
            putString(0, 0, "界界界", 4)
            putString(0, 1, "e\u0301😀", 3)
        }
        val text = AnsiFrames().encode(frame)
        assertContains(text, "界界")
        assertFalse("界界界" in text)
        assertContains(text, "e\u0301😀")
        assertContains(text, "\u001b[48;2;135;88;44m")
        assertContains(text, "\u001b[36m")
    }

    @Test fun `incremental input handles arrows function keys and compact navigation`() {
        val input = AnsiInput()
        input.feed("\u001b[".encodeToByteArray()); assertNull(input.poll())
        input.feed("A".encodeToByteArray()); assertEquals(MonitorEvent.Key(MonitorKey.ArrowUp), input.poll())
        for ((text, key) in listOf("\u001bOQ" to MonitorKey.F2, "\u001b[12~" to MonitorKey.F2,
            "\u001b[6~" to MonitorKey.PageDown, "\u001b[F" to MonitorKey.End, "\t" to MonitorKey.Tab)) {
            input.feed(text.encodeToByteArray()); assertEquals(MonitorEvent.Key(key), input.poll())
        }
        input.feed("+".encodeToByteArray()); assertEquals(MonitorEvent.Key(MonitorKey.Character, '+'), input.poll())
        input.feed("\u001b".encodeToByteArray()); assertNull(input.poll()); assertNotNull(input.poll(true))
    }

    @Test fun `SGR mouse preserves large coordinates and wheel events`() {
        val input = AnsiInput()
        input.feed("\u001b[<0;151;13M\u001b[<65;151;13M\u001b[<0;151;13m".encodeToByteArray())
        assertEquals(MonitorEvent.Mouse(MonitorMouseAction.CLICK_DOWN, 1, 150, 12), input.poll())
        assertEquals(MonitorEvent.Mouse(MonitorMouseAction.SCROLL_DOWN, 5, 150, 12), input.poll())
        assertEquals(MonitorMouseAction.OTHER, (input.poll() as MonitorEvent.Mouse).actionType)
    }

    @Test fun `bracketed paste does not execute worker control keys and fragmented UTF8 is consumed`() {
        val input = AnsiInput()
        input.feed("\u001b[200~q-p+\u001b[20".encodeToByteArray()); assertNull(input.poll())
        input.feed("1~p".encodeToByteArray()); assertEquals(MonitorEvent.Key(MonitorKey.Character, 'p'), input.poll())
        val bytes = "Ж".encodeToByteArray()
        input.feed(bytes.copyOf(1)); assertNull(input.poll())
        input.feed(bytes.copyOfRange(1, bytes.size)); assertEquals(MonitorEvent.Key(MonitorKey.Other), input.poll())
    }

    @Test fun `oversized paste cannot turn into worker control commands`() {
        val input = AnsiInput()
        input.feed(("\u001b[200~" + "q-p+".repeat(20000)).encodeToByteArray())
        assertNull(input.poll())
        input.feed("\u001b[201~q".encodeToByteArray())
        assertEquals(MonitorEvent.Key(MonitorKey.Character, 'q'), input.poll())
    }
}
