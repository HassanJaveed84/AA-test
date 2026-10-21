package io.github.hassanjaveed84.aatube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReducerTest {

    private val video = VideoRef("dQw4w9WgXcQ")
    private val surface = SurfaceInfo(1920, 720, 160)
    private val open = GateState.Open(SpeedSource.CAR)
    private val moving = GateState.Closed(BlockReason.MOVING)

    private fun reduce(vararg events: AppEvent, from: AppState = AppState()) =
        events.fold(from) { state, event -> AppReducer.reduce(state, event) }

    @Test fun startsClosedAndIdle() {
        val state = AppState()
        assertEquals(GateState.Closed(BlockReason.NO_SPEED_SIGNAL), state.gate)
        assertFalse(state.videoAllowed)
    }

    @Test fun videoAllowedNeedsSurfaceAndOpenGate() {
        assertFalse(reduce(AppEvent.GateChanged(open)).videoAllowed)
        assertFalse(reduce(AppEvent.SurfaceAvailable(surface)).videoAllowed)
        assertTrue(reduce(AppEvent.SurfaceAvailable(surface), AppEvent.GateChanged(open)).videoAllowed)
    }

    @Test fun requestBeforeConnectIsQueued() {
        val state = reduce(AppEvent.VideoRequested(video))
        assertEquals(video, state.video)
        assertEquals(PlayerStatus.Idle, state.player)
        assertEquals(PlayerStatus.Loading, reduce(AppEvent.CarSessionStarted, AppEvent.SurfaceAvailable(surface), from = state).player)
    }

    @Test fun gateClosingPausesActivePlayback() {
        val playing = reduce(
            AppEvent.SurfaceAvailable(surface), AppEvent.GateChanged(open),
            AppEvent.VideoRequested(video), AppEvent.PlayerReported(PlayerStatus.Playing),
        )
        assertEquals(PlayerStatus.Playing, playing.player)
        assertEquals(PlayerStatus.Paused(byGate = true), reduce(AppEvent.GateChanged(moving), from = playing).player)
    }

    @Test fun gateReopeningDoesNotResume() {
        val paused = reduce(
            AppEvent.SurfaceAvailable(surface), AppEvent.GateChanged(open), AppEvent.VideoRequested(video),
            AppEvent.PlayerReported(PlayerStatus.Playing), AppEvent.GateChanged(moving),
        )
        assertEquals(PlayerStatus.Paused(byGate = true), reduce(AppEvent.GateChanged(open), from = paused).player)
    }

    @Test fun playReportWhileClosedIsRecordedAsPausedByGate() {
        val state = reduce(AppEvent.SurfaceAvailable(surface), AppEvent.PlayerReported(PlayerStatus.Playing))
        assertEquals(PlayerStatus.Paused(byGate = true), state.player)
    }

    @Test fun surfaceLossKeepsVideoForReconnect() {
        val state = reduce(
            AppEvent.CarSessionStarted, AppEvent.SurfaceAvailable(surface), AppEvent.VideoRequested(video),
            AppEvent.SurfaceDestroyed,
        )
        assertNull(state.surface)
        assertEquals(video, state.video)
        assertEquals(PlayerStatus.Idle, state.player)
    }

    @Test fun sessionEndResetsCarSideButKeepsChoice() {
        val state = reduce(
            AppEvent.CarSessionStarted, AppEvent.SurfaceAvailable(surface), AppEvent.GateChanged(open),
            AppEvent.ModeSelected(PlayerMode.YOUTUBE_TV), AppEvent.VideoRequested(video), AppEvent.CarSessionEnded,
        )
        assertFalse(state.carConnected)
        assertNull(state.surface)
        assertEquals(GateState.Closed(BlockReason.NO_SPEED_SIGNAL), state.gate)
        assertEquals(PlayerMode.YOUTUBE_TV, state.mode)
        assertEquals(video, state.video)
    }

    @Test fun videoRequestLeavesTestPattern() {
        val state = reduce(AppEvent.ModeSelected(PlayerMode.TEST_PATTERN), AppEvent.VideoRequested(video))
        assertEquals(PlayerMode.DIRECT, state.mode)
    }

    @Test fun stopClearsVideo() {
        val state = reduce(AppEvent.VideoRequested(video), AppEvent.StopRequested)
        assertNull(state.video)
        assertEquals(PlayerStatus.Idle, state.player)
    }

    @Test fun failureIsKept() {
        val failed = PlayerStatus.Failed(PlayerError.EMBED_BLOCKED)
        val state = reduce(AppEvent.SurfaceAvailable(surface), AppEvent.GateChanged(open), AppEvent.PlayerReported(failed))
        assertEquals(failed, state.player)
    }

    @Test fun resendingTheSameLinkBumpsTheRequest() {
        val once = reduce(AppEvent.VideoRequested(video))
        val twice = reduce(AppEvent.VideoRequested(video), from = once)
        assertEquals(once.requestSeq + 1, twice.requestSeq)
        assertEquals(once.requestSeq, reduce(AppEvent.GateChanged(open), from = once).requestSeq)
    }
}
