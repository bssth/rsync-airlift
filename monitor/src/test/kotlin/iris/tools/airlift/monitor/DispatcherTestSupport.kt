package iris.tools.airlift.monitor

internal suspend inline fun <T> iris.tools.airlift.AirliftDispatcher.use(block: (iris.tools.airlift.AirliftDispatcher) -> T): T =
    try { block(this) } finally { close() }
