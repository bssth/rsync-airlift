package iris.tools.airlift.monitor

import kotlinx.io.files.Path

/** Absolute lexical path; unlike filesystem resolution it also supports missing destinations. */
internal expect fun Path.absolutePath(normalize: Boolean = false): Path
internal fun Path.resolve(child: String): Path = Path(this, child)

/** Demo runs already own a unique temporary directory; never collide with an older run. */
internal fun monitorEndpoint(explicit: String?, demoRoot: Path?): Path =
    (explicit?.let(::Path) ?: demoRoot?.resolve("airlift-monitor.connection")
        ?: Path("airlift-monitor.connection")).absolutePath()

