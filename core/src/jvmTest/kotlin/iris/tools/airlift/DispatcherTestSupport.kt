package iris.tools.airlift

internal suspend inline fun <T> AirliftDispatcher.use(block: (AirliftDispatcher) -> T): T =
    try { block(this) } finally { close() }
