package io.github.hassanjaveed84.aatube.core

/** How the car display renders YouTube. */
enum class PlayerMode {
    /** One video by URL through YouTube's IFrame Player API. */
    DIRECT,

    /** youtube.com/tv (leanback UI); a passenger can pair and cast to it. */
    YOUTUBE_TV,

    /** Local, unprotected animated test pattern: no network, no YouTube. */
    TEST_PATTERN,
}

sealed interface PlayerStatus {
    data object Idle : PlayerStatus
    data object Loading : PlayerStatus
    data object Buffering : PlayerStatus
    data object Playing : PlayerStatus
    data class Paused(val byGate: Boolean = false) : PlayerStatus
    data object Ended : PlayerStatus
    data class Failed(val error: PlayerError) : PlayerStatus
}

data class SurfaceInfo(val width: Int, val height: Int, val dpi: Int)

/**
 * Everything the phone UI and the car screen show. Car display state
 * (session, surface) is kept apart from player state, and the gate decides
 * whether the two may meet.
 */
data class AppState(
    val carConnected: Boolean = false,
    val surface: SurfaceInfo? = null,
    val gate: GateState = GateState.Closed(BlockReason.NO_SPEED_SIGNAL),
    val mode: PlayerMode = PlayerMode.DIRECT,
    val video: VideoRef? = null,
    val player: PlayerStatus = PlayerStatus.Idle,
) {
    val videoAllowed: Boolean get() = gate is GateState.Open && surface != null
}

sealed interface AppEvent {
    data object CarSessionStarted : AppEvent
    data object CarSessionEnded : AppEvent
    data class SurfaceAvailable(val info: SurfaceInfo) : AppEvent
    data object SurfaceDestroyed : AppEvent
    data class GateChanged(val gate: GateState) : AppEvent
    data class ModeSelected(val mode: PlayerMode) : AppEvent
    data class VideoRequested(val video: VideoRef) : AppEvent
    data class PlayerReported(val status: PlayerStatus) : AppEvent
    data object StopRequested : AppEvent
}

object AppReducer {

    fun reduce(state: AppState, event: AppEvent): AppState = when (event) {
        AppEvent.CarSessionStarted -> state.copy(carConnected = true)

        // The WebView dies with the session; the chosen video and mode stay
        // so a wireless reconnect can pick them up again.
        AppEvent.CarSessionEnded -> state.copy(
            carConnected = false,
            surface = null,
            gate = GateState.Closed(BlockReason.NO_SPEED_SIGNAL),
            player = PlayerStatus.Idle,
        )

        is AppEvent.SurfaceAvailable -> state.copy(
            surface = event.info,
            player = if (state.video != null || state.mode != PlayerMode.DIRECT) {
                PlayerStatus.Loading
            } else {
                PlayerStatus.Idle
            },
        )

        AppEvent.SurfaceDestroyed -> state.copy(surface = null, player = PlayerStatus.Idle)

        is AppEvent.GateChanged -> state.copy(
            gate = event.gate,
            player = if (event.gate is GateState.Closed && state.player.isActive()) {
                PlayerStatus.Paused(byGate = true)
            } else {
                state.player
            },
        )

        is AppEvent.ModeSelected -> if (event.mode == state.mode) state else state.copy(
            mode = event.mode,
            player = if (state.surface != null) PlayerStatus.Loading else PlayerStatus.Idle,
        )

        is AppEvent.VideoRequested -> state.copy(
            video = event.video,
            mode = if (state.mode == PlayerMode.TEST_PATTERN) PlayerMode.DIRECT else state.mode,
            player = if (state.surface != null) PlayerStatus.Loading else PlayerStatus.Idle,
        )

        // A player that reports itself playing while the gate is closed is
        // recorded as paused-by-gate: the display enforces the pause.
        is AppEvent.PlayerReported -> state.copy(
            player = if (state.gate is GateState.Closed && event.status.isActive()) {
                PlayerStatus.Paused(byGate = true)
            } else {
                event.status
            },
        )

        AppEvent.StopRequested -> state.copy(video = null, player = PlayerStatus.Idle)
    }

    private fun PlayerStatus.isActive(): Boolean =
        this == PlayerStatus.Playing || this == PlayerStatus.Buffering || this == PlayerStatus.Loading
}
