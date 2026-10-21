package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateDiagnosticsTest {

    private fun diagnostics(gate: ParkedGate, now: Long) = GateDiagnostics(
        nowMillis = now,
        evaluation = gate.lastEvaluation,
        carApiLevel = 7,
        carSetup = SourceSetup.STARTED,
        carSpeedPermission = true,
        car = gate.carReading,
        carStatus = "SUCCESS",
        carCallbacks = 3,
        fineLocationPermission = true,
        coarseLocationPermission = true,
        gpsSetup = SourceSetup.STARTED,
        gpsProviderEnabled = true,
        phone = gate.phoneReading,
        phoneAccuracyMeters = 4.5f,
        phoneFixes = 12,
        keepAlive = KeepAliveState.RUNNING,
        notificationsPermission = true,
    )

    @Test fun noSourceIsReportedAsNone() {
        val gate = ParkedGate()
        gate.evaluate(0)
        val line = diagnostics(gate, 0).format()
        assertTrue(line, line.startsWith("decision=CLOSED NO_SPEED_SIGNAL speedSource=NONE"))
        assertTrue(line, line.contains("car: api=7 setup=STARTED perm=granted last=none"))
    }

    @Test fun carSourceWithAge() {
        val gate = ParkedGate()
        gate.onCarSpeed(0f, available = true, nowMillis = 1_000)
        gate.evaluate(3_500)
        val d = diagnostics(gate, 3_500)
        assertEquals("CAR", d.speedSource)
        assertTrue(d.format(), d.format().contains("last=0.00m/s(age 2.50s)"))
    }

    @Test fun gpsFallbackSource() {
        val gate = ParkedGate()
        gate.onCarSpeed(null, available = false, nowMillis = 0)
        gate.onPhoneSpeed(0.2f, nowMillis = 0)
        gate.evaluate(500)
        val d = diagnostics(gate, 500)
        assertEquals("PHONE_GPS", d.speedSource)
        assertTrue(d.format(), d.format().contains("car: api=7 setup=STARTED perm=granted last=unavailable(age 0.50s)"))
        assertTrue(d.format(), d.format().contains("acc=4.50m fixes=12"))
    }

    @Test fun bothSourcesAndMovingBy() {
        val gate = ParkedGate()
        gate.onCarSpeed(0f, available = true, nowMillis = 0)
        gate.onPhoneSpeed(6f, nowMillis = 0)
        gate.evaluate(0)
        val d = diagnostics(gate, 0)
        assertEquals("CAR+PHONE_GPS", d.speedSource)
        assertTrue(d.format(), d.format().startsWith("decision=CLOSED MOVING speedSource=CAR+PHONE_GPS movingBy=PHONE_GPS"))
    }

    @Test fun staleGpsDropsOutOfTheSource() {
        val gate = ParkedGate()
        gate.onPhoneSpeed(0f, nowMillis = 0)
        gate.evaluate(30_000)
        assertEquals("NONE", diagnostics(gate, 30_000).speedSource)
    }

    @Test fun keepAliveAndPermissionsAppear() {
        val gate = ParkedGate()
        gate.evaluate(0)
        val line = diagnostics(gate, 0).format()
        assertTrue(line, line.contains("keepAlive=RUNNING notifications=granted"))
        assertTrue(line, line.contains("gps: perm fine=granted coarse=granted setup=STARTED provider=enabled"))
    }
}
