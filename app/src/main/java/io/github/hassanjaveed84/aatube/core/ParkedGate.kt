package io.github.hassanjaveed84.aatube.core

/** Where a speed reading came from. */
enum class SpeedSource { CAR, PHONE_GPS }

/** Why video is not allowed on the car screen right now. */
enum class BlockReason {
    /** A speed source reports the vehicle moving. */
    MOVING,

    /** Stopped, but not yet for long enough to trust it. */
    SETTLING,

    /** No usable speed signal at all, so "parked" cannot be confirmed. */
    NO_SPEED_SIGNAL,
}

sealed interface GateState {
    data class Open(val confirmedBy: SpeedSource) : GateState
    data class Closed(val reason: BlockReason) : GateState
}

/** What the gate last knew about one speed source, for diagnostics. */
data class SpeedReading(
    val source: SpeedSource,
    val metersPerSecond: Float?,
    val atMillis: Long,
    val available: Boolean,
)

/**
 * Decides whether video may be shown on the car display. It allows video
 * only when the vehicle is positively known to be stationary, and closes on
 * any sign of movement. Every build uses it; there is no switch to disable it.
 *
 * - The car's own speed (Car App Library CarInfo) is trusted without expiry:
 *   the host reports it on change, so a parked car may send 0 once and then
 *   stay silent for as long as it stays parked.
 * - Phone GPS speed is a fallback and expires after [Config.phoneMaxAgeMillis].
 * - Any fresh source above its threshold closes the gate, even if the other
 *   says stationary.
 * - After movement the gate stays closed for [Config.settleMillis] of
 *   continuous standstill before it reopens.
 * - With no usable source at all it stays closed (fail closed).
 */
class ParkedGate(private val config: Config = Config()) {

    data class Config(
        val carMovingMetersPerSecond: Float = 0.5f,
        val phoneMovingMetersPerSecond: Float = 1.4f,
        val phoneMaxAgeMillis: Long = 10_000,
        val settleMillis: Long = 5_000,
    )

    /** What the last [evaluate] call saw, for diagnostics. */
    data class Evaluation(
        val state: GateState,
        val carUsable: Boolean,
        val phoneUsable: Boolean,
        val movingBy: Set<SpeedSource>,
    )

    private var car: SpeedReading? = null
    private var phone: SpeedReading? = null
    private var lastMovingAt: Long? = null
    private var last: Evaluation? = null

    val carReading: SpeedReading? get() = car
    val phoneReading: SpeedReading? get() = phone
    val lastEvaluation: Evaluation? get() = last

    fun onCarSpeed(metersPerSecond: Float?, available: Boolean, nowMillis: Long) {
        car = SpeedReading(SpeedSource.CAR, metersPerSecond?.let { kotlin.math.abs(it) }, nowMillis, available)
    }

    fun onPhoneSpeed(metersPerSecond: Float?, nowMillis: Long) {
        phone = SpeedReading(SpeedSource.PHONE_GPS, metersPerSecond?.let { kotlin.math.abs(it) }, nowMillis, metersPerSecond != null)
    }

    fun evaluate(nowMillis: Long): GateState {
        val carSpeed = car?.takeIf { it.available }?.metersPerSecond
        val phoneSpeed = phone
            ?.takeIf { it.available && nowMillis - it.atMillis <= config.phoneMaxAgeMillis }
            ?.metersPerSecond

        val movingBy = buildSet {
            if (carSpeed != null && carSpeed > config.carMovingMetersPerSecond) add(SpeedSource.CAR)
            if (phoneSpeed != null && phoneSpeed > config.phoneMovingMetersPerSecond) add(SpeedSource.PHONE_GPS)
        }
        if (movingBy.isNotEmpty()) lastMovingAt = nowMillis
        val since = lastMovingAt
        val state = when {
            movingBy.isNotEmpty() -> GateState.Closed(BlockReason.MOVING)
            carSpeed == null && phoneSpeed == null -> GateState.Closed(BlockReason.NO_SPEED_SIGNAL)
            since != null && nowMillis - since < config.settleMillis -> GateState.Closed(BlockReason.SETTLING)
            carSpeed != null -> GateState.Open(SpeedSource.CAR)
            else -> GateState.Open(SpeedSource.PHONE_GPS)
        }
        last = Evaluation(state, carSpeed != null, phoneSpeed != null, movingBy)
        return state
    }
}
