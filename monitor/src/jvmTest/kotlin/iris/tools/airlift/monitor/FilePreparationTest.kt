package iris.tools.airlift.monitor

import iris.tools.airlift.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FilePreparationTest {
    private fun config(root: Path, direction: TransferDirection = TransferDirection.UPLOAD) =
        AirliftConfig("user@host", "/source/", root.toPortablePath(), direction = direction)

    @Test fun `local discovery measures all files and schedules largest first`() = kotlinx.coroutines.test.runTest {
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

    @Test fun `mask and manifest union removes duplicates and retains files outside mask`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("a.MYD"), ByteArray(2))
            Files.write(root.resolve("b.MYI"), ByteArray(12))
            Files.write(root.resolve("ignored"), ByteArray(30))
            val manifest = root.resolve("files.txt")
            Files.writeString(manifest, "a.MYD\r\nb.MYI\r\na.MYD\r\n")
            val plan = prepareTransfer(config(root), manifest.toPortablePath(), source = "$root/*.MYD".replace('\\', '/'))
            assertEquals(listOf("b.MYI", "a.MYD"), plan.tasks.map { it.path })
            assertEquals(root.toPortablePath(), plan.config.localRoot)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `manifest alone selects only listed files and supports NUL paths`() = kotlinx.coroutines.test.runTest {
        val root = Files.createTempDirectory("airlift-plan-")
        try {
            Files.write(root.resolve("with space"), ByteArray(3))
            Files.write(root.resolve("other"), ByteArray(10))
            val manifest = root.resolve("list")
            Files.writeString(manifest, "with space\u0000")
            assertEquals(listOf("with space"), prepareTransfer(config(root), manifest.toPortablePath(), true).tasks.map { it.path })
            Files.writeString(manifest, "missing\n")
            assertFailsWith<IllegalStateException> { prepareTransfer(config(root), manifest.toPortablePath()) }
            Files.writeString(manifest, "../other\n")
            assertFailsWith<IllegalArgumentException> { prepareTransfer(config(root), manifest.toPortablePath()) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `remote inventory shell command executes with quoted paths and exact sizes`() = kotlinx.coroutines.test.runTest {
        val bash = Path.of("C:/Program Files/Git/bin/bash.exe")
        if (!Files.exists(bash)) return@runTest // Optional Windows integration with the bundled GNU find.
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
            val records = output.split('\u0000')
            assertEquals("D", records.first())
            assertTrue("f\t17\twith space" in records)
            assertTrue("f\t5\tnested/other" in records)
            assertTrue(records.any { it.startsWith("d\t") && it.endsWith("\tnested") })
        } finally { parent.toFile().deleteRecursively() }
    }
}
