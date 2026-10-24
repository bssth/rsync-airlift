package iris.tools.airlift.monitor

import iris.tools.airlift.AirliftSnapshot

class AirliftMonitor(private val source: MonitorSource, private val title: String = source.title, private val swing: Boolean = false) {
    private val renderer = MonitorRenderer(source, title)
    fun frame(size: MonitorSize, snapshot: AirliftSnapshot, model: MonitorModel): MonitorFrame = renderer.render(size, snapshot, model)

    /** Q detaches only; it never closes the source or dispatcher. */
    suspend fun run(terminal: MonitorTerminal = createMonitorTerminal(title, swing)) {
        try {
            terminal.start()
            val model = MonitorModel()
            while (true) {
                terminal.present(frame(terminal.size(), source.snapshot(), model))
                val event = terminal.pollEvent()
                if (event is MonitorEvent.Mouse) {
                    model.handleMouse(event, source.snapshot())
                    continue
                }
                val key = event as? MonitorEvent.Key
                when (key?.key) {
                    MonitorKey.EOF -> return
                    MonitorKey.ArrowUp -> model.selected--
                    MonitorKey.ArrowDown -> model.selected++
                    MonitorKey.ArrowLeft -> if (model.view == MonitorView.WORKERS) model.selected -= model.gridRows
                    MonitorKey.ArrowRight -> if (model.view == MonitorView.WORKERS) model.selected += model.gridRows
                    MonitorKey.PageUp -> model.selected -= model.pageCapacity
                    MonitorKey.PageDown -> model.selected += model.pageCapacity
                    MonitorKey.Home -> model.selected = 0
                    MonitorKey.End -> model.selected = Int.MAX_VALUE
                    MonitorKey.F2, MonitorKey.Enter -> model.details = !model.details
                    MonitorKey.Tab -> { model.view = MonitorView.entries[(model.view.ordinal + 1) % 3]; model.selected = 0 }
                    MonitorKey.Character -> {
                        val char = key.character ?: ' '
                        if (char.lowercaseChar() == 'q') return
                        try { model.handle(char, source) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (error: Exception) { model.message = error.message ?: "Command failed" }
                    }
                    else -> Unit
                }
                kotlinx.coroutines.delay(200)
            }
        } finally { terminal.close() }
    }
}
