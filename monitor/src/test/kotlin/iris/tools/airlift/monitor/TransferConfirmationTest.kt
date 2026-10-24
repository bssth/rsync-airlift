package iris.tools.airlift.monitor

import iris.tools.airlift.TransferDirection
import kotlin.test.*

class TransferConfirmationTest {
    @Test fun `MC shell links work automatically in either direction`() {
        val mc = "/sh://user@example.org/home/my files/"
        val download = TransferRoots.parseSources(listOf(mc, "/backup/" )).single()
        assertEquals("user@example.org", download.host)
        assertEquals("/home/my files/", download.remoteRoot)
        assertEquals(TransferDirection.DOWNLOAD, download.direction)
        val args = LaunchArguments.parse(listOf("/backup/", mc))
        assertEquals("user@example.org:/home/my files/", args.paths.last())
        assertEquals(TransferDirection.UPLOAD, TransferRoots.parseSources(args.paths).single().direction)
        assertEquals(args.paths, LaunchArguments.parse(listOf("--mc", "/backup/", mc)).paths)
        assertEquals("user@example.org:/", normalizeEndpoint("/sh://user@example.org"))
        assertEquals("user@[2001:db8::1]:/data", normalizeEndpoint("/sh://user@[2001:db8::1]/data"))
    }

    @Test fun `ordinary paths are unchanged and mixed remote hosts stay rejected`() {
        for (path in listOf("/local/path", "user@host:/data", "./file", "C:\\data")) assertEquals(path, normalizeEndpoint(path))
        assertFailsWith<IllegalArgumentException> { TransferRoots.parseSources(listOf("/sh://user@one/data", "/sh://user@two/backup")) }
        assertFailsWith<IllegalArgumentException> { normalizeEndpoint("/sh:///data") }
        assertFailsWith<IllegalArgumentException> { normalizeEndpoint("/sh://user:password@host/data") }
    }

    @Test fun `confirmation displays normalized endpoints including multiple sources and respects refusal`() {
        var calls = 0
        assertFalse(confirmTransfer(listOf("/sh://user@host/data", "/sh://user@host/more", "/backup/"), false, ask = { text ->
            calls++
            assertContains(text, "From: user@host:/data\n      user@host:/more")
            assertContains(text, "To:   /backup/")
            false
        }))
        assertEquals(1, calls)
        assertTrue(confirmTransfer(listOf("/data", "user@host:/backup"), false, ask = { true }))
        assertTrue(confirmTransfer(listOf("/data", "user@host:/backup"), true, ask = { fail("--yes must not prompt") }))
        assertTrue("--yes" in LaunchArguments.parse(listOf("--serve", "--yes", "/data", "user@host:/backup")).enabled)
    }
}
