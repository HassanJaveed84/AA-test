package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransitionLogTest {

    @Test fun boundedCapacity() {
        val log = TransitionLog(capacity = 3)
        repeat(5) { log.add(it.toLong(), "c", "m$it") }
        assertEquals(listOf("m2", "m3", "m4"), log.snapshot().map { it.message })
    }

    @Test fun gateEntriesOnlyOnChange() {
        val log = TransitionLog()
        log.addGateIfChanged(0, GateState.Closed(BlockReason.NO_SPEED_SIGNAL))
        assertNull(log.addGateIfChanged(1, GateState.Closed(BlockReason.NO_SPEED_SIGNAL)))
        log.add(2, "speed", "noise")
        assertNull(log.addGateIfChanged(3, GateState.Closed(BlockReason.NO_SPEED_SIGNAL)))
        log.addGateIfChanged(4, GateState.Open(SpeedSource.CAR))
        assertEquals(
            listOf("CLOSED NO_SPEED_SIGNAL", "OPEN by CAR"),
            log.snapshot().filter { it.category == TransitionLog.GATE }.map { it.message },
        )
    }
}
