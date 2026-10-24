package iris.tools.airlift.monitor

internal actual fun confirmationColorsEnabled(): Boolean = System.getenv("NO_COLOR") == null && System.getenv("TERM") != "dumb"
internal actual fun askTransferConfirmation(text: String, swing: Boolean): Boolean {
    return if (swing) javax.swing.JOptionPane.showConfirmDialog(null, text, "Rsync Airlift — confirm transfer",
        javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE) == javax.swing.JOptionPane.YES_OPTION
    else {
        val console = System.console() ?: error("Transfer confirmation requires a terminal. Use --yes for an unattended run.")
        console.printf("%s%n", emphasizeConfirmation(text))
        console.readLine("Start synchronization? [y/N]: ")?.trim()?.lowercase() in setOf("y", "yes")
    }
}
