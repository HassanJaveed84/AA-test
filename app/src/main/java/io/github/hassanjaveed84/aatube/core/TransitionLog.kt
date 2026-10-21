package io.github.hassanjaveed84.aatube.core

/**
 * Bounded, in-memory event log: gate transitions, surface callbacks, player
 * states. Lines are redacted before they are stored. The lab build also
 * writes it to a file the user can export; nothing leaves the phone otherwise.
 */
class TransitionLog(private val capacity: Int = 2_000) {

    data class Entry(val atMillis: Long, val category: String, val message: String)

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(atMillis: Long, category: String, message: String): Entry {
        val entry = Entry(atMillis, category, LogRedactor.redact(message))
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
        return entry
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    /** Records a gate change only when it differs from the previous gate entry. */
    @Synchronized
    fun addGateIfChanged(atMillis: Long, gate: GateState): Entry? {
        val text = describe(gate)
        val previous = entries.lastOrNull { it.category == GATE }
        if (previous?.message == text) return null
        val entry = Entry(atMillis, GATE, text)
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
        return entry
    }

    companion object {
        const val GATE = "gate"

        fun describe(gate: GateState): String = when (gate) {
            is GateState.Open -> "OPEN by ${gate.confirmedBy}"
            is GateState.Closed -> "CLOSED ${gate.reason}"
        }
    }
}
