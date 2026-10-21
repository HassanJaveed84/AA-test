package io.github.hassanjaveed84.aatube.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Commands for the car-screen player, from the phone UI or the car's action strip. */
enum class PlayerCommand { PLAY, PAUSE, RELOAD, BACK }

/**
 * Process-wide meeting point of the phone UI, the car session and the
 * player. The phone activity and the car session live in the same process,
 * so a single state holder is enough; nothing is persisted here.
 */
object AppHub {

    var clock: () -> Long = System::currentTimeMillis

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _commands = MutableSharedFlow<PlayerCommand>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val commands: SharedFlow<PlayerCommand> = _commands.asSharedFlow()

    val log = TransitionLog()

    /** Listeners for every new log line (the lab build's file writer). */
    private val logListeners = mutableListOf<(TransitionLog.Entry) -> Unit>()

    fun dispatch(event: AppEvent) {
        val before: AppState
        val after: AppState
        synchronized(this) {
            before = _state.value
            after = AppReducer.reduce(before, event)
            _state.value = after
        }
        val now = clock()
        if (event is AppEvent.GateChanged) {
            log.addGateIfChanged(now, event.gate)?.let(::notifyLog)
        } else {
            record("event", describe(event))
        }
        if (before.player != after.player) record("player", StatusText.player(after.player))
    }

    /**
     * Asks the player to act. PLAY is refused while video is not allowed,
     * and the returned text explains why; null means the command was sent.
     */
    fun command(command: PlayerCommand): String? {
        val current = _state.value
        if (command == PlayerCommand.PLAY && !current.videoAllowed) {
            val reason = if (current.surface == null) {
                StatusText.connection(current)
            } else {
                StatusText.gate(current.gate)
            }
            record("command", "PLAY refused: $reason")
            return reason
        }
        record("command", command.name)
        _commands.tryEmit(command)
        return null
    }

    fun record(category: String, message: String) {
        notifyLog(log.add(clock(), category, message))
    }

    @Synchronized
    fun addLogListener(listener: (TransitionLog.Entry) -> Unit) {
        logListeners += listener
    }

    @Synchronized
    fun removeLogListener(listener: (TransitionLog.Entry) -> Unit) {
        logListeners -= listener
    }

    @Synchronized
    private fun notifyLog(entry: TransitionLog.Entry) {
        logListeners.forEach { it(entry) }
    }

    /** Test hook: back to a fresh state. */
    fun resetForTest() {
        _state.value = AppState()
    }

    private fun describe(event: AppEvent): String = when (event) {
        is AppEvent.VideoRequested -> "VideoRequested id=${LogRedactor.videoId(event.video.id)} start=${event.video.startSeconds}s"
        is AppEvent.SurfaceAvailable -> "SurfaceAvailable ${event.info.width}x${event.info.height}@${event.info.dpi}"
        else -> event.toString()
    }
}
