package iris.tools.airlift

import iris.tools.airlift.process.ProcessByteSink
import iris.tools.airlift.process.ProcessSpec
import kotlinx.io.files.Path

interface SshFiles {
    suspend fun temporaryDirectory(prefix: String): Path
    suspend fun createDirectories(path: Path)
    suspend fun readTail(path: Path, maxCharacters: Int): String
    suspend fun log(path: Path): ProcessByteSink
    /** Only called for a unique directory created by this session; must not follow symlinks. */
    suspend fun deleteOwnedDirectory(path: Path)
}

fun interface ProcessAuthentication {
    fun prepare(spec: ProcessSpec): ProcessSpec
}

internal expect fun platformProcessLauncher(): iris.tools.airlift.process.ProcessLauncher
internal expect suspend fun platformSshTemporaryDirectory(prefix: String): Path
internal expect suspend fun platformDeleteSshDirectory(path: Path)
