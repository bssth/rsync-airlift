package iris.tools.airlift

import iris.tools.airlift.io.JvmFileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal actual suspend fun platformSshTemporaryDirectory(prefix: String): Path = withContext(Dispatchers.IO) {
    Path(JvmFileAccess.temporaryDirectory(prefix).toString())
}

internal actual suspend fun platformDeleteSshDirectory(path: Path) {
    withContext(Dispatchers.IO) { JvmFileAccess.deleteOwnedDirectory(java.nio.file.Path.of(path.toString())) }
}
