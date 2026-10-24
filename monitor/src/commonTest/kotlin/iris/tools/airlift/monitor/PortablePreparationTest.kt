package iris.tools.airlift.monitor

import iris.tools.airlift.*
import kotlinx.io.files.Path
import kotlin.test.*

class PortablePreparationTest {
    @Test fun `remote mask discovers relative names and adjusts source root`() = kotlinx.coroutines.test.runTest {
        val plan = prepareTransfer(AirliftConfig("user@host", "/source/", Path("destination"), direction = TransferDirection.DOWNLOAD).copy(remoteRoot = "/db/*.MYD"), inventory = { base ->
            assertEquals("/db/", base)
            FileInventory(false, mapOf("small.MYD" to 2L, "big.MYD" to 12L, "sub/hidden.MYD" to 50L, "index.MYI" to 30L))
        })
        assertEquals("/db/", plan.config.remoteRoot)
        assertEquals(listOf("big.MYD", "small.MYD"), plan.tasks.map { it.path })
        assertEquals(listOf(12L, 2L), plan.tasks.map { it.size })
    }

    @Test fun `single file source uses parent root`() = kotlinx.coroutines.test.runTest {
        val plan = prepareTransfer(AirliftConfig("user@host", "/source/", Path("destination"), direction = TransferDirection.DOWNLOAD).copy(remoteRoot = "/db/one"), inventory = {
            FileInventory(true, mapOf("one" to 23L))
        })
        assertEquals("/db", plan.config.remoteRoot)
        assertEquals(23L, plan.tasks.single().size)
        assertEquals("one", plan.tasks.single().path)
    }

    @Test fun `masks support recursive and component matching`() = kotlinx.coroutines.test.runTest {
        assertTrue(fileMask("**/*.MY[DI]").matches("a.MYD"))
        assertTrue(fileMask("**/*.MY[DI]").matches("deep/a.MYI"))
        assertFalse(fileMask("*.MYD").matches("deep/a.MYD"))
        assertTrue(fileMask("a?.[!x]").matches("ab.y"))
        assertFalse(fileMask("a?.[!x]").matches("ab.x"))
    }

    @Test fun `SSH inventory quotes shell metacharacters and uses NUL records`() = kotlinx.coroutines.test.runTest {
        val command = inventoryCommand("/data/a'b;touch nope")
        assertTrue(command.contains("'/data/a'\"'\"'b;touch nope'"))
        assertTrue(command.contains("%s\\t%P\\0"))
        assertTrue(command.contains("find"))
    }

    @Test fun `CLI accepts masks without a manifest`() = kotlinx.coroutines.test.runTest {
        assertTrue("--run" in LaunchArguments.parse(listOf("user@host:/db/*.MYD", "/destination/")).enabled)
    }


    @Test fun `inventory decoder retains files directories symlinks and special entries`() = kotlinx.coroutines.test.runTest {
        val inventory = decodeInventory(listOf("D", "d\t0\t", "f\t42\tfile", "l\t7\tlink", "d\t0\tsub", "s\t0\tsocket"))
        assertFalse(inventory.singleFile)
        assertEquals(mapOf("file" to 42L, "link" to 7L), inventory.sizes)
        assertEquals(setOf("", "sub"), inventory.directories)
        assertEquals(setOf("socket"), inventory.otherEntries)
        assertEquals(mapOf("one" to 23L), decodeInventory(listOf("F", "23\tone")).sizes)
        assertFailsWith<IllegalArgumentException> { decodeInventory(listOf("invalid")) }
        assertFailsWith<IllegalArgumentException> { decodeInventory(listOf("D", "broken")) }
    }
}
