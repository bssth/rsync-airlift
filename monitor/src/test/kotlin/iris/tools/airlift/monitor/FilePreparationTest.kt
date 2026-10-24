package iris.tools.airlift.monitor

import iris.tools.airlift.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FilePreparationTest {
    private fun config(root: Path, direction: TransferDirection = TransferDirection.UPLOAD) =
        AirliftConfig("user@host", "/source/", root, direction = direction)

    @Test fun `local discovery measures all files and schedules largest first`() {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("small"), ByteArray(2))
            Files.createDirectories(root.resolve("sub"))
            Files.write(root.resolve("sub/large"), ByteArray(20))
            val plan = prepareTransfer(config(root))
            assertEquals(listOf("sub/large", "small"), plan.tasks.map { it.path })
            assertEquals(listOf(20L, 2L), plan.tasks.map { it.size })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `mask and manifest union removes duplicates and retains files outside mask`() {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("a.MYD"), ByteArray(2))
            Files.write(root.resolve("b.MYI"), ByteArray(12))
            Files.write(root.resolve("ignored"), ByteArray(30))
            val manifest = root.resolve("files.txt")
            Files.writeString(manifest, "a.MYD\r\nb.MYI\r\na.MYD\r\n")
            val plan = prepareTransfer(config(root), manifest, source = "$root/*.MYD".replace('\\', '/'))
            assertEquals(listOf("b.MYI", "a.MYD"), plan.tasks.map { it.path })
            assertEquals(root, plan.config.localRoot)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `manifest alone selects only listed files and supports NUL paths`() {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("with space"), ByteArray(3))
            Files.write(root.resolve("other"), ByteArray(10))
            val manifest = root.resolve("list")
            Files.writeString(manifest, "with space\u0000")
            assertEquals(listOf("with space"), prepareTransfer(config(root), manifest, true).tasks.map { it.path })
            Files.writeString(manifest, "missing\n")
            assertFailsWith<IllegalStateException> { prepareTransfer(config(root), manifest) }
            Files.writeString(manifest, "../other\n")
            assertFailsWith<IllegalArgumentException> { prepareTransfer(config(root), manifest) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `remote mask discovers relative names and adjusts source root`() {
        val plan = prepareTransfer(config(Path.of("destination"), TransferDirection.DOWNLOAD).copy(remoteRoot = "/db/*.MYD"), inventory = { base ->
            assertEquals("/db/", base)
            FileInventory(false, mapOf("small.MYD" to 2L, "big.MYD" to 12L, "sub/hidden.MYD" to 50L, "index.MYI" to 30L))
        })
        assertEquals("/db/", plan.config.remoteRoot)
        assertEquals(listOf("big.MYD", "small.MYD"), plan.tasks.map { it.path })
        assertEquals(listOf(12L, 2L), plan.tasks.map { it.size })
    }

    @Test fun `single file source uses parent root`() {
        val plan = prepareTransfer(config(Path.of("destination"), TransferDirection.DOWNLOAD).copy(remoteRoot = "/db/one"), inventory = {
            FileInventory(true, mapOf("one" to 23L))
        })
        assertEquals("/db", plan.config.remoteRoot)
        assertEquals(23L, plan.tasks.single().size)
        assertEquals("one", plan.tasks.single().path)
    }

    @Test fun `masks support recursive and component matching`() {
        assertTrue(fileMask("**/*.MY[DI]").matches("a.MYD"))
        assertTrue(fileMask("**/*.MY[DI]").matches("deep/a.MYI"))
        assertFalse(fileMask("*.MYD").matches("deep/a.MYD"))
        assertTrue(fileMask("a?.[!x]").matches("ab.y"))
        assertFalse(fileMask("a?.[!x]").matches("ab.x"))
    }

    @Test fun `SSH inventory quotes shell metacharacters and uses NUL records`() {
        val command = inventoryCommand("/data/a'b;touch nope")
        assertTrue(command.contains("'/data/a'\"'\"'b;touch nope'"))
        assertTrue(command.contains("%s\\t%P\\0"))
        assertTrue(command.contains("find"))
    }

    @Test fun `CLI accepts masks without a manifest`() {
        assertTrue("--run" in LaunchArguments.parse(listOf("user@host:/db/*.MYD", "/destination/")).enabled)
    }

    @Test fun `remote inventory shell command executes with quoted paths and exact sizes`() {
        val bash = Path.of("C:/Program Files/Git/bin/bash.exe")
        if (!Files.exists(bash)) return // Optional Windows integration with the bundled GNU find.
        val parent = Files.createTempDirectory("airlift-shell-")
        try {
            val root = Files.createDirectory(parent.resolve("a'b; literal"))
            Files.write(root.resolve("with space"), ByteArray(17))
            Files.createDirectory(root.resolve("nested"))
            Files.write(root.resolve("nested/other"), ByteArray(5))
            val process = ProcessBuilder(bash.toString(), "-s").start()
            process.outputStream.bufferedWriter().use { it.write(inventoryCommand(root.toString().replace('\\', '/'))) }
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            val error = process.errorStream.readAllBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.waitFor(), error)
            assertEquals(setOf("D", "17\twith space", "5\tnested/other", ""), output.split('\u0000').toSet())
        } finally { parent.toFile().deleteRecursively() }
    }
}
