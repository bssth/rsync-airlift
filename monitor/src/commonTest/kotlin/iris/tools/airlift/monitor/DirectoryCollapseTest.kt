package iris.tools.airlift.monitor

import iris.tools.airlift.TransferTask
import kotlin.test.*

class DirectoryCollapseTest {
    private fun inventory(vararg files: String) = DirectoryInventory(null, "", files.toSet(), emptySet(), emptySet())
    private fun job(vararg files: String) = TransferTask(files.first(), files.size.toLong(), files = files.toList())

    @Test fun `seventeen thousand files collapse to root without losing accounting`() {
        val files = (1..17000).map { "f$it" }
        val input = files.map { TransferTask(it, 0) }
        val jobs = batchSmallFiles(input)
        val optimized = DirectoryCollapser(listOf(inventory(*files.toTypedArray()))).optimize(jobs.single())
        assertEquals(listOf("."), optimized.entries)
        assertEquals(17000, optimized.fileCount)
        assertEquals(0L, optimized.size)
        assertEquals(files.toSet(), optimized.files.toSet())
    }

    @Test fun `hybrid list collapses only fully owned directories and never already assigned files`() {
        val collapser = DirectoryCollapser(listOf(inventory("a/x", "a/y", "b/x", "b/y")))
        assertEquals(listOf("a/", "b/x"), collapser.optimize(job("a/x", "a/y", "b/x")).entries)
        assertTrue(collapser.optimize(job("a/x", "b/x")).entries.isEmpty())
        assertEquals(listOf("."), collapser.optimize(job("a/x", "a/y", "b/x", "b/y")).entries)
    }

    @Test fun `empty dirs special entries and incomplete inventory prevent parent collapse`() {
        val source = inventory("a/x", "a/y").copy(directories = setOf("a", "empty"))
        assertEquals(listOf("a/"), DirectoryCollapser(listOf(source)).optimize(job("a/x", "a/y")).entries)
        val special = inventory("a/x", "a/y").copy(otherEntries = setOf("a/socket"))
        assertTrue(DirectoryCollapser(listOf(special)).optimize(job("a/x", "a/y")).entries.isEmpty())
        assertTrue(DirectoryCollapser(emptyList()).optimize(job("a/x", "a/y")).entries.isEmpty())
        assertTrue(DirectoryCollapser(listOf(inventory("a/u", "a/v"))).optimize(job("a/x", "a/y")).entries.isEmpty())
    }

    @Test fun `scoped inventories cannot prove their parents or another source root`() {
        val source = DirectoryInventory("/source", "a", setOf("a/x", "a/y"), setOf("a"), emptySet())
        assertEquals(listOf("a/"), DirectoryCollapser(listOf(source)).optimize(job("a/x", "a/y").copy(sourceRoot = "/source")).entries)
        assertTrue(DirectoryCollapser(listOf(source)).optimize(job("a/x", "a/y")).entries.isEmpty())
    }

    @Test fun `destructive or symlink following options keep exact file lists`() {
        for (options in listOf("--delete", "--delete-excluded", "--remove-source-files", "-aL", "--copy-dirlinks", "--no-recursive")) {
            assertTrue(DirectoryCollapser(listOf(inventory("a", "b")), options).optimize(job("a", "b")).entries.isEmpty())
        }
    }

    @Test fun `large prefix stays size ordered while small tail becomes path ordered`() {
        val tasks = listOf(TransferTask("big-b", 300), TransferTask("big-a", 200), TransferTask("z", 90), TransferTask("a", 80), TransferTask("b", 10))
        val jobs = batchSmallFiles(tasks, 100)
        assertEquals(listOf("big-b", "big-a"), jobs.take(2).map { it.path })
        assertEquals(listOf("a", "b", "z"), jobs.last().files)
    }
}
