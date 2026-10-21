package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppHubTest {

    @Before fun reset() = AppHub.resetForTest()

    @Test fun playIsRefusedWithoutSurface() {
        val refusal = AppHub.command(PlayerCommand.PLAY)
        assertEquals("Not connected to Android Auto", refusal)
    }

    @Test fun playIsRefusedWhileMovingWithReason() {
        AppHub.dispatch(AppEvent.CarSessionStarted)
        AppHub.dispatch(AppEvent.SurfaceAvailable(SurfaceInfo(800, 480, 160)))
        AppHub.dispatch(AppEvent.GateChanged(GateState.Closed(BlockReason.MOVING)))
        assertEquals("Vehicle moving: video paused", AppHub.command(PlayerCommand.PLAY))
    }

    @Test fun playIsSentWhenParked() {
        AppHub.dispatch(AppEvent.CarSessionStarted)
        AppHub.dispatch(AppEvent.SurfaceAvailable(SurfaceInfo(800, 480, 160)))
        AppHub.dispatch(AppEvent.GateChanged(GateState.Open(SpeedSource.CAR)))
        assertNull(AppHub.command(PlayerCommand.PLAY))
    }

    @Test fun pauseIsAlwaysAllowed() = assertNull(AppHub.command(PlayerCommand.PAUSE))

    @Test fun gateTransitionsAreLogged() {
        AppHub.dispatch(AppEvent.GateChanged(GateState.Open(SpeedSource.PHONE_GPS)))
        AppHub.dispatch(AppEvent.GateChanged(GateState.Closed(BlockReason.MOVING)))
        val gateLines = AppHub.log.snapshot().filter { it.category == TransitionLog.GATE }.takeLast(2).map { it.message }
        assertEquals(listOf("OPEN by PHONE_GPS", "CLOSED MOVING"), gateLines)
    }

    @Test fun logListenersSeeNewLines() {
        val seen = mutableListOf<String>()
        val listener: (TransitionLog.Entry) -> Unit = { seen += it.message }
        AppHub.addLogListener(listener)
        AppHub.record("test", "hello https://youtu.be/dQw4w9WgXcQ?si=secret")
        AppHub.removeLogListener(listener)
        assertNotNull(seen.lastOrNull())
        assertTrue(seen.last().contains("youtu.be/dQw…"))
        assertTrue(!seen.last().contains("secret"))
    }
}
