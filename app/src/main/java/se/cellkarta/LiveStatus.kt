package se.cellkarta

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Delad status mellan bakgrundstjänsten och kartvyn. */
object LiveStatus {

    data class State(
        val running: Boolean = false,
        val serving: String? = null,
        val servingKey: String? = null,
        val lastScan: Long = 0L,
        val note: String? = null,
        /** Det telefonen visar i statusraden, till exempel "5G (via 4G)". */
        val display: String? = null,
    )

    val state = MutableStateFlow(State())

    /** Räknas upp när något nytt ska ritas på kartan. */
    val dataVersion = MutableStateFlow(0L)

    fun setRunning(running: Boolean) = state.update {
        if (running) it.copy(running = true) else State()
    }

    fun scanned(serving: String?, servingKey: String?, ts: Long) {
        val changedCell = state.value.servingKey != servingKey
        state.update { it.copy(serving = serving, servingKey = servingKey, lastScan = ts, note = null) }
        if (changedCell) bump()
    }

    fun note(text: String) = state.update { it.copy(note = text) }

    fun display(text: String?) = state.update { it.copy(display = text) }

    fun bump() = dataVersion.update { it + 1 }
}
