package io.github.hassanjaveed84.aatube.core

/** One-line, user-facing explanations of the current state. */
object StatusText {

    fun gate(gate: GateState): String = when (gate) {
        is GateState.Open -> when (gate.confirmedBy) {
            SpeedSource.CAR -> "Parked (car speed 0)"
            SpeedSource.PHONE_GPS -> "Parked (phone GPS)"
        }
        is GateState.Closed -> when (gate.reason) {
            BlockReason.MOVING -> "Vehicle moving: video paused"
            BlockReason.SETTLING -> "Stopped: video available in a few seconds"
            BlockReason.NO_SPEED_SIGNAL ->
                "Cannot confirm the car is parked: no car speed and no GPS fix. " +
                    "Grant the car speed and location permissions, or move where GPS works."
        }
    }

    fun player(status: PlayerStatus): String = when (status) {
        PlayerStatus.Idle -> "Idle"
        PlayerStatus.Loading -> "Loading…"
        PlayerStatus.Buffering -> "Buffering…"
        PlayerStatus.Playing -> "Playing"
        is PlayerStatus.Paused -> if (status.byGate) "Paused for driving" else "Paused"
        PlayerStatus.Ended -> "Ended"
        is PlayerStatus.Failed -> status.error.userMessage
    }

    fun connection(state: AppState): String = when {
        !state.carConnected -> "Not connected to Android Auto"
        state.surface == null -> "Connected; open AA Tube on the car screen"
        else -> "Car screen ${state.surface.width}×${state.surface.height} @ ${state.surface.dpi}dpi"
    }
}
