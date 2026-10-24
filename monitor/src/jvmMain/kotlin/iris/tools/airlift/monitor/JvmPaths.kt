package iris.tools.airlift.monitor

import kotlinx.io.files.Path

internal fun java.nio.file.Path.toPortablePath() = Path(toString())
internal fun Path.toNioPath() = java.nio.file.Path.of(toString())
internal actual fun Path.absolutePath(normalize: Boolean): Path = toNioPath().toAbsolutePath()
    .let { if (normalize) it.normalize() else it }.toPortablePath()
