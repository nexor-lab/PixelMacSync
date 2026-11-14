package it.luigi.macsync

/**
 * Debug-only diagnostics hook.
 *
 * `log()` is a no-op unless a sink is installed, and the only sink is installed
 * by the **debug** source set (`DebugJournal`, via `DebugApp`). Beta and release
 * builds never install a sink, so they never create or store diagnostics content.
 */
object Diagnostics {

    @Volatile
    private var sink: ((String) -> Unit)? = null

    fun install(s: (String) -> Unit) {
        sink = s
    }

    fun log(message: String) {
        sink?.invoke(message)
    }
}
