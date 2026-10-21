package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextTest {

    @Test fun missingTelemetryIsExplained() {
        val text = StatusText.gate(GateState.Closed(BlockReason.NO_SPEED_SIGNAL))
        assertTrue(text.contains("no car speed"))
        assertTrue(text.contains("permission"))
    }

    @Test fun gateSourcesAreNamed() {
        assertEquals("Parked (car speed 0)", StatusText.gate(GateState.Open(SpeedSource.CAR)))
        assertEquals("Parked (phone GPS)", StatusText.gate(GateState.Open(SpeedSource.PHONE_GPS)))
    }

    @Test fun pausedByGateIsDistinct() {
        assertEquals("Paused for driving", StatusText.player(PlayerStatus.Paused(byGate = true)))
        assertEquals("Paused", StatusText.player(PlayerStatus.Paused()))
    }

    @Test fun connectionStates() {
        assertEquals("Not connected to Android Auto", StatusText.connection(AppState()))
        assertEquals("Connected; open AA Tube on the car screen", StatusText.connection(AppState(carConnected = true)))
        assertEquals(
            "Car screen 1920×720 @ 160dpi",
            StatusText.connection(AppState(carConnected = true, surface = SurfaceInfo(1920, 720, 160))),
        )
    }

    @Test fun iframeErrorsMap() {
        assertEquals(PlayerError.EMBED_BLOCKED, PlayerError.fromIframeCode(150))
        assertEquals(PlayerError.EMBED_BLOCKED, PlayerError.fromIframeCode(101))
        assertEquals(PlayerError.MISSING_REFERRER, PlayerError.fromIframeCode(153))
        assertEquals(PlayerError.NOT_FOUND, PlayerError.fromIframeCode(100))
        assertEquals(PlayerError.UNKNOWN, PlayerError.fromIframeCode(999))
    }
}
