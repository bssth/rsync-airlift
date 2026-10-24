package iris.tools.airlift.monitor

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.input.*
import java.io.StringReader
import kotlin.test.*

class SgrMouseTest {
    @Test fun `decoder preserves wide terminal coordinates and reaches second column worker`() {
        val decoder = InputDecoder(StringReader("\u001b[<0;140;12M"))
        decoder.addProfile(DefaultKeyDecodingProfile())
        decoder.addProfile(KeyDecodingProfile { listOf(SgrMousePattern()) })
        val action = assertIs<MouseAction>(decoder.getNextCharacter(false))
        assertEquals(TerminalPosition(139, 11), action.position)
        assertEquals(MouseActionType.CLICK_DOWN, action.actionType)
        assertEquals(1, action.button)
        val model = MonitorModel()
        model.mouseRows.add(MonitorModel.MouseRow(91, 11, 88, 37))
        model.handleMouse(action, iris.tools.airlift.AirliftSnapshot(emptyList(), emptyList(), false, false))
        assertEquals(37, model.selected)
    }

    @Test fun `SGR supports release wheel modifiers and large coordinates`() {
        fun decode(text: String) = assertIs<MouseAction>(SgrMousePattern().match(text.toList())?.fullMatch)
        assertEquals(MouseActionType.CLICK_RELEASE, decode("\u001b[<0;140;12m").actionType)
        assertEquals(MouseActionType.SCROLL_UP, decode("\u001b[<64;140;12M").actionType)
        assertEquals(MouseActionType.SCROLL_DOWN, decode("\u001b[<65;140;12M").actionType)
        assertEquals(1, decode("\u001b[<4;140;12M").button)
        assertEquals(TerminalPosition(999, 299), decode("\u001b[<0;1000;300M").position)
        assertNull(SgrMousePattern().match("\u001b[<0;0;12M".toList()))
        assertTrue(SgrMousePattern().match("\u001b[<0;140;".toList())!!.partialMatch)
    }
}
