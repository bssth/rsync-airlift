package iris.tools.airlift.monitor

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.*

class LocalInventoryTest {
    @Test fun `inventory includes directory links without traversing their targets`() {
        val root = Files.createTempDirectory("airlift-links-")
        val outside = Files.createTempDirectory("airlift-link-target-")
        try {
            Files.writeString(outside.resolve("outside"), "do not traverse")
            val link = root.resolve("linked")
            try { Files.createSymbolicLink(link, outside) }
            catch (_: java.nio.file.FileSystemException) { return }
            catch (_: UnsupportedOperationException) { return }
            val inventory = readLocalInventory(root.toString())
            assertEquals(setOf("linked"), inventory.sizes.keys)
            assertEquals(Files.readAttributes(link, BasicFileAttributes::class.java, NOFOLLOW_LINKS).size(), inventory.sizes["linked"])
            assertEquals(setOf(""), inventory.directories)
            val single = readLocalInventory(link.toString())
            assertTrue(single.singleFile)
        } finally {
            iris.tools.airlift.io.JvmFileAccess.deleteOwnedDirectory(root)
            iris.tools.airlift.io.JvmFileAccess.deleteOwnedDirectory(outside)
        }
    }
}
