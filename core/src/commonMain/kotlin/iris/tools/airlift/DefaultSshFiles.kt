package iris.tools.airlift

import iris.tools.airlift.io.FileContent
import iris.tools.airlift.process.ProcessByteSink
import kotlinx.coroutines.*
import kotlinx.io.files.Path

/** Shared file contents and buffered logs; only atomic temporary-directory operations are platform-specific. */
class DefaultSshFiles(private val dispatcher: CoroutineDispatcher = platformWorkerDispatcher()) : SshFiles {
    override suspend fun temporaryDirectory(prefix: String): Path {
        val directory = withContext(NonCancellable) { platformSshTemporaryDirectory(prefix) }
        try { currentCoroutineContext().ensureActive(); return directory }
        catch (e: CancellationException) { deleteOwnedDirectory(directory); throw e }
    }
    override suspend fun deleteOwnedDirectory(path: Path) {
        withContext(NonCancellable) { platformDeleteSshDirectory(path) }
    }

    override suspend fun createDirectories(path: Path) {
        withContext(dispatcher) { FileContent.createDirectories(path.toString()) }
    }

    override suspend fun readTail(path: Path, maxCharacters: Int): String = withContext(dispatcher) {
        require(maxCharacters >= 0)
        FileContent.readText(FileContent.source(path.toString())).takeLast(maxCharacters)
    }

    override suspend fun log(path: Path): ProcessByteSink {
        // A cancellation during open must not lose the newly created sink.
        val sink = withContext(NonCancellable) {
            withContext(dispatcher) { FileContent.sink(path.toString()) }
        }
        val output = object : ProcessByteSink {
            override suspend fun write(bytes: ByteArray) { withContext(dispatcher) { sink.write(bytes) } }
            override suspend fun flush() { withContext(dispatcher) { sink.flush() } }
            override suspend fun close() { withContext(dispatcher) { sink.close() } }
        }
        try { currentCoroutineContext().ensureActive(); return output }
        catch (e: CancellationException) { withContext(NonCancellable) { output.close() }; throw e }
    }
}
