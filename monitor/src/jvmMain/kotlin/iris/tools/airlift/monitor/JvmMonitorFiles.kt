package iris.tools.airlift.monitor

import iris.tools.airlift.io.JvmFileAccess
import kotlinx.io.files.Path

internal object MonitorFiles {
    fun temporaryDirectory(prefix: String): Path = JvmFileAccess.temporaryDirectory(prefix).toPortablePath()
    fun temporaryFile(prefix: String, suffix: String): Path = JvmFileAccess.temporaryFile(prefix, suffix).toPortablePath()
    fun createDirectories(path: Path) = JvmFileAccess.createDirectories(path)
    fun createPrivateFile(path: Path) = JvmFileAccess.createPrivateFile(path.toNioPath())
    fun inputStream(path: Path) = JvmFileAccess.inputStream(path.toNioPath())
    fun outputStream(path: Path) = JvmFileAccess.outputStream(path.toNioPath())
    fun readText(path: Path) = JvmFileAccess.readText(path)
    fun bufferedWriter(path: Path) = JvmFileAccess.bufferedWriter(path)
    fun deleteIfExists(path: Path) = JvmFileAccess.deleteIfExists(path.toNioPath())
}
