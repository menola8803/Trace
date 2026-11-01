package se.cellkarta

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Delad status mellan bakgrundstjänsten och kartvyn. */
object LiveStatus {

    data class State(
        val running: Boolean = false,
        val serving: String? = null,
        val lastScan: Long = 0L,
        val note: String? = null,
    )

    val state = MutableStateFlow(State())

    /** Räknas upp när något nytt ska ritas på kartan. */
    val dataVersion = MutableStateFlow(0L)

    fun setRunning(running: Boolean) = state.update {
        if (running) it.copy(running = true) else State()
    }

    fun scanned(serving: String?, ts: Long) =
        state.update { it.copy(serving = serving, lastScan = ts, note = null) }

    fun note(text: String) = state.update { it.copy(note = text) }

    fun bump() = dataVersion.update { it + 1 }
}
