package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ParkedGateTest {

    private val gate = ParkedGate()

    @Test fun closedWithoutAnySignal() =
        assertEquals(GateState.Closed(BlockReason.NO_SPEED_SIGNAL), gate.evaluate(0))

    @Test fun carSpeedZeroOpens() {
        gate.onCarSpeed(0f, available = true, nowMillis = 0)
        assertEquals(GateState.Open(SpeedSource.CAR), gate.evaluate(0))
    }

    @Test fun carSpeedDoesNotExpire() {
        // The host reports on change; a parked car may stay silent for hours.
        gate.onCarSpeed(0f, available = true, nowMillis = 0)
        assertEquals(GateState.Open(SpeedSource.CAR), gate.evaluate(3_600_000))
    }

    @Test fun carMovingCloses() {
        gate.onCarSpeed(2f, available = true, nowMillis = 0)
        assertEquals(GateState.Closed(BlockReason.MOVING), gate.evaluate(0))
    }

    @Test fun reverseSpeedCountsAsMoving() {
        gate.onCarSpeed(-1f, available = true, nowMillis = 0)
        assertEquals(GateState.Closed(BlockReason.MOVING), gate.evaluate(0))
    }

    @Test fun unavailableCarSpeedIsIgnored() {
        gate.onCarSpeed(null, available = false, nowMillis = 0)
        assertEquals(GateState.Closed(BlockReason.NO_SPEED_SIGNAL), gate.evaluate(0))
    }

    @Test fun phoneGpsFallbackOpensWhileFresh() {
        gate.onCarSpeed(null, available = false, nowMillis = 0)
        gate.onPhoneSpeed(0.3f, nowMillis = 0)
        assertEquals(GateState.Open(SpeedSource.PHONE_GPS), gate.evaluate(1_000))
    }

    @Test fun phoneGpsExpires() {
        gate.onPhoneSpeed(0f, nowMillis = 0)
        assertEquals(GateState.Closed(BlockReason.NO_SPEED_SIGNAL), gate.evaluate(10_001))
    }

    @Test fun phoneFixWithoutSpeedDoesNotConfirmParked() {
        gate.onPhoneSpeed(null, nowMillis = 0)
        assertEquals(GateState.Closed(BlockReason.NO_SPEED_SIGNAL), gate.evaluate(0))
    }

    @Test fun anyMovingSourceWinsOverAStationaryOne() {
        gate.onCarSpeed(0f, available = true, nowMillis = 0)
        gate.onPhoneSpeed(5f, nowMillis = 0)
        assertEquals(GateState.Closed(BlockReason.MOVING), gate.evaluate(0))
    }

    @Test fun gpsJitterBelowThresholdStaysOpen() {
        gate.onPhoneSpeed(1.2f, nowMillis = 0)
        assertEquals(GateState.Open(SpeedSource.PHONE_GPS), gate.evaluate(0))
    }

    @Test fun settlesForFiveSecondsAfterMoving() {
        gate.onCarSpeed(3f, available = true, nowMillis = 0)
        assertEquals(GateState.Closed(BlockReason.MOVING), gate.evaluate(0))
        gate.onCarSpeed(0f, available = true, nowMillis = 1_000)
        assertEquals(GateState.Closed(BlockReason.SETTLING), gate.evaluate(1_000))
        assertEquals(GateState.Closed(BlockReason.SETTLING), gate.evaluate(4_999))
        assertEquals(GateState.Open(SpeedSource.CAR), gate.evaluate(5_000))
    }

    @Test fun movementDuringSettlingRestartsTheWait() {
        gate.onCarSpeed(3f, available = true, nowMillis = 0)
        gate.evaluate(0)
        gate.onCarSpeed(0f, available = true, nowMillis = 1_000)
        gate.evaluate(4_000)
        gate.onCarSpeed(1f, available = true, nowMillis = 4_500)
        assertEquals(GateState.Closed(BlockReason.MOVING), gate.evaluate(4_500))
        gate.onCarSpeed(0f, available = true, nowMillis = 5_000)
        assertEquals(GateState.Closed(BlockReason.SETTLING), gate.evaluate(9_000))
        assertEquals(GateState.Open(SpeedSource.CAR), gate.evaluate(9_500))
    }

    @Test fun losingAllSignalsWhileParkedCloses() {
        gate.onPhoneSpeed(0f, nowMillis = 0)
        assertEquals(GateState.Open(SpeedSource.PHONE_GPS), gate.evaluate(0))
        assertEquals(GateState.Closed(BlockReason.NO_SPEED_SIGNAL), gate.evaluate(20_000))
    }

    @Test fun readingsAreKeptForDiagnostics() {
        gate.onCarSpeed(0f, available = true, nowMillis = 7)
        gate.onPhoneSpeed(0.5f, nowMillis = 9)
        assertEquals(SpeedReading(SpeedSource.CAR, 0f, 7, true), gate.carReading)
        assertEquals(SpeedReading(SpeedSource.PHONE_GPS, 0.5f, 9, true), gate.phoneReading)
    }
}
