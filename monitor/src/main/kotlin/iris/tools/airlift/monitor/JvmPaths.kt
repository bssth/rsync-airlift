package iris.tools.airlift.monitor

internal fun java.nio.file.Path.toPortablePath() = kotlinx.io.files.Path(toString())
internal fun kotlinx.io.files.Path.toNioPath() = java.nio.file.Path.of(toString())
